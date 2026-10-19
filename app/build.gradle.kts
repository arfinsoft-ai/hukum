plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raxvaayan.hukum"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raxvaayan.hukum"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("hukum") {
            storeFile = file("hukum.keystore")
            storePassword = "hukum123"
            keyAlias = "hukum"
            keyPassword = "hukum123"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("hukum") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("hukum")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
