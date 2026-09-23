plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.phone"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("JARVIS_VERSION_CODE")?.toIntOrNull() ?: 200
        versionName = System.getenv("JARVIS_VERSION_NAME") ?: "0.3.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
}
