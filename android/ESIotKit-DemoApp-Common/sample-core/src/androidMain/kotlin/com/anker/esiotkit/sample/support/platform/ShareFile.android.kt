package com.anker.esiotkit.sample.support.platform

import android.content.Intent
import androidx.core.content.FileProvider
import com.anker.esiotkit.sample.support.sampleApplication
import java.io.File

/**
 * @version 2024/10/28
 */
actual fun shareFile(filePath: String) {
  sampleApplication?.let {
    val shareIntent: Intent = Intent().apply {
      action = Intent.ACTION_SEND
      // Example: content://com.google.android.apps.photos.contentprovider/...
      val uri = FileProvider.getUriForFile(it, "${it.packageName}.fileprovider", File(filePath))
      putExtra(Intent.EXTRA_STREAM, uri)
      type = "image/jpeg"
    }
    val shareIntentChooser = Intent.createChooser(shareIntent, "Share File").apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    it.startActivity(shareIntentChooser)
  }
}