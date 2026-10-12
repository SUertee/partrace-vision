@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.demo.data

import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain.model.MegaPicture
import com.anker.esiotkit.support.ktx.toDateStart
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import com.anker.esiotkit.support.utils.DateUtil
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * 本地样本素材回退源。
 *
 * AutoCaptureScreen 抓取的事件缩略图落盘在：
 *   getDocumentFilePath()/test_data/thumbnails/thumb_<n>_<megaEventId>.jpg
 *
 * 本对象把该目录下的图片构造成「合成事件」追加在真实事件之后参与 AI 分析：
 * - 真实事件足够时（分析上限 5/10）样本自然被挤掉，优先级低于真实数据；
 * - 真实事件不足时用样本补位，保证离线/少事件场景下演示链路仍然可用。
 *
 * 目录为空或读取失败时返回空列表，绝不抛出。
 */
object LocalSampleSource {

  private const val TAG = "LocalSampleSource"

  /** 合成事件的 deviceSn（非真实设备，`EventDataSourceImpl.isDeviceOnline` 对其直接判定在线） */
  const val DEVICE_SN = "local-sample"

  /** 合成事件 eventId 前缀：local~sample~<fileName> */
  const val EVENT_ID_PREFIX = "local~sample~"

  /** 单次最多追加的样本数 */
  const val MAX_LOCAL_SAMPLES = 20

  private const val THUMBNAIL_DIR_NAME = "thumbnails"
  private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

  /** 文件名内嵌时间：thumb_1_T8170T...~local~20260916152601.jpg */
  private val TIME_IN_FILE_NAME = Regex("~local~(\\d{14})")

  /** 样本缩略图目录（与 AutoCaptureScreen 的输出目录保持一致） */
  fun thumbnailDir(): Path =
    getDocumentFilePath().toPath().resolve("test_data").resolve(THUMBNAIL_DIR_NAME)

  /** 是否本地样本事件（合成 eventId，或缩略图路径位于样本目录内） */
  fun isLocalSampleEvent(event: MegaEvent): Boolean =
    event.megaEventId.startsWith(EVENT_ID_PREFIX) || isLocalSamplePath(event.thumbnail.picturePath)

  /** 缩略图路径是否位于本地样本目录内 */
  fun isLocalSamplePath(path: String): Boolean {
    if (path.isBlank()) return false
    val dir = runCatching { thumbnailDir().toString().trimEnd('/') }.getOrNull() ?: return false
    return path.startsWith("$dir/")
  }

