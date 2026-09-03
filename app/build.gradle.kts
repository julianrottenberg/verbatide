plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.julianrottenberg.verbatide"

    // APK file name: verbatide-vX.Y.Z.apk

    compileSdk = 35

    defaultConfig {
        applicationId = "com.julianrottenberg.verbatide"
        minSdk = 30
        targetSdk = 35
        versionCode = 28
        versionName = "0.9.13"

        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        // Committed debug keystore so every CI build signs with the SAME key.
        // Without this, each CI run generates a fresh debug key and Android
        // refuses in-place updates (signature mismatch). Debug certs are not
        // security-sensitive; release signing (if ever added) uses secrets.
        named("debug") {
            storeFile = file("debug.p12")
            storePassword = "android"
            keyAlias = "phonewhisper"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    // sherpa-onnx Kotlin API wrapper is vendored under app/src/main/kotlin/com/k2fsa/sherpa/onnx/
    // but the native libsherpa-onnx-jni.so ships via this AAR (fixes UnsatisfiedLinkError
    // that crashed the accessibility service on startup).
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
