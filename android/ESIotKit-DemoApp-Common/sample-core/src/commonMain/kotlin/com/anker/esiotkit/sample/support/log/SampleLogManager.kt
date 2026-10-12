@file:OptIn(InternalIotApi::class)

package com.anker.esiotkit.sample.support.log

import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.support.InternalIotApi
import com.anker.esiotkit.support.ioSupervisorContext
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.createFile
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.platform.ensureDirectoryExists
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import com.anker.esiotkit.support.utils.DateUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.BufferedSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

object SampleLogManager {
  var logLevel: Int = InfoCache.logLevel
    private set

  var enableLogRotation: Boolean = true
  var rotationIntervalMinutes: Long = 1L
  var logRetentionDays: Int = 7

  private val scope = CoroutineScope(ioSupervisorContext)
  private val channel = Channel<String>(capacity = Channel.UNLIMITED)
  private val timeZone = TimeZone.currentSystemDefault()
  private val dirPath get() = getDocumentFilePath().toPath().resolve("logs")
  private var start = true

  init {
    recoverPendingFiles()
    clearExpiredLog()
    restartWriteLog()
  }

  private fun recoverPendingFiles() {
    try {
      platformFileSystem.ensureDirectoryExists(dirPath)
      platformFileSystem.list(dirPath)
        .filter { it.name.endsWith("_pending.log") }
        .forEach { pendingFile ->
          val metadata = platformFileSystem.metadata(pendingFile)
          val endMillis = metadata.lastModifiedAtMillis ?: currentTimeMillis()
          val startMillis = parseLogStartTime(pendingFile.name) ?: endMillis
          val finalName = generateFinalFileName(startMillis, endMillis)
          val finalPath = dirPath.resolve(finalName)
          try {
            if (platformFileSystem.exists(finalPath)) {
              platformFileSystem.delete(finalPath)
            }
            platformFileSystem.atomicMove(pendingFile, finalPath)
            appLogger.i("Recovered pending file: ${pendingFile.name} -> $finalName")
          } catch (e: Exception) {
            appLogger.w("Recover pending file failed: ${e.message}")
          }
        }
    } catch (e: Exception) {
      appLogger.w("Recover pending files failed: ${e.message}")
    }
  }

  private fun clearExpiredLog() {
    try {
      clearExpiredLogEnhanced()
    } catch (e: Exception) {
      appLogger.w("Enhanced log cleanup failed, fallback to legacy: ${e.message}")
      clearExpiredLogLegacy()
    }
  }

  private fun clearExpiredLogLegacy() {
    getLogFileList().filter { it.fileName.contains("-000") }
      .forEach { deleteFile(it.fileName) }
  }

  private fun clearExpiredLogEnhanced() {
    val expireTime = currentTimeMillis() - logRetentionDays * 24L * 60L * 60L * 1000L
    getLogFileList()
      .filter { log ->
        val startTime = parseLogStartTime(log.fileName)
        startTime != null && startTime < expireTime
      }
      .forEach { deleteFile(it.fileName) }
  }

  private var writeLogJob: Job? = null

  fun restartWriteLog() {
    if (!enableLogRotation) {
      restartWriteLogLegacy()
      return
    }
    try {
      restartWriteLogWithRotation()
    } catch (e: Exception) {
      appLogger.e("Log rotation init failed, fallback to legacy: ${e.message}")
      restartWriteLogLegacy()
    }
  }

  private fun restartWriteLogLegacy() {
    writeLogJob?.cancel()
    writeLogJob = scope.launch {
      platformFileSystem.ensureDirectoryExists(dirPath)
      val filePath = createFile()
      val sink = platformFileSystem.sink(filePath).buffer()
      try {
        for (msg in channel) {
          sink.writeUtf8("${getCurrentTime()}   $msg").flush()
          sink.writeUtf8("\n").flush()
        }
      } finally {
        sink.close()
      }
    }
  }

