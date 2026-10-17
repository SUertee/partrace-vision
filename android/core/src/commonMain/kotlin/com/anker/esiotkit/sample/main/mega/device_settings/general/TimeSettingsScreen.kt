package com.anker.esiotkit.sample.main.mega.device_settings.general

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.iotmodel.default_service.SystemTimeFormat
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.OptionRowItem
import com.anker.esiotkit.sample.support.TimeZoneResolver
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.sample.support.log.logW
import com.anker.esiotkit.support.utils.DateUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Transient
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_time_title

/**
 * @version 2024/10/25
 */

class TimeSettingsScreen(
  @Transient private val viewModel: DeviceGeneralViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_time_title

  private var loading by mutableStateOf(false)

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val state = rememberModalBottomSheetState(ModalBottomSheetValue.Hidden)
    val scope = rememberCoroutineScope()

    ModalBottomSheetLayout(
      sheetState = state,
      sheetContent = {
        FilterSheetContent(
          timeFormat = viewModel.timeFormatValue,
          onCancel = {
            scope.launch { state.hide() }
          },
          onConfirm = { format ->
            scope.launch {
              loading = true
              state.hide()
              appLogger.i("TZ|TimeSettings/setFormat/loading: format=$format, sn=${viewModel.megaDevice.deviceSn}")
              viewModel.setTimeFormatAwait(format)
              loading = false
            }
          }
        )
      }
    ) {
      MainContent(
        timeFormat = viewModel.timeFormatValue,
        timeZone = viewModel.timezone.also { appLogger.i("TZ|TimeSettings/display: raw=$it, timeFormat=${viewModel.timeFormatValue}") },
        state = state,
        scope = scope,
        toTimeZoneScreen = {
          navigator.push(
            TimeZoneScreen(viewModel = viewModel)
          )
        }
      )
    }
    MegaLoading(loading)
  }

  private fun getTimeZone(timeZone: String, deviceSn: String): TimeZone {
    val resolvedId = TimeZoneResolver.resolveTimezoneId(timeZone)
    return try {
      TimeZone.of(resolvedId).also {
        appLogger.i("TimeSettingsScreen/getTimeZone/success: raw=$timeZone, resolvedId=$resolvedId, deviceSn=$deviceSn")
      }
    } catch (e: Exception) {
      logW("TimeSettingsScreen/getTimeZone/fallback: reason=invalid_timezone_id, raw=$timeZone, resolvedId=$resolvedId, fallbackTo=UTC, deviceSn=$deviceSn", e)
      TimeZone.UTC
    }
  }

  @Composable
  fun MainContent(
    timeFormat: SystemTimeFormat,
    timeZone: String,
    state: ModalBottomSheetState,
    scope: CoroutineScope,
    toTimeZoneScreen: () -> Unit = {}
  ) {
    val deviceSn = viewModel.megaDevice.deviceSn
    val resolvedTimeZone = remember(timeZone) { getTimeZone(timeZone, deviceSn) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      CommonRow(
        title = "Current Time",
        rightContent = {
          val currentTime = remember(resolvedTimeZone, timeFormat) {
            if (timeFormat == SystemTimeFormat.TIME_SYSTEM_12) {
              DateUtil.getCurrentHourMin12(resolvedTimeZone)
            } else {
              DateUtil.getCurrentHourMin24(resolvedTimeZone)
            }
          }
          Text(text = currentTime, color = Color.Gray)
        }
      )
      CommonRow(
        title = "Time Format",
        rightContent = {
          Text(
            text = if (timeFormat == SystemTimeFormat.TIME_SYSTEM_12) "12h" else "24h",
            color = MaterialTheme.colorScheme.primary
          )
          Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
        onClick = { scope.launch { state.show() } }
      )
      CommonRow(
        title = "Time Zone",
        rightContent = {
          val timeZoneText = remember(resolvedTimeZone) {
            Clock.System.now().offsetIn(resolvedTimeZone).totalSeconds.let { offset ->
              val sign = if (offset >= 0) "+" else ""
              val hour = offset / 3600
              val minute = (offset % 3600) / 60
              "GMT $sign${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
            }
          }
          Text(text = timeZoneText, color = MaterialTheme.colorScheme.primary)
          Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
        onClick = { toTimeZoneScreen() }
      )
    }
  }

  @Composable
  fun FilterSheetContent(
    timeFormat: SystemTimeFormat,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
  ) {
    var tempFormat by remember { mutableStateOf(if (timeFormat == SystemTimeFormat.TIME_SYSTEM_12) "0" else "1") }
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        .background(Color.White, RoundedCornerShape(16.dp))
    ) {
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        TextButton(onClick = onCancel) {
          Text("Cancel", style = TextStyle(color = Color.Gray))
        }
        Text("Select the time format", style = TextStyle(fontWeight = FontWeight.Bold))
        TextButton(
          onClick = { onConfirm(tempFormat) },
          content = {
            Text("Ok", style = TextStyle(color = MaterialTheme.colorScheme.primary))
          }
        )
      }
      OptionRowItem(
        title = "12h",
        isSelected = tempFormat == "0",
        onClick = { tempFormat = "0" }
      )
      OptionRowItem(
        title = "24h",
        isSelected = tempFormat == "1",
        onClick = { tempFormat = "1" }
      )
    }
  }

}