plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace   = "com.crm.whatsagent"
    compileSdk  = 34

    defaultConfig {
        applicationId   = "com.crm.whatsagent"
        minSdk          = 29          // Android 10 minimum
        targetSdk       = 34
        versionCode     = 1
        versionName     = "1.0.0"

        // Injected via local.properties or CI env vars
        buildConfigField("String", "BACKEND_WS_URL",  "\"${project.findProperty("BACKEND_WS_URL") ?: "wss://api.crm.example.com/ws/agent"}\"")
        buildConfigField("String", "BACKEND_API_URL", "\"${project.findProperty("BACKEND_API_URL") ?: "https://api.crm.example.com"}\"")
        buildConfigField("String", "DEVICE_TOKEN",    "\"${project.findProperty("DEVICE_TOKEN") ?: "REPLACE_WITH_REAL_TOKEN"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // AndroidX
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager (background retry)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
