package com.anker.esiotkit.sample.model

/**
 * T-DM-02: 设备类型枚举
 *
 * 支持所有已知的 Eufy 设备类型，包含 UI 展示属性和类型判断方法。
 */
enum class DeviceType(
  val code: Int,
  val displayName: String,
  val iconRes: String,
  val supportsThumbnail: Boolean,
  val isStation: Boolean
) {
  BASE_STATION(
    code = 0,
    displayName = "Base Station",
    iconRes = "ic_device_base_station",
    supportsThumbnail = false,
    isStation = true
  ),
  CAMERA(
    code = 1,
    displayName = "Camera",
    iconRes = "ic_device_camera",
    supportsThumbnail = true,
    isStation = false
  ),
  DOORBELL(
    code = 2,
    displayName = "Doorbell",
    iconRes = "ic_device_doorbell",
    supportsThumbnail = true,
    isStation = false
  ),
  SENSOR(
    code = 3,
    displayName = "Sensor",
    iconRes = "ic_device_sensor",
    supportsThumbnail = false,
    isStation = false
  ),
  SIREN(
    code = 4,
    displayName = "Siren",
    iconRes = "ic_device_siren",
    supportsThumbnail = false,
    isStation = false
  ),
  LIGHT(
    code = 5,
    displayName = "Light",
    iconRes = "ic_device_light",
    supportsThumbnail = false,
    isStation = false
  ),
  OTHER(
    code = 99,
    displayName = "Unknown Device",
    iconRes = "ic_device_default",
    supportsThumbnail = false,
    isStation = false
  );

  companion object {
    /**
     * 通过 code 获取设备类型
     *
     * @param code 设备类型代码
     * @return 匹配的设备类型，未匹配返回 OTHER
     */
    fun fromCode(code: Int): DeviceType {
      return entries.find { it.code == code } ?: OTHER
    }
  }
}

/**
 * T-DM-02: 设备状态枚举
 *
 * 表示设备当前的在线状态，包含 UI 展示属性和可达性判断。
 */
enum class DeviceStatus(
  val code: Int,
  val displayName: String,
  val isReachable: Boolean,
  val cardStyleVariant: String
) {
  ONLINE(
    code = 1,
    displayName = "Online",
    isReachable = true,
    cardStyleVariant = "default"
  ),
  OFFLINE(
    code = 0,
    displayName = "Offline",
    isReachable = false,
    cardStyleVariant = "offline"
  ),
  SLEEPING(
    code = 2,
    displayName = "Sleeping",
    isReachable = false,
    cardStyleVariant = "offline"
  ),
  UPGRADING(
    code = 3,
    displayName = "Upgrading",
    isReachable = false,
    cardStyleVariant = "upgrading"
  ),
  UNKNOWN(
    code = -1,
    displayName = "Unknown",
    isReachable = false,
    cardStyleVariant = "default"
  );

  companion object {
    /**
     * 通过 code 获取设备状态
     *
     * @param code 状态代码
     * @return 匹配的设备状态，未匹配返回 UNKNOWN
     */
    fun fromCode(code: Int): DeviceStatus {
      return entries.find { it.code == code } ?: UNKNOWN
    }
  }
}
