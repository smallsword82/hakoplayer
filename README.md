# HakoPlayer (Android telefon + Android TV / Box)

IBO Player / Hiremco tarzı bir IPTV **oynatıcı**. İçerik sağlamaz; kullanıcı kendi Xtream Codes
veya M3U aboneliğini ekler.

## Özellikler
- Xtream Codes girişi (sunucu / kullanıcı / şifre) ve M3U URL desteği, birden fazla kayıtlı liste
- **Telefonla ekleme (QR):** Liste ekranında QR kod çıkar. Telefonla okutunca TV'de çalışan yerel sunucunun
  sayfası açılır (`http://<cihaz-ip>:8686`). Sunucu, kullanıcı adı ve şifre telefondan girilir; bilgiler
  doğrulanır, TV'ye kaydedilir ve liste otomatik açılır. Ekrandaki 4 haneli kod olmadan ekleme yapılamaz.
  Telefon ve cihaz aynı ağda olmalı.
- Canlı TV, Filmler, Diziler (sezon ve bölüm), Favoriler, Son İzlenenler
- Kategoriler ve anlık arama
- ExoPlayer (Media3): HLS (.m3u8), MPEG-TS (.ts), MP4/MKV; hata olursa otomatik tekrar dener
- Canlı TV'de kumandayla Yukarı/Aşağı veya CH+/CH- ile kanal değiştirme, kısa EPG (Şimdi / Sonra)
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
