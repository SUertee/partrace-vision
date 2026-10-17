package com.anker.esiotkit.sample.main.station.events

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.anker.esiotkit.sample.main.station.agent.TraceApiFailure
import com.anker.esiotkit.sample.main.station.agent.normalizeTraceBaseUrl
import com.anker.esiotkit.sample.main.station.agent.requireTraceId
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import kotlin.coroutines.resumeWithException

internal class AndroidStationRecordingPlayback(internal val file: File) : StationRecordingPlayback {
  override fun release() { file.delete() }
}

private val recordingHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
  .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

/** Fixed authorized endpoint; MP4 is streamed privately, never put in an Intent or shared URL. */
internal suspend fun downloadStationRecording(cacheDir: File, origin: String, bearer: String, recordingId: String,
  client: OkHttpClient = recordingHttpClient, maxBytes: Long = 64L * 1024 * 1024): StationRecordingPlayback {
  val trustedOrigin = normalizeTraceBaseUrl(origin) ?: throw TraceApiFailure(0, "invalid_configuration")
  requireTraceId(recordingId)
  require(maxBytes > 0)
  val request = Request.Builder().url("$trustedOrigin/api/v1/recordings/$recordingId/media")
    .header("Authorization", "Bearer $bearer").header("Accept", "video/mp4").get().build()
  return suspendCancellableCoroutine { continuation ->
    val partial = AtomicReference<File?>(null)
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel(); partial.getAndSet(null)?.delete() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        partial.getAndSet(null)?.delete()
        if (continuation.isActive) continuation.resumeWithException(TraceApiFailure(0, "network_unavailable"))
      }
      override fun onResponse(call: Call, response: Response) {
        var finished: AndroidStationRecordingPlayback? = null
        var ownedFile: File? = null
        try {
          response.use {
            if (it.code != 200) throw TraceApiFailure(it.code, if (it.code == 404) "media_missing" else "http_error")
            val body = it.body ?: throw TraceApiFailure(it.code, "invalid_media")
            if (body.contentType()?.let { type -> "${type.type}/${type.subtype}" } != "video/mp4")
              throw TraceApiFailure(it.code, "invalid_media")
            if (body.contentLength() > maxBytes) throw TraceApiFailure(it.code, "response_too_large")
            val directory = File(cacheDir, "station-recording-playback")
            if (!directory.isDirectory && !directory.mkdirs()) throw TraceApiFailure(0, "media_storage_unavailable")
            val file = File.createTempFile("recording-", ".mp4", directory)
            ownedFile = file
            partial.set(file)
            if (!continuation.isActive) { partial.getAndSet(null)?.delete(); return }
            var count = 0L
            file.outputStream().use { output ->
              body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                  val size = input.read(buffer)
                  if (size < 0) break
                  count += size
                  if (count > maxBytes) throw TraceApiFailure(it.code, "response_too_large")
                  output.write(buffer, 0, size)
                }
              }
            }
            if (count == 0L) throw TraceApiFailure(it.code, "invalid_media")
            finished = AndroidStationRecordingPlayback(file)
            partial.set(null)
          }
          val playback = finished ?: throw TraceApiFailure(0, "invalid_media")
          // Also clean up cancellation after IO completes but before the caller resumes.
          continuation.resume(playback) { _, resource, _ -> resource.release() }
        } catch (failure: Exception) {
          partial.getAndSet(null)?.delete()
          ownedFile?.delete()
          finished?.release()
          if (continuation.isActive) continuation.resumeWithException(
            failure as? TraceApiFailure ?: TraceApiFailure(0, "network_unavailable"))
        }
      }
    })
  }
}

@Composable
actual fun StationRecordingPlayer(playback: StationRecordingPlayback, startOffsetMs: Long,
  modifier: Modifier, onFailure: (String) -> Unit) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val reportFailure by rememberUpdatedState(onFailure)
  val local = playback as? AndroidStationRecordingPlayback
  if (local == null || startOffsetMs !in 0..Int.MAX_VALUE.toLong()) {
    LaunchedEffect(playback, startOffsetMs) { reportFailure("原片播放参数无效，请重新打开片段。") }
    return
  }
  val view = remember(playback, startOffsetMs, context) {
    VideoView(context).apply {
      val playerView = this
      setMediaController(MediaController(context).apply { setAnchorView(playerView) })
      setOnErrorListener { _, _, _ -> reportFailure("原片无法解码或播放，请刷新后重试。"); true }
      setOnPreparedListener { player ->
        if (player.duration <= 0 || startOffsetMs >= player.duration) {
          reportFailure("事件时间超出可播放原片范围，请刷新后重试。")
        } else if (startOffsetMs > 0) {
          player.setOnSeekCompleteListener { playerView.start() }
          seekTo(startOffsetMs.toInt())
        } else start()
      }
      setVideoPath(local.file.absolutePath)
    }
  }
  DisposableEffect(view, lifecycle) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) view.stopPlayback() }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer); view.stopPlayback() }
  }
  AndroidView(factory = { view }, modifier = modifier)
}
