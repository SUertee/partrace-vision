@file:OptIn(InternalIotApi::class)

package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.media.event_stream.EventPlayerState
import com.anker.esiotkit.media.RecordState
import com.anker.esiotkit.media.VideoInfo
import com.anker.esiotkit.media.event_stream.EventDownloadState
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.event_stream.EventPlayListener
import com.anker.esiotkit.sample.main.mega.media.stopAndRelease
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.support.platform.saveVideo
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.InternalIotApi
import com.anker.esiotkit.support.IoDispatcher
import com.anker.esiotkit.support.ioScope
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.platform.ensureDirectoryExists
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * @version 2024/10/21
 */
class EventPlayViewModel(
  megaDevice: MegaDevice,
  private val megaEvent: MegaEvent
) : BaseScreenModel() {
  val player = EventMediaPlayer(megaEvent, megaDevice)
  var playState by mutableStateOf(EventPlayerState.Idle)
  var playProgress by mutableStateOf(0f)
  var downloadProgress by mutableStateOf(0f)
  var downErrorInfo by mutableStateOf("")
  var aspectRatio by mutableStateOf(16 / 9f)
  var saveFilePath = ""
  var silentDownloading by mutableStateOf(false)

  init {
    player.setPlayListener(object : EventPlayListener {
      private var updateProgressTime = currentTimeMillis()
      override fun onPlayState(status: EventPlayerState, message: String) {
        playState = status
      }

      override fun onMuteState(isMute: Boolean) {
        dataLogger.i("EVT-DL onMuteState: isMute=$isMute")
      }

      override fun onProgress(progress: Float) {
        dataLogger.i("EventPlayViewModel/onProgress/progress:$progress")
        if (progress == 1f) {
          playState = EventPlayerState.Finished
          this@EventPlayViewModel.playProgress = progress
        }
        if (currentTimeMillis() - updateProgressTime < 250) {
          return
        }
        if (playState != EventPlayerState.Playing && playState != EventPlayerState.Paused) {
          playState = EventPlayerState.Playing
        }
        updateProgressTime = currentTimeMillis()
        this@EventPlayViewModel.playProgress = progress
      }

      override fun onDownloadState(status: EventDownloadState, progress: Float, message: String) {
        when (status) {
          EventDownloadState.Idle -> {
            emitToast(if (silentDownloading) "Silent download starting..." else "Download starting...")
          }
          EventDownloadState.Recording -> {
            downloadProgress = progress
            if (silentDownloading) {
              dataLogger.i("EVT-DL progress: $progress")
            }
            dataLogger.i("EventPlayViewModel/downloadEvent/progress:$progress")
          }
          EventDownloadState.Success -> {
            dataLogger.i("EventPlayViewModel/onDownloadState/progress:$progress")
            if (silentDownloading) {
              dataLogger.i("EVT-DL success: $message")
              silentDownloading = false
            }
            downloadProgress = 1f
            val actualPath = message.ifEmpty { saveFilePath }
            saveVideo(actualPath) { saved ->
              if (saved) {
                emitToast("Download success")
                ioScope.launch(IoDispatcher) {
                  delay(100)
                  kotlin.runCatching {
                    platformFileSystem.delete(actualPath.toPath())
                  }.onFailure {
                    dataLogger.i("EventPlayViewModel/onDownloadState/delete file failed:$it")
                  }
                }
                dataLogger.i("EventPlayViewModel/downloadEvent/save video success!!")
              } else {
                emitToast("Save to album failed")
                dataLogger.e("EventPlayViewModel/onDownloadState/save to album failed, path=$actualPath")
              }
            }
          }
          EventDownloadState.Failed -> {
            if (silentDownloading) {
              dataLogger.i("EVT-DL failed: $message")
              silentDownloading = false
            }
            emitToast("Download failed:$message")
            downErrorInfo = message
          }
          EventDownloadState.Timeout -> {
            if (silentDownloading) {
              dataLogger.i("EVT-DL timeout")
              silentDownloading = false
            }
            emitToast("Download timeout")
          }
        }
      }

      override fun onVideoInfo(info: VideoInfo) {
        dataLogger.i("EventPlayViewModel/onVideoInfo/info:$info")
        val aspectRatio = info.width / info.height.toFloat()
        if (this@EventPlayViewModel.aspectRatio != aspectRatio) {
          this@EventPlayViewModel.aspectRatio = aspectRatio
        }
      }
    })
  }

  fun play() {
    player.startPlay()
  }

  fun stop() {
    playState = EventPlayerState.Finished
    player.stopPlay()
  }

  fun pause() {
    playState = EventPlayerState.Paused
    player.pause()
  }

  fun resume() {
    playState = EventPlayerState.Playing
    player.resume()
  }

  fun seek(progress: Float) {
    playProgress = progress
    player.seek(progress)
  }

  fun stopAndRelease() {
    dataLogger.i("EventPlayViewModel/stopAndRelease/entry: playState=$playState")
    playState = EventPlayerState.Finished
    player.stopAndRelease()
    dataLogger.i("EventPlayViewModel/stopAndRelease/done")
  }

  fun release() {
    player.release()
  }

  fun mute(isMute: Boolean) {
    player.setMute(isMute)
  }

  var isMuted by mutableStateOf(false)

  fun toggleMute() {
    isMuted = !isMuted
    player.setMute(isMuted)
    dataLogger.i("EVT-DL toggleMute: muted=$isMuted")
  }

  fun downloadEvent() {
    val downloadDir = getDocumentFilePath().toPath().resolve("download_event")
    platformFileSystem.ensureDirectoryExists(downloadDir)
    val saveFileName = "${currentTimeMillis()}.mp4"
    saveFilePath = downloadDir.resolve(saveFileName).toString()
    dataLogger.i("EVT-DL downloadEvent: path=$saveFilePath, event=${megaEvent.megaEventId}")
    dataLogger.i("EventPlayViewModel/downloadEvent/path:${saveFilePath}")
    emitToast("Download started")
    player.startDownload(path = saveFilePath)
  }

  fun downloadEventSilently() {
    val downloadDir = getDocumentFilePath().toPath().resolve("download_event_silent")
    platformFileSystem.ensureDirectoryExists(downloadDir)
    val saveFileName = "${currentTimeMillis()}.mp4"
    saveFilePath = downloadDir.resolve(saveFileName).toString()
    dataLogger.i("EVT-DL downloadEventSilently: savePath=$saveFilePath, event=${megaEvent.megaEventId}")
    try { player.pause() } catch (e: Exception) { dataLogger.i("EVT-DL pause exception: ${e.message}") }
    dataLogger.i("EVT-DL paused before startDownload")
    emitToast("Silent download started")
    player.startDownload(path = saveFilePath)
    dataLogger.i("EVT-DL startDownload called")
    silentDownloading = true
  }

}
