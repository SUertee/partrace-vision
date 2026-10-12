pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "ESIotKit-DemoApp-Android"

include(":app")

// 公共模块（与 iOS 工程共享；存放于同级目录 ../ESIotKit-DemoApp-Common）
include(":sample-core")
project(":sample-core").projectDir = file("../ESIotKit-DemoApp-Common/sample-core")
