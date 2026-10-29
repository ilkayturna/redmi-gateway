# CI, release and device scripts (build-ios.yml / build-android.yml / ci-health.yml, TestFlight upload+promote, teslim-kapisi, APK install/rollback, archive sync, DONUNCE-YAPILACAK)

## Summary
I only read files for this. Nothing was edited, no adb or network calls were made, and no commands touched the device.

1) WORKFLOWS
- build-ios.yml
  - Triggers: push to main, pull_request to main, and workflow_dispatch with two inputs: `release` (a required boolean) and `promote_build` (a string). There is no schedule, no path filter and no concurrency setting, so every push to main, docs included, starts a macOS compile.
  - `compile` (macos-latest) runs two tests built with plain swiftc, a typecheck of all sources, an xcodebuild Debug simulator build, and a simulator launch with a screenshot.
  - `testflight` runs only when workflow_dispatch is used with `release == true`. Steps:
    - installs the p12 and profile;
    - runs `ensure_push_profile.py` with `continue-on-error`;
    - a push-profile gate step whose own condition is `if: inputs.release == true`;
    - sets `CFBundleVersion = $GITHUB_RUN_NUMBER` with PlistBuddy;
    - archives, exports the IPA and uploads it with `altool`.
  - `CFBundleShortVersionString` is never set by CI. It stays hardcoded as "2.0" in Info.plist, and `GENERATE_INFOPLIST_FILE = NO`.
  - `promote_existing` (ubuntu) is a separate dispatch that runs `scripts/assign_testflight.py`. It waits at most 15 minutes for the build to become VALID, then adds it to beta group 2268c752… and checks that it is there.
  - Secrets used: IOS_DIST_P12_B64, IOS_DIST_P12_PASSWORD, IOS_PROFILE_B64, ASC_API_KEY_B64, ASC_API_KEY_ID, ASC_ISSUER_ID.
- build-android.yml
  - Triggers: push to main and to `codex/audio-jitter-fix` (a deleted branch, so this entry is stale), pull_request, and workflow_dispatch.
  - It runs `testDebugUnitTest`, `assembleDebug` and `lintDebug` (with a baseline), then uploads the APK and reports.
- ci-health.yml: an ubuntu echo job, started by workflow_dispatch or by a push that changes the file itself.

2) A SCHEDULED TESTFLIGHT REFRESH is feasible with the same secrets. Build 155 expires around 27.01.2027, but the owner needs it until about the end of February 2027, so at least one refresh is mandatory. It needs:
- **Trigger:** a `schedule:` cron. Cron cannot express "every 60 days", so use a monthly or weekly cron plus a cheap ubuntu guard job. The guard asks App Store Connect for the age or expiry of the newest VALID build and only continues when, for example, 35 days or fewer remain. It reuses `request()` from `assign_testflight.py`.
- **Job conditions:** `testflight.if` must also accept `github.event_name == 'schedule'`. The push-profile gate's own condition `inputs.release == true` would be skipped on a schedule (inputs are empty), so a build without push could be uploaded. It must be changed.
- **Promote:** an in-run promote step with `ASC_BUILD_NUMBER = github.run_number`, preferably with a longer processing deadline than 15 minutes.
- **Repository guard:** `github.repository == 'ilkayturna/redmi-gateway'`, because the archive repo receives copies of the workflow files.
- **Pinned source:** check out a known-good tag rather than main HEAD (AGENTS 6.1), and preferably pin the runner and Xcode version.

Failure emails are a real risk: AGENTS 0.1 says every failed run emails the owner. Likely causes are quota exhaustion (macOS minutes count 10x), certificate or profile expiry, Apple processing taking longer than 15 minutes, a re-run reusing the same run_number as the build number, altool or Xcode changes in the macos-latest image, and the archive repo firing the same cron.

3) CHECKS ON EVERY PUSH
- iOS: two standalone swiftc test executables, CallAudioTiming and BLEReassembly. There is no XCTest target. Then the typecheck, the simulator build and a launch screenshot.
- Android: unit tests (41 test files), assembleDebug, and lint against the baseline.
- The Python tests in `scripts/` are not run by CI.

4) TESLIM-KAPISI.PS1 (in `RedmiGateway/scripts/quality/`, not `scripts/quality/`)
- What it does: checks the repo is clean and the version in gradle, runs the gradle tests and build locally, and with `-Cihaz` checks the installed version, permissions, the app process and the freshness of the two diagnostics logs. With `-Hotspot` it turns Wi-Fi off; this runs over USB only, guarded by checking for ':' in the serial. It also checks TestFlight through the external `asc.mjs` and the latest GitHub run.
- Stale or hardcoded parts:
  - The default expected Android version is 2.0.13/213, but gradle is now 2.0.17/217.
  - The device check hardcodes `17df6357\s+device`, so it fails over Wi-Fi adb.
  - The check labelled "iOS CI" actually reads the latest run of any workflow.
- No install or rollback script exists. The only installers are the legacy `autonomous_deployer.py` and `watch_and_configure.py` (`install -r -g`; the second one passes no `-s`).
- Rollback APKs:
  - `RedmiGateway-Archive/artifacts/rollback/redmigateway-2.0.2-KNOWN-GOOD-20261015-2157.apk` has no wake feature.
  - The 10 APKs in `RedmiGateway-Archive/apk/` are all from 16.10 and all versionCode 202.
  - The current 2.0.17 (217) APK exists only locally: `RedmiGateway/build/recovery-kit-217/RedmiGateway.apk` and `app/build/outputs/apk/debug/app-debug.apk`. Both are gitignored.

5) ARCHIVE SYNC: AGENTS 4.2 says every push goes to both `redmi-gateway` and `redmi-gateway-archive`. The script is `C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\scripts\push-both-repos.ps1` and does not live in this repo. It uses the Git Data API (blobs, tree on the remote base, commit, PATCH ref) and only sends files changed in HEAD~1..HEAD. Problems:
- Deleted files are skipped.
- File mode is always 100644.
- The default `-Branch` is `codex/audio-jitter-fix`, which is deleted, so it 404s unless `-Branch main` is passed.

6) DONUNCE-YAPILACAK.MD is dated 16.10.2026 and mostly superseded by `docs/DURUM-20261029.md`. It still says build 96, the IDLE-WAKE APK and branches that are now archived. Items still relevant:
- Check the vendor audio fix first after any OTA.
- Real-call checks are still open: transfer under 1000 ms, AUDIO-STATE-REPEAT verification, beacon battery effect not measured, and the `tryKnownNetwork` race.

For the one-command PC script: no repo script uses mDNS or `adb pair`. Reusable pieces are the Invoke-Adb wrapper with timeout and Assert-SelectedDevice in `collect_redmi_capabilities.ps1`, and the pull and analysis logic in `analyze-call-diagnostics.ps1` and `zil-izi.ps1`.
- Files to pull all sit in `/sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics/`: `call-diagnostics.log`, `iphone-diagnostics.log` and `iphone-diagnostics.1.log`.
- The script must disconnect wireless adb at the end; leaving it connected drains the battery 4x (AGENTS 0.5).
- It must never toggle Wi-Fi.
- It must not use `logcat -c`.
- An mDNS instance-name serial contains no ':' and so bypasses teslim-kapisi's Wi-Fi guard.

