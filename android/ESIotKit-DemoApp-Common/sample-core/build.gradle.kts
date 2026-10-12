import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary) apply false
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.multiplatform)
}

// ============================================================
// 平台开关：由消费方工程通过 gradle.properties 提供
//   ESIotKit-DemoApp-Android → esiotkit.demo.platform=android
//   ESIotKit-DemoApp-iOS     → esiotkit.demo.platform=ios
// 未设置时两端全开（兼容单独打开本模块的场景）。
// ============================================================
val demoPlatform = providers.gradleProperty("esiotkit.demo.platform").orNull
val buildAndroid = demoPlatform != "ios"
val buildIos = demoPlatform != "android"

// ============================================================
// CocoaPods 插件仅 iOS 模式应用（根治 Android 侧 Gradle Sync 失败）。
//
// 原因：kotlin("native.cocoapods") 无条件应用时，即使没有 Apple 目标，
// 插件也会创建 generateDummyFramework 等任务；其 frameworkName/outputDsym
// 属性引用 apple targets 集合（Android 模式下为空）。Gradle 在任务图校验/
// IDE Sync 查询任务属性时会强制求值 → "Collection is empty."。
// （enabled=false 只跳过执行动作，不阻止属性求值，无法修复 Sync。）
//
// iOS 侧不受影响：SampleCore.podspec 为仓库内静态文件（不依赖 podspec 任务
// 生成），Xcode 构建脚本调用 :sample-core:syncFramework（iOS 模式下插件正常
// 应用，任务存在）。
// ============================================================
if (buildIos) {
  apply(plugin = "org.jetbrains.kotlin.native.cocoapods")
}

if (buildAndroid) {
  apply(plugin = "com.android.library")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
  }

  if (buildAndroid) {
    androidTarget {
      compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
      }
    }
  }

  if (buildIos) {
    iosArm64()

    // ==========================================================
    // iOS 集成（迁移自原 demoapp）：CocoaPods + 原生框架链接
    // 注意：插件改为条件 apply 后，类型安全访问器 cocoapods{} 不可用，
    // 需通过扩展名显式获取 CocoapodsExtension 配置。
    // ==========================================================
    configure<org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension> {
      version = "2.2.7"
      summary = "ESIotKit Demo SampleCore"
      homepage = "https://www.eufy.com"
      license = "Proprietary"
      authors = "Eufy"
      ios.deploymentTarget = "14.0"
      pod("SQLCipher", "4.5.4", linkOnly = true)
      pod("SSZipArchive")
      pod("AWSCore", "2.41.0", linkOnly = true)
      pod("AWSIoT", "2.41.0", linkOnly = true)
      framework {
        baseName = "SampleCore"
        isStatic = false
        linkerOpts(
          "-F${rootProject.projectDir}/../libs/ios-frameworks/BC_P2PClient",
          "-F${rootProject.projectDir}/../libs/ios-frameworks/BC_VideoSDK",
          "-F${rootProject.projectDir}/../libs/ios-frameworks/A2BProject",
          "-F${rootProject.projectDir}/../libs/ios-frameworks/openssl",
          "-F${rootProject.projectDir}/../libs/ios-frameworks/BC_WebRTC",
          "-F${rootProject.projectDir}/../libs/ios-frameworks/BC_MediaLib",
          "-F${rootProject.projectDir}/../libs/ios-frameworks/BC_Renderer_Protocol",
          "-F${rootProject.projectDir}/../libs/ios-frameworks",
          "-weak_framework", "AnkerWebRTC",
          "-weak_framework", "BC_P2PClient",
          "-weak_framework", "BC_VideoSDK",
          "-weak_framework", "A2BProject",
          "-weak_framework", "openssl",
          "-weak_framework", "BC_WebRTC",
          "-weak_framework", "BC_MediaLib",
          "-weak_framework", "BC_Renderer_Protocol",
          "-framework", "OpenAL",
          "-framework", "AVFoundation",
          "-framework", "CoreMedia",
          "-framework", "VideoToolbox",
          "-framework", "AudioToolbox",
          "-framework", "CoreTelephony",
          "-liconv",
          "-ObjC"
        )
      }
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        // ============================================================
        // ESIotKit 依赖见平台侧：
        //   Android = fat-AAR（androidMain）；iOS = klib 集成包（iosMain）
        // ============================================================

        // ============================================================
        // SDK 运行时三方依赖（fat-AAR 未内嵌类库，需宿主工程提供）
        // ============================================================
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.serialization.json)
        api(libs.kotlinx.dateTime)
        api(libs.okio.core)
        api(libs.ktor.network)
        api(libs.androidx.annotation)
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.websockets)
        implementation(libs.koin.core)

        // ============================================================
        // UI
        // ============================================================
        api(compose.ui)
        api(compose.foundation)
        api(compose.material)
        api(compose.animation)
        api(compose.runtime)
        api(compose.material3)
        api(compose.materialIconsExtended)
        api(compose.components.uiToolingPreview)
        api(compose.components.resources)
        api(libs.voyager.navigator)
        api(libs.voyager.koin)
        api(libs.voyager.bottomSheetNavigator)
        api(libs.voyager.transitions)
        api(libs.voyager.screenModel)
        api(libs.voyager.tabNavigator)
        api(libs.molecule.runtime)
        api(libs.unstyled)
        api(libs.kermit)
        api(libs.coil.core)
        api(libs.coil.compose)
        api(libs.coil.svg)
        implementation(libs.koin.compose)
        implementation(kotlin("reflect"))
        implementation(libs.moko.permissions)
        implementation(libs.moko.permissionsCompose)
        implementation(libs.kotlinx.atomic)
        implementation(libs.easyqrscan)
        implementation(libs.sonner)
        implementation(libs.korge.krypto)
        implementation(libs.uuid)
        implementation(libs.cryptography.core)
        implementation(libs.cryptography.optimal)
        implementation(libs.kotlinx.io.core)
        implementation(libs.kotlinx.collections.immutable)
        implementation(libs.stately.common)
        implementation(libs.ktorfit.lib)
        implementation(libs.napier)
        implementation(libs.ktor.client.contentNegotiation)
        implementation(libs.ktor.serializationKotlinxJson)
        implementation(libs.ktor.client.logging)
      }
    }
    if (buildAndroid) {
      androidMain.dependencies {
        // Android：ESIotKit fat-AAR（Release 产物直接放入工程）
        api(fileTree(mapOf("dir" to "${rootProject.projectDir}/libs", "include" to listOf("ESIotKit-*.aar"))))
        api(project.dependencies.platform(libs.compose.bom))
        implementation(libs.ui)
        implementation(libs.ui.tooling)
        implementation(libs.ui.tooling.preview)
        api(libs.coil.networkOkhttp)
        api(compose.uiTooling)
        api(compose.preview)
        api(libs.activity.compose)
        api(libs.accompanist.permissions)
        implementation(libs.zxing.core)
        implementation(libs.appcompat)
        implementation(libs.ktor.client.okhttp)
        implementation(libs.sqldelight.android.driver)
        implementation(libs.sqldelight.runtime)
        implementation(libs.sqldelight.primitive.adapters)
        implementation(libs.aws.iot)
        implementation(libs.paho.mqtt)
        implementation(libs.conscrypt)
        implementation(libs.zip4j)
        implementation(libs.gson)
        implementation(libs.okhttp)
        implementation(libs.datastore.preferences)
        implementation(libs.datastore.core.okio)
        implementation(libs.room.runtime)
        implementation(libs.room.ktx)
        implementation(libs.parcelize.runtime)
        implementation(libs.javax.inject)
        implementation(libs.cryptography.jdk)
      }
    }
    if (buildIos) {
      iosMain.dependencies {
        // iOS：ESIotKit klib 集成包（本地 maven：libs/ios-kotlin-repo）
        api("com.anker.esiotkit:ESIotKit:2.2.7")
        api("com.anker.esiotkit:ESIotMegaKit:2.2.6.7.dev.8")
        api("io.github.g0dkar:qrcode-kotlin:4.1.1")
        // iOS 网络引擎
        api("io.coil-kt.coil3:coil-network-ktor3:3.0.0-rc01")
        implementation("io.ktor:ktor-client-darwin:3.3.3")
      }
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    if (buildAndroid) {
      androidUnitTest.dependencies {
        implementation(libs.junit)
        implementation(libs.mockito.core)
        implementation(libs.mockito.kotlin)
        implementation(libs.mockk)
        implementation(libs.robolectric)
        implementation(libs.compose.ui.test.junit4)
      }
    }
  }
}

