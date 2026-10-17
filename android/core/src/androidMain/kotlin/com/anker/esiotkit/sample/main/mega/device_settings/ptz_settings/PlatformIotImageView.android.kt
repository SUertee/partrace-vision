package com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Android implementation of PlatformIotImageView
 * Displays a Bitmap image (IotImage is typealias to Bitmap on Android)
 */
@Composable
actual fun PlatformIotImageView(image: Bitmap, modifier: Modifier) {
  Image(
    bitmap = image.asImageBitmap(),
    contentDescription = "Preset thumbnail",
    modifier = modifier,
    contentScale = ContentScale.Crop
  )
}
