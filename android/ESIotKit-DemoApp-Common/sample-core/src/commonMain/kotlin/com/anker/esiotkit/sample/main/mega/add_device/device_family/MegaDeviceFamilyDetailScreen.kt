package com.anker.esiotkit.sample.main.mega.add_device.device_family

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.material3.Icon
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.anker.esiotkit.sample.main.SampleAppEvent
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.MergedDeviceCard
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_device_family_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/7/23
 */
class MegaDeviceFamilyDetailScreen(@Transient val category: DeviceCategory) : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_add_device_device_family_title

  @Composable
  override fun Content() = BaseColumn {

    val viewModel = rememberScreenModel { MegaDeviceFamilyViewModel() }
    val uiState by viewModel.uiState.collectAsState()

    var showDialog by remember { mutableStateOf(false) }

    val navigator = LocalNavigator.currentOrThrow
    val mergedDevices = category.deviceList
      .groupBy { it.deviceCateLv2 }
      .map { (cateLv2, devices) ->
        MergedDevice(
          deviceCateLv2 = cateLv2,
          deviceTypeNames = devices.map { it.deviceTypeName },
          iconURL = devices.first().iconURL,
          devicePNs = devices.flatMap { it.devicePNs },
          isBlur = devices.any { it.isBlur },
          isNewTag = devices.any { it.isNewTag },
          isHidden = devices.all { it.isHidden },
          countryCodeBlackList = devices.flatMap { it.countryCodeBlackList }.distinct(),
          countryCodeWhiteList = devices.flatMap { it.countryCodeWhiteList }.distinct(),
          isFollowNewProcess = devices.any { it.isFollowNewProcess }
        )
      }

    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)
    ) {
      items(mergedDevices) { device ->
        LaunchedEffect(device.devicePNs) {
          device.devicePNs.forEach { pn ->
            viewModel.loadDeviceIconUrl(pn)
          }
        }
        val iconUrl = uiState.deviceIconUrls[device.devicePNs.first()] ?: ""
        appLogger.i("deviceiconUrl:$iconUrl")
        MergedDeviceCard(device = device.copy(iconURL = iconUrl), onClick = {
          SampleAppEvent.emit(SampleAppEvent.IntoPairing(device.devicePNs.first()))
          appLogger.i("MegaDeviceFamilyDetailScreen/Content/onClick/pn:${device.devicePNs}")
        })
        Spacer(modifier = Modifier.height(16.dp))
      }
    }

    ConfirmDialog(
      showDialog = showDialog,
      onDismissRequest = {

      },
      onConfirm = {
        navigator.popUntil { it is MegaMainScreen }
        showDialog = false
      },
      onCancel = {
        navigator.popUntil { it is MegaMainScreen }
        showDialog = false
      }
    )
  }

  @Composable
  fun ConfirmDialog(
    showDialog: Boolean,
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit
  ) {
    if (showDialog) {
      AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = {
          Text(
            text = "Please add HomeBase before setup camera.", textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
          )
        },
        text = null,
        shape = RoundedCornerShape(16.dp),
        buttons = {
          Column(
            modifier = Modifier
              .background(Color.White)
              .padding(top = 8.dp)
          ) {
            Row {
              TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(
                  backgroundColor = Color.Transparent,
                  contentColor = Color.Gray
                ),
                modifier = Modifier.weight(1f).border(1.dp, Color.Gray)
              ) {
                Text("Cancel")
              }

              TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent),
                modifier = Modifier.weight(1f).border(1.dp, Color.Gray)

              ) {
                Text("OK", color = Color.Blue)
              }
            }
          }
        },
        backgroundColor = Color.White,
        contentColor = Color.Gray
      )
    }
  }
}