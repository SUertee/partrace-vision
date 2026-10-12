package com.anker.esiotkit.sample.demo.audio

/** 音频播放器：播放 MP3/音频字节 */
interface AudioPlayer {
  fun play(data: ByteArray, onComplete: (() -> Unit)? = null)
  fun stop()
}

expect fun createAudioPlayer(): AudioPlayer
