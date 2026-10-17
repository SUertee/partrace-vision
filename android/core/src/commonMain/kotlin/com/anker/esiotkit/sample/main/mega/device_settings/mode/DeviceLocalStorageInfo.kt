package com.anker.esiotkit.sample.main.mega.device_settings.mode

import kotlinx.serialization.Serializable

@Serializable
data class DeviceLocalStorageInfo(
  val totalSpace: Int = 0,
  val freeSpace: Int = 0,
  val status: StorageStatus? = null
)

@Serializable
data class StorageStatus(
  val status: Int,
  val buttonList: List<Int>
)
