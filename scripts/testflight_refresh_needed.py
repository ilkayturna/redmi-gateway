"""Decide whether the weekly scheduled run must upload a fresh TestFlight build.

A TestFlight build stops opening 90 days after upload, and the owner's iPhone has no other copy of
the app. Cron cannot say "every 60 days", so build-ios.yml runs this every week on a cheap Linux
runner before any macOS minute is spent. It reads, through the App Store Connect API, the newest
valid build in the internal group (the one the iPhone can install) and the distribution certificate
that signs new builds, and writes needed=true to GITHUB_OUTPUT when either has
REFRESH_WITHIN_DAYS or fewer left. Nothing secret is printed.
"""

import dataclasses
import datetime as dt
import math
import os
import urllib.parse

REFRESH_WITHIN_DAYS = 30
TESTFLIGHT_LIFETIME_DAYS = 90


@dataclasses.dataclass
class Decision:
    needed: bool
    report: list
    warnings: list
    # Set when a refresh is due but cannot succeed; the job fails so the owner hears about it.
    error: str | None = None
    build_version: str = ""
    build_days_left: int | None = None


def parse_time(text) -> dt.datetime | None:
    """Apple's ISO-8601 time ("2026-10-28T22:17:33.000-07:00" or "...Z") as an aware datetime."""
    if not isinstance(text, str) or not text.strip():
        return None
    value = text.strip()
    if value.endswith("Z"):
        # fromisoformat only accepts "Z" from Python 3.11 on.
        value = value[:-1] + "+00:00"
    try:
        parsed = dt.datetime.fromisoformat(value)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        # Apple always sends an offset; a bare time is read as UTC, never as the runner's zone.
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed


def build_expiry(attributes: dict) -> dt.datetime | None:
    """When TestFlight stops serving the build: Apple's expirationDate, else upload + 90 days."""
    expiry = parse_time(attributes.get("expirationDate"))
    if expiry is not None:
        return expiry
    uploaded = parse_time(attributes.get("uploadedDate"))
    if uploaded is None:
        return None
    return uploaded + dt.timedelta(days=TESTFLIGHT_LIFETIME_DAYS)


def newest_valid_build(builds: list) -> dict | None:
    """The newest build TestFlight still serves: processed, not expired, latest upload time."""
    best = None
    best_uploaded = None
    for build in builds:
        attributes = build.get("attributes") or {}
        if attributes.get("processingState") != "VALID" or attributes.get("expired") is True:
            continue
        uploaded = parse_time(attributes.get("uploadedDate"))
        # A build without an upload time cannot be ordered against the others.
        if uploaded is None:
            continue
        if best_uploaded is None or uploaded > best_uploaded:
            best, best_uploaded = build, uploaded
    return best


def signing_certificate(certificates: list, serial: str) -> dict | None:
    """The distribution certificate the release job signs with.

    Matched by serial when the workflow could read it from the p12, the way
    ensure_push_profile.py picks it; otherwise the only one. With several and no match, the one
    that lasts longest is taken, so an unused old certificate cannot start an upload every week.
    """
    wanted = serial.strip().upper().lstrip("0")
    if wanted:
        for cert in certificates:
            number = (cert.get("attributes") or {}).get("serialNumber") or ""
            if number.upper().lstrip("0") == wanted:
                return cert
    if not certificates:
        return None
    if len(certificates) == 1:
        return certificates[0]
    far_future = dt.datetime.max.replace(tzinfo=dt.timezone.utc)
    return max(
        certificates,
        key=lambda cert: parse_time((cert.get("attributes") or {}).get("expirationDate"))
        or far_future,
    )


def days_left(moment: dt.datetime, now: dt.datetime) -> float:
    return (moment - now).total_seconds() / 86400


