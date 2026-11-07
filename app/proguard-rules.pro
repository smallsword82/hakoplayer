# Kayıtlı verilerde enum adları (Kind.LIVE vb.) kullanılıyor
-keep enum com.hako.player.Kind { *; }
# Hata mesajlarında satır numarası kalsın
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
