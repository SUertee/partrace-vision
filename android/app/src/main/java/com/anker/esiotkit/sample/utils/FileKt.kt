package com.anker.esiotkit.sample.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import java.io.File

/**
@date: 2023/8/16
@desc:
 */
object FileKt {
  fun getExternalDirPath(context: Context?, mediaType: String, sku: String?): String {
    val path: String?
    var externalFilesDir: File? = context?.getExternalFilesDir(mediaType)
    if (externalFilesDir == null) {
      externalFilesDir = context?.filesDir
    }
    path = externalFilesDir?.absolutePath + File.separator
    return if (TextUtils.isEmpty(sku)) {
      val file = File(path)
      if (!file.exists()) {
        file.mkdirs()
      }
      path
    } else {
      val file = File(path, sku)
      if (!file.exists()) {
        file.mkdirs()
      }
      file.absolutePath + File.separator
    }
  }

}