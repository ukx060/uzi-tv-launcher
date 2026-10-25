plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ee.uzi.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "ee.uzi.tvlauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.currentTimeMillis() / 60000L).toInt() // minutes since epoch: always increasing
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    val ksFile = rootProject.file(System.getenv("UZI_KEYSTORE") ?: "keystore/uzi.keystore")
    val ksPass = System.getenv("UZI_KEYSTORE_PASS")
    val hasKey = ksFile.exists() && ksPass != null

    signingConfigs {
        if (hasKey) create("uzi") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = "uzi"
            keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKey) signingConfigs.getByName("uzi") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = true
        textReport = true
        textOutput = file("build/lint.txt")
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
