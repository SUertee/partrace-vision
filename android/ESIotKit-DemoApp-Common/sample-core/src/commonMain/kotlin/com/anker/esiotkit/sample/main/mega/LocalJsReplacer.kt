@file:OptIn(InternalIotApi::class)

package com.anker.esiotkit.sample.main.mega

import com.anker.esiotkit.domain_thing.repository.TDRepository
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.support.InternalIotApi
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.ensureDirectoryExists
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

object LocalJsReplacer {
  private val localJsDir get() = getDocumentFilePath().toPath().resolve("LocalJs")

  suspend fun apply(): List<String> {
    val replaced = mutableListOf<String>()
    val pns = MegaDeviceManager.deviceList.value.map { it.devicePn }.distinct()

    platformFileSystem.ensureDirectoryExists(localJsDir)

    for (pn in pns) {
      val localFile = localJsDir.resolve("${pn}.js")
      if (!platformFileSystem.exists(localFile)) continue

      try {
        val targetPath = TDRepository.instance.getJSFilePath(pn).toPath()
        val targetDir = targetPath.parent ?: continue
        platformFileSystem.ensureDirectoryExists(targetDir)

        val bytes = platformFileSystem.source(localFile).buffer().readByteArray()
        platformFileSystem.sink(targetPath).buffer().use { it.write(bytes) }

        TDRepository.instance.deleteThingFiles(pn)

        appLogger.i("LocalJsReplacer: replaced JS for $pn")
        replaced.add(pn)
      } catch (e: Exception) {
        appLogger.w("LocalJsReplacer: failed for $pn: ${e.message}")
      }
    }

    return replaced
  }
}
