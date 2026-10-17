package com.anker.esiotkit.sample.demo.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.anker.esiotkit.support.log.appLogger
import java.io.ByteArrayOutputStream

actual fun createAudioRecorder(): AudioRecorder = AndroidAudioRecorder()

private class AndroidAudioRecorder : AudioRecorder {

  private companion object {
    const val SAMPLE_RATE = 16000
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
  }

  private var audioRecord: AudioRecord? = null
  private var worker: Thread? = null
  private val pcmBuffer = ByteArrayOutputStream()

  @Volatile
  private var recording = false

  @SuppressLint("MissingPermission")
  override fun start() {
    if (recording) return
    appLogger.i("AndroidAudioRecorder/start: sampleRate=$SAMPLE_RATE, source=MIC")
    val minBuffer = try {
      AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    } catch (e: Exception) {
      appLogger.e("AndroidAudioRecorder/start/failure: reason=getMinBufferSize, error=${e.message ?: e::class.simpleName}")
      return
    }
    if (minBuffer <= 0) {
      appLogger.e("AndroidAudioRecorder/start/failure: reason=invalid-min-buffer, minBuffer=$minBuffer")
      return
    }
    val record = try {
      AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBuffer * 4)
    } catch (e: Exception) {
      appLogger.e("AndroidAudioRecorder/start/failure: reason=create, error=${e.message ?: e::class.simpleName}")
      return
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      val state = record.state
      record.release()
      appLogger.e("AndroidAudioRecorder/start/failure: reason=init-failed, state=$state")
      return
    }
    try {
      record.startRecording()
    } catch (e: Exception) {
      record.release()
      appLogger.e("AndroidAudioRecorder/start/failure: reason=startRecording, error=${e.message ?: e::class.simpleName}")
      return
    }
    audioRecord = record
    pcmBuffer.reset()
    recording = true
    worker = Thread {
      val chunk = ByteArray(minBuffer)
      while (recording) {
        try {
          val read = record.read(chunk, 0, chunk.size)
          if (read > 0) {
            pcmBuffer.write(chunk, 0, read)
          } else {
            if (recording) appLogger.e("AndroidAudioRecorder/read/failure: code=$read")
            break
          }
        } catch (e: Exception) {
          appLogger.e("AndroidAudioRecorder/read/failure: reason=exception, error=${e.message ?: e::class.simpleName}")
          break
        }
      }
    }.also { it.start() }
    appLogger.i("AndroidAudioRecorder/start/success: minBuffer=$minBuffer")
  }

  override fun stop(): ByteArray? {
    if (!recording) {
      appLogger.w("AndroidAudioRecorder/stop/failure: reason=not-recording")
      return null
    }
    appLogger.i("AndroidAudioRecorder/stop/start: pcmBytes=${pcmBuffer.size()}")
    recording = false
    runCatching { worker?.join(500) }
    audioRecord?.let { record ->
      runCatching { record.stop() }
      runCatching { record.release() }
    }
    audioRecord = null
    worker = null
    val pcm = pcmBuffer.toByteArray()
    pcmBuffer.reset()
    if (pcm.isEmpty()) {
      appLogger.e("AndroidAudioRecorder/stop/failure: reason=empty-pcm")
      return null
    }
    val wav = wrapWav(pcm)
    appLogger.i("AndroidAudioRecorder/stop/success: pcmBytes=${pcm.size}, wavBytes=${wav.size}")
    return wav
  }

  override fun isRecording(): Boolean = recording

  private fun wrapWav(pcm: ByteArray): ByteArray {
    val header = ByteArray(44)
    fun putAscii(offset: Int, text: String) {
      for (i in text.indices) header[offset + i] = text[i].code.toByte()
    }
    fun putIntLE(offset: Int, value: Int) {
      header[offset] = (value and 0xFF).toByte()
      header[offset + 1] = (value shr 8 and 0xFF).toByte()
      header[offset + 2] = (value shr 16 and 0xFF).toByte()
      header[offset + 3] = (value shr 24 and 0xFF).toByte()
    }
    fun putShortLE(offset: Int, value: Int) {
      header[offset] = (value and 0xFF).toByte()
      header[offset + 1] = (value shr 8 and 0xFF).toByte()
    }
    putAscii(0, "RIFF")
    putIntLE(4, pcm.size + 36)
    putAscii(8, "WAVE")
    putAscii(12, "fmt ")
    putIntLE(16, 16)
    putShortLE(20, 1)
    putShortLE(22, 1)
    putIntLE(24, SAMPLE_RATE)
    putIntLE(28, SAMPLE_RATE * 2)
    putShortLE(32, 2)
    putShortLE(34, 16)
    putAscii(36, "data")
    putIntLE(40, pcm.size)
    return header + pcm
  }
}
