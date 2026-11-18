plugins { id("com.android.library") }
android {
    namespace = "com.termux.emulator"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        externalNativeBuild { ndkBuild { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections") } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
