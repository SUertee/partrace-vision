package com.anker.esiotkit.sample.support.platform

/**
 * App version information
 */
data class AppVersionInfo(
  val versionName: String,
  val versionCode: Int
)

/**
 * Get current app version info
 */
expect fun getAppVersionInfo(): AppVersionInfo
