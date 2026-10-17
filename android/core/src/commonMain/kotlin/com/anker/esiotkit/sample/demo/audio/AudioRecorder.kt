package com.anker.esiotkit.sample.demo.audio

/** 录音器：16kHz / 16bit / 单声道，stop() 返回 WAV 字节 */
interface AudioRecorder {
  fun start()
  fun stop(): ByteArray?
  fun isRecording(): Boolean
}

expect fun createAudioRecorder(): AudioRecorder
