# OTA sonrası ses onarımı — 28.10.2026

**Cihaz:** Redmi Note 8 (ginkgo) · LineageOS tabanlı community ROM
`4.4.1-a16-20261022695941-community-ginkgo` (incremental `1792673413`)

**Sonuç:** İki katman da geri kondu ve cihazda doğrulandı. Gerçek arama testi kullanıcıda.
ROM bu telefonda günlük kullanım (pil ömrü) için seçildi; ses geçidi sonradan eklendi.

---

## Belirti

iPhone'a ses gitmiyor; uygulama ekranında iki ayrı eksik:

- "iPhone ses kanalı (SIM 2) düzeltmesi yok"
- "Uygulama ayrıcalıklı (priv-app) kurulu değil" + dört ayrıcalıklı izin eksik

## Kök neden — iki bağımsız katman

1. **/vendor ses eşlemesi.** Vendor dosyaları SIM 2 için yukarı akışı eşlemiyor:
   `USECASE_INCALL_MUSIC_UPLINK2` (PCM 27) tanımı yok ve
   `Incall_Music_2 Audio Mixer MultiMedia2` → `MultiMedia9` düzeltmesi yok.
2. **/system/priv-app kurulumu.** APK + `privapp-permissions-redmi-gateway.xml` yok.
   `ro.control_privapp_permissions=enforce` altında allowlist olmadan dört izin
   (`TETHER_PRIVILEGED`, `CAPTURE_AUDIO_OUTPUT`, `CALL_AUDIO_INTERCEPTION`,
   `MODIFY_PHONE_STATE`) verilmiyor.

OTA, `/vendor` ve `/system` bölümlerini baştan yazdı. ROM'da `addon.d`/backuptool yok;
uygulama bu bölümlere kendi yazamaz (root yok). Normal APK güncellemesi bunları silmez —
tekrar yalnız ROM güncellemesinde olur.

## Ölçülen cihaz gerçekleri

- `ro.build.type=user`, `ro.debuggable=0`; bootloader **açık** (`verifiedbootstate=orange`,
  `vbmeta.device_state=unlocked`), root yok.
- `/system` fstab satırı `wait,avb`; `/vendor` satırı `wait,recoveryonly` (AVB yok).
- `ro.boot.veritymode=enforcing` **yanıltıcı**: bu ROM'da bootloader değeri vbmeta'dan
  bağımsız yazıyor. Gerçek karar vbmeta (mmcblk0p9) başlığının 120. baytındaki 32 bitlik
  `flags` alanında: bu cihazda `0x3` → `HASHTREE_DISABLED`. Yani dm-verity doğrulaması
  kapalı; `/system`'e yazmak açılışı bozmuyor. (Önceki priv-app kurulumunun çalışması da
  bunu doğruluyor.)
- `/vendor` zaten AVB dışı; yazma güvenli ve önceki yamalarla kanıtlı.

## Kurtarma dersi: gerçek bölümü kurtarmanın /vendor'u üzerine mount etmek

28.10.2026'da `mount /vendor` (gerçek vendor imajı, kurtarmanın kendi `/vendor`'u üzerine)
denendiğinde kabuk öldü:

```
Exec '/system/bin/sh' failed: Too many symbolic links encountered
```

Neden: kurtarmanın `/system/bin` → `/vendor/bin` yönlendirmesi ile vendor imajındaki
`/vendor/bin/sh` → `/system/bin/sh` bağlantısı üst üste binince symlink döngüsü oluşuyor.
Bu durumda `adb shell`, `exec-out` ve `adb reboot` çalışmaz.

- **Kural:** gerçek bölümleri `/mnt/rg-vendor` ve `/mnt/rg-system` altına mount et.
- Acil çıkış: `adb push` ile `/proc/sys/kernel/sysrq`=`1`, `/proc/sysrq-trigger`=`s` (sync),
  sonra `b` (reboot). Push, kabuk bozukken de çalışıyor.
- MSYS/Git Bash yol dönüşümünü kapat: `export MSYS_NO_PATHCONV=1`.

## Uygulanan onarım

Yerel kit: `RedmiGateway/build/recovery-kit-217/` (depoya girmez).

```
bash apply.sh
```

Sıra: kurtarma/root denetimi → vbmeta bayrak denetimi (`0x2`) → gerçek bölümleri
`/mnt/rg-*`'ye rw mount → yazma denemesi → vendor yaması (yedek:
`/vendor/etc/phonebridge-backup`) → APK 2.0.17 (217) + allowlist → dosya/izin doğrulamaları
→ `sync` → unmount → reboot. Doğrulama geçmezse telefon **yeniden başlatılmaz**.

## Doğrulama (cihaz)

- `codePath=/system/priv-app/RedmiGateway`, `versionCode=217`, `versionName=2.0.17`
- Dört ayrıcalıklı izin de `granted=true`
- `vendor_audio_fix.py status` → `mixer path fixed: True; platform mapping fixed: True`
- SELinux: vendor dosyaları `vendor_configs_file`, priv-app ve allowlist `system_file`
- Servis çalışıyor (`pidof`), sağlık uyarısı bildirimi yok
- Gerçek arama testi: **kullanıcı doğrulayacak**

## Tekrarlama ve kalıcı çözüm

- Tekrar yalnız OTA'da olur; uygulama güncellemesi etkilemez.
- Kalıcı çözüm: ROM'a gömme — `RepairKit.romSnippet()` çıktısındaki `device.mk` parçası,
  APK'yı `priv-app` olarak paketler ve allowlist'i yerleştirir. ROM'u derleyen kişi ekleyene
  kadar her OTA sonrası kit bir kez çalıştırılır (~2 dakika).
- OTA otomatik alınıyorsa sürpriz olur; manuel almaya geçmek seçenek.

## İlgili dosyalar

- `RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/service/RepairKit.kt`
  (kit üretimi, vbmeta denetimi, `/mnt/rg-*` mount kuralı)
- `RedmiGateway/scripts/device/vendor_audio_fix.py` (`VENDOR_ROOT`, kurtarmada HAL restart yok)
- `RedmiGateway/android/app/src/main/assets/repair/vendor_audio_fix.py` (APK içindeki kopya)
- `RedmiGateway/android/system/privapp-permissions-redmi-gateway.xml`
- Testler: `RepairKitTest`, `InstallContractTest`, `HealthCheckTest`, `UpdateWatchTest`
