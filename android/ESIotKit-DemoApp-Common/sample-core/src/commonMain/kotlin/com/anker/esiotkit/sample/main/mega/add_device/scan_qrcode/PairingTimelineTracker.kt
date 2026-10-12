package com.anker.esiotkit.sample.main.mega.add_device.scan_qrcode

import com.anker.esiotkit.sample.support.log.PairingLogEntry

enum class PairingNodeStatus { RUNNING, SUCCESS, FAILED }

data class PairingTimelineNode(
  val key: String,                    // stable identity, e.g. "bind_attempt_2" or "get_p2pconn"
  val title: String,                  // Chinese node name
  val status: PairingNodeStatus,
  val timestampMs: Long,
  val details: List<String>,          // max 10 lines, curated key info (NOT raw logs)
)

class PairingTimelineTracker {
  private val nodes = mutableListOf<PairingTimelineNode>()
  private var bindAttemptCount = 0
  private var hasSeenCmd1000 = false

  fun reset() {
    nodes.clear()
    bindAttemptCount = 0
    hasSeenCmd1000 = false
  }

  // 新配网尝试：清空过程节点但保留二维码节点（二维码日志产生于点击配网之前）
  fun resetForNewAttempt() {
    val qrNode = nodes.find { it.key == "qr_generated" }
    nodes.clear()
    if (qrNode != null) {
      nodes.add(qrNode)
    }
    bindAttemptCount = 0
    hasSeenCmd1000 = false
  }

  fun snapshot(): List<PairingTimelineNode> {
    return nodes.sortedByDescending { it.timestampMs }
  }

  private fun findNode(key: String): PairingTimelineNode? {
    return nodes.find { it.key == key }
  }

  private fun updateOrAddNode(node: PairingTimelineNode) {
    val index = nodes.indexOfFirst { it.key == node.key }
    if (index >= 0) {
      nodes[index] = node
    } else {
      nodes.add(node)
    }
  }

  // 配网到达终态后，收敛其余仍在 RUNNING 的节点，避免时间轴冻结后仍有闪烁点
  private fun finalizeRunningNodes(finalStatus: PairingNodeStatus) {
    nodes.indices.forEach { i ->
      if (nodes[i].key != "result" && nodes[i].status == PairingNodeStatus.RUNNING) {
        nodes[i] = nodes[i].copy(status = finalStatus)
      }
    }
  }

