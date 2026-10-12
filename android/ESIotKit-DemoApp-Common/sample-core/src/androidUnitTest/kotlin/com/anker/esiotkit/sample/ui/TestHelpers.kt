package com.anker.esiotkit.sample.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.anker.esiotkit.core.entities.device.ESIotDevice
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.support.i18n.AppLanguage
import com.anker.esiotkit.sample.support.i18n.LanguageManager
import io.mockk.every
import io.mockk.mockk
import java.util.Locale

/**
 * T-UI-01~06: UI 测试辅助工具
 *
 * 提供创建 mock MegaDevice 实例的辅助方法。
 * 使用 MockK relaxed mocks 避免 SDK 方法调用导致的问题。
 */
object TestHelpers {

  /**
   * 创建 mock MegaDevice 实例
   */
  fun createMockDevice(
    deviceSn: String,
    parentSn: String = deviceSn,
    deviceName: String = ""
  ): MegaDevice {
    val iotDevice = ESIotDevice(
      deviceSn = deviceSn,
      parentSn = parentSn,
      deviceName = deviceName
    )

    val mock = mockk<MegaDevice>(relaxed = true)
    every { mock.deviceSn } returns deviceSn
    every { mock.parentSn } returns parentSn
    every { mock.iotDevice } returns iotDevice
    every { mock.getProperty(any<String>()) } returns null
    return mock
  }

  /**
   * 创建基站设备 mock
   */
  fun createMockStation(
    deviceSn: String,
    deviceName: String = "",
    childCount: Int = 0
  ): MegaDevice {
    return createMockDevice(
      deviceSn = deviceSn,
      parentSn = deviceSn,
      deviceName = deviceName
    )
  }

  /**
   * 创建子设备 mock
   */
  fun createMockChild(
    deviceSn: String,
    deviceName: String = ""
  ): MegaDevice {
    // 子设备的 parentSn 不等于 deviceSn
    val parentSn = deviceSn.replace(Regex("T30\\d\\d"), "T8010")
      .replace(Regex("CHILD.*"), "")
      .takeIf { it.isNotEmpty() } ?: "T8010PARENT"

    return createMockDevice(
      deviceSn = deviceSn,
      parentSn = parentSn,
      deviceName = deviceName
    )
  }

  /**
   * 创建独立设备 mock
   */
  fun createMockStandalone(
    deviceSn: String,
    deviceName: String = ""
  ): MegaDevice {
    return createMockDevice(
      deviceSn = deviceSn,
      parentSn = deviceSn,
      deviceName = deviceName
    )
  }
}

fun forceEnglishLocale() {
    trySetRobolectricQualifiers("+en-rUS")
    Locale.setDefault(Locale.US)
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
    try { LanguageManager.setLanguage(AppLanguage.EN) } catch (_: Throwable) {}
}

fun forceZhLocale() {
    trySetRobolectricQualifiers("+zh-rCN")
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"))
    try { LanguageManager.setLanguage(AppLanguage.ZH) } catch (_: Throwable) {}
}

private fun trySetRobolectricQualifiers(qualifiers: String) {
    try {
        val clazz = Class.forName("org.robolectric.RuntimeEnvironment")
        val method = clazz.getMethod("setQualifiers", String::class.java)
        method.invoke(null, qualifiers)
    } catch (_: Throwable) {
    }
}
