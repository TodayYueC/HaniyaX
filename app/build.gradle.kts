plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.haniya.x"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.haniya.x"
        minSdk = 26
        // Kept at 28 (like Termux) so binaries inside the app data dir may be executed (W^X on API 29+).
        @Suppress("ExpiredTargetSdkVersion")
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/haniyax-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("HANIYAX_KS_PASS") ?: "haniyax"
                keyAlias = "haniyax"
                keyPassword = System.getenv("HANIYAX_KS_PASS") ?: "haniyax"
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = "" }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (rootProject.file("keystore/haniyax-release.jks").exists()) "release" else "debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += listOf("xz") }
    packaging { resources.excludes += listOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*") }
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
