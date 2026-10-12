package com.anker.esiotkit.sample.support.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import com.anker.esiotkit.domain.add_device.wifi.model.WifiNetwork
import com.anker.esiotkit.sample.support.applicationContext
import com.anker.esiotkit.support.log.appLogger

actual fun getCurrentWifiSsid(): String {
  return try {
    val context = applicationContext as? Context
    if (context == null) {
      appLogger.w("Application context is not available")
      return ""
    }

    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    if (wifiManager == null) {
      appLogger.w("WifiManager not available")
      return ""
    }

    val wifiInfo = wifiManager.connectionInfo
    if (wifiInfo == null) {
      appLogger.w("WifiInfo is null")
      return ""
    }

    var ssid = wifiInfo.ssid ?: ""

    // Remove quotes from SSID (Android returns SSID in quotes like "MyWiFi")
    ssid = ssid.trim('"')

    // For Android 10+, SSID might be "<unknown ssid>" if location permission is not granted
    if (ssid == "<unknown ssid>" || ssid.isEmpty()) {
      appLogger.w("WiFi SSID is unknown or empty (might need location permission)")
      return ""
    }

    appLogger.i("Current WiFi SSID: $ssid")
    ssid
  } catch (e: Exception) {
    appLogger.e("WifiManager") { "Failed to get current WiFi SSID: ${e.message}" }
    ""
  }
}

@SuppressLint("MissingPermission")
actual fun scanNearbyWifiNetworks(): List<WifiNetwork> {
  return try {
    val context = applicationContext as? Context
    if (context == null) {
      appLogger.w("WifiManager/scanNearbyWifiNetworks: applicationContext is null")
      return emptyList()
    }
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    if (wifiManager == null) {
      appLogger.w("WifiManager/scanNearbyWifiNetworks: WifiManager not available")
      return emptyList()
    }

    val locationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!locationGranted) {
      appLogger.w("WifiManager/scanNearbyWifiNetworks: ACCESS_FINE_LOCATION not granted, skip scan")
      return emptyList()
    }

    // startScan 可能因限流(API9+: 前台4次/2分钟)或权限问题失败——单独捕获，
    // 不阻断 scanResults 缓存读取（系统会周期性后台扫描，缓存通常可用）
    try {
      wifiManager.startScan()
    } catch (e: Exception) {
      appLogger.w("WifiManager/scanNearbyWifiNetworks: startScan failed, fallback to cached scanResults: ${e.message}")
    }

    val securityKeywords = listOf("WPA", "WEP", "EAP", "OWE", "SAE", "PSK")
    val results = wifiManager.scanResults
      .mapNotNull { result ->
        val ssid = result.SSID?.trim('"') ?: return@mapNotNull null
        if (ssid == "<unknown ssid>" || ssid.isEmpty()) null
        else {
          val security = if (securityKeywords.any { result.capabilities.contains(it, ignoreCase = true) }) "SECURED" else "NONE"
          WifiNetwork(ssid = ssid, rssi = result.level, security = security, bssid = result.BSSID)
        }
      }
      .distinctBy { it.ssid }
      .sortedByDescending { it.rssi }

    appLogger.i("WifiManager/scanNearbyWifiNetworks/result: count=${results.size}")
    results
  } catch (e: Exception) {
    appLogger.e("WifiManager/scanNearbyWifiNetworks") { "Failed to scan WiFi: ${e.message}" }
    emptyList()
  }
}