## Facts
- build-ios.yml triggers: push branches [main], pull_request branches [main], workflow_dispatch with inputs release (boolean, required, default false) and promote_build (string, default ''). No schedule, no paths filter, no concurrency block. (.github/workflows/build-ios.yml:3)
- compile job: if github.event_name != 'workflow_dispatch' || inputs.promote_build == '' ; runs-on macos-latest (so it also runs on schedule events if one were added). (.github/workflows/build-ios.yml:24)
- compile steps: swiftc-built CallAudioTiming test executable (30-37), BLE reassembly test executable (43-50), swiftc -typecheck of every app .swift for arm64-apple-ios15.0-simulator (56-65), xcodebuild Debug simulator build with CODE_SIGNING_ALLOWED=NO plus PrivacyInfo.xcprivacy lint (66-77), simulator boot/install/launch with pairing_token SIMULATOR_PREVIEW and screenshot (78-91), screenshot artifact upload (92-97). (.github/workflows/build-ios.yml:30)
- There is no XCTest target in the Xcode project (0 matches for xctest/XCTest); iOS tests are two standalone files compiled with swiftc: RedmiGateway/ios/Tests/BLEReassemblyTests.swift and CallAudioTimingTests.swift. (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:?)
- testflight job: if github.event_name == 'workflow_dispatch' && inputs.release == true; needs compile; runs-on macos-latest; permissions contents: read. (.github/workflows/build-ios.yml:101)
- Signing secrets IOS_DIST_P12_B64, IOS_DIST_P12_PASSWORD, IOS_PROFILE_B64 are installed into a temp keychain; the cert serial is extracted to $RUNNER_TEMP/cert_serial; the base profile is copied as N6ZRK2NV2U.mobileprovision. (.github/workflows/build-ios.yml:108)
- 'Provision push notifications' step is continue-on-error: true and runs scripts/ensure_push_profile.py in a venv with ASC_API_KEY_B64/ASC_API_KEY_ID/ASC_ISSUER_ID. (.github/workflows/build-ios.yml:138)
- The 'Require push profile for release' gate has its own condition `if: inputs.release == true`; under a schedule event inputs are empty, so this gate would be skipped. (.github/workflows/build-ios.yml:158)
- CFBundleVersion is set with PlistBuddy to $GITHUB_RUN_NUMBER right before archiving; CFBundleShortVersionString is not modified by the workflow. (.github/workflows/build-ios.yml:177)
- Info.plist hardcodes CFBundleShortVersionString 2.0 and CFBundleVersion 1; ITSAppUsesNonExemptEncryption is false (no Missing Compliance hold). (RedmiGateway/ios/RedmiGateway/Info.plist:17)
- Project uses GENERATE_INFOPLIST_FILE = NO with MARKETING_VERSION = 2.0 and CURRENT_PROJECT_VERSION = 1; IPHONEOS_DEPLOYMENT_TARGET 15.0. (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:294)
- Archive uses DEVELOPMENT_TEAM=6FR2S79P48, manual signing, profile 'Telefon Gecidi App Store 2026' or the push profile + CODE_SIGN_ENTITLEMENTS=RedmiGateway/RedmiGateway.entitlements; export method app-store-connect; upload with xcrun altool --upload-app using the ASC API key. (.github/workflows/build-ios.yml:180)
- promote_existing job: if workflow_dispatch && inputs.promote_build != ''; ubuntu-latest; pip installs PyJWT; runs scripts/assign_testflight.py with ASC_BUILD_NUMBER=inputs.promote_build. (.github/workflows/build-ios.yml:239)
- assign_testflight.py hardcodes APP_ID 6816340544 and internal group GROUP_ID 2268c752-72d2-4f2f-8b7e-a707f3567b01; requires a decimal build number. (scripts/assign_testflight.py:15)
- assign_testflight.py polls /builds every 30 s for at most 15 minutes until processingState VALID, raises on FAILED/INVALID or on multiple matches, then POSTs to betaGroups/{id}/relationships/builds and re-verifies membership. (scripts/assign_testflight.py:74)
- ensure_push_profile.py uses profile name 'Telefon Gecidi App Store Push', picks the distribution certificate by CERT_SERIAL (or the only one), reuses an ACTIVE profile or deletes non-active ones and creates a new IOS_APP_STORE profile, requires aps-environment, and writes push_profile to GITHUB_OUTPUT. (scripts/ensure_push_profile.py:90)
- build-android.yml triggers: push branches [main, codex/audio-jitter-fix], pull_request [main], workflow_dispatch (no inputs); no path filters. (.github/workflows/build-android.yml:3)
- Android CI job (ubuntu, temurin 17) runs :app:testDebugUnitTest, :app:assembleDebug, :app:lintDebug (baseline app/lint-baseline.xml), uploads the debug APK always and lint/test reports on failure. (.github/workflows/build-android.yml:28)
- Only main remains in origin; codex/audio-jitter-fix and other branches were closed and tagged archive/* (so the Android push trigger on codex/audio-jitter-fix is stale). (docs/DURUM-20261029.md:141)
- ci-health.yml: workflow_dispatch plus push limited to paths .github/workflows/ci-health.yml; single ubuntu echo job; its comment that main is 127 commits behind is stale. (.github/workflows/ci-health.yml:20)
- Every failed CI run emails the repository owner; rule: never push into a known-broken workflow, stop retrying after an outage is confirmed, disable workflows via PUT .../disable and re-enable later. (AGENTS.md:15)
- macos-latest has a 10x minute multiplier on this private repo; the 16.10.2026 outage (runner_id=0, steps=0) was attributed to the Actions minute quota. (DONUNCE-YAPILACAK.md:21)
- TestFlight build 155 expires about 90 days after upload (~27.01.2027); when it expires the app will not open; this is the one mandatory exception to 'no update needed'. (docs/DURUM-20261029.md:125)
- Owner goal: the iPhone has no cellular until roughly end of February 2027; Redmi stays in the pocket as hotspot + call gateway (so one TestFlight refresh before ~27.01.2027 is required). (C:/Users/ilkay.turna/.claude/projects/C--Users-ilkay-turna-source-repos-redmi-gateway/memory/owner-goal-redmi-in-pocket.md:13)
- AGENTS 4.3: TestFlight troubleshooting list - release=true, build number = GITHUB_RUN_NUMBER, look under CFBundleShortVersionString, promote with promote_build, ITSAppUsesNonExemptEncryption, UIBackgroundModes. (AGENTS.md:348)
- AGENTS 4.1: git push to github.com:443 is blocked on this network; remote updates go through Git Data API (blobs -> trees -> commits -> PATCH refs). (AGENTS.md:336)
- AGENTS 4.2: two repos redmi-gateway (main) + redmi-gateway-archive (archive/logs); every push goes to both. (AGENTS.md:344)
- push-both-repos.ps1 lives only in the local archive folder (not in this repo); default -Branch is codex/audio-jitter-fix, repos redmi-gateway and redmi-gateway-archive; token from git credential fill. (C:/Users/ilkay.turna/Documents/GitHub/RedmiGateway-Archive/scripts/push-both-repos.ps1:1)
- push-both-repos.ps1 uploads only files from git diff HEAD~1..HEAD as blobs on top of the remote tree, skips files that no longer exist (deletions not propagated), always uses mode 100644, creates a commit with the remote SHA as parent and PATCHes the ref with force=false. (C:/Users/ilkay.turna/Documents/GitHub/RedmiGateway-Archive/scripts/push-both-repos.ps1:42)
- The archive repo is a thin archive carrying only changed files; its main was advanced to 98050bf; branch archive/artifacts (no common ancestor with main) is kept on purpose because it carries rollback APKs. (docs/DURUM-20261029.md:149)
- The archive repo also carries the three workflows; on 16.10.2026 pushes to it produced 79 failing runs (each a notification) because only the main repo had been disabled. (docs/CI-DEVRE-DISI.md:10)
- teslim-kapisi.ps1 lives at RedmiGateway/scripts/quality/teslim-kapisi.ps1; params default Adb C:\Users\ilkay.turna\platform-tools\adb.exe, Seri 17df6357, BeklenenAndroidSurum 2.0.13, BeklenenVersionCode 213, AscKlasor C:\Users\ilkay.turna\Documents\asc-cli, AscAppId 6816340544; exit 0 all GECTI, 1 any KALDI. (RedmiGateway/scripts/quality/teslim-kapisi.ps1:18)
- Gradle is at versionCode 217 / versionName 2.0.17, so teslim-kapisi's default expectation (213/2.0.13) fails unless parameters are passed. (RedmiGateway/android/app/build.gradle.kts:14)
- teslim-kapisi device section hardcodes the regex '17df6357\s+device' regardless of -Seri, so it reports 'not connected' over Wi-Fi adb. (RedmiGateway/scripts/quality/teslim-kapisi.ps1:144)
- teslim-kapisi checks call-diagnostics.log (>1000 bytes, <=180 min old) and iphone-diagnostics.log freshness via ls -l, and optionally the iPhone build via 'linked app=\S+ build=(\d+)' in the last 400 lines. (RedmiGateway/scripts/quality/teslim-kapisi.ps1:178)
- teslim-kapisi -Hotspot is skipped only when the serial contains ':'; it runs logcat -c and svc wifi disable/enable (AGENTS 0.5 USB-only rule). (RedmiGateway/scripts/quality/teslim-kapisi.ps1:231)
- teslim-kapisi TestFlight check uses node asc.mjs builds/group-builds/buildBetaDetail and requires VALID + in group + IN_BETA_TESTING; its 'iOS CI' gate reads the latest completed run of any workflow in the repo. (RedmiGateway/scripts/quality/teslim-kapisi.ps1:309)
- No repo script installs or rolls back the current app; legacy autonomous_deployer.py installs the repo debug APK with install -r -g on the first 'device' serial and starts the service. (RedmiGateway/scripts/autonomous_deployer.py:105)
- watch_and_configure.py installs the repo APK with 'adb install -r -g' without -s (ambiguous if another device such as the TV is in adb). (RedmiGateway/scripts/watch_and_configure.py:108)
- Rollback APK: artifacts\rollback\redmigateway-2.0.2-KNOWN-GOOD-20261015-2157.apk (no wake feature); all archived APKs show versionCode 202 / 2.0.2 and are told apart only by name/hash. (docs/APK-ARSIVI.md:38)
- Installed app after OTA repair: codePath=/system/priv-app/RedmiGateway, versionCode 217, 2.0.17; the recovery kit is local at RedmiGateway/build/recovery-kit-217/ (contains RedmiGateway.apk, apply.sh, allowlist, vendor_audio_fix.py) and is not in git. (docs/OTA-ONARIM-20261028.md:64)
- *.apk and build/ are gitignored, so the only 217 APK copies are local build outputs. (.gitignore:19)
- call-diagnostics.log lives in getExternalFilesDir("diagnostics") and is trimmed from 4000 to the newest 2500 lines; timestamps are MM-dd HH:mm:ss.SSS (no year). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/journal/DiagnosticsLog.kt:24)
- iphone-diagnostics.log (same directory) rotates to iphone-diagnostics.1.log at 512 KB; lines truncated at 4000 chars; mirrored to logcat tag IphoneDiag. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/journal/IphoneDiagnostics.kt:26)
- client_log lines are stored with label iPhone[appVersion/appBuild]; client_status is stored as 'iPhone-status' with keys appVersion, appBuild, appState, callkit, audio, mic, notify, ble, battery, thermal, lowPower, network; the structured DiagnosticsReport is only returned over the WebSocket 'diagnostics' message. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/server/GatewayWebSocketServer.kt:432)
- analyze-call-diagnostics.ps1 supports -Serial auto (first 'device' in adb devices) or ip:port, pulls call-diagnostics.log into RedmiGateway-Archive\logs\diagnostics and prints ring/transfer/RELAY_HEALTH/hotspot metrics; stamps assume the current year. (scripts/analyze-call-diagnostics.ps1:14)
- zil-izi.ps1 hardcodes '17df6357\s+device', pulls both diagnostics logs into C:\Users\ilkay.turna\Documents\deepseek-harness\default-workspace and merges ring-chain lines. (RedmiGateway/scripts/quality/zil-izi.ps1:31)
- collect_redmi_capabilities.ps1 has a reusable read-only adb wrapper with 12 s timeout, serial validation ^[A-Za-z0-9._:-]+$ and Assert-SelectedDevice before and after collection. (RedmiGateway/scripts/device/collect_redmi_capabilities.ps1:19)
- capture-call-diagnostics.ps1 clears logcat (logcat -c) before capturing; the archive's collect-device-logs.ps1 also runs logcat -c immediately before logcat -d (so its logcat-tail is near empty). (scripts/capture-call-diagnostics.ps1:25)
- No script in the repo uses adb mdns, adb pair or _adb-tls service discovery (grep found no matches); wireless usage so far is ip:5555. (AGENTS.md:133)
- AGENTS 0.5: over Wi-Fi adb never disable Wi-Fi or change network settings; nohup background jobs are unreliable; always 'adb disconnect' after work because a connected adb server woke the Redmi ~2,400 times/hour and drained idle battery about 4x. (AGENTS.md:141)
- AGENTS 0.6: before any device-writing action check 'dumpsys telecom' mCalls for both SIM slots; empty output means unknown, not idle. (AGENTS.md:160)
- ADB access as of 29.10.2026 was waiting for USB debugging authorization on the phone; pull command documented for iphone-diagnostics.log. (docs/DURUM-20261029.md:110)
- DONUNCE-YAPILACAK.md is dated 16.10.2026 (main 1ec8a65, v2 2585549) and describes build 96, IDLE-WAKE/LATEST APKs, the CI outage and main being 127 commits behind - all superseded by DURUM-20261029 (build 155, Redmi 2.0.17, everything on main, CI green). (DONUNCE-YAPILACAK.md:3)
- DONUNCE open items still relevant: vendor audio fix must be checked first after any ROM/OTA (python scripts\device\vendor_audio_fix.py status); 2.0.15+ raises a notification and builds a repair zip in Downloads. (DONUNCE-YAPILACAK.md:35)
- DONUNCE known states: audio transfer <1000 ms not met (~2504 ms), beacon battery impact not measured, low race in tryKnownNetwork:277 not to be changed without device test. (DONUNCE-YAPILACAK.md:281)
- DONUNCE: AUDIO-STATE-REPEAT build awaited a real call test (look for 'Repeated the preparing state', RELAY_HEALTH iphone=N/s, AUDIO_OWNER=IPHONE); IDLE-WAKE APK named as the fallback. (DONUNCE-YAPILACAK.md:404)

## Extension points
- Add a schedule trigger for a TestFlight refresh (.github/workflows/build-ios.yml:3): Add `schedule: - cron: '<m> <h> 1 * *'` (monthly) or weekly; cron cannot express 60 days, so pair it with an expiry guard job. Inputs are empty on schedule, so every condition must test github.event_name == 'schedule' explicitly. Add `if: github.repository == 'ilkayturna/redmi-gateway'` to scheduled jobs because push-both-repos copies changed workflow files into redmi-gateway-archive, which also runs workflows (docs/CI-DEVRE-DISI.md:10-30).
- Expiry guard job (ubuntu, cheap) before any macOS minutes are spent (scripts/assign_testflight.py:26): New script (e.g. scripts/testflight_refresh_needed.py) reusing request()/JWT from assign_testflight.py:26-56: list builds for APP_ID 6816340544, take the newest VALID, compute days to expiry (uploadedDate+90d or the API's expiration field - verify), write needed=true|false to GITHUB_OUTPUT. compile/testflight jobs then use `needs: guard` and `if: needs.guard.outputs.needed == 'true'`. Exit 0 when not needed so no email is sent.
- Allow testflight job on schedule (.github/workflows/build-ios.yml:101): Change to (workflow_dispatch && inputs.release == true) || (schedule && guard says needed). compile.if (line 24) already passes for schedule events.
- Keep the push-profile gate active for scheduled releases (.github/workflows/build-ios.yml:158): `if: inputs.release == true` is skipped on schedule; change to also cover github.event_name == 'schedule' (or drop the condition since the job only runs for releases). Otherwise a build without the push entitlement could ship.
- Promote the fresh build inside the same run (.github/workflows/build-ios.yml:237): After the altool upload, add a step (or a job needs: testflight on ubuntu to save macOS minutes) running `python3 scripts/assign_testflight.py` with ASC_BUILD_NUMBER=${{ github.run_number }} and the three ASC secrets, the same as lines 249-255. Consider raising the 15-minute deadline at assign_testflight.py:74 so slow Apple processing does not become a failure email.
- Pin the source and toolchain of an unattended release (.github/workflows/build-ios.yml:107): actions/checkout@v4 accepts `with: ref:`; point scheduled runs at a known-good tag (e.g. the build-155 source f50bc614 or a moving 'release/known-good' tag) instead of main HEAD to respect AGENTS 6.1. runs-on macos-latest (lines 25, 103) can move to a new Xcode; pin macos-NN and an Xcode version for reproducibility.
- Avoid parallel/duplicate runs and wasted minutes (.github/workflows/build-ios.yml:21): Add a `concurrency:` group for release runs; consider `paths-ignore: ['docs/**','**/*.md']` on push so doc commits stop starting macOS jobs (DONUNCE-YAPILACAK.md:21 says 10x multiplier). Re-running a failed release reuses run_number, which gives a duplicate CFBundleVersion; dispatch a new run instead.
- Stale Android branch trigger (.github/workflows/build-android.yml:5): Remove codex/audio-jitter-fix (deleted per docs/DURUM-20261029.md:141).
- New one-command PC diagnostics script (mDNS discovery, connect, pull, disconnect, summarise) (RedmiGateway/scripts/quality/teslim-kapisi.ps1:18): Place it next to teslim-kapisi.ps1 / zil-izi.ps1 (e.g. RedmiGateway/scripts/quality/redmi-tanilama-cek.ps1). Steps: `adb mdns check`, `adb mdns services`, filter `_adb-tls-connect._tcp` and resolve ip:port, `adb connect ip:port` and use the ip:port form as the serial (it contains ':' so the AGENTS 0.5 guard in teslim-kapisi:231 still recognises Wi-Fi). Run read-only: `adb pull /sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics/` (call-diagnostics.log, iphone-diagnostics.log, iphone-diagnostics.1.log), `logcat -d` (never -c), dumpsys package/battery/telecom snapshot. Always `adb disconnect ip:port` in finally (AGENTS.md:147). Fall back to USB serial 17df6357. Save to C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\logs\<stamp> like the other scripts.
- Reusable adb wrapper for that script (RedmiGateway/scripts/device/collect_redmi_capabilities.ps1:19): Invoke-Adb with 12 s timeout, Assert-SelectedDevice (61-68) and serial ValidatePattern (line 3) can be copied; it already rejects offline/unauthorized devices without writing a misleading report.
- Summary/analysis logic to reuse (scripts/analyze-call-diagnostics.ps1:45): Ring sync, transfer budget, wake, RELAY_HEALTH and hotspot sections; add a -LocalFile parameter so the new script can feed the pulled file instead of pulling again. Add iPhone-side summary: latest 'iPhone-status' line (GatewayWebSocketServer.kt:452-458), 'linked app= build=' (line 361), 'telemetry dropped N lines' (line 443). The stamp parser (line 47-53) assumes the current year, so Dec->Jan logs will misparse.
- Parameterise hardcoded serial checks so Wi-Fi adb works (RedmiGateway/scripts/quality/teslim-kapisi.ps1:144): Use [regex]::Escape($Seri) instead of the literal 17df6357 (also zil-izi.ps1:31). Update default BeklenenAndroidSurum/VersionCode (lines 25-26) to 2.0.17/217 or read them from build.gradle.kts. Filter the 'iOS CI' API call (line 347) by workflow (build-ios.yml) instead of any run.
- Archive sync script defaults (C:/Users/ilkay.turna/Documents/GitHub/RedmiGateway-Archive/scripts/push-both-repos.ps1:3): Default -Branch must become main (the old branch is deleted, so the GET ref call 404s and the script throws under ErrorActionPreference Stop). Deletions (line 49) and file modes (line 53) are not carried; workflow-file changes do get copied into the archive repo.

