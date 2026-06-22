plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "io.github.tetratheta.npviewer"
  // compileSdk controls which Android API level the app is compiled against.
  // Raising it does not opt the app into new platform runtime behavior.
  compileSdk = 37
  defaultConfig {
    applicationId = "io.github.tetratheta.npviewer"
    // minSdk is the oldest Android version this app supports.
    minSdk = 31
    // targetSdk opts the app into platform behavior changes up to this API level.
    // Keep it separate from compileSdk so dependency API requirements can be met
    // without adopting newer runtime behavior before it is tested.
    //noinspection OldTargetApi - Setting it to 37 nags me, so I'll stay in 36 for awhile.
    targetSdk = 36
    versionCode = (property("app.versionCode") as String).toInt()
    versionName = property("app.versionName") as String
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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  androidResources {
    noCompress += "js"
  }
}

kotlin {
  jvmToolchain(17)
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
