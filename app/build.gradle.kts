import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** Build Android executables, then package them under library names for APK-managed extraction. */
abstract class BuildHostTools @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val jniOutput: DirectoryProperty

    @TaskAction
    fun build() {
        val ndk = ndkDir.get().asFile
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val tool = File(ndk, if (windows) "ndk-build.cmd" else "ndk-build")
        val libs = File(temporaryDir, "libs")
        val output = jniOutput.get().asFile
        output.deleteRecursively()
        execOperations.exec {
            workingDir(sources.get().asFile)
            val launch = if (windows) listOf("cmd.exe", "/c", tool.absolutePath) else listOf(tool.absolutePath)
            commandLine(launch + listOf(
                "NDK_PROJECT_PATH=null",
                "APP_BUILD_SCRIPT=${sources.get().file("Android.mk").asFile.absolutePath}",
                "APP_PLATFORM=android-26",
                "APP_ABI=${abis.get().joinToString(" ")}",
                "NDK_OUT=${File(temporaryDir, "obj").absolutePath}",
                "NDK_LIBS_OUT=${libs.absolutePath}",
            ))
        }
        for (abi in abis.get()) {
            for (toolName in listOf("haniyax_host_runner", "haniyax_host_cli")) {
                File(libs, "$abi/$toolName").copyTo(File(output, "$abi/lib$toolName.so"), overwrite = true)
            }
        }
    }
}

val hostNdkVersion = "28.2.13676358"

// Release signing: keystore/haniyax-release.jks + keystore/keystore.properties (both git-ignored).
val ksProps: Properties = Properties().also { p ->
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
val hasReleaseKey: Boolean = rootProject.file("keystore/haniyax-release.jks").exists() && ksProps.getProperty("storePassword") != null

android {
    namespace = "dev.haniya.x"
    ndkVersion = hostNdkVersion
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "dev.haniya.x"
        minSdk = 26
        // Kept at 28 (like Termux) so binaries inside the app data dir may be executed (W^X on API 29+).
        @Suppress("ExpiredTargetSdkVersion")
        targetSdk = 28
        versionCode = 10
        versionName = "0.1.9"
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
        jniLibs.keepDebugSymbols += setOf("**/libhaniyax_host_runner.so", "**/libhaniyax_host_cli.so")
        resources.excludes += listOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

androidComponents {
    val ndkPath = sdkComponents.ndkDirectory
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercaseChar() }
        val buildHostTools = tasks.register<BuildHostTools>("compile${name}HostTools") {
            sources.set(layout.projectDirectory.dir("src/main/host-native"))
            ndkDir.set(ndkPath)
            abis.set(if (variant.buildType == "debug") listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildHostTools, BuildHostTools::jniOutput)
    }
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
