plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nnexe.overlaybrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nnexe.overlaybrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload build: reuse this machine's debug key so updates install over each other.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Lets us call ActivityOptions.setLaunchWindowingMode (hidden API) to open Brave as a freeform window.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
