plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Base64

val keystoreFile = rootProject.file("keystore.jks")
val keystoreBase64File = rootProject.file("keystore.base64")
if (keystoreBase64File.exists() && (!keystoreFile.exists() || keystoreFile.length() == 0L)) {
    try {
        val cleanBase64 = keystoreBase64File.readText().replace("\r", "").replace("\n", "").trim()
        val decodedBytes = Base64.getDecoder().decode(cleanBase64)
        keystoreFile.writeBytes(decodedBytes)
    } catch (_: Exception) {}
}

android {
    namespace = "com.cosu.kiosk"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "kiosk"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.cosu.kiosk"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Use standard Android debug signing to prevent CI packageDebug password errors
        }
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
