package com.anker.esiotkit.sample.support.platform

import android.graphics.Bitmap
import com.anker.esiotkit.sample.support.utils.StoragePathUtils
import com.anker.esiotkit.support.contextProvider
import com.anker.esiotkit.support.ioScope
import kotlinx.coroutines.launch

internal actual fun saveImage(image: Bitmap) {
  ioScope.launch {
    StoragePathUtils.saveBitmap(contextProvider(), image)
  }
}