## Risks
- An unattended scheduled release from main HEAD would ship whatever is on main to the owner's only iPhone, bypassing AGENTS 6.1 (no audio-path change without a real call test). Pin a known-good ref.
- The push-profile gate (build-ios.yml:158, `if: inputs.release == true`) silently does not run under a schedule event. Without a change, a build without the push entitlement could be uploaded, and it never rings away from the Redmi.
- Failure emails: every failed run mails the owner (AGENTS.md:15). Likely scheduled failure causes:
- the Actions minute quota (the 16.10 outage);
- distribution certificate or profile expiry;
- Apple processing longer than the 15-minute deadline (assign_testflight.py:74);
- altool or Xcode changes when the macos-latest image moves;
- a re-run reusing the same run_number as CFBundleVersion.
- redmi-gateway-archive receives workflow files through push-both-repos.ps1 and has run the same workflows before (CI-DEVRE-DISI.md:10-30). A cron added to build-ios.yml would also fire in the archive repo, which probably has no signing secrets, and fail on every tick, mailing the owner each time, unless guarded by github.repository.
- A TestFlight refresh only helps if the new build is actually installed on the iPhone; if TestFlight automatic updates are off, the owner must tap Install, or build 155 still expires around 27.01.2027.
- Both the compile job and the testflight job run on macOS, which costs 10x minutes. Every push to main, including docs-only commits, already starts the macOS compile because there are no path filters.
- Wireless adb left connected drains the Redmi battery about 4x (AGENTS.md:147-150). The new script must disconnect in a finally block, even on errors.
- mDNS instance-name serials (adb-<serial>-<id>._adb-tls-connect._tcp) contain no ':', so teslim-kapisi's Wi-Fi guard (line 231) would not see them as Wi-Fi and would allow `svc wifi disable` over a wireless link. That reproduces the 25.10 incident where the Redmi dropped off the network.
- Existing collectors run `logcat -c` (capture-call-diagnostics.ps1:25, archive collect-device-logs.ps1:74, teslim-kapisi.ps1:238), which destroys evidence on the device. A diagnostics puller must stay strictly read-only.
- Log timestamps have no year (DiagnosticsLog.kt:33, IphoneDiagnostics.kt:38) and the parsers use (Get-Date).Year, so time-window analysis breaks across the Dec 2026 to Jan 2027 rollover.
- Rollback options are poor:
- Every archived APK is 2.0.2 (202) and older than the installed priv-app 2.0.17 (217), so `adb install -r` needs -d (downgrade) and would lose later fixes.
- The current 217 APK exists only in gitignored local build folders.
- CI and PC debug builds are signed with different debug keystores (build.gradle.kts has no signingConfig), so they cannot replace each other.
- The legacy installer scripts (autonomous_deployer.py, watch_and_configure.py) do not run the AGENTS 0.4 / 0.6 call-in-progress checks, and watch_and_configure.py installs without -s.
- DONUNCE-YAPILACAK.md (16.10) contradicts the current state in DURUM-20261029.md (build 96 vs 155, deleted branches, IDLE-WAKE APK). A reader acting on it could install a stale APK or TestFlight build.
- push-both-repos.ps1 with its default branch fails with a 404. With -Branch main it builds a new API commit, so local and remote SHAs differ (by design under AGENTS 4.1), and deletions are not propagated.

