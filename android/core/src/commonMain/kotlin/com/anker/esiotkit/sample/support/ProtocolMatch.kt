package com.anker.esiotkit.sample.support

import com.anker.esiotkit.net_api.response.thing.TypeConfig
import com.anker.esiotkit.support.log.dataLogger

/**
 * 检查配网协议是否匹配
 * @param protocol 用户选择的协议，如 "QRCode", "BLE", "Cable" 等
 * @param typeConfig 服务端返回的配网类型配置
 * @return 是否匹配
 */
fun isProtocolMatching(protocol: String, typeConfig: TypeConfig): Boolean {
  dataLogger.i("isProtocolMatching: protocol=$protocol, typeConfig.type=${typeConfig.type}, typeConfig.protocol=${typeConfig.protocol}")

  // 直接匹配
  if (typeConfig.type.equals(protocol, ignoreCase = true)) {
    dataLogger.i("isProtocolMatching: exact match")
    return true
  }

  // 移除前缀 "+" 后匹配（如 "+QRCode" 匹配 "QRCode"）
  val typeWithoutPrefix = typeConfig.type.removePrefix("+")
  if (typeWithoutPrefix.equals(protocol, ignoreCase = true)) {
    dataLogger.i("isProtocolMatching: match after removing + prefix")
    return true
  }

  // 包含匹配（如 typeConfig.type 包含 protocol）
  if (typeConfig.type.contains(protocol, ignoreCase = true) ||
      protocol.contains(typeConfig.type, ignoreCase = true)) {
    dataLogger.i("isProtocolMatching: contains match")
    return true
  }

  // 对于 QRCode 配网，如果 type 是数字（如 "1", "2"）也认为匹配
  // 因为有些设备的配网类型用数字表示
  if (protocol.equals("QRCode", ignoreCase = true) && typeConfig.type.toIntOrNull() != null) {
    dataLogger.i("isProtocolMatching: QRCode with numeric type")
    return true
  }

  dataLogger.w("isProtocolMatching: no match found")
  return false
}