  private fun restartWriteLogWithRotation() {
    writeLogJob?.cancel()
    writeLogJob = scope.launch {
      try {
        platformFileSystem.ensureDirectoryExists(dirPath)

        val rotationIntervalMs = rotationIntervalMinutes * 60L * 1000L
        var segmentStartTime = currentTimeMillis()
        var lastRotationTime = segmentStartTime

        var currentTempPath = createTempFile(segmentStartTime)
        var sink: BufferedSink? = platformFileSystem.sink(currentTempPath).buffer()
        val pendingBuffer = mutableListOf<String>()
        var rotating = false

        try {
          for (msg in channel) {
            if (rotating) {
              pendingBuffer.add(msg)
              continue
            }

            val now = currentTimeMillis()
            if (now - lastRotationTime >= rotationIntervalMs) {
              rotating = true
              try {
                sink?.close()
                sink = null

                val finalName = generateFinalFileName(segmentStartTime, now)
                renameFileSafely(currentTempPath, finalName)

                segmentStartTime = currentTimeMillis()
                lastRotationTime = segmentStartTime
                currentTempPath = createTempFile(segmentStartTime)
                sink = platformFileSystem.sink(currentTempPath).buffer()

                if (pendingBuffer.isNotEmpty()) {
                  val timestamp = getCurrentTime()
                  for (bufferedMsg in pendingBuffer) {
                    sink?.writeUtf8("$timestamp   $bufferedMsg\n")
                  }
                  sink?.flush()
                  pendingBuffer.clear()
                }
              } catch (e: Exception) {
                appLogger.e("Rotation step failed: ${e.message}")
                if (sink == null) {
                  try {
                    sink = platformFileSystem.sink(currentTempPath).buffer()
                  } catch (e2: Exception) {
                    appLogger.e("Reopen sink failed: ${e2.message}")
                    pendingBuffer.clear()
                    throw e2
                  }
                }
                pendingBuffer.clear()
              } finally {
                rotating = false
              }
              continue
            }

            sink?.writeUtf8("${getCurrentTime()}   $msg\n")
            sink?.flush()
          }
        } catch (e: CancellationException) {
          throw e
        } finally {
          sink?.close()
          sink = null

          if (platformFileSystem.exists(currentTempPath)) {
            val endTime = currentTimeMillis()
            val finalName = generateFinalFileName(segmentStartTime, endTime)
            try {
              renameFileSafely(currentTempPath, finalName)
            } catch (e: Exception) {
              appLogger.w("Final rename failed: ${e.message}")
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        appLogger.e("Log rotation fatal error, fallback to legacy: ${e.message}")
        restartWriteLogLegacy()
      }
    }
  }

  private fun createTempFile(startTime: Long): Path {
    val fileName = "log_${formatDateTime(startTime)}_pending.log"
    val filePath = dirPath.resolve(fileName)
    platformFileSystem.createFile(filePath)
    return filePath
  }

  private fun generateFinalFileName(startTime: Long, endTime: Long): String {
    return "log_${formatDateTime(startTime)}_${formatDateTime(endTime)}.log"
  }

  private fun formatDateTime(timeMillis: Long): String {
    val dateTime = kotlinx.datetime.Instant.fromEpochMilliseconds(timeMillis)
      .toLocalDateTime(timeZone)
    return buildString {
      append(dateTime.year)
      append(dateTime.monthNumber.toString().padStart(2, '0'))
      append(dateTime.dayOfMonth.toString().padStart(2, '0'))
      append("_")
      append(dateTime.hour.toString().padStart(2, '0'))
      append(dateTime.minute.toString().padStart(2, '0'))
    }
  }

  private fun renameFileSafely(oldPath: Path, newFileName: String): Path? {
    val newPath = dirPath.resolve(newFileName)
    return try {
      if (platformFileSystem.exists(newPath)) {
        platformFileSystem.delete(newPath)
      }
      platformFileSystem.atomicMove(oldPath, newPath)
      newPath
    } catch (e: Exception) {
      appLogger.w("atomicMove failed, keep pending name: ${e.message}")
      null
    }
  }

  private fun createFile(): Path {
    val dir = dirPath
    var fileName = DateUtil.timeMillisToDateTimeString(currentTimeMillis()) + ".log"
    fileName = if (start) {
      start = false
      "${fileName.substringBefore(".")}-start.log"
    } else {
      fileName
    }
    platformFileSystem.createFile(dir.resolve(fileName))
    return dir.resolve(fileName)
  }

  fun getLogFileList(): List<LogFileItem> {
    platformFileSystem.ensureDirectoryExists(dirPath)
    return platformFileSystem.list(dirPath).map {
      it to platformFileSystem.metadata(it)
    }.sortedByDescending { (path, metadata) ->
      metadata.createdAtMillis
    }.map { (path, metadata) ->
      val createTime = parseCreateTime(path.name, metadata)
      val size = bytesToMB(metadata.size ?: 0)
      val name = path.name
      LogFileItem(name, size, path.toString(), createTime)
    }.sortedByDescending { it.fileName }
  }

  private fun parseCreateTime(fileName: String, metadata: okio.FileMetadata): String {
    return try {
      val startTime = parseLogStartTime(fileName)
      if (startTime != null) {
        DateUtil.timeMillisToTimeString(startTime)
      } else {
        DateUtil.timeMillisToTimeString(metadata.createdAtMillis ?: 0)
      }
    } catch (e: Exception) {
      DateUtil.timeMillisToTimeString(metadata.createdAtMillis ?: 0)
    }
  }

  private fun parseLogStartTime(fileName: String): Long? {
    return try {
      when {
        fileName.startsWith("log_") && fileName.endsWith(".log") -> {
          val content = fileName.removePrefix("log_").removeSuffix(".log")
          val parts = content.split("_")
          if (parts.isNotEmpty()) parseDateTime(parts[0]) else null
        }
        fileName.contains("-start.log") -> {
          parseDateTime(fileName.substringBefore("-start.log"))
        }
        fileName.endsWith(".log") -> {
          parseDateTime(fileName.removeSuffix(".log"))
        }
        else -> null
      }
    } catch (e: Exception) {
      null
    }
  }

  private fun parseDateTime(dateTimeStr: String): Long? {
    return try {
      DateUtil.dateTimeStringToTimeMillis(dateTimeStr)
    } catch (e: Exception) {
      null
    }
  }

  fun writeLog(message: String) {
    channel.trySend(message)
  }

  fun setLogLevel(level: Int) {
    logLevel = level
    InfoCache.logLevel = level
  }

  fun deleteFile(fileName: String) {
    val file = dirPath.resolve(fileName)
    platformFileSystem.delete(file)
  }

  private fun getCurrentTime(): String {
    val dateTime = Clock.System.now().toLocalDateTime(timeZone)
    return dateTime.toString().replace("T", " ").substringBeforeLast(".")
  }

  private fun bytesToMB(bytes: Long): String {
    val kb = bytes / 1024
    val mb = kb / 1024
    val afterPoint = kb % 1024
    return "$mb.${afterPoint.toString().take(2)} MB"
  }
}
