// Aura 0.1.101 public recombination build, 2026-10-20. Packaged assets are bootstrapped from the matching public APK.
plugins { id("com.android.library") }

android {
    namespace = "com.winlator"
    buildToolsVersion = "37.0.0"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 34
        // Upstream base documented in THIRD_PARTY_NOTICES.md; Aura carries local runtime patches.
        buildConfigField("String", "WINLATOR_VERSION", "\"11.2\"")
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    buildFeatures { buildConfig = true }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Export the base class of the engine's public Activities to the Kotlin host module.
    api("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.preference:preference:1.2.1")
    // XServerDisplayActivity publicly implements NavigationView's listener interface.
    api("com.google.android.material:material:1.13.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    implementation("org.tukaani:xz:1.11")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    // Shared with the host/Steam dependency; decode only bounded native-crash metadata.
    implementation("com.google.protobuf:protobuf-java:4.31.1")
}
