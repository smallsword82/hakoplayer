# HakoPlayer (Android telefon + Android TV / Box)

IBO Player / Hiremco tarzı bir IPTV **oynatıcı**. İçerik sağlamaz; kullanıcı kendi Xtream Codes
veya M3U aboneliğini ekler.

## Özellikler
- Xtream Codes girişi (sunucu / kullanıcı / şifre) ve M3U URL desteği, birden fazla kayıtlı liste
- Canlı TV, Filmler, Diziler (sezon ve bölüm), Favoriler, Son İzlenenler
- Kategoriler ve anlık arama
- ExoPlayer (Media3): HLS (.m3u8), MPEG-TS (.ts), MP4/MKV; hata olursa otomatik tekrar dener
- Canlı TV'de kumandayla Yukarı/Aşağı veya CH+/CH- ile kanal değiştirme, kısa EPG (Şimdi / Sonra)
- Film ve dizide kaldığı yerden devam; bölüm bitince sıradakine geçer
- Görüntü oranı: Sığdır / Uzat / Yakınlaştır (kumandada MENU tuşu veya ⤢)
- Uzun basınca (kumandada OK'e uzun basmak) favorilere ekleme / çıkarma
- Tek APK: telefon (dokunmatik, dikey ve yatay) ve Android TV / Box (kumanda, Leanback launcher)

## Kurulum
`HakoPlayer-1.0.0.apk` dosyasını cihaza atıp açın ("Bilinmeyen kaynaklar" izni gerekir).
TV box için: APK'yı USB belleğe atın veya "Downloader" uygulamasıyla indirin.

## Derleme
Gereken: JDK 17 veya üzeri ve Android SDK (platform 35).
```
./gradlew assembleDebug
```
Çıktı: `app/build/outputs/apk/debug/app-debug.apk`

## Yayın öncesi yapılacaklar
- Kendi imza anahtarınızı oluşturup `app/build.gradle.kts` içindeki release `signingConfig` ayarını değiştirin
- Uygulama adı, ikon ve paket adı (`com.hako.player`) kendi markanızla değiştirilmeli
- Google Play'de IPTV uygulamaları için "içerik sağlamıyoruz" açıklaması zorunlu tutuluyor
