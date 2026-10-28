import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version lives in android/version.properties (scripts/release.sh bumps it).
// versionCode is derived: 0.1.3 -> 103, 1.2.10 -> 10210. -PvpndeckVersion=… overrides
// it for test builds (e.g. an older-looking build to exercise the updater).
val vpndeckVersion: String = (findProperty("vpndeckVersion") as String?)
    ?: Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }.getProperty("VERSION_NAME")
val vpndeckVersionCode: Int = vpndeckVersion.split('.').map { it.toInt() }.let { p ->
    require(p.size in 3..4 && p.all { it in 0..99 }) { "version must look like 0.1.3 (or 0.1.2.9 for tests)" }
    p[0] * 10000 + p[1] * 100 + p[2] // a 4th part only marks test builds and doesn't count
}

// Release signing comes from android/keystore.properties (not in git).
// Keep that keystore safe: phones only accept updates signed with the same key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.vpndeck"
    compileSdk = 37
    compileSdkMinor = 1
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.vpndeck"
        minSdk = 26
        targetSdk = 36
        versionCode = vpndeckVersionCode
        versionName = vpndeckVersion
        base.archivesName.set("vpndeck-$versionName")
        buildConfigField("String", "UPDATE_REPO", "\"maksimvpronin-byte/Vpndeck\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json; Profile and ConfigBuilder tests need the real thing.
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    implementation(files("libs/libbox.aar"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
