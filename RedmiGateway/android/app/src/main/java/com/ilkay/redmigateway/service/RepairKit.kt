package com.ilkay.redmigateway.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ilkay.redmigateway.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The repair package: the exact files and the exact commands that put back what a ROM update takes.
 *
 * The app cannot write /vendor or grant itself a signature permission, and this phone has a locked
 * bootloader and no root, so "the app repairs itself" is not something an app update can deliver.
 * What it can do is stop the rediscovery: the package carries the proven vendor script, its own
 * APK, the priv-app allowlist and one command that applies all of it, so the repair after every
 * update is one step instead of an afternoon.
 *
 * The text parts are built here rather than shipped as files so a test can assert what goes into
 * the package without a device or a zip reader.
 */
object RepairKit {
    const val README = "README.txt"
    const val APPLY = "apply.sh"
    const val VENDOR_SCRIPT = "vendor_audio_fix.py"
    const val ALLOWLIST = "privapp-permissions-redmi-gateway.xml"
    const val APK = "RedmiGateway.apk"
    const val ROM_SNIPPET = "rom/device.mk.snippet.txt"

    const val PRIV_APP_PATH = "/system/priv-app/RedmiGateway/RedmiGateway.apk"
    const val ALLOWLIST_PATH = "/system/etc/permissions/privapp-permissions-redmi-gateway.xml"
    const val PLATFORM_PATH = "/vendor/etc/audio_platform_info_intcodec.xml"
    const val MIXER_PATH = "/vendor/etc/mixer_paths_idp.xml"

    const val PLATFORM_LINE =
        "<usecase name=\"USECASE_INCALL_MUSIC_UPLINK2\" type=\"out\" id=\"27\" />"
    const val PLATFORM_ANCHOR =
        "<usecase name=\"USECASE_INCALL_MUSIC_UPLINK\" type=\"out\" id=\"27\" />"
    const val MIXER_OLD =
        "<ctl name=\"Incall_Music_2 Audio Mixer MultiMedia2\" value=\"1\" />"
    const val MIXER_NEW =
        "<ctl name=\"Incall_Music_2 Audio Mixer MultiMedia9\" value=\"1\" />"

    /**
     * Every privileged permission the manifest requests.
     *
     * ro.control_privapp_permissions=enforce on this ROM: an allowlist that misses one of these
     * does not fail quietly, it fails the boot. The repository copy of this file is
     * android/system/privapp-permissions-redmi-gateway.xml and a test keeps the two equal.
     */
    val allowlistPermissions = listOf(
        "android.permission.TETHER_PRIVILEGED",
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.CALL_AUDIO_INTERCEPTION",
        "android.permission.MODIFY_PHONE_STATE"
    )

    fun allowlistXml(packageName: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<permissions>\n")
        append("    <privapp-permissions package=\"$packageName\">\n")
        for (permission in allowlistPermissions) {
            append("        <permission name=\"$permission\" />\n")
        }
        append("    </privapp-permissions>\n")
        append("</permissions>\n")
    }

