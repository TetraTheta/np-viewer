plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "io.github.tetratheta.npviewer"
  compileSdk {
    version = release(36) {
      minorApiLevel = 1
    }
  }
  defaultConfig {
    applicationId = "io.github.tetratheta.npviewer"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.6.0"
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
      )
    }
    create("prerelease") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.preference)
  implementation(libs.androidx.preference.ktx)
  implementation(libs.androidx.swiperefreshlayout)
  implementation(libs.androidx.webkit)
  implementation(libs.material)
}