## Tests
location: RedmiGateway/ios/Tests (CallAudioTimingTests.swift, BLEReassemblyTests.swift - standalone swiftc executables, no XCTest target); RedmiGateway/android/app/src/test (41 Kotlin test files: audio, ble, journal, push, security, server, service, sms, telecom); scripts/test_*.py and RedmiGateway/scripts/test_udp_audio_loopback.py (not run by CI)
run: Android locally: set JAVA_HOME to C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\jdk\jdk-17.0.19+10, then in RedmiGateway\android run gradlew.bat :app:testDebugUnitTest :app:lintDebug. CI runs the same tasks through ./gradlew --console=plain. iOS tests need macOS and run only in CI, using swiftc <source> <test> -o bin && ./bin (build-ios.yml:30-50). Full local gate: powershell -NoProfile -ExecutionPolicy Bypass -File RedmiGateway\scripts\quality\teslim-kapisi.ps1 [-Cihaz]. Pass -BeklenenAndroidSurum 2.0.17 -BeklenenVersionCode 217 until the defaults are updated, and never use -Hotspot over Wi-Fi.
- RedmiGateway/ios/Tests/CallAudioTimingTests.swift
- RedmiGateway/ios/Tests/BLEReassemblyTests.swift
- RedmiGateway/android/app/src/test/java/com/ilkay/redmigateway/journal (DiagnosticsLog trim rules)
- RepairKitTest, InstallContractTest, HealthCheckTest, UpdateWatchTest (named in docs/OTA-ONARIM-20261028.md:99)
- RedmiGateway/scripts/quality/teslim-kapisi.ps1

