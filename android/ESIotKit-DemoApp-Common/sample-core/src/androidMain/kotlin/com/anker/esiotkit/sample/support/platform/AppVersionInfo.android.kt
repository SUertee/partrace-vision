package com.anker.esiotkit.sample.support.platform

import android.os.Build
import com.anker.esiotkit.sample.support.sampleApplication

@Suppress("DEPRECATION")
actual fun getAppVersionInfo(): AppVersionInfo {
  val context = sampleApplication ?: return AppVersionInfo("Unknown", 0)
  val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
  return AppVersionInfo(
    versionName = packageInfo.versionName ?: "Unknown",
    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.longVersionCode.toInt()
    } else {
      packageInfo.versionCode
    }
  )
}
