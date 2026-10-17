package com.anker.esiotkit.sample.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.anker.esiotkit.sample.support.rememberP2PImageRequest
import com.anker.esiotkit.support.log.appLogger

@Composable
fun DeviceAsyncImage(
  parentSn: String,
  imagePath: String,
  modifier: Modifier = Modifier,
  onLoadError: () -> Unit = {}
) {
  val context = LocalPlatformContext.current
  val model = if (imagePath.startsWith("/")) {
    ImageRequest.Builder(context)
      .data(imagePath)
      .memoryCachePolicy(CachePolicy.DISABLED)
      .build()
  } else {
    rememberP2PImageRequest(parentSn, imagePath)
  }

  AsyncImage(
    model = model,
    contentDescription = null,
    modifier = modifier,
    contentScale = ContentScale.Crop,
    onLoading = {
      appLogger.i("[IMG-CACHE] Loading: path=$imagePath, sn=$parentSn")
    },
    onSuccess = {
      appLogger.i("[IMG-CACHE] Success: path=$imagePath, sn=$parentSn")
    },
    onError = { error ->
      appLogger.e("[IMG-CACHE]", "Failed: path=$imagePath, sn=$parentSn, error=${error.result.throwable?.message}")
      onLoadError()
    }
  )
}
