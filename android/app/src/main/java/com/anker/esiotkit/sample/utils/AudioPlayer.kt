package com.anker.esiotkit.sample.utils

import android.media.MediaPlayer
import java.io.IOException

/**
@date: 2023/8/16
@desc:
 */
object AudioPlayer {

  private var player: MediaPlayer? = null

  fun startPlay(path: String) {
    player = MediaPlayer().apply {
      try {
        setDataSource(path)
        prepare()
        start()
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }

  fun stopPlay() {
    player?.apply {
      stop()
      release()
    }
    player = null
  }
}