if (buildAndroid) {
  extensions.getByType(LibraryExtension::class.java).apply {
    namespace = "com.anker.esiotkit.samplecore"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].java.srcDirs("src/androidMain/kotlin")

    defaultConfig {
      minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
      sourceCompatibility = JavaVersion.VERSION_17
      targetCompatibility = JavaVersion.VERSION_17
    }
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildFeatures {
      compose = true
    }
    testOptions {
      unitTests.isIncludeAndroidResources = true
    }
  }
}

// Compose Multiplatform 资源配置：固定生成的资源访问器包名
compose.resources {
  packageOfResClass = "com.anker.esiotkit.demo.generated.resources"
}

kotlin.sourceSets.configureEach {
  languageSettings {
    optIn("kotlinx.cinterop.ExperimentalForeignApi")
    optIn("kotlin.experimental.ExperimentalNativeApi")
    optIn("kotlin.io.encoding.ExperimentalEncodingApi")
    optIn("androidx.compose.material3.ExperimentalMaterial3Api")
    optIn("androidx.compose.foundation.ExperimentalFoundationApi")
    optIn("androidx.compose.material.ExperimentalMaterialApi")
  }
}

tasks.withType<Test>().configureEach {
  systemProperty("runAwsSmoke", System.getProperty("runAwsSmoke") ?: "false")
  systemProperty("runRouterSmoke", System.getProperty("runRouterSmoke") ?: "false")
  systemProperty("runRouterConcurrent", System.getProperty("runRouterConcurrent") ?: "false")
  System.getProperty("testDataDir")?.let { systemProperty("testDataDir", it) }
}

// sample-core 仅被 :app 直接消费，且依赖本地 fat-AAR（AGP 不支持为库产出包含本地 .aar 的 AAR），
// 因此禁用 AAR 发布打包任务。
// 注意：排除 LocalLintAar —— lint 任务链（lintVitalAnalyze* / generateDebugAndroidTestLintModel）依赖
// local_aar_for_lint 的 out.aar 产物，误禁会导致 ./gradlew build 全链失败（ExtractAarTransform 缺文件）。
tasks.matching {
  it.name.startsWith("bundle") && it.name.endsWith("Aar") && !it.name.contains("LocalLintAar")
}.configureEach { enabled = false }

// commonMain 直接引用平台侧 SDK（Android fat-AAR / iOS klib），无公共元数据可产出；
// 该模块仅按目标编译被 :app 与 iOS 框架消费，故禁用 metadata 编译，保证 ./gradlew build 全链路可过。
tasks.matching { it.name == "compileCommonMainKotlinMetadata" }.configureEach { enabled = false }
