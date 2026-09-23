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
        manifestPlaceholders["jarvisLabel"] = "J.A.R.V.I.S."
    }

    // Optional isolated package for real-device microphone tests; never replaces
    // the installed Jarvis, so the existing app and its data remain untouched.
    buildTypes {
        getByName("debug") {
            if (System.getenv("JARVIS_DIAGNOSTIC") == "1") {
                applicationIdSuffix = ".diagnostic"
                manifestPlaceholders["jarvisLabel"] = "J.A.R.V.I.S. TEST"
            }
        }
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
