@file:OptIn(InternalIotApi::class)

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
import com.anker.esiotkit.core.imageCache.MegaImageManager
import com.anker.esiotkit.support.InternalIotApi
import com.anker.esiotkit.support.platform.platformFileSystem
import okio.Path.Companion.toPath

/**
 * @version 2024/10/22
 */

class P2PImageFetcher(private val parentSn: String, private val p2pPath: String) : Fetcher {
  @OptIn(ExperimentalStdlibApi::class)
  override suspend fun fetch(): FetchResult? {
    com.anker.esiotkit.support.log.appLogger.i("P2PImageFetcher", "Fetching image: parentSn=$parentSn, path=$p2pPath")

    val detail = MegaImageManager.getImageDetail(p2pPath, parentSn, true)

    com.anker.esiotkit.support.log.appLogger.i("P2PImageFetcher", "MegaImageManager result: code=${detail.code}, imagePath=${detail.imagePath}")

    return if (detail.code == 0) {
      SourceFetchResult(
        source = ImageSource(
          file = detail.imagePath.toPath(),
          fileSystem = platformFileSystem,
        ),
        mimeType = "image/jpeg",
        dataSource = DataSource.DISK
      )
    } else {
      com.anker.esiotkit.support.log.appLogger.e("P2PImageFetcher", "Failed to fetch image: code=${detail.code}")
      null
    }
  }

  class Factory : Fetcher.Factory<RequestInfo> {
    override fun create(data: RequestInfo, options: Options, imageLoader: ImageLoader): Fetcher {
      return P2PImageFetcher(data.parentSn, data.path)
    }

  }

  data class RequestInfo(val parentSn: String, val path: String)
}

@Composable
fun rememberP2PImageRequest(parentSn: String, path: String): ImageRequest {
  val context = LocalPlatformContext.current
  return remember(parentSn, path) {
    val info = P2PImageFetcher.RequestInfo(parentSn, path)
    ImageRequest.Builder(context)
      .data(info)
      .fetcherFactory(P2PImageFetcher.Factory())
      .build()
  }
}