package com.anker.esiotkit.sample.support.platform

import com.anker.esiotkit.domain.add_device.wifi.model.WifiNetwork

/**
 * Get current WiFi SSID (network name) that the device is connected to
 * @return WiFi SSID or empty string if not connected or permission denied
 */
expect fun getCurrentWifiSsid(): String

/** 扫描附近 WiFi 网络（Android: 系统扫描结果；iOS: 无公开 API，返回空列表） */
expect fun scanNearbyWifiNetworks(): List<WifiNetwork>
