package com.anker.esiotkit.sample.support.log

import com.anker.esiotkit.support.log.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * @version 2024/7/19
 */
object SampleLogger {
  private const val MAX_LOGS = 200

  private val _httpLogs = MutableStateFlow<List<String>>(emptyList())
  val httpLogs: StateFlow<List<String>> = _httpLogs.asStateFlow()

  // 逐条 emit 的共享流：消费方按条目处理，避免自增列表 takeLast 截断导致的索引死锁
  private val _pairingLogs = MutableSharedFlow<PairingLogEntry>(extraBufferCapacity = 300)
  val pairingLogs: SharedFlow<PairingLogEntry> = _pairingLogs.asSharedFlow()

  fun clearHttpLogs() {
    _httpLogs.value = emptyList()
  }

  val iotLogger = object : Logger {

    override fun v(tag: String, msg: String) {
      sendLogToChannel(tag, msg)
      if (SampleLogManager.logLevel >= SampleLogLevel.VERBOSE) {
        SampleLogManager.writeLog("[V]$tag: $msg")
      }
      splitMessage(msg).forEach {
        co.touchlab.kermit.Logger.v(tag = tag, messageString = it)
      }
      sendPairingLog("V", tag, msg)
    }

    override fun i(tag: String, msg: String) {
      sendLogToChannel(tag, msg)
      if (SampleLogManager.logLevel >= SampleLogLevel.INFO) {
        SampleLogManager.writeLog("[I]$tag: $msg")
      }
      splitMessage(msg).forEach {
        co.touchlab.kermit.Logger.i(tag = tag, messageString = it)
      }
      sendPairingLog("I", tag, msg)
    }

    override fun d(tag: String, msg: String) {
      sendLogToChannel(tag, msg)
      if (SampleLogManager.logLevel >= SampleLogLevel.DEBUG) {
        SampleLogManager.writeLog("[D]$tag: $msg")
      }
      splitMessage(msg).forEach {
        co.touchlab.kermit.Logger.d(tag = tag, messageString = it)
      }
      sendPairingLog("D", tag, msg)
    }

    override fun w(tag: String, msg: String) {
      sendLogToChannel(tag, msg)
      if (SampleLogManager.logLevel >= SampleLogLevel.WARN) {
        SampleLogManager.writeLog("[W]$tag: $msg")
      }
      splitMessage(msg).forEach {
        co.touchlab.kermit.Logger.w(tag = tag, messageString = it)
      }
      sendPairingLog("W", tag, msg)
    }

    override fun e(tag: String, msg: String) {
      sendLogToChannel(tag, msg)
      if (SampleLogManager.logLevel >= SampleLogLevel.ERROR) {
        SampleLogManager.writeLog("[E]$tag: $msg")
      }
      splitMessage(msg).forEach {
        co.touchlab.kermit.Logger.e(tag = tag, messageString = it)
      }
      sendPairingLog("E", tag, msg)
    }

    private fun sendPairingLog(level: String, tag: String, msg: String) {
      // 日志旁路熔断：任何异常静默吞掉，绝不向 SDK 日志管道上抛（此处为非业务观测路径，不影响业务结果）
      runCatching {
        val accept = when (tag) {
          "ESIot/pairing", "ESIot/p2p" -> true
          // p2pLib 的 V 级心跳噪音极多，仅接收绑定指令结果行
          "ESIot/p2pLib" -> msg.contains("sendConnectedCmd")
          "ESIot/data", "ESIot/app" -> {
            msg.contains("performPairing") ||
            msg.contains("monitorReconnect") ||
            msg.contains("QrCode") ||
            msg.contains("qrcode") ||
            msg.contains("credentials") ||
            msg.contains("Pairing")
          }
          else -> false
        }
        if (accept) {
          val entry = PairingLogEntry(level, tag, msg, com.anker.esiotkit.support.platform.currentTimeMillis())
          _pairingLogs.tryEmit(entry)
        }
      }
    }

    private fun sendLogToChannel(tag: String, msg: String) {
      if (tag == "ESIot/http") {
        // 更新缓存的日志
        if (msg.contains("Response for requestId:")) {
          val processedLog = processLog(msg)
          _httpLogs.value = (_httpLogs.value + processedLog).takeLast(MAX_LOGS)
        }
      }
    }
  }

  private fun processLog(log: String): String {
    val lines = log.split("\n")
    val url = lines.find { it.contains("REQUEST URL:") || it.contains("FROM:") }?.let {
      it.substringAfter(":").trim()
    } ?: ""

    val responseCode = lines.find { it.contains("RESPONSE:") }?.let {
      it.substringAfter(":").trim()
    } ?: ""

    return buildString {
      append("URL: $url\n")
      append("Response Code: $responseCode")
    }
  }

  private fun splitMessage(msg: String): List<String> {
    val result = mutableListOf<String>()
    val maxLogSize = 2000
    for(i in 0..msg.length / maxLogSize) {
      val start = i * maxLogSize
      var end = (i + 1) * maxLogSize
      end = if (end > msg.length) msg.length else end
      result.add(msg.substring(start, end))
    }
    return result
  }

}

data class PairingLogEntry(val level: String, val tag: String, val msg: String, val ts: Long)