def decide(build: dict | None, certificate: dict | None, now: dt.datetime) -> Decision:
    decision = Decision(needed=False, report=[], warnings=[])
    due = False
    uploaded = None
    if build is None:
        decision.report.append("No valid build in the internal TestFlight group.")
        due = True
    else:
        attributes = build.get("attributes") or {}
        decision.build_version = str(attributes.get("version") or "?")
        uploaded = parse_time(attributes.get("uploadedDate"))
        expiry = build_expiry(attributes)
        if expiry is None:
            decision.error = (
                f"Build {decision.build_version} has neither expirationDate nor uploadedDate; "
                "the guard cannot tell when it expires."
            )
            return decision
        left = days_left(expiry, now)
        decision.build_days_left = math.floor(left)
        source = "expirationDate" if attributes.get("expirationDate") else "upload + 90 days"
        decision.report.append(
            f"Build {decision.build_version}: expires {expiry:%Y-%m-%d} ({source}), "
            f"{decision.build_days_left} days left."
        )
        if left <= REFRESH_WITHIN_DAYS:
            due = True

    cert_left = None
    if certificate is None:
        decision.warnings.append(
            "No distribution certificate found in App Store Connect; a release cannot be signed."
        )
    else:
        cert_expiry = parse_time((certificate.get("attributes") or {}).get("expirationDate"))
        if cert_expiry is None:
            decision.warnings.append("The distribution certificate has no readable expiry date.")
        else:
            cert_left = days_left(cert_expiry, now)
            decision.report.append(
                f"Distribution certificate: expires {cert_expiry:%Y-%m-%d}, "
                f"{math.floor(cert_left)} days left."
            )
            if cert_left <= REFRESH_WITHIN_DAYS:
                verb = "expired" if cert_left <= 0 else "expires"
                decision.warnings.append(
                    f"The distribution certificate {verb} on {cert_expiry:%Y-%m-%d}; renew it "
                    "and update IOS_DIST_P12_B64, or no new TestFlight build can be signed."
                )
            # A build uploaded before the certificate expires keeps working for its own 90 days.
            # One upload inside the certificate's last 30 days therefore buys the most time; the
            # next weeks find that upload and do not start another one.
            window_start = cert_expiry - dt.timedelta(days=REFRESH_WITHIN_DAYS)
            if 0 < cert_left <= REFRESH_WITHIN_DAYS and (uploaded is None or uploaded < window_start):
                decision.report.append(
                    "Refreshing before the certificate expires: the newest build predates its "
                    "last 30 days."
                )
                due = True

    if due and certificate is None:
        decision.error = "A refresh is due but there is no distribution certificate to sign it."
        return decision
    if due and cert_left is not None and cert_left <= 0:
        decision.error = (
            "A refresh is due but the distribution certificate has expired; renew it and update "
            "IOS_DIST_P12_B64 before the TestFlight build expires."
        )
        return decision
    decision.needed = due
    return decision


def distribution_certificates(request) -> list:
    unique = {}
    for kind in ("DISTRIBUTION", "IOS_DISTRIBUTION"):
        query = urllib.parse.urlencode({"filter[certificateType]": kind, "limit": 200})
        for cert in request("GET", f"/certificates?{query}").get("data", []):
            unique[cert.get("id")] = cert
    return list(unique.values())


def main() -> None:
    # Imported here, not at the top, so the date logic and its test need neither PyJWT nor
    # App Store Connect credentials.
    from assign_testflight import group_builds, request

    now = dt.datetime.now(dt.timezone.utc)
    build = newest_valid_build(list(group_builds()))
    certificate = signing_certificate(
        distribution_certificates(request), os.environ.get("CERT_SERIAL", "")
    )
    decision = decide(build, certificate, now)
    verdict = "refresh due" if decision.needed else "no refresh needed"
    if decision.error:
        verdict = "refresh blocked"
    for line in decision.report:
        print(line, flush=True)
    for warning in decision.warnings:
        print(f"::warning title=TestFlight refresh::{warning}", flush=True)
    print(f"Result: {verdict} (threshold {REFRESH_WITHIN_DAYS} days)", flush=True)

    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as out:
            out.write(f"needed={'true' if decision.needed else 'false'}\n")
            out.write(f"build={decision.build_version}\n")
            days = "" if decision.build_days_left is None else str(decision.build_days_left)
            out.write(f"days_left={days}\n")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("### TestFlight refresh guard\n\n")
            for line in decision.report + decision.warnings:
                out.write(f"- {line}\n")
            if decision.error:
                out.write(f"- **{decision.error}**\n")
            out.write(f"- Result: **{verdict}**\n")

    if decision.error:
        print(f"::error title=TestFlight refresh::{decision.error}", flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
