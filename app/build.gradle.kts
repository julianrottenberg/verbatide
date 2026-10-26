import java.util.Base64

private val BASE64_DECODER = Base64.getDecoder()
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
        versionCode = 36
        versionName = "0.9.21"

        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        // The keystore is never committed. CI supplies it via env vars; local
        // builds fall back to the default Android debug key.
        // Required CI secrets: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD,
        // ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD.
        val ksB64 = System.getenv("ANDROID_KEYSTORE_BASE64")
        if (!ksB64.isNullOrBlank()) {
            val ksFile = layout.buildDirectory.file("ci-keystore.p12").get().asFile
            ksFile.parentFile.mkdirs()
            ksFile.writeBytes(BASE64_DECODER.decode(ksB64))
            named("debug") {
                storeFile = ksFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                storeType = "PKCS12"
            }
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
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
