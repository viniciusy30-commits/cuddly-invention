val accessControlUrl = providers.gradleProperty("accessControlUrl")
    .orElse(providers.environmentVariable("ACCESS_CONTROL_URL"))
    .orElse("")
    .get()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.quadbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.quadbrowser"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "ACCESS_CONTROL_URL", "\"${accessControlUrl.replace("\\", "\\\\").replace("\"", "\\\")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}