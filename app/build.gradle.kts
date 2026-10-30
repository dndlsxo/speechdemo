plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dndlsxo.speechmemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dndlsxo.speechmemo"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }

    // One fixed key for every build, so a new APK installs over the old one.
    // (CI otherwise generates a fresh debug key per run, and Android rejects the update.)
    signingConfigs {
        create("fixed") {
            storeFile = file("speechmemo.keystore")
            storePassword = "speechmemo"
            keyAlias = "speechmemo"
            keyPassword = "speechmemo"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
