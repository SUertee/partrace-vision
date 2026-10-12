@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.demo.audio

import android.media.MediaPlayer
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import okio.Path.Companion.toPath
import okio.buffer

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayer()

private class AndroidAudioPlayer : AudioPlayer {

  private var mediaPlayer: MediaPlayer? = null

  override fun play(data: ByteArray, onComplete: (() -> Unit)?) {
    stop()
    try {
      val path = getDocumentFilePath().toPath().resolve("aidemo_tts.mp3")
      platformFileSystem.sink(path).buffer().use { it.write(data) }
      val player = MediaPlayer()
      player.setDataSource(path.toString())
      player.setOnCompletionListener {
        onComplete?.invoke()
        stop()
      }
      player.setOnErrorListener { _, _, _ ->
        stop()
        onComplete?.invoke()
        true
      }
      player.prepare()
      player.start()
      mediaPlayer = player
      appLogger.i("AndroidAudioPlayer/play/start: bytes=${data.size}")
    } catch (e: Exception) {
      appLogger.e("AndroidAudioPlayer/play/failure: ${e.message}")
      onComplete?.invoke()
    }
  }

  override fun stop() {
    runCatching { mediaPlayer?.stop() }
    runCatching { mediaPlayer?.release() }
    mediaPlayer = null
  }
}
