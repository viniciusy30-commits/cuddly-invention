plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.quadbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.quadbrowser"
        minSdk = 23
        targetSdk = 35
        // O número do run do GitHub vira o versionCode: é ele que o app compara
        // com a tag da Release (v12 -> 12) para saber se há versão nova.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
    }

    // Keystore FIXO: todo APK sai assinado com a mesma chave, senão o Android
    // não deixa instalar a atualização por cima. NUNCA troque este arquivo.
    signingConfigs {
        getByName("debug") {
            storeFile = file("quadbrowser.keystore")
            storePassword = "quadbrowser123"
            keyAlias = "quadbrowser"
            keyPassword = "quadbrowser123"
        }
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
