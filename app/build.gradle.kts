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
    defaultConfig {
        applicationId = "io.github.handshakehackin.nuviomaxd.androidtv"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersion.getProperty("VERSION_CODE").toInt()
        versionName = releaseVersion.getProperty("VERSION_NAME")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