  /**
   * 扫描样本目录并构造合成事件（按时间升序，最多 [MAX_LOCAL_SAMPLES] 个）。
   * 目录缺失/为空/读取失败时返回 emptyList。
   */
  suspend fun load(): List<MegaEvent> = withContext(Dispatchers.IO) {
    val dir = runCatching { thumbnailDir() }.getOrNull()
    if (dir == null) {
      dataLogger.w("$TAG/load/skip: reason=dir-unavailable")
      return@withContext emptyList()
    }
    try {
      val entries = platformFileSystem.listOrNull(dir).orEmpty()
        .filter { it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
      if (entries.isEmpty()) {
        dataLogger.i("$TAG/load/skip: reason=empty-dir, path=$dir")
        return@withContext emptyList()
      }

      val events = entries
        .mapNotNull { toSampleEvent(it) }
        .sortedBy { it.startTimestamp }
        .take(MAX_LOCAL_SAMPLES)
      dataLogger.i("$TAG/load/success: count=${events.size}")
      events
    } catch (e: Exception) {
      dataLogger.w("$TAG/load/skip: reason=error, path=$dir, error=${e.message}")
      emptyList()
    }
  }

  /** 读取样本图片并 Base64 编码；内容非法或读取失败返回 null（分析侧走文本降级） */
  suspend fun readThumbnailBase64(event: MegaEvent): String? = withContext(Dispatchers.IO) {
    val path = event.thumbnail.picturePath
    try {
      val bytes = platformFileSystem.source(path.toPath()).buffer().use { it.readByteArray() }
      val base64 = toImageBase64(bytes)
      if (base64 != null) {
        dataLogger.i("$TAG/readThumbnailBase64/success: eventId=${event.megaEventId} length=${base64.length}")
        base64
      } else {
        dataLogger.w("$TAG/readThumbnailBase64/failure: eventId=${event.megaEventId} reason=invalid-image path=$path")
        null
      }
    } catch (e: Exception) {
      dataLogger.w("$TAG/readThumbnailBase64/failure: eventId=${event.megaEventId} path=$path error=${e.message}")
      null
    }
  }

  private fun toImageBase64(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val base64 = if (isBase64Text(bytes)) normalizeBase64Text(bytes.decodeToString()) else Base64.encode(bytes)
    return base64.takeIf { isSupportedImage(it) }
  }

  private fun normalizeBase64Text(raw: String): String =
    raw.trim()
      .removePrefix("data:image/jpeg;base64,")
      .removePrefix("data:image/png;base64,")
      .filterNot { it.isWhitespace() }

  private fun isSupportedImage(base64: String): Boolean {
    val head = runCatching {
      val len = (base64.length / 4) * 4
      Base64.decode(base64.take(minOf(len, 24)))
    }.getOrNull() ?: return false
    return head.startsWith(0xFF, 0xD8, 0xFF) ||
      head.startsWith(0x89, 0x50, 0x4E, 0x47) ||
      head.startsWith(0x47, 0x49, 0x46, 0x38) ||
      (head.size >= 12 &&
        head.startsWith(0x52, 0x49, 0x46, 0x46) &&
        head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
        head[10] == 0x42.toByte() && head[11] == 0x50.toByte())
  }

  private fun ByteArray.startsWith(vararg magic: Int): Boolean =
    size >= magic.size && magic.indices.all { this[it] == magic[it].toByte() }

  private fun isBase64Text(bytes: ByteArray): Boolean {
    val head = bytes.take(64)
    return head.isNotEmpty() && head.all { b ->
      val c = b.toInt().toChar()
      (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '='
    }
  }

  // ─── private ───

  private fun toSampleEvent(file: Path): MegaEvent? {
    val fileName = file.name
    val eventId = "$EVENT_ID_PREFIX$fileName"
    val startMs = resolveStartMillis(file, fileName)
    val timeText = DateUtil.timeMillisToDateTimeString(startMs)
    return MegaEvent(
      megaEventId = eventId,
      parentSn = DEVICE_SN,
      deviceSn = DEVICE_SN,
      deviceName = "local-sample",
      recordId = eventId,
      thumbnail = MegaPicture(
        parentSn = DEVICE_SN,
        picturePath = file.toString(),
        // 样本来自本地录像事件（通常为移动侦测触发），是否有人交给 AI 判定
        detectionType = DetectionType.MOTION.value,
        start_time = timeText,
      ),
      videoPath = "",
      pictures = emptyList(),
      startTimestamp = startMs,
      endTimestamp = startMs,
      startTime = timeText,
      endTime = timeText,
      stringExtra = "local sample: $fileName",
    )
  }

  /**
   * 取文件名中 `~local~yyyyMMddHHmmss` 的「时刻」，拼到今天；
   * 解析失败回退文件 mtime，再回退 now。
   */
  private fun resolveStartMillis(file: Path, fileName: String): Long {
    val digits = TIME_IN_FILE_NAME.find(fileName)?.groupValues?.getOrNull(1)
    val hour = digits?.substring(8, 10)?.toIntOrNull()
    val minute = digits?.substring(10, 12)?.toIntOrNull()
    val second = digits?.substring(12, 14)?.toIntOrNull()
    if (hour != null && minute != null && second != null &&
      hour in 0..23 && minute in 0..59 && second in 0..59
    ) {
      val dateTime = DateUtil.timeMillisToDateTimeString(currentTimeMillis())
      val todayStart = DateUtil.dateTimeStringToTimeMillis(
        try { dateTime.toDateStart() } catch (_: Exception) { dateTime }
      )
      return todayStart + hour * 3_600_000L + minute * 60_000L + second * 1_000L
    }
    val mtime = platformFileSystem.metadataOrNull(file)?.lastModifiedAtMillis
    return mtime?.takeIf { it > 0 } ?: currentTimeMillis()
  }
}