    fun readme(problems: List<HealthProblem>): String = buildString {
        append("Telefon Geçidi onarım paketi\n")
        append("Sürüm ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("\n")
        append("Neden gerekli\n")
        append("-------------\n")
        if (problems.isEmpty()) {
            append("Bu paket şu an eksik görünmüyor; yine de her ROM güncellemesinden sonra " +
                "çalıştırılabilir, hiçbir şeyi bozmaz.\n")
        } else {
            for (problem in problems) {
                append("- ${problem.title}\n  ${problem.detail}\n")
            }
        }
        append("\n")
        append("Bu dosyalar /vendor ve /system içinde yaşar; ROM güncellemesi bu bölümleri\n")
        append("baştan yazar. Uygulama bu bölümlere kendisi yazamaz (bootloader kilitli, root yok).\n")
        append("\n")
        append("Nasıl uygulanır\n")
        append("---------------\n")
        append("Gereken: bilgisayarda adb ve Python 3 (yoksa betik sed ile devam eder).\n")
        append("\n")
        append("1) Telefonu kurtarma (recovery) modunda başlat ve USB ile bağla.\n")
        append("   Ekranda izin penceresi çıkarsa 'İzin ver'e bas.\n")
        append("2) Bilgisayarda bu klasörde:\n")
        append("       bash apply.sh\n")
        append("   (Windows'ta Git Bash; gerekirse: ADB_SERIAL=<seri> bash apply.sh)\n")
        append("   Betik gerçek /vendor ve /system bölümlerini /mnt/rg-* altına bağlar;\n")
        append("   kurtarmanın kendi araçlarına dokunmaz. Bir doğrulama başarısız olursa\n")
        append("   telefonu yeniden başlatmaz, böylece yarım kalan iş görülebilir.\n")
        append("3) Telefon açılınca Ayarlar ekranında şu satırlar yeşil olmalı:\n")
        append("       iPhone ses kanalı (SIM 2) · Ayrıcalıklı kurulum · Ses izinleri (ayrıcalıklı)\n")
        append("\n")
        append("Paket ne içerir\n")
        append("---------------\n")
        append("- apply.sh                : tek komut; bölümleri açar, yamayı ve kurulumu yapar\n")
        append("- vendor_audio_fix.py     : /vendor ses yamasının denenmiş betiği (status/apply/revert)\n")
        append("- $ALLOWLIST : ayrıcalıklı izin listesi\n")
        append("- $APK             : uygulamanın kendisi (aynı imza, aynı sürüm)\n")
        append("- $ROM_SNIPPET : ROM'u derleyen kişi için device.mk parçası\n")
        append("\n")
        append("ROM'u derleyen kişi bu iki dosyayı cihaz ağacına koyar ve her güncellemede\n")
        append("düzeltme kendiliğinden gelir; o zaman bu pakete gerek kalmaz.\n")
    }

    fun romSnippet(): String = buildString {
        append("# ROM derleyicisi için: cihaz ağacındaki device.mk dosyasına ekle.\n")
        append("# 1) Uygulamayı priv-app olarak göm:\n")
        append("PRODUCT_PACKAGES += RedmiGateway\n")
        append("#    (Android.bp: android_app { name: \"RedmiGateway\", certificate: \"platform\", privileged: true })\n")
        append("# 2) Ayrıcalıklı izin listesini yerleştir:\n")
        append("PRODUCT_COPY_FILES += \\\n")
        append("    vendor/redmi-gateway/privapp-permissions-redmi-gateway.xml:$ALLOWLIST_PATH\n")
        append("# 3) /vendor ses düzeltmesini cihaz ağacının vendor dosyalarına işle:\n")
        append("#    $PLATFORM_PATH\n")
        append("#      $PLATFORM_ANCHOR satırının hemen altına:\n")
        append("#        $PLATFORM_LINE\n")
        append("#    $MIXER_PATH\n")
        append("#      $MIXER_OLD\n")
        append("#      -> $MIXER_NEW\n")
    }

    fun applyScript(): String = """
        |#!/usr/bin/env bash
        |# Telefon Gecidi onarim paketi. Bilgisayarda calisir; telefonda degil.
        |# Kurtarmanin kendi /vendor ve /system noktalarina BAGLAMA YAPILMAZ: kurtarma
        |# araclari /system/bin -> /vendor/bin baglantisi kullanir ve ust uste baglama
        |# kabugu sembolik baglanti dongusune cevirir (olculdu 28.10.2026).
        |set -euo pipefail
        |cd "$(dirname "$0")"
        |# Git Bash on Windows must pass device paths such as /mnt/rg-vendor through verbatim.
        |export MSYS_NO_PATHCONV=1
        |
        |ADB=${'$'}{ADB:-adb}
        |SERIAL=${'$'}{ADB_SERIAL:-}
        |adb_() { if [ -n "${'$'}SERIAL" ]; then "${'$'}ADB" -s "${'$'}SERIAL" "${'$'}@"; else "${'$'}ADB" "${'$'}@"; fi; }
        |say() { printf '\n== %s\n' "${'$'}1"; }
        |die() { printf '\nHATA: %s\n' "${'$'}1" >&2; exit 1; }
        |
        |say "Telefon bekleniyor"
        |STATE=""
        |for _ in ${'$'}(seq 1 60); do
        |  STATE=${'$'}(adb_ get-state 2>/dev/null | tr -d '\r' || true)
        |  case "${'$'}STATE" in device|recovery) break;; esac
        |  sleep 1
        |done
        |case "${'$'}STATE" in
        |  device|recovery) ;;
        |  unauthorized) die "Telefon unauthorized: ekrandaki izin penceresinde 'Izin ver'e bas." ;;
        |  *) die "Telefon bagli degil. Kurtarma modunda USB ile bagla." ;;
        |esac
        |
        |say "Kurtarma denetimi"
        |BOOTED=${'$'}(adb_ shell getprop sys.boot_completed | tr -d '\r' || true)
        |[ "${'$'}BOOTED" != "1" ] || \
        |  die "Bu paket kurtarma modunda calisir. 'adb reboot recovery' ile baslat."
        |
        |say "Root denetimi"
        |if [ "${'$'}(adb_ shell id -u | tr -d '\r')" != "0" ]; then
        |  adb_ root >/dev/null 2>&1 || true
        |  sleep 2
        |fi
        |[ "${'$'}(adb_ shell id -u | tr -d '\r')" = "0" ] || \
        |  die "adb root degil. Telefonu kurtarma modunda baslat ya da root'lu adb kullan."
        |
        |VERITY=${'$'}(adb_ shell getprop ro.boot.veritymode | tr -d '\r' || true)
        |say "dm-verity denetimi (veritymode=${'$'}{VERITY:-bos})"
        |# ro.boot.veritymode yaniltir: bootloader onu vbmeta'dan bagimsiz "enforcing" yazar.
        |# Karar vbmeta'daki 32 bitlik bayragin hashtree-disable bitine (0x2) bakar; bu ROM'da
        |# bayrak 0x3 oldugu icin /system ve /vendor dogrulanmaz, yazmak guvenlidir.
        |FLAGS=${'$'}(adb_ shell 'dd if=/dev/block/by-name/vbmeta bs=1 skip=120 count=4 2>/dev/null | od -An -tx1' | tr -d ' \r\n' || true)
        |[ "${'$'}{#FLAGS}" -eq 8 ] || die "vbmeta okunamadi; bolumlere yazilmadi."
        |LAST=$((0x${'$'}{FLAGS: -2}))
        |if [ $((LAST & 2)) -eq 0 ]; then
        |  die "dm-verity etkin (vbmeta hashtree'yi kapatmiyor): yazmak acilisi bozabilir. ROM'a gomme gerekli."
        |fi
        |
        |say "Gercek bolumler bulunuyor"
        |VDEV=${'$'}(adb_ shell 'readlink -f /dev/block/by-name/vendor 2>/dev/null' | tr -d '\r')
        |[ -n "${'$'}VDEV" ] || VDEV=${'$'}(adb_ shell 'readlink -f /dev/block/mapper/vendor 2>/dev/null' | tr -d '\r')
        |SDEV=${'$'}(adb_ shell 'readlink -f /dev/block/by-name/system 2>/dev/null' | tr -d '\r')
        |[ -n "${'$'}SDEV" ] || SDEV=${'$'}(adb_ shell 'readlink -f /dev/block/mapper/system 2>/dev/null' | tr -d '\r')
        |[ -n "${'$'}VDEV" ] || die "vendor bolumu bulunamadi."
        |[ -n "${'$'}SDEV" ] || die "system bolumu bulunamadi."
        |
        |say "Bolumler /mnt/rg-* altina baglaniyor"
        |adb_ shell 'mkdir -p /mnt/rg-vendor /mnt/rg-system'
        |adb_ shell 'umount /mnt/rg-vendor 2>/dev/null || true; umount /mnt/rg-system 2>/dev/null || true'
        |adb_ shell "mount -o rw ${'$'}VDEV /mnt/rg-vendor" || die "vendor baglanamadi (${'$'}VDEV)."
        |adb_ shell "mount -o rw ${'$'}SDEV /mnt/rg-system" || die "system baglanamadi (${'$'}SDEV)."
        |
        |VROOT=/mnt/rg-vendor
        |SROOT=/mnt/rg-system
        |adb_ shell "test -f ${'$'}VROOT/etc/mixer_paths_idp.xml && test -f ${'$'}VROOT/etc/audio_platform_info_intcodec.xml" || \
        |  die "vendor yapisi taninmadi."
        |if adb_ shell "test ! -L ${'$'}SROOT/system && test -d ${'$'}SROOT/system/priv-app"; then
        |  SROOT=${'$'}SROOT/system
        |fi
        |adb_ shell "test -d ${'$'}SROOT/priv-app && test -f ${'$'}SROOT/build.prop" || \
        |  die "system yapisi taninmadi."
        |
        |say "Yazma denemesi"
        |adb_ shell "touch ${'$'}VROOT/.rg-write-test && rm ${'$'}VROOT/.rg-write-test" || die "vendor yazilamiyor."
        |adb_ shell "touch ${'$'}SROOT/.rg-write-test && rm ${'$'}SROOT/.rg-write-test" || die "system yazilamiyor."
        |
        |say "Vendor ses duzeltmesi"
        |if command -v python >/dev/null 2>&1; then
        |  VENDOR_OUT=${'$'}(ADB="${'$'}ADB" ADB_SERIAL="${'$'}SERIAL" VENDOR_ROOT="${'$'}VROOT" python vendor_audio_fix.py apply)
        |  printf '%s\n' "${'$'}VENDOR_OUT"
        |  printf '%s' "${'$'}VENDOR_OUT" | grep -qE 'after apply: mixer path fixed: True; platform mapping fixed: True|already applied' || \
        |    die "vendor duzeltmesi dogrulanamadi."
        |else
        |  say "Python yok; sed ile devam ediliyor"
        |  tmp=${'$'}(mktemp -d)
        |  tmpwin="${'$'}tmp"
        |  case "${'$'}(uname -s)" in MINGW*|MSYS*|CYGWIN*) tmpwin=${'$'}(cd "${'$'}tmp" && pwd -W);; esac
        |  adb_ pull "${'$'}VROOT/etc/audio_platform_info_intcodec.xml" "${'$'}tmpwin/platform.xml" >/dev/null
        |  adb_ pull "${'$'}VROOT/etc/mixer_paths_idp.xml" "${'$'}tmpwin/mixer.xml" >/dev/null
        |  adb_ shell "mkdir -p ${'$'}VROOT/etc/phonebridge-backup && " \
        |    "[ -f ${'$'}VROOT/etc/phonebridge-backup/mixer_paths_idp.xml ] || " \
        |    "cp -p ${'$'}VROOT/etc/mixer_paths_idp.xml ${'$'}VROOT/etc/phonebridge-backup/mixer_paths_idp.xml; " \
        |    "[ -f ${'$'}VROOT/etc/phonebridge-backup/audio_platform_info_intcodec.xml ] || " \
        |    "cp -p ${'$'}VROOT/etc/audio_platform_info_intcodec.xml ${'$'}VROOT/etc/phonebridge-backup/audio_platform_info_intcodec.xml"
        |  grep -q 'USECASE_INCALL_MUSIC_UPLINK2' "${'$'}tmp/platform.xml" || \
        |    sed -i '/USECASE_INCALL_MUSIC_UPLINK"/a\    $PLATFORM_LINE' "${'$'}tmp/platform.xml"
        |  sed -i 's|$MIXER_OLD|$MIXER_NEW|' "${'$'}tmp/mixer.xml"
        |  grep -q 'USECASE_INCALL_MUSIC_UPLINK2' "${'$'}tmp/platform.xml" || die "platform yamasi uygulanmadi"
        |  grep -q 'Incall_Music_2 Audio Mixer MultiMedia9' "${'$'}tmp/mixer.xml" || die "mixer yamasi uygulanmadi"
        |  adb_ push "${'$'}tmpwin/platform.xml" /data/local/tmp/audio_platform_info_intcodec.xml >/dev/null
        |  adb_ push "${'$'}tmpwin/mixer.xml" /data/local/tmp/mixer_paths_idp.xml >/dev/null
        |  adb_ shell "cat /data/local/tmp/audio_platform_info_intcodec.xml > ${'$'}VROOT/etc/audio_platform_info_intcodec.xml; " \
        |    "cat /data/local/tmp/mixer_paths_idp.xml > ${'$'}VROOT/etc/mixer_paths_idp.xml; " \
        |    "rm -f /data/local/tmp/audio_platform_info_intcodec.xml /data/local/tmp/mixer_paths_idp.xml"
        |  rm -rf "${'$'}tmp"
        |fi
        |adb_ shell "chmod 644 ${'$'}VROOT/etc/audio_platform_info_intcodec.xml ${'$'}VROOT/etc/mixer_paths_idp.xml; " \
        |  "chown root:root ${'$'}VROOT/etc/audio_platform_info_intcodec.xml ${'$'}VROOT/etc/mixer_paths_idp.xml 2>/dev/null || true; " \
        |  "restorecon ${'$'}VROOT/etc/audio_platform_info_intcodec.xml ${'$'}VROOT/etc/mixer_paths_idp.xml 2>/dev/null || " \
        |  "chcon u:object_r:vendor_configs_file:s0 ${'$'}VROOT/etc/audio_platform_info_intcodec.xml ${'$'}VROOT/etc/mixer_paths_idp.xml 2>/dev/null || true"
        |
        |say "Uygulama priv-app olarak kuruluyor"
        |PRIV="${'$'}SROOT/priv-app/RedmiGateway/RedmiGateway.apk"
        |ALLOW="${'$'}SROOT/etc/permissions/privapp-permissions-redmi-gateway.xml"
        |adb_ shell "mkdir -p ${'$'}SROOT/priv-app/RedmiGateway ${'$'}SROOT/etc/permissions"
        |adb_ push $APK "${'$'}PRIV" >/dev/null
        |adb_ push $ALLOWLIST "${'$'}ALLOW" >/dev/null
        |adb_ shell "chmod 644 ${'$'}PRIV ${'$'}ALLOW; " \
        |  "chown root:root ${'$'}PRIV ${'$'}ALLOW 2>/dev/null || true; " \
        |  "restorecon ${'$'}PRIV ${'$'}ALLOW 2>/dev/null || " \
        |  "chcon u:object_r:system_file:s0 ${'$'}PRIV ${'$'}ALLOW 2>/dev/null || true"
        |adb_ shell "test -s ${'$'}PRIV" || die "priv-app kopyasi yazilamadi."
        |adb_ shell "grep -q 'com.ilkay.redmigateway' ${'$'}ALLOW" || die "izin listesi yanlis paketi gosteriyor."
        |for permission in TETHER_PRIVILEGED CAPTURE_AUDIO_OUTPUT CALL_AUDIO_INTERCEPTION MODIFY_PHONE_STATE; do
        |  adb_ shell "grep -q '${'$'}permission' ${'$'}ALLOW" || die "izin listesinde ${'$'}permission yok."
        |done
        |
        |say "Bitti. Telefon yeniden baslatiliyor."
        |adb_ shell sync
        |adb_ shell 'umount /mnt/rg-system 2>/dev/null || true; umount /mnt/rg-vendor 2>/dev/null || true'
        |adb_ reboot
        |echo "Acildiktan sonra Ayarlar > Durum satirlarini kontrol et."
        |""".trimMargin().trimStart()

    /** Writes the package where the user can reach it from a computer, and returns its URI. */
    fun write(context: Context, problems: List<HealthProblem>): Uri {
        val name = "RedmiGateway-Onarim-${BuildConfig.VERSION_NAME}.zip"
        val bytes = build(context, problems)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("İndirilenler'e yazılamadı")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Paket yazılamadı")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, name)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
    }

    fun build(context: Context, problems: List<HealthProblem>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))

            put(README, readme(problems))
            put(APPLY, applyScript())
            put(VENDOR_SCRIPT, context.assets.open("repair/vendor_audio_fix.py").readBytes())
            put(ALLOWLIST, allowlistXml(context.packageName))
            put(ROM_SNIPPET, romSnippet())
            put(APK, File(context.applicationInfo.sourceDir).readBytes())
        }
        return out.toByteArray()
    }
}
