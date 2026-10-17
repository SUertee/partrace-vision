package com.anker.esiotkit.sample.main.preview

import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.anker.esiotkit.sample.main.mega.event.DatePickerModal
import com.anker.esiotkit.sample.main.mega.event.MegaEventScreen
import kotlin.time.Clock
import kotlinx.coroutines.launch

/** Official event UI with local state only; no device, event, or download services. */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun UiPreviewEvents() {
  val eventScreen = remember { MegaEventScreen() }
  val sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Hidden)
  val scope = rememberCoroutineScope()
  var selectedDateMillis by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
  var showDatePicker by remember { mutableStateOf(false) }
  var batchDownloadMode by remember { mutableStateOf(false) }
  var batchDeleteMode by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf<String?>(null) }

  var selectedCategory by remember { mutableStateOf("All") }
  var selectedEventType by remember { mutableStateOf("All") }
  var selectedDetectionTypes by remember { mutableStateOf(emptyList<String>()) }
  var tempCategory by remember { mutableStateOf("All") }
  var tempEventType by remember { mutableStateOf("All") }
  var tempDetectionTypes by remember { mutableStateOf(emptyList<String>()) }

  LaunchedEffect(sheetState.currentValue) {
    if (sheetState.currentValue == ModalBottomSheetValue.Hidden) {
      tempCategory = selectedCategory
      tempEventType = selectedEventType
      tempDetectionTypes = selectedDetectionTypes
    }
  }

  ModalBottomSheetLayout(
    sheetState = sheetState,
    sheetContent = {
      eventScreen.FilterSheetContent(
        tempSelectedDeviceList = emptyList(),
        allDeviceList = emptyList(),
        onToggleSelectAllTemp = {},
        onToggleSelectTemp = {},
        onCancel = { scope.launch { sheetState.hide() } },
        onConfirm = {
          selectedCategory = tempCategory
          selectedEventType = tempEventType
          selectedDetectionTypes = tempDetectionTypes
          scope.launch { sheetState.hide() }
        },
        selectedCategory = tempCategory,
        onCategoryChange = { tempCategory = it },
        selectedEventType = tempEventType,
        onEventTypeChange = { tempEventType = it },
        selectedDetectionTypes = tempDetectionTypes,
        onDetectionTypeToggle = { type ->
          tempDetectionTypes = when {
            type == "All" -> emptyList()
            type in tempDetectionTypes -> tempDetectionTypes - type
            else -> tempDetectionTypes + type
          }
        },
      )
    },
  ) {
    eventScreen.MainContent(
      selectedDeviceList = emptyList(),
      megaEventList = emptyList(),
      selectedDateMillis = selectedDateMillis,
      state = sheetState,
      scope = scope,
      batchDownloadMode = batchDownloadMode,
      batchDeleteMode = batchDeleteMode,
      onBatchDownloadModeChange = {
        batchDownloadMode = it
        batchDeleteMode = false
      },
      onBatchDownloadCancel = { batchDownloadMode = false },
      onNavigateToDownloadQueue = { notice = "本地界面预览，没有事件或下载任务。" },
      onBatchDeleteModeChange = {
        batchDeleteMode = it
        batchDownloadMode = false
      },
      onBatchDeleteCancel = { batchDeleteMode = false },
      onDateChange = { selectedDateMillis = it },
      onShowDialog = { showDatePicker = true },
      onToggleSelect = {},
      onEventClick = {},
      onDeleteEvent = {},
      isRefreshing = false,
      onRefresh = { notice = "本地界面预览，尚未登录或连接设备。" },
    )
  }

  if (showDatePicker) {
    DatePickerModal(
      selectedDateMillis = selectedDateMillis,
      onDismiss = { showDatePicker = false },
      onDateSelected = { date ->
        date?.let { selectedDateMillis = it }
        showDatePicker = false
      },
      eventDates = emptyList(),
    )
  }

  notice?.let { message ->
    AlertDialog(
      onDismissRequest = { notice = null },
      title = { Text("界面预览") },
      text = { Text(message) },
      confirmButton = {
        TextButton(onClick = { notice = null }) { Text("知道了") }
      },
    )
  }
}
