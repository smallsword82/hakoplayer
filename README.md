# HakoPlayer (Android telefon + Android TV / Box)

IBO Player / Hiremco tarzı bir IPTV **oynatıcı**. Uygulama içerik sağlamaz, yalnızca oynatıcıdır;
kullanıcı kendi Xtream Codes veya M3U aboneliğini ekler.

## Özellikler
- **Ana sayfa:** Açılışta son liste otomatik yüklenir. Büyük kutucuklar (Canlı TV / Filmler / Diziler ve sayıları),
  Favoriler, Son İzlenen, Listeler, Ayarlar, Yenile; liste adı, abonelik bitişi ve saat
- **Ayarlar:** otomatik yükleme, açılışta son kanalı oynatma, saat formatı, A-Z sıralama, canlı yayın formatı
  (Otomatik / TS / HLS), özel User-Agent, liste güncelleme sıklığı, önbellek temizleme, cihaz bilgisi
- **Ebeveyn kontrolü (varsayılan PIN 0000):**
  - Yetişkin içerik otomatik algılanır: sunucunun `is_adult` işareti + kategori adlarında anahtar kelimeler
    (XXX, Adult, 18+, Yetişkin, Porn, Erotik, Erwachsene, Adulte, Adultos...). "Sussex", "Adult Swim" gibi adlar hariç.
  - Mod: PIN ile kilitle / Tamamen gizle / Kapalı (değiştirmek PIN ister)
  - İstenen kategoriler elle kilitlenebilir (Ayarlar'dan veya kategoriye uzun basarak)
  - PIN bir kez girilince uygulama kapanana kadar açık kalır; "Kilitleri şimdi kapat" ile tekrar kilitlenir
- Xtream Codes girişi (sunucu / kullanıcı / şifre) ve M3U URL desteği, birden fazla kayıtlı liste
- **Telefonla ekleme (QR):** Liste ekranında QR kod çıkar. Telefonla okutunca TV'de çalışan yerel sunucunun
  sayfası açılır (`http://<cihaz-ip>:8686`). Sunucu, kullanıcı adı ve şifre telefondan girilir; bilgiler
  doğrulanır, TV'ye kaydedilir ve liste otomatik açılır. Ekrandaki 4 haneli kod olmadan ekleme yapılamaz.
  Telefon ve cihaz aynı ağda olmalı.
- Canlı TV, Filmler, Diziler (sezon ve bölüm), Favoriler, Son İzlenenler
- Kategoriler ve anlık arama
- ExoPlayer (Media3): HLS (.m3u8), MPEG-TS (.ts), MP4/MKV; hata olursa otomatik tekrar dener
- **Ücretsiz Türk kanalları (isteğe bağlı):** Liste ekranındaki düğme, üçüncü taraf açık liste olan
  [iptv-org](https://github.com/iptv-org/iptv) Türkiye listesini (`countries/tr.m3u`) "Ücretsiz TR" adıyla ekler.
  Onay sorulur, kendiliğinden eklenmez; içerik HakoPlayer'a ait değildir, kanalların çalışması garanti edilmez.
- **Canlı TV düzeni:** üst sekmeler, kategoriler | kanallar | sağda önizleme. İlk OK önizler, ikinci OK tam ekran.
  Girişte ilk kanal önizlemede açılır (Ayarlar'dan kapatılabilir). Kategoriden sağa basınca listenin başına gider.
- **Tam ekran kumanda:** Yukarı/Aşağı ve CH+/CH- kanal değiştirir; OK veya Sol yarı saydam kanal listesini,
  ikinci Sol kategorileri açar; Sağ oynat/duraklat ve ayarlar panelini açar; Geri önce panelleri kapatır,
  sonra önizleme ekranına döner. EPG yoksa "Program bilgisi yok" yazar.
- **Kategori gizleme:** Ayarlar'dan canlı TV / film / dizi kategorileri tamamen gizlenebilir (PIN ister).
  Yetişkin içerik Son İzlenenler'e hiç yazılmaz.
- Film ve dizide kaldığı yerden devam; bölüm bitince sıradakine geçer
- Görüntü oranı: Sığdır / Uzat / Yakınlaştır (kumandada MENU tuşu veya ⤢)
- Uzun basınca (kumandada OK'e uzun basmak) favorilere ekleme / çıkarma
- Tek APK: telefon (dokunmatik, dikey ve yatay) ve Android TV / Box (kumanda, Leanback launcher)

## Performans
- Release APK R8 ile küçültülür ve optimize edilir (~2 MB). Cihaza her zaman **release** APK kurun; debug sürümü belirgin şekilde yavaştır.
- Kanal/film listeleri akış halinde ayrıştırılır ve 6 saat diskte önbelleklenir. "Yenile" sunucudan taze liste çeker.
- Canlı TV yüklendikten sonra Film ve Dizi listeleri arka planda hazırlanır.
- Logolar/afişler bellek + 200 MB disk önbelleğinde, RGB565 ile tutulur.
- Arama arka planda ve 300 ms gecikmeli çalışır; kategori geçişleri önceden gruplanmış listeden yapılır.
- Canlı yayın 0,5 sn, film/dizi 1 sn tamponla başlar (varsayılan 2,5 sn).
- Hızlı kanal tarama: tuşa arka arkaya basınca sadece kanal adı değişir, yayın durulan kanalda açılır.
- Kumandadaki rakam tuşlarıyla kanal numarası girilebilir.

## Güvenlik
- Kayıtlı listeler (sunucu, kullanıcı adı, şifre, M3U adresi), favoriler ve son izlenenler Android Keystore'daki
  AES-256-GCM anahtarıyla şifreli saklanır (Android 6.0+). Eski sürümden kalan kayıtlar ilk açılışta şifrelenir.
- Uygulama verisi yedeklenmez (`allowBackup=false`, bulut/cihaz aktarımı kapalı); adb backup ile şifreler alınamaz.
- Ebeveyn PIN'i tuzlu PBKDF2 özeti olarak saklanır; 5 hatalı denemeden sonra 30 sn'den başlayıp 15 dk'ya kadar artan bekleme.
- Yerel QR sunucusu: 5 yanlış koddan sonra 60 sn bekleme, 10 yanlışta kod yenilenir; en fazla 4 eşzamanlı bağlantı;
  istek boyutu sınırlı; CSP / X-Frame-Options başlıkları. Sunucu yalnızca liste ekleme ekranı açıkken çalışır.
- Yalnızca http/https adresleri kabul edilir (file://, content:// reddedilir); indirilen liste en fazla 300 MB.
- HTTPS'te yalnızca sistem sertifikalarına güvenilir; release'te oynatıcı ve uygulama kayıtları (logcat) kapalı.
- **Bilinen sınırlar:** IPTV sunucularının çoğu düz HTTP kullandığı için yayın ve giriş trafiği ağda şifresizdir.
  QR ile telefondan gönderilen bilgiler de yerel ağda HTTP ile gider; ortak Wi-Fi'da kullanmayın.

## Release imzası
Yayınlamadan önce kendi anahtarınızı oluşturun ve **güvenli bir yerde yedekleyin** (kaybederseniz güncelleme yayınlayamazsınız):
```
keytool -genkeypair -v -keystore hako-release.keystore -alias hako -keyalg RSA -keysize 4096 -validity 10000
```
Proje köküne `keystore.properties` (git'e eklenmez):
```
storeFile=hako-release.keystore
storePassword=...
keyAlias=hako
keyPassword=...
```
Dosya yoksa release APK debug anahtarıyla imzalanır (yalnızca test için).

## Kurulum
`HakoPlayer-1.0.0.apk` dosyasını cihaza atıp açın ("Bilinmeyen kaynaklar" izni gerekir).
TV box için: APK'yı USB belleğe atın veya "Downloader" uygulamasıyla indirin.

## Derleme
Gereken: JDK 17 veya üzeri ve Android SDK (platform 35).
```
./gradlew assembleRelease
```
Çıktı: `app/build/outputs/apk/release/app-release.apk`

## Yayın öncesi yapılacaklar
- Kendi imza anahtarınızı oluşturup `app/build.gradle.kts` içindeki release `signingConfig` ayarını değiştirin
- Uygulama adı, ikon ve paket adı (`com.hako.player`) kendi markanızla değiştirilmeli
- Google Play'de IPTV uygulamaları için "içerik sağlamıyoruz" açıklaması zorunlu tutuluyor
