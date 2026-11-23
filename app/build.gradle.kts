import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val patchedOpenhongguoAar = providers.gradleProperty("patchedOpenhongguoAar").orNull

val signingFile = rootProject.file("hongguoTV-release.jks")
val signingSecrets = Properties().apply {
    val file = rootProject.file("signing-secrets.txt")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseSigning = signingFile.exists() &&
    signingSecrets.getProperty("ANDROID_KEYSTORE_PASSWORD").orEmpty().isNotBlank() &&
    signingSecrets.getProperty("ANDROID_KEY_ALIAS").orEmpty().isNotBlank() &&
    signingSecrets.getProperty("ANDROID_KEY_PASSWORD").orEmpty().isNotBlank()

android {
    namespace = "com.xiaobei206.hongguotv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaobei206.hongguotv"
        minSdk = 23
        targetSdk = 35
        versionCode = 14
        versionName = "1.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = signingFile
                storePassword = signingSecrets.getProperty("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingSecrets.getProperty("ANDROID_KEY_ALIAS")
                keyPassword = signingSecrets.getProperty("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module",
        )
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    implementation("androidx.compose.runtime:runtime:1.9.3")
    implementation("androidx.compose.ui:ui:1.9.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.3")
    implementation("androidx.compose.foundation:foundation:1.9.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
    implementation("androidx.media3:media3-session:1.8.1")
    if (patchedOpenhongguoAar != null) {
        implementation(files(patchedOpenhongguoAar))
    } else {
        implementation("io.github.beilunyang:openhongguo:0.4.0")
    }

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