## Open questions
- Expiry dates of the distribution certificate behind IOS_DIST_P12_B64 and of the 'Telefon Gecidi App Store 2026' and 'Telefon Gecidi App Store Push' profiles. If either expires before about March 2027, the scheduled refresh fails.
- Does the App Store Connect builds resource expose an expiry field (e.g. expirationDate or expired) that the guard can read, or must the guard compute uploadedDate + 90 days? This needs checking against Apple's API docs before coding.
- Is the TestFlight Automatic Updates setting on for this app on the owner's iPhone?
- Does redmi-gateway-archive currently have its workflows enabled, and does it hold any of the signing or ASC secrets?
- Who last edited the workflow file? GitHub sends scheduled-workflow failure notifications to the user who last modified the cron line; this is not verified for this repo.
- Do GitHub's 60-day inactivity auto-disable rules for scheduled workflows apply to this private repo? ARSIV-README.md:45 says the repo is private, and GitHub documents the rule for public repos.
- Is git push still blocked on this network (AGENTS 4.1)? Recent commits reached origin/main, but the method is not recorded in these files.
- Will `xcrun altool --upload-app` keep working on future macos-latest images, or should the upload move to another uploader?
- Does the version-2.0 TestFlight train stay open indefinitely for new build numbers? Nothing has been submitted for App Store review.
- Does the adb at C:\Users\ilkay.turna\platform-tools\adb.exe support `adb mdns services` with the openscreen backend on Windows? Not checked, because running adb was out of scope.
- Has wireless debugging ever been paired (adb pair) on the Redmi? DURUM-20261029.md:110 says USB authorization was still pending on 29.10. Does wireless debugging survive reboots and network changes on this ROM? Is the legacy `adb tcpip 5555` listener (192.168.1.111:5555) still active?
- Where do the PC and the Redmi share a network in daily use (home or office Wi-Fi)? Outdoors the Redmi is the hotspot, so the PC can only reach it if it joins that hotspot.
- Is the installed priv-app APK debuggable, so `run-as com.ilkay.redmigateway` can read internal files? recovery-kit-217/RedmiGateway.apk has the same size as the debug build output (6968675 bytes), which suggests a debug build but does not prove it.
- Should the structured DiagnosticsReport (WebSocket-only, GatewayWebSocketServer.kt:432) also be written to the diagnostics directory so adb can pull it? That would be an Android code change outside this area.