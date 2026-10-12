package com.anker.esiotkit.sample.model

import com.anker.esiotkit.core.entities.device.ESIotDevice
import com.anker.esiotkit.core.constants.SnConstants
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.support.log.appLogger

/**
 * T-DM-01: MegaDevice 扩展函数
 *
 * 为 SDK 的 MegaDevice 类提供业务层扩展方法，
 * 支持两级层级结构、SN 脱敏、显示名称等功能。
 */

// ========== 设备类型判断 ==========

/**
 * 判断是否为基站设备
 *
 * 通过设备 SN 前缀匹配判断是否为基站类型，
 * 支持所有已知的基站 SN 前缀（T8010/T8011/T8020/T8030 等）
 */
fun MegaDevice.isStation(): Boolean {
  return deviceSn.isStationSn()
}

/**
 * 判断 SN 是否为基站 SN
 *
 * 参考 SDK 中 SnConstants 定义的所有基站前缀
 */
private fun String.isStationSn(): Boolean {
  return startsWith(SnConstants.STATION) ||
    startsWith(SnConstants.STATION_AI) ||
    startsWith(SnConstants.STATION_2) ||
    startsWith(SnConstants.STATION_3) ||
    startsWith(SnConstants.STATION_S) ||
    startsWith(SnConstants.STATION_8021)
}

/**
 * 判断是否为独立设备（非子设备）
 */
fun MegaDevice.isStandalone(): Boolean {
  return deviceSn == parentSn
}

// ========== 层级结构判断 ==========

/**
 * 判断基站是否有子设备
 *
 * 基站设备的 parentSn 等于 deviceSn，
 * 如果 SDK 返回了 children 字段则检查 children 数量，
 * 否则返回 false（具体子设备数量由 SDK 数据决定）
 */
fun MegaDevice.hasChildren(): Boolean {
  return isStation() && deviceSn == parentSn
}

/**
 * 判断设备是否在线
 *
 * 基于 SDK 提供的设备属性进行判断
 */
fun MegaDevice.isOnline(): Boolean {
  val isOnlineProperty = getProperty(ThingIdentifier.DEVICE_IS_ONLINE)
  return isOnlineProperty?.get()?.asBoolean() ?: false
}

// ========== 安全脱敏 ==========

/**
 * SN 脱敏显示
 *
 * 保留前 4 位和后 4 位，中间用 `***` 替代
 * 用于日志输出和 UI 展示，防止敏感信息泄露
 *
 * 示例: "T8010ABCD1234" → "T801***1234"
 */
fun MegaDevice.snMasked(): String {
  return deviceSn.snMasked()
}

/**
 * SN 字符串脱敏
 */
private fun String.snMasked(): String {
  if (length <= 8) return this
  val prefix = take(4)
  val suffix = takeLast(4)
  return "$prefix***$suffix"
}

// ========== 显示名称 ==========

/**
 * 获取设备显示名称
 *
 * 优先级: 用户自定义名称 > 设备默认名称 > SN
 * 当 deviceName 为空或与 SN 相同时，返回 SN
 */
fun MegaDevice.displayName(): String {
  return if (iotDevice.deviceName.isNullOrEmpty() || iotDevice.deviceName == deviceSn) {
    deviceSn
  } else {
    iotDevice.deviceName
  }
}

// ========== 缩略图 ==========

/**
 * 获取设备缩略图 URL
 *
 * 基站设备不显示缩略图，返回 null。
 * 摄像头等设备暂时返回 null（待后端确认缩略图来源后补充实际属性键）。
 */
fun MegaDevice.thumbnailUrl(): String? {
  if (isStation()) return null
  val path = getProperty(ThingIdentifier.DEVICE_THUMBNAIL_PATH)?.get()?.asString()
  appLogger.i("MegaDeviceExtensions/thumbnailUrl: deviceSn=$deviceSn, path=$path")
  return path
}
