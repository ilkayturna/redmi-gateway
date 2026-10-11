# Redmi Android Auto kurulum kaydı — 10 Ekim 2026

## Doğrulanan kurulum

Kullanıcı ekran kilidini açtı. /e/OS'un resmî yoluyla App Lounge içinde Google LLC uygulamaları kuruldu/güncellendi. APK dosyaları üçüncü taraf sitelerden indirilmedi; microG ve telefon köprüsü değiştirilmedi.

| Paket | Kurulu sürüm | Build |
|---|---|---|
| Google | 17.64.15.ve.arm64 | 301817218 |
| Google Maps | 26.40.00.989295350 | 1068809342 |
| Speech Recognition & Synthesis | googletts.google-speech-apk_20260914.02_p2.986315744 | 210683481 |
| Android Auto | 17.7.663654-release | 177663654 |

Android Auto artık sürüm 1.0 sistem yer tutucusu yerine gerçek güncel uygulamadır. Gerekli yeniden başlatma öncesinde bu sürümler doğrudan paket yöneticisinden doğrulandı.

## Telefon köprüsü ve geçici ayarlar

Yükleme sırasında köprü foreground service olarak çalıştı; varsayılan arama rolü `com.ilkay.redmigateway` olarak kaldı. Kurulu köprü APK'sı SHA256 değeri önceki yerel build ile birebir eşleşti; APK kopyası geri dönüş için yerelde tutuldu. Köprü uygulama verisi/eşleşme anahtarları okunmadı veya değiştirilmedi.

Mağaza kontrolü için ekran kapanma süresi geçici olarak 600000 ms yapıldı; yeniden başlatmadan önce önceki 60000 ms değerine geri döndürüldü ve bu değer tekrar okundu. Termal koruma, pil geçmişi, root, ROM, Bluetooth eşleşmeleri veya hotspot politikası değiştirilmedi.

## Termal gözlem

Bir mağaza kontrolünde Android thermal status 3 ve JobScheduler'ın `Restricted due to: thermal` durumu görüldü. Daha sonraki ölçümde thermal status kendiliğinden 0 oldu ve indirmeler ilerledi. Statü override false idi. HAL'ın güncel sıcaklıkları ile önbellekteki sıcaklıklar farklıdır; eski CPU sıcaklıkları güncel ölçüm diye sunulmadı. Bu geçici olay, bildirilen hızlı pil boşalmasının veya görüşme cızırtısının nedenini tek başına kanıtlamaz.

## Henüz doğrulanmamış işler

- Yeniden başlatma sonrası köprü servisi ve Android Auto'nun ilk açılışı.
- Android Auto onay/izin ekranları ve telefonun ilk kilit açılışı.
- Resmî Desktop Head Unit ile gerçek Redmi projeksiyon testi. DHU 2.0 Windows araçları bilgisayara kuruldu; bu simüle araç ünitesi gerçek Hyundai kabulü değildir.
- Hyundai'de park halinde gerçek görüntü/dokunma/ses, USB kopma/yeniden bağlanma ve iPhone/araç çağrı ses sahipliği.
- Android Auto video ve sınırsız Android ekranı: bu Android 16 cihazda desteklenen güvenilir yol henüz doğrulanmadı. Sahte oyun/navigasyon kategorisi veya sürüş kilidi aşma uygulanmadı.
- Doğal fişten çekilmiş pil ölçümü ve gerçek iki yönlü görüşme sırasında sayısal ses sağlık sayaçları.

Kaynaklar: [/e/OS kurulum rehberi](https://doc.e.foundation/os/how-to/android-auto/), [resmî Google DHU rehberi](https://developer.android.com/training/cars/testing/dhu).
