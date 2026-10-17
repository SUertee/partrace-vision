@file:OptIn(ExperimentalResourceApi::class)

package com.anker.esiotkit.sample.support

import com.anker.esiotkit.support.json.kJson
import com.anker.esiotkit.sample.main.mega.add_device.device_family.DeviceCategory
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.support.IoDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * @version 2024/11/20
 */
object DeviceFamilyCategoryUtil {
  suspend fun getAll(): List<DeviceCategory> = withContext(IoDispatcher) {
    try {
      val bytes = Res.readBytes("files/eufy_PN_deviceFamilyList.json")
      val jsonString = bytes.decodeToString()
      if (jsonString.isNotEmpty()) {
        return@withContext kJson.decodeFromString<List<DeviceCategory>>(jsonString)
      } else {
        println("Error: JSON content is empty")
      }
    } catch (e: Exception) {
      println("Error reading or parsing JSON: ${e.message}")
    }
    return@withContext emptyList()
  }

  suspend fun query(deviceCategories: List<DeviceCategory>, searchQuery: String): List<DeviceCategory> {
    return deviceCategories.map { category ->
      category.copy(
        deviceList = category.deviceList.filter { device ->
          device.deviceTypeName.contains(searchQuery, ignoreCase = true) ||
          device.deviceCateLv2.contains(searchQuery, ignoreCase = true) ||
          device.devicePNs.any { it.contains(searchQuery, ignoreCase = true) }
        }
      )
    }
  }
}