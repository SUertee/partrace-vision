@file:OptIn(ExperimentalPermissionsApi::class)

package com.anker.esiotkit.sample.support.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.anker.esiotkit.sample.support.platform.permission.MegaPermissionStatus
import com.anker.esiotkit.sample.support.platform.permission.MegaPermissionState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState

/**
 * @version 2024/9/9
 */
@Composable
actual fun rememberAudioPermissionState(): MegaPermissionState {
  val permissionState = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
  val context = LocalContext.current
  val wrapper = remember(permissionState) { MegaPermissionWrapper(permissionState, context) }
  return wrapper
}

@Composable
actual fun rememberBlePermissionState(): MegaPermissionState {
  val permissionState = rememberMultiplePermissionsState(
    listOf(
      android.Manifest.permission.BLUETOOTH_SCAN,
      android.Manifest.permission.BLUETOOTH_CONNECT,
      android.Manifest.permission.ACCESS_COARSE_LOCATION,
      // android.Manifest.permission.NEARBY_WIFI_DEVICES
    )
  )
  val context = LocalContext.current
  val wrapper = remember(permissionState) { MegaMultiPermissionWrapper(permissionState, context) }
  return wrapper
}

@OptIn(ExperimentalPermissionsApi::class)
class MegaMultiPermissionWrapper(
  private val megaPermissionState: com.google.accompanist.permissions.MultiplePermissionsState,
  private val context: Context
) :
  MegaPermissionState {
  override val status: MegaPermissionStatus
    get() {
      return if (megaPermissionState.allPermissionsGranted) {
        MegaPermissionStatus.Granted
      } else {
        MegaPermissionStatus.Denied
      }
    }

  override fun requestCameraPermission() {
    megaPermissionState.launchMultiplePermissionRequest()
  }

  override fun goToSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.parse("package:" + context.packageName)
    ContextCompat.startActivity(context, intent, null)
  }
}

@OptIn(ExperimentalPermissionsApi::class)
class MegaPermissionWrapper(
  private val megaPermissionState: com.google.accompanist.permissions.PermissionState,
  private val context: Context
) :
  MegaPermissionState {
  override val status: MegaPermissionStatus
    get() = megaPermissionState.status.toPermissionStatus()

  override fun requestCameraPermission() {
    megaPermissionState.launchPermissionRequest()
  }

  override fun goToSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.parse("package:" + context.packageName)
    ContextCompat.startActivity(context, intent, null)
  }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun PermissionStatus.toPermissionStatus(): MegaPermissionStatus {
  return when (this) {
    is PermissionStatus.Denied -> MegaPermissionStatus.Denied
    PermissionStatus.Granted -> MegaPermissionStatus.Granted
  }
}
