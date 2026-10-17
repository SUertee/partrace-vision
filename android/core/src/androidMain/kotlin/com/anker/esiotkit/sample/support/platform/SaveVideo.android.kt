package com.anker.esiotkit.sample.support.platform

import com.anker.esiotkit.sample.support.utils.StoragePathUtils
import java.io.File

/**
 * @version 2024/9/4
 */
internal actual fun saveVideo(path: String, onResult: (success: Boolean) -> Unit) {
  val srcFile = File(path)
  if (!srcFile.exists()) {
    onResult(false)
    return
  }
  StoragePathUtils.shareVideoToGallery(path)
  onResult(true)
}