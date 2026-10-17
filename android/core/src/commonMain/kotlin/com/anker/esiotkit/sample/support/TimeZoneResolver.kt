package com.anker.esiotkit.sample.support

import com.anker.esiotkit.core.entities.settings.TimeZoneModel
import com.anker.esiotkit.security_device.util.TimeZoneJson
import kotlinx.serialization.json.Json

/**
 * 时区原始值解析器 — timezone_set 属性值全格式兼容解析
 *
 * 背景（Coding #8695）：timezone_set 属性存在两条写入路径且格式不一致：
 * - SecurityTimezoneProp.doGet() → 转换后 IANA ID（如 "Asia/Shanghai"）
 * - ThingProperty.updateValue()（JS 物模引擎推送）→ 原始 P2P 格式（如 "CST-8|1.1297"）
 * Sample 层经 propertyState/Flow 读取时拿到的是原始格式。
 *
 * 解析顺序对齐 Mega 原生 findTimeZone（见 SecurityTimezoneProp.transformTimeZone），
 * 由 TimeSettingsScreen / TimeZoneScreen 共用，防止两处解析逻辑再次分叉。
 *
 * @see com.anker.esiotkit.sample.main.mega.device_settings.general.TimeSettingsScreen
 * @see com.anker.esiotkit.sample.main.mega.device_settings.general.TimeZoneScreen
 */
object TimeZoneResolver {

  private val allTimeZoneModels: List<TimeZoneModel> by lazy {
    Json.decodeFromString<List<TimeZoneModel>>(TimeZoneJson.getTimeZoneJson())
  }

  /**
   * 将时区原始值解析为 IANA 时区 ID（如 "Asia/Shanghai"）。
   *
   * 支持格式：
   * 1. "...|Region/City" — NVR 三段式 "GMT|1.SN|Region/City" 或两段 IANA 后缀 → 取最后一段
   * 2. "<POSIX-TZ>|1.<SN>" — 传统 P2P 原始格式（如 "CST-8|1.1297"）→ 按 timeSn 匹配本地时区表
   * 3. 纯 IANA ID 或 GMT 字符串（如 "Asia/Shanghai" / "GMT0"）→ 整串匹配本地时区表
   *
   * 无法解析时原样返回，由调用方决定兜底策略（如 TimeZone.of 抛异常后回退 UTC）。
   */
  fun resolveTimezoneId(raw: String): String {
    if (raw.isEmpty()) return raw
    // 格式1: 最后一个竖线段含 "/" → IANA 城市名
    val afterLastPipe = raw.substringAfterLast("|")
    if (afterLastPipe.contains("/")) return afterLastPipe
    // 格式2: 含 "1.<SN>" → 按 timeSn 匹配本地时区表
    val sn = raw.split("|").find { it.startsWith("1.") }
    if (sn != null) {
      allTimeZoneModels.find { it.timeSn == sn.removePrefix("1.") }?.timeId?.let { return it }
    }
    // 格式3: GMT 字符串或 IANA ID 整串匹配
    return allTimeZoneModels.find { it.timeZoneGMT == raw || it.timeId == raw }?.timeId ?: raw
  }
}
