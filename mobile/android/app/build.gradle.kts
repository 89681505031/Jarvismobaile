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
        // Only the isolated PLUS tester can be ARM64-only. Keep standard
        // debug/release outputs universal for device and emulator coverage.
        if (System.getenv("JARVIS_ARM64_ONLY") == "1") {
            ndk { abiFilters.add("arm64-v8a") }
        }
    }

    // Optional isolated package for real-device microphone tests; never replaces
    // the installed Jarvis, so the existing app and its data remain untouched.
    buildTypes {
        getByName("debug") {
            if (System.getenv("JARVIS_DIAGNOSTIC") == "1") {
                applicationIdSuffix = ".hud3"
                manifestPlaceholders["jarvisLabel"] = "J.A.R.V.I.S. HUD 3"
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
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
