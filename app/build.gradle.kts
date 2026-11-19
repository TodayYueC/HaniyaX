import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: keystore/haniyax-release.jks + keystore/keystore.properties (both git-ignored).
val ksProps: Properties = Properties().also { p ->
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
val hasReleaseKey: Boolean = rootProject.file("keystore/haniyax-release.jks").exists() && ksProps.getProperty("storePassword") != null

android {
    namespace = "dev.haniya.x"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "dev.haniya.x"
        minSdk = 26
        // Kept at 28 (like Termux) so binaries inside the app data dir may be executed (W^X on API 29+).
        @Suppress("ExpiredTargetSdkVersion")
        targetSdk = 28
        versionCode = 3
        versionName = "0.1.2"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file("keystore/haniyax-release.jks")
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias", "haniyax")
            keyPassword = ksProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            // Debug builds also run on x86_64 emulators (native x86_64 proot + amd64 rootfs in src/debug/assets).
            ndk { abiFilters += listOf("x86_64") }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += listOf("xz", "ttf") }
    packaging {
        resources.excludes += listOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(project(":relief"))
    implementation(project(":terminal-view"))
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
