package com.anker.esiotkit.sample

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.anker.esiotkit.core.config.IotDebugConfig
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.i18n.LanguageManager
import com.anker.esiotkit.sample.support.sampleApplication
import com.anker.esiotkit.support.contextProvider

class MyApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    instance = this
    sampleApplication = this
    ESIotActivityManager.init(this)

    // 提前初始化 contextProvider，确保 InfoCache 在 ESIotKit.start() 之前可用
    // 否则首次访问 InfoCache 会触发 KVSaver 创建，后者立即调用 getContextProvider() 抛异常
    contextProvider = { this }

    // IJKPlayer native 日志静默：开关 ON（默认）→ 关闭 IJKPlayer 所有日志
    // 必须在第一个 IjkMediaPlayer 实例化之前调用，否则已产生的日志无法拦截
    if (InfoCache.ijkLogSilent) {
      try {
        val clazz = Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        val method = clazz.getMethod("native_setLogLevel", Int::class.javaPrimitiveType!!)
        // 使用级别 8 (SILENT)，而非 6 (ERROR) — ffp_queue_picture_v2 等 E 级日志选 6 屏蔽不掉
        val ijkLogSilent = clazz.getField("IJK_LOG_SILENT").getInt(null)
        method.invoke(null, ijkLogSilent)
        Log.i("MyApplication", "IJKPlayer log level set to SILENT (value=$ijkLogSilent) — native ffp logs suppressed")
      } catch (e: ClassNotFoundException) {
        // IJKPlayer not integrated in this app variant, silent is expected
      } catch (e: NoSuchMethodException) {
        Log.w("MyApplication", "IJKPlayer: native_setLogLevel method not available")
      } catch (e: NoSuchFieldException) {
        Log.w("MyApplication", "IJKPlayer: IJK_LOG_SILENT field not found")
      } catch (e: Exception) {
        Log.w("MyApplication", "Failed to set IJK log level: ${e.message}")
      }
    }

    LanguageManager.initialize()

    if (IotDebugConfig.enableDetailDebugLog) {
      val uptimeMs = SystemClock.elapsedRealtime()
      val heapKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
      val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024
      PerfBaseline.appCreatedUptime = uptimeMs
      PerfBaseline.appCreatedHeapKb = heapKb
      PerfBaseline.appCreatedNativeKb = nativeKb
      android.util.Log.i("SdkPerf", "══════ BASELINE: App.onCreate() done ══════")
      android.util.Log.i("SdkPerf", "  uptime=${uptimeMs}ms | heap=${heapKb}KB | native=${nativeKb}KB")
    }
  }

  companion object {
    lateinit var instance: Application
  }
}

object PerfBaseline {
  var appCreatedUptime: Long = 0
  var appCreatedHeapKb: Long = 0
  var appCreatedNativeKb: Long = 0
}
