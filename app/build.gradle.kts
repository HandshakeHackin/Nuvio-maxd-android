import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val signingPath = System.getenv("NUVIO_KEYSTORE_PATH")

android {
    namespace = "io.github.handshakehackin.nuviomaxd.androidtv"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "io.github.handshakehackin.nuviomaxd.androidtv"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersion.getProperty("VERSION_CODE").toInt()
        versionName = releaseVersion.getProperty("VERSION_NAME")
        ndk {
            abiFilters += (providers.gradleProperty("nuvioAbis").orNull ?: "arm64-v8a,armeabi-v7a,x86_64").split(",")
        }
        externalNativeBuild { cmake { cppFlags += "-std=c++20" } }
    }
    signingConfigs {
        if (signingPath != null) {
            create("androidRelease") {
                storeFile = file(signingPath)
                storePassword = System.getenv("NUVIO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NUVIO_KEY_ALIAS")
                keyPassword = System.getenv("NUVIO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            if (signingPath != null) signingConfig = signingConfigs.getByName("androidRelease")
        }
    }
    buildFeatures { buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.videolan.android:libvlc-all:3.6.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
