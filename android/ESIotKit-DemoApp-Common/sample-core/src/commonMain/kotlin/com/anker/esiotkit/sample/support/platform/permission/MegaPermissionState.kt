package com.anker.esiotkit.sample.support.platform.permission

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * @version 2024/9/9
 */

interface MegaPermissionState {
  val status: MegaPermissionStatus
  fun requestCameraPermission()
  fun goToSettings()
}

abstract class MutablePermissionState : MegaPermissionState {
  override var status: MegaPermissionStatus by mutableStateOf(MegaPermissionStatus.Denied)
}

enum class MegaPermissionStatus {
  Denied, Granted
}

