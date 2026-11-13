import java.util.Properties

plugins {
    id("com.android.application")
}

// The watch's association secret (klSecret) lives outside git, in secrets.properties:
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
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
