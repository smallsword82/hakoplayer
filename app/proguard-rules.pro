# Kayıtlı verilerde enum adları (Kind.LIVE vb.) kullanılıyor
-keep enum com.hako.player.Kind { *; }
# Hata mesajlarında satır numarası kalsın
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release'te android.util.Log çağrıları tamamen kaldırılır (bilgi sızıntısı olmasın)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static boolean isLoggable(java.lang.String, int);
}
