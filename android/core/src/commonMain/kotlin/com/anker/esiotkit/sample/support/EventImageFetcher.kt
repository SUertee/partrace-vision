package com.anker.esiotkit.sample.support

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import com.anker.esiotkit.domain.model.MegaPicture
import com.anker.esiotkit.manager.MegaEventManager
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.toByteArray
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okio.FileSystem
import kotlin.coroutines.resume

/**
 * Demo of the public event-picture API (MegaEventManager.getEventImage):
 * customer-equivalent path for per-event thumbnails (0811 QA: A1/C2).
 */
class EventImageFetcher(private val picture: MegaPicture) : Fetcher {
  override suspend fun fetch(): FetchResult? {
    val cached = EventImageCache.get(picture.picturePath)
    if (cached != null) {
      appLogger.i("[IMG-CACHE] cache hit, skip P2P: path=${picture.picturePath}")
      return SourceFetchResult(
        source = ImageSource(source = Buffer().write(cached), fileSystem = FileSystem.SYSTEM),
        mimeType = "image/jpeg",
        dataSource = DataSource.MEMORY
      )
    }

    appLogger.i("[IMG-CACHE] fetch: parentSn=${picture.parentSn}, path=${picture.picturePath}")
    val bytes = suspendCancellableCoroutine<ByteArray?> { cont ->
      val job = MegaEventManager.getEventImage(
        picture,
        onSuccess = { esBytes -> if (cont.isActive) cont.resume(esBytes.toByteArray()) },
        onFailure = { code, msg ->
          appLogger.e("EventImageFetcher", "fetch failed: code=$code, msg=$msg")
          if (cont.isActive) cont.resume(null)
        }
      )
      cont.invokeOnCancellation { runCatching { job.cancel() } }
    }
    if (bytes == null) return null
    EventImageCache.put(picture.picturePath, bytes)
    appLogger.i("[IMG-CACHE] fetch success: parentSn=${picture.parentSn}, path=${picture.picturePath}, bytes=${bytes.size}")
    return SourceFetchResult(
      source = ImageSource(source = Buffer().write(bytes), fileSystem = FileSystem.SYSTEM),
      mimeType = "image/jpeg",
      dataSource = DataSource.MEMORY
    )
  }

  class Factory : Fetcher.Factory<MegaPicture> {
    override fun create(data: MegaPicture, options: Options, imageLoader: ImageLoader): Fetcher {
      return EventImageFetcher(data)
    }
  }
}

@Composable
fun rememberEventImageRequest(picture: MegaPicture): ImageRequest {
  val context = LocalPlatformContext.current
  return remember(picture.pictureId, picture.parentSn, picture.picturePath) {
    ImageRequest.Builder(context)
      .data(picture)
      .fetcherFactory(EventImageFetcher.Factory())
      .build()
  }
}