  fun onLog(entry: PairingLogEntry, targetSn: String) {
    // 1. qr_generated 「二维码生成」
    if (entry.tag == "ESIot/data" && (entry.msg.contains("generateAndShowQrCode/qrCodeString") || entry.msg.contains("credentials:"))) {
      val snMatch = Regex("""sn=([A-Za-z0-9]+)""").find(entry.msg)?.groupValues?.get(1) ?: targetSn
      val wifiLenMatch = Regex("""wifiNameLength=(\d+)""").find(entry.msg)?.groupValues?.get(1)
      val details = mutableListOf<String>()
      details.add("设备序列号: $snMatch")
      if (wifiLenMatch != null) {
        details.add("WiFi 名称长度: $wifiLenMatch")
      } else {
        details.add("WiFi 已配置")
      }
      updateOrAddNode(
        PairingTimelineNode(
          key = "qr_generated",
          title = "二维码生成",
          status = PairingNodeStatus.SUCCESS,
          timestampMs = entry.ts,
          details = details.takeLast(10)
        )
      )
    }

    // 2. pairing_started 「启动配对」
    if (entry.msg.contains("performPairing/start")) {
      val snMatch = Regex("""sn=([A-Za-z0-9]+)""").find(entry.msg)?.groupValues?.get(1) ?: targetSn
      val pnMatch = Regex("""pn=([A-Za-z0-9]+)""").find(entry.msg)?.groupValues?.get(1) ?: ""
      val details = listOf(
        "设备序列号: $snMatch",
        "产品型号: $pnMatch",
        "配对超时时间: 120000ms"
      )
      updateOrAddNode(
        PairingTimelineNode(
          key = "pairing_started",
          title = "启动配对",
          status = PairingNodeStatus.SUCCESS,
          timestampMs = entry.ts,
          details = details.takeLast(10)
        )
      )
    }

    // 3. broadcast 「设备广播监听」
    if (entry.msg.contains("startReceiveBroadcast") || entry.msg.contains("startPairing/isChangeWifi")) {
      val existing = findNode("broadcast")
      if (existing == null) {
        updateOrAddNode(
          PairingTimelineNode(
            key = "broadcast",
            title = "设备广播监听",
            status = PairingNodeStatus.RUNNING,
            timestampMs = entry.ts,
            details = listOf("正在监听设备广播...")
          )
        )
      }
    } else if (entry.msg.contains("receiveSnFromBroadcast/cmd: 1000")) {
      hasSeenCmd1000 = true
      val snMatch = Regex("""deviceSN:\s*([A-Za-z0-9]+)""").find(entry.msg)?.groupValues?.get(1) ?: targetSn
      updateOrAddNode(
        PairingTimelineNode(
          key = "broadcast",
          title = "设备广播监听",
          status = PairingNodeStatus.SUCCESS,
          timestampMs = entry.ts,
          details = listOf("收到设备广播, SN: $snMatch")
        )
      )
    } else if (entry.msg.contains("workFlow/foundSn")) {
      if (!hasSeenCmd1000) {
        updateOrAddNode(
          PairingTimelineNode(
            key = "broadcast",
            title = "设备广播监听",
            status = PairingNodeStatus.SUCCESS,
            timestampMs = entry.ts,
            details = listOf("SN 来自二维码，未等到设备广播")
          )
        )
      }
    }

    // 4. get_p2pconn 「获取 P2P 连接信息」
    if (entry.msg.contains("getP2pConnectionInfo")) {
      val attemptMatch = Regex("""attempt:(\d+)""").find(entry.msg)?.groupValues?.get(1) ?: ""
      val codeMatch = Regex("""code=(\d+)""").find(entry.msg)?.groupValues?.get(1)
      val p2pDidMatch = Regex("""p2pDid:(\S+)""").find(entry.msg)?.groupValues?.get(1) ?: ""
      
      val details = mutableListOf<String>()
      if (attemptMatch.isNotEmpty()) {
        details.add("尝试次数: $attemptMatch/10")
      }
      if (p2pDidMatch.isNotEmpty()) {
        details.add("P2P DID: $p2pDidMatch")
      }

      val status = if (codeMatch == "0") {
        PairingNodeStatus.SUCCESS
      } else if (codeMatch != null) {
        PairingNodeStatus.FAILED
      } else {
        PairingNodeStatus.RUNNING
      }

      if (status == PairingNodeStatus.FAILED) {
        val errorMsg = Regex("""msg:([^,]+)""").find(entry.msg)?.groupValues?.get(1) ?: ""
        val truncatedMsg = if (errorMsg.length > 60) errorMsg.take(60) + "..." else errorMsg
        details.add("云端返回 code=$codeMatch, msg=$truncatedMsg")
      }

      updateOrAddNode(
        PairingTimelineNode(
          key = "get_p2pconn",
          title = "获取 P2P 连接信息",
          status = status,
          timestampMs = entry.ts,
          details = details.takeLast(10)
        )
      )
    }

    // 5. bind_cmd 「下发绑定指令」
    if (entry.msg.contains("DeviceCmdKtx/bindHubEx/parentSn")) {
      bindAttemptCount++
      val details = mutableListOf("第 ${bindAttemptCount} 次下发")
      updateOrAddNode(
        PairingTimelineNode(
          key = "bind_cmd",
          title = "下发绑定指令",
          status = PairingNodeStatus.RUNNING,
          timestampMs = entry.ts,
          details = details.takeLast(10)
        )
      )
    } else if (entry.msg.contains("sendConnectedCmd result:") && entry.msg.contains(targetSn)) {
      val regex = Regex("""sendConnectedCmd result:\s*(-?\d+)\s+deviceSn(\S+)""")
      val match = regex.find(entry.msg)
      if (match != null) {
        val result = match.groupValues[1].toIntOrNull()
        val deviceSn = match.groupValues[2]
        if (deviceSn.contains(targetSn)) {
          val existing = findNode("bind_cmd")
          val details = existing?.details?.toMutableList() ?: mutableListOf()
          var status = existing?.status ?: PairingNodeStatus.RUNNING
          
          if (result != null) {
            if (result >= 0) {
              details.add("第 ${bindAttemptCount} 次执行：设备已确认")
              status = PairingNodeStatus.SUCCESS
            } else if (result == -6) {
              details.add("第 ${bindAttemptCount} 次执行：P2P 连接超时，设备不可达")
            } else if (result == -3) {
              details.add("第 ${bindAttemptCount} 次执行：P2P 指令发送失败")
            } else {
              details.add("第 ${bindAttemptCount} 次执行：结果 $result")
            }
          }
          
          updateOrAddNode(
            PairingTimelineNode(
              key = "bind_cmd",
              title = "下发绑定指令",
              status = status,
              timestampMs = entry.ts,
              details = details.takeLast(10)
            )
          )
        }
      }
    } else if (entry.msg.contains("bind repeat") && entry.msg.contains("code:-40301")) {
      val existing = findNode("bind_cmd")
      val details = existing?.details?.toMutableList() ?: mutableListOf()
      details.add("第 ${bindAttemptCount} 次绑定指令超时未响应")
      updateOrAddNode(
        PairingTimelineNode(
          key = "bind_cmd",
          title = "下发绑定指令",
          status = existing?.status ?: PairingNodeStatus.RUNNING,
          timestampMs = entry.ts,
          details = details.takeLast(10)
        )
      )
    }

    // 6. device_online 「设备上线」
    if (entry.msg.contains("monitorReconnect/device online detected")) {
      val elapsedMatch = Regex("""elapsedMs=(\d+)""").find(entry.msg)?.groupValues?.get(1)
      val details = mutableListOf<String>()
      if (elapsedMatch != null) {
        details.add("耗时: ${elapsedMatch}ms")
      } else {
        details.add("设备已上线")
      }
      updateOrAddNode(
        PairingTimelineNode(
          key = "device_online",
          title = "设备上线",
          status = PairingNodeStatus.SUCCESS,
          timestampMs = entry.ts,
          details = details.takeLast(10)
        )
      )
    }

    // 7. result 「配网结果」
    if (entry.msg.contains("performPairing/success")) {
      val elapsedMatch = Regex("""elapsedMs=(\d+)""").find(entry.msg)?.groupValues?.get(1)
      val details = mutableListOf("配网成功")
      if (elapsedMatch != null) {
        details.add("总耗时: ${elapsedMatch}ms")
      }
      updateOrAddNode(
        PairingTimelineNode(
          key = "result",
          title = "配网结果",
          status = PairingNodeStatus.SUCCESS,
          timestampMs = entry.ts,
          details = details.takeLast(10)
        )
      )
      finalizeRunningNodes(PairingNodeStatus.SUCCESS)
    } else if (entry.msg.contains("performPairing/failed")) {
      val codeMatch = Regex("""code:([^,\s]+)""").find(entry.msg)?.groupValues?.get(1) ?: ""
      // 花括号整体匹配优先，避免被首个备选分支在空格处截断导致 reason 关键词丢失
      val payloadMatch = Regex("""payload:(\{[^}]*\}|[^,\s]+)""").find(entry.msg)?.groupValues?.get(1) ?: ""
      
      val reason = when {
        codeMatch.contains("RequestTimeout") || payloadMatch.contains("pairing timeout") -> 
          "配对超时：120s 内设备未完成绑定（设备可能未连上 WiFi 或未扫码）"
        payloadMatch.contains("INVALID_SSID") -> 
          "WiFi 名称无效"
        payloadMatch.contains("VERIFY_NOT_CONFIRMED") -> 
          "云端核验未找到设备"
        codeMatch.contains("ResponseFailed") -> 
          "云端请求失败"
        else -> {
          val truncatedPayload = if (payloadMatch.length > 60) payloadMatch.take(60) + "..." else payloadMatch
          "配网失败：$codeMatch $truncatedPayload"
        }
      }
      
      updateOrAddNode(
        PairingTimelineNode(
          key = "result",
          title = "配网结果",
          status = PairingNodeStatus.FAILED,
          timestampMs = entry.ts,
          details = listOf(reason)
        )
      )
      finalizeRunningNodes(PairingNodeStatus.FAILED)
    }
  }
}
