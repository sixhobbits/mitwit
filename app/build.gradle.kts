import java.util.Properties

plugins {
    id("com.android.application")
}

// The watch's Bluetooth address and association secret (klSecret) live outside git,
// in secrets.properties:
//   watchAddress=AA:BB:CC:DD:EE:FF
//   watchSecret=...
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.scanwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.scanwatch"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "WATCH_SECRET", "\"${secrets.getProperty("watchSecret", "")}\"")
        buildConfigField("String", "WATCH_ADDRESS", "\"${secrets.getProperty("watchAddress", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
