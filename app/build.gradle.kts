plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Base64

// Decode permanent shared debug keystore so every CI build shares the identical signature
val debugKeystoreFile = rootProject.file("debug.keystore")
val debugKeystoreBase64File = rootProject.file("debug.keystore.base64")
if (debugKeystoreBase64File.exists() && (!debugKeystoreFile.exists() || debugKeystoreFile.length() == 0L)) {
    try {
        val cleanBase64 = debugKeystoreBase64File.readText().replace("\r", "").replace("\n", "").trim()
        val decodedBytes = Base64.getDecoder().decode(cleanBase64)
        debugKeystoreFile.writeBytes(decodedBytes)
    } catch (_: Exception) {}
}

android {
    namespace = "com.cosu.kiosk"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.cosu.kiosk"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Dhizuku API for Device Owner Proxy Integration
    implementation(libs.dhizuku.api)
}
