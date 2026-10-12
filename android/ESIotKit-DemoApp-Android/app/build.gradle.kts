plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.kotlinAndroid)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.anker.esiotkit.sample"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "com.anker.esiotkit.demo"
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    versionCode = 44
    versionName = "2.2.7"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables {
      useSupportLibrary = true
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
  buildFeatures {
    compose = true
  }
  packagingOptions {
    excludes += listOf(
      "META-INF/*.kotlin_module",
      "META-INF/LICENSE*",
      "META-INF/NOTICE*",
      "META-INF/ASL2.0",
    )
    jniLibs {
      useLegacyPackaging = false
    }
    pickFirsts.addAll(
      listOf(
        "lib/armeabi-v7a/*.so",
        "lib/arm64-v8a/*.so",
      )
    )
  }
}

dependencies {
  // Demo 业务与 UI 层
  implementation(project(":sample-core"))

  // AndroidX / UI
  implementation(libs.appcompat)
  implementation(libs.activity.compose)

  // QR Code
  implementation(libs.zxing.core)
}

configurations.all {
  resolutionStrategy {
    force("org.slf4j:slf4j-api:1.7.32")
    force("androidx.appcompat:appcompat:1.6.1")
    force("androidx.fragment:fragment:1.5.4")
    force("androidx.appcompat:appcompat-resources:1.6.1")
    force("androidx.lifecycle:lifecycle-livedata-core:2.7.0")
    force("androidx.profileinstaller:profileinstaller:1.3.1")
    force("io.insert-koin:koin-core:4.1.1")
  }
}
