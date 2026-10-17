package com.anker.esiotkit.sample.main.mega.add_device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.security_device.domain.repository.SecurityDeviceRepository
import com.anker.esiotkit.support.di.esInject

/**
 * @version 2024/9/29
 */
class MegaScanViewModel(
  private val repository: SecurityDeviceRepository = esInject()
) : BaseScreenModel() {
  var sn: String = ""
  var isScanned by mutableStateOf(false)

  fun onScanned(code: String) {
    // 如果转换成功（即code是有效的SN号）
    launchWithLoading {
      sn = if (isSn(code)) code else MegaDeviceManager.getDeviceSnBySetupCode(code).await()
      isScanned = true
      // repository
    }
  }

  private fun isSn(sn: String): Boolean {
    val regex = Regex("^T[0-9A-Za-z]{15}$") // 定义正则表达式，以 "T" 开头，后面跟着15位数字或字母
    return if (regex.matches(sn)) true else false
  }

}