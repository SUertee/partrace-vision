package com.anker.esiotkit.sample.main.mega.device_settings.mode

import kotlinx.serialization.Serializable

@Serializable
data class DeviceTimeZoneInfo(
  val timeZoneName: String = "",
  val timeId: String = "",
  val timeSn: String = "",
  val timeZoneGMT: String = ""
)
