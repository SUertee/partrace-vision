package com.anker.esiotkit.sample.utils

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
@date: 2023/8/2
@desc:
 */
object QRCode {
  fun createQRCode(text: String?, size: Int, level: ErrorCorrectionLevel?): Bitmap? {
    return try {
      val hints = HashMap<EncodeHintType, Any?>()
      hints[EncodeHintType.CHARACTER_SET] = "utf-8"
      hints[EncodeHintType.ERROR_CORRECTION] = level
      hints[EncodeHintType.MARGIN] = 1
      val bitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE, size, size, hints
      )
      val pixels = IntArray(size * size)
      for(y in 0 until size) {
        for(x in 0 until size) {
          if (bitMatrix[x, y]) {
            pixels[y * size + x] = -0x1000000
          } else {
            pixels[y * size + x] = -0x1
          }
        }
      }
      val bitmap = Bitmap.createBitmap(
        size, size,
        Bitmap.Config.ARGB_8888
      )
      bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
      bitmap
    } catch (e: WriterException) {
      e.printStackTrace()
      null
    }
  }
}