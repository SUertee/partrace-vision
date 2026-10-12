package com.anker.esiotkit.sample.support.platform

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

actual fun generateQrCode(text: String, size: Int, version: Int): ByteArray {
  val hints = hashMapOf<EncodeHintType, Any>(
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,  // 低纠错，方块更粗
    EncodeHintType.MARGIN to 1  // 最小边距
    // 不指定版本，让 ZXing 根据内容自动选择最小版本
  )

  val writer = QRCodeWriter()
  val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints)

  val width = bitMatrix.width
  val height = bitMatrix.height
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

  for (x in 0 until width) {
    for (y in 0 until height) {
      bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
  }

  val stream = ByteArrayOutputStream()
  bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
  return stream.toByteArray()
}
