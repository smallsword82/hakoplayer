plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// Release imzasÄ±: proje kÃ¶kÃ¼nde keystore.properties varsa onu kullanÄ±r (git'e eklenmez)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hako.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hako.player"
        minSdk = 21
        targetSdk = 35
        versionCode = 17
        versionName = "2.3.6"
        // TV box ve telefonlar ARM: FFmpeg kütüphanesinin x86 kopyaları APK'ya girmesin
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8: kod kÃ¼Ã§Ã¼ltme + optimizasyon (daha kÃ¼Ã§Ã¼k ve hÄ±zlÄ± APK)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // keystore.properties yoksa (geliÅŸtirme) debug anahtarÄ±yla imzalanÄ±r
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false; buildConfig = false }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin") }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // MP2, AC-3 vb. cihazda çözücüsü olmayan ses biçimleri için FFmpeg (Jellyfin derlemesi, Media3 1.5 uyumlu)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
