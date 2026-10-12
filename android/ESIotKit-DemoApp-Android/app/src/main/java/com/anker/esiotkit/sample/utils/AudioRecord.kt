package com.anker.esiotkit.sample.utils

import android.media.MediaRecorder
import java.io.File
import java.io.IOException

/**
@date: 2023/8/16
@desc:
 */
object AudioRecord {
  private var recorder: MediaRecorder? = null

  fun startRecord(path: String) {
    val file = File(path)
    if (!file.exists()) {
      file.createNewFile()
    }
    recorder = MediaRecorder().apply {
      setAudioSource(MediaRecorder.AudioSource.MIC)
      setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS) // For AAC format
      // setOutputFormat(MediaRecorder.OutputFormat.DEFAULT) // For WAV format
      setAudioSamplingRate(16000)
      setOutputFile(path)
      setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // For AAC format
      // setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT) // For WAV format

      try {
        prepare()
      } catch (e: IOException) {
        e.printStackTrace()
      }

      start()
    }
  }

  fun stopRecord() {
    recorder?.apply {
      stop()
      release()
    }
    recorder = null
  }
}