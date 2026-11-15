plugins {
  id("com.android.application")
}

android {
  namespace = "com.dynedge"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.dynedge"
    minSdk = 30
    // Deliberately 28: keeps WifiManager.setWifiEnabled, BluetoothAdapter.enable/disable and the
    // hidden insets-listener API usable. See the dynamic-edge skill.
    targetSdk = 28
    versionCode = 10
    versionName = "1.7.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    buildConfig = false
    aidl = false
    shaders = false
    resValues = false
  }

  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }

  packaging {
    resources {
      excludes += setOf(
        "/META-INF/*.kotlin_module",
        "/META-INF/*.version",
        "/META-INF/com/android/build/gradle/app-metadata.properties",
        "/DebugProbesKt.bin",
        "/kotlin-tooling-metadata.json",
      )
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }
}

kotlin {
  jvmToolchain(17)
}
