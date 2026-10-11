# Redmi ve Hyundai ekranı incelemesi — 10 Ekim 2026

## Sonuç

İstenen uygulama henüz teslim edilmedi. Redmi'nin araç ekranında video ve Android uygulamalarını çalıştırması, sıradan Android Auto bağlantısından farklı bir iştir. Bu araç ve telefon için güvenilir, yalnızca park halinde çalışan bir yol henüz doğrulanmadı.

Kullanıcı ekranın orijinal Hyundai ekranı olduğunu doğruladı. Kablolu Android Auto ilk inceleme adayıdır. USB girişinin veri/projeksiyon portu olduğu, Hyundai modeli ve ekran yazılımı doğrulanmalıdır. Bayon adı henüz açıkça teyit edilmedi. Kablosuz kullanımda Redmi'nin mevcut iPhone hotspot bağlantısı ile Wi-Fi eşzamanlılığı ayrıca test edilmelidir.

## Güncel kaynakların söylediği

- [Google bağlantı koşulları](https://support.google.com/androidauto/answer/6348019?hl=en): kablolu kullanım Android 9 ve üzeri, uyumlu araç ve veri kablosu gerektirir; kablosuz kullanımda uygun Android sürümü, 5 GHz ve uyumlu ekran gerekir.
- [Google park uygulaması rehberi](https://developer.android.com/training/cars/parked/auto): Android 15 ve üzerindeki Activity projeksiyonunu açıklar, ancak şu anda desteklenen park kategorisinin yalnızca oyun olduğunu belirtir.
- [Google I/O 2026 geliştirici duyurusu](https://android-developers.googleblog.com/2026/05/17-things-android-developers-google-io.html?m=1): parkta Android Auto videosunun Android 17 telefonlara geleceğini açıklar. Bu, 2024 Hyundai ekranına veya mevcut Redmi'ye uyumluluk kanıtı değildir.
- [AI box üreticisinin teknik açıklaması](https://www.carlinkit.com/tbox): kutu kendi işlemcisi ve Android sistemiyle çalışır, araçta kablolu CarPlay desteği ister. Sadece USB bellek veya telefondaki bir ayar değildir.

Root ile üçüncü taraf ekran yansıtma projeleri vardır. Bunların varlığı bu araçta uyumluluk, DRM video, güncelleme dayanıklılığı veya güvenilir park kilidi anlamına gelmez. Hareket kısıtlamalarını kaldırmak, uygulamayı sahte oyun/navigasyon kategorisiyle tanıtmak veya çalışan telefon köprüsünün ROM'unu değiştirmek bu incelemede uygulanmadı.

## Güncel cihaz ölçümü — erişim açıldıktan sonra

Kullanıcının USB hata ayıklamayı açmasıyla Redmi 17df6357 yetkili olarak bağlandı. Teşhis aracı gerçek cihazda çalıştırıldı; çıktı `scratch/redmi-car-20261010/redmi-capabilities-17df6357-20261010-201109-534.json` altında tutuldu.

- Telefon: Redmi Note 8 / ginkgo, Android 16 / API 36, güvenlik yaması 2026-09-01; /e/OS topluluk sürümü `4.3-a16-20260919677978-community-ginkgo`, user build. `su` bulunamadı; root yetkisi doğrulanmadı.
- Köprü: 2.0.2 / build 202, varsayılan arama uygulaması ve çalışan foreground service. Kurulu APK SHA256 değeri yerel önceki doğrulanmış APK ile aynı: `DB98790B4806D442E30680E07200883B31609E67440D3372867D28667F864BFC`. Geri dönüş için kurulu APK kopyası alındı; eşleşme/veri değiştirilmedi.
- Android Auto: `/system/priv-app/AndroidAutoStub`, sürüm 1.0 / build 1. Bu bir yer tutucudur; gerçek Android Auto değildir. Google uygulaması, Google Maps ve Google Speech paketleri de yok. microG GmsCore 0.3.16.252432-38 korunuyor.
- Pil anlık: %56, 28.8 derece C, 4006 mV; USB şarjına bağlı. Health enum 2, pilin fiziksel kapasitesinin iyi olduğunu kanıtlamaz. Sysfs pil alanları shell'e okunabilir değil; eksik alanlar sıfır sayılmadı.
- O an: güç durumu Dozing, köprü CPU örneği %0; eski sürekli RedmiGateway kısmi wake lock görünmedi.
- Pil muhasebesi: son kayıt aralığı 3 saat 13 dakika fişten çekilmiş süre, yaklaşık 8 dakika 40 saniye sistem uyanıklığı; ekran açık 25 saniye, toplam kısmi wake lock 1 dakika 39 saniye. Android'in discharge hesabı yaklaşık 38.3 mAh. Bu kısa aralık hızlı boşalmayı yeniden üretmedi; şarj bağlantısı/hesap modeli/farklı kullanım sebebiyle pil sağlığı veya uzun dönem tüketim sonucu çıkarılmaz.
- Mevcut log tamponunda sayısal RELAY_HEALTH penceresi yok. Cızırtı için güncel gerçek görüşme kanıtı henüz alınmadı.

## Desteklenen kurulum hazırlığı

[Telefonun işletim sistemi üreticisinin rehberi](https://doc.e.foundation/os/how-to/android-auto/) A13 ve üzeri /e/OS için App Lounge üzerinden Google, Google Maps, Speech Recognition & Synthesis kurulmasını, Android Auto'nun güncellenmesini ve yeniden başlatmayı açıklıyor. Bu normal Android Auto hazırlığıdır; Android 16 üzerinde video veya sınırsız Android masaüstü desteği sağladığı anlamına gelmez.

App Lounge açıldı ancak Android ekran kilidi önünde kaldı; kullanıcıdan telefonun kilidini kendisinin açması istendi. Şifre istenmedi, kilit aşılmadı. Henüz uygulama kurulmadı, microG değiştirilmedi, telefon yeniden başlatılmadı. Kurulumdan sonra köprü servisinin/dialer rolünün geri gelmesi, iPhone eşleşmesi, medya/arama ses sahipliği ve gerçek Hyundai bağlantısı tekrar kontrol edilecek.

## Önceki USB bağlantı teşhisi

Windows USB'de Redmi Note 8'i görüyor. Sunulan arayüz MTP/dosya aktarımı sınıfında. İki kurulu yönetim aracı sürümü ve iki USB arka ucu denendi; yetkili cihaz listesi boş kaldı. Kullanıcının tekrar deneme isteği üzerine kontrol tekrarlandı; durum aynı. Windows'un eski Google sürücüsü MTP arayüzünü ACER ADB olarak etiketliyor; bu, gerçek ADB arayüzü bulunduğunu göstermez. Sadece bu cihazın bilgisayar tarafındaki USB yeniden başlatması denendi; işletim sistemi erişimi engelledi ve aygıt değiştirilmedi. Telefonun güncel Android/uygulama/root sürümü veya pil ölçümleri alınamadı. USB hata ayıklama bağlantısı ve bilgisayara izin gereklidir. Mevcut uygulama/ayar/ROM değiştirilmedi.

## Pil ve ses

Kaynak kodda SmartHotspot'un köprü durduğunda durdurulmayan 5 saniyelik kontrolü ve ağ dinleyicisi bulundu. Bu ayrı bir yaşam döngüsü kusurudur; kullanıcının son hızlı pil tüketiminin nedeni olarak doğrulanmamıştır. Sürekli multicast dinleme/hotspot çalışması ölçüm adaylarıdır; keşif ve iPhone bağlantısını bozabilecek toplu güç değişiklikleri yapılmadı.

Önceki ses düzeltmesinin kayıtları incelendi; bunlar güncel cihaz/insan konuşması kanıtı değildir. Cızırtı için gerçek iki yönlü görüşmede paket geliş aralıkları, atılan/geciken kareler, ses tamponu boşalmaları ve fallback nedeni sayısal olarak toplanmalıdır. Yakın mesafe tek başına ağ veya ses motoru kaynaklı kesintiyi ayırmaz.

## Hazır teşhis aracı ve kalan iş

`scripts/device/collect_redmi_capabilities.ps1` yalnızca seçilmiş, yetkili Redmi'den sürüm ve pil/güç özetini okumak için hazırlandı. Kişisel içerik/logcat, çağrı numarası, SMS, eşleşme anahtarı veya Wi-Fi kimliği kaydetmez; pil geçmişini sıfırlamaz. Sözdizimi, cihaz yokken hata/no-artifact davranışı ve yetkili cihazdan başarılı veri toplama doğrulandı. Bu araç pil teşhisi yerine bir anlık durum raporu verir.

1. Ekran kilidi kullanıcı tarafından açıldıktan sonra /e/OS'un desteklediği gerçek Android Auto kurulumunu tamamlamak; köprü/rol/eşleşmeyi yeniden doğrulamak.
2. Hyundai modelini, orijinal ekranı ve yazılım sürümünü doğrulamak.
3. Park halindeki araçta kablolu bağlantı ve gerçek ekran kabulünü test etmek.
4. Medya oynarken gelen/giden arama, iPhone ses sahipliği, Bluetooth HFP, USB kopma/yeniden bağlanma davranışlarını doğrulamak.
5. Desteklenen video yolu veya güvenilir park durumuyla çalışan deneysel yol oluşursa uygulamayı onun üzerinden geliştirmek ve gerçek araçta doğrulamak.

Bu kapılar geçilmeden “video çalışıyor”, “pil düzeldi” veya “cızırtı çözüldü” sonucu verilmez.
