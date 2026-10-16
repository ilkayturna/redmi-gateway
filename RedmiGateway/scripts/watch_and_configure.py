"""RedmiGateway provisioning loop: install the APK and configure the phone.

DURDURUCU KONTROL (16.10.2026) - iki gercek hata bu dosyadan cikti:

1) Yollar bir ara C:\\Users\\ilkay.turna\\Documents\\antigravity\\elegant-borg\\ govdesine
   isaret ediyordu. O dizindeki app-debug.apk 26.09.2026 tarihli. Betik sonsuz dongude
   calisip gateway'i bulamazsa o ESKI APK'yi kuruyordu -> o gunden beri yapilan tum
   duzeltmeler (uyanma push'u, kapi loglamasi, 86 test) SESSIZCE kaybolurdu ve belirti
   "uyanma calismiyor" gibi gorunurdu. Artik her sey BU depodan cozulur ve kurmadan once
   ne kurdugu yazilir; eski proje dizininden gelen yol REDDEDILIR.

2) Dosyada `if __name__ == "__main__"` korumasi YOKTU; govde en ust seviyedeydi. Modulu
   test icin import etmek donguyu BASLATTI ve APK kurmaya basladi (16.10.2026'da boyle
   oldu; cihaz bagli olmadigi icin kurulum basarisiz oldu). Artik her sey main() icinde.

Ayrica: gateway APK'si artik kurulmazsa dongu sonsuza kadar donmuyor.
"""
import os
import subprocess
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
STALE_MARKER = "elegant-borg"

ADB = r"C:\Users\ilkay.turna\platform-tools\adb.exe"
LOG_FILE = os.path.join(REPO_ROOT, "docs", "watch_and_configure.log")
REPO_APK = os.path.join(REPO_ROOT, "android", "app", "build", "outputs", "apk", "debug",
                        "app-debug.apk")
MAGISK_APK = os.path.join(REPO_ROOT, "root", "Magisk.apk")

PERMISSIONS = [
    "android.permission.READ_PHONE_STATE",
    "android.permission.CALL_PHONE",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_SMS",
    "android.permission.RECORD_AUDIO",
]


def log(msg):
    t = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{t}] {msg}"
    print(line, flush=True)
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def run(cmd):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                           timeout=15)
        return p.stdout.strip()
    except Exception as e:
        return str(e)


def apk_is_sane(path, label="APK"):
    """Kurulacak dosya bu depodan mi, var mi, ve ne kadar eski?"""
    if STALE_MARKER in path:
        print(f"REFUSING {label}: {path}")
        print(f"          Eski proje dizininde ({STALE_MARKER}); o dosya gunler eski olabilir")
        print("          ve mevcut duzeltmeleri geri alir.")
        return False
    if not os.path.isfile(path):
        print(f"REFUSING {label}: bulunamadi: {path}")
        return False
    age_days = (time.time() - os.path.getmtime(path)) / 86400.0
    size_mb = os.path.getsize(path) / (1024 * 1024)
    print(f"{label}: {path}")
    print(f"     {size_mb:.2f} MB, {age_days:.1f} gun once olusturuldu")
    if age_days > 7:
        print(f"UYARI: {label} {age_days:.0f} gunluk - guncel oldugundan emin ol.")
    return True


def configure_gateway(installed_packages):
    """Permissions, dialer, battery whitelist, background appops, service start."""
    log("DETECTED: com.ilkay.redmigateway installed! Configuring permissions...")
    for p in PERMISSIONS:
        log(f"Grant {p}: {run([ADB, 'shell', f'pm grant com.ilkay.redmigateway {p}'])}")
    log(f"Default dialer: {run([ADB, 'shell', 'telecom set-default-dialer com.ilkay.redmigateway'])}")
    log(f"Battery whitelist: {run([ADB, 'shell', 'dumpsys deviceidle whitelist +com.ilkay.redmigateway'])}")
    run([ADB, "shell", "cmd appops set com.ilkay.redmigateway RUN_IN_BACKGROUND allow"])
    run([ADB, "shell", "cmd appops set com.ilkay.redmigateway RUN_ANY_IN_BACKGROUND allow"])
    run([ADB, "shell", "am set-inactive com.ilkay.redmigateway false"])
    log(f"GatewayService started: "
        f"{run([ADB, 'shell', 'am start-foreground-service -n com.ilkay.redmigateway/.service.GatewayService'])}")
    time.sleep(2)
    log(f"Port 8787 status: {run([ADB, 'shell', 'netstat -tuln | grep 8787'])}")
    log("SUCCESS: RedmiGateway is configured and active!")


def install_gateway_apk():
    """Kurulum denemesi. True donerse kurulum basarili."""
    if not apk_is_sane(REPO_APK, "Gateway APK"):
        log("Gateway APK NOT installed: yol reddedildi ya da dosya yok.")
        log("Derleyin: .\\gradlew.bat assembleDebug   (sessizce ESKI bir APK kurulmaz)")
        return False
    log(f"Installing gateway APK from this repo: {REPO_APK}")
    res = run([ADB, "install", "-r", "-g", REPO_APK])
    log(f"Gateway install result: {res}")
    return "Success" in res


def main():
    log("Starting watch_and_configure loop...")
    gateway_configured = False
    magisk_configured = False
    attempts = 0
    max_attempts = 40          # sonsuz dongu yok

    while not (gateway_configured and magisk_configured) and attempts < max_attempts:
        attempts += 1
        packages = run([ADB, "shell", "pm list packages"])

        if "com.ilkay.redmigateway" in packages and not gateway_configured:
            configure_gateway(packages)
            gateway_configured = True

        if ("com.topjohnwu.magisk" in packages or "io.github.vvb2060.magisk" in packages) \
                and not magisk_configured:
            log("DETECTED: Magisk Manager installed! Launching...")
            run([ADB, "shell", "monkey -p com.topjohnwu.magisk -c android.intent.category.LAUNCHER 1"])
            time.sleep(3)
            log(f"Root check after Magisk launch: {run([ADB, 'shell', 'su -c id'])}")
            magisk_configured = True

        if not magisk_configured and apk_is_sane(MAGISK_APK, "Magisk APK"):
            res = run([ADB, "install", "-r", MAGISK_APK])
            if "Success" in res:
                log(f"Magisk installed via adb: {res}")
                magisk_configured = True
            else:
                log(f"Magisk install result: {res}")
                magisk_configured = True     # reddedilen yolu tekrar tekrar denemeyelim

        if not gateway_configured:
            if install_gateway_apk():
                continue                      # bir sonraki turda yapilandirilir
            gateway_configured = True         # kurulum yapilamadi; dongude takilma

        time.sleep(3)

    if gateway_configured and magisk_configured:
        log("ALL COMPONENTS SUCCESSFULLY INSTALLED AND CONFIGURED!")
    else:
        log(f"Stopped after {attempts} attempts without completing everything. "
            f"gateway={gateway_configured} magisk={magisk_configured}")


if __name__ == "__main__":
    main()
