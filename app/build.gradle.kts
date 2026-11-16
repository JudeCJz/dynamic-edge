import java.util.Properties

plugins {
  id("com.android.application")
}

// Release signing key: keystore.properties + keystore/ live next to the project and are never committed.
val keyProps = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
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
    versionCode = 15
    versionName = "1.9.0"
  }

  signingConfigs {
    create("release") {
      if (keyProps.getProperty("storeFile") != null) {
        storeFile = rootProject.file(keyProps.getProperty("storeFile"))
        storePassword = keyProps.getProperty("storePassword")
        keyAlias = keyProps.getProperty("keyAlias")
        keyPassword = keyProps.getProperty("keyPassword")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      // the real release key when keystore.properties exists, else the debug key (local builds only)
      signingConfig = if (keyProps.getProperty("storeFile") != null) signingConfigs.getByName("release")
        else signingConfigs.getByName("debug")
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
