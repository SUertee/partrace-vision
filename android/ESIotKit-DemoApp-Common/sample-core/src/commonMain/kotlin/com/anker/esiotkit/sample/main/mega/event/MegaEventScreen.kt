package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.ui.component.DisableOverscrollEffect
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.sample.view.toastNotSupport
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * @version 2024/5/1
 */

data class MegaEventScreen(val filterDeviceSn: String? = null) : Screen {
  @OptIn(ExperimentalMaterialApi::class)
  @Composable
  override fun Content() {
    val navigator = LocalNavigator.currentOrThrow
    // 添加 key 参数，确保 filterDeviceSn 变化时重新创建 ViewModel
    val viewModel = rememberScreenModel(tag = "MegaEventViewModel_$filterDeviceSn") {
      MegaEventViewModel(filterDeviceSn)
    }
    val selectedDateMillis by viewModel.selectedDateMillis.collectAsState()
    val showDialog = viewModel.showDialog

    val state = rememberModalBottomSheetState(ModalBottomSheetValue.Hidden)
    val scope = rememberCoroutineScope()

    val eventDates by viewModel.eventDates.collectAsState()

    LaunchedEffect(state.currentValue) {
      if (state.currentValue == ModalBottomSheetValue.Hidden) {
        viewModel.resetTempSelection()
      }
    }

    val allDeviceList by viewModel.allDeviceList.collectAsState()
    var batchDownloadMode by remember { mutableStateOf(false) }
    val selectedDownloadEvents = remember { mutableStateListOf<MegaEvent>() }
    var batchDeleteMode by remember { mutableStateOf(false) }
    val selectedDeleteEvents = remember { mutableStateListOf<MegaEvent>() }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    ModalBottomSheetLayout(
      sheetState = state,
      sheetContent = {
        FilterSheetContent(
          tempSelectedDeviceList = viewModel.tempSelectedDeviceList,
          allDeviceList = allDeviceList,
          onToggleSelectAllTemp = viewModel::onToggleSelectAllTemp,
          onToggleSelectTemp = viewModel::onToggleSelectTemp,
          onCancel = {
            scope.launch { state.hide() }
            viewModel.resetTempSelection()
          },
          onConfirm = {
            scope.launch { state.hide() }
            viewModel.applyTempSelection()
          },
          selectedCategory = viewModel.tempSelectedCategory,
          onCategoryChange = viewModel::onCategoryChange,
          selectedEventType = viewModel.tempSelectedEventType,
          onEventTypeChange = viewModel::onEventTypeChange,
          selectedDetectionTypes = viewModel.tempSelectedDetectionTypes,
          onDetectionTypeToggle = viewModel::onDetectionTypeToggle
        )
      }
    ) {
      MainContent(
        selectedDeviceList = viewModel.selectedDeviceList,
        megaEventList = viewModel.megaEventList,
        error = viewModel.error,
        isRefreshing = viewModel.loading,
        selectedDateMillis = selectedDateMillis,
        state = state,
        scope = scope,
        batchDownloadMode = batchDownloadMode,
        selectedDownloadEvents = selectedDownloadEvents,
        batchDeleteMode = batchDeleteMode,
        selectedDeleteEvents = selectedDeleteEvents,
        onBatchDownloadModeChange = { batchDownloadMode = it },
        onToggleSelectDownload = { event ->
          if (selectedDownloadEvents.any { e -> e.megaEventId == event.megaEventId }) {
            selectedDownloadEvents.removeAll { e -> e.megaEventId == event.megaEventId }
          } else {
            selectedDownloadEvents.add(event)
          }
        },
        onBatchDownload = {
          appLogger.i("EVT-BATCH start batch download, count=${selectedDownloadEvents.size}, events=${selectedDownloadEvents.map { e -> e.megaEventId }}, queue.tasks=${DownloadQueueManager.tasks.value.size}, queue.isRunning=${DownloadQueueManager.isRunning}")
          val eventsToDownload = selectedDownloadEvents.toList()
          batchDownloadMode = false
          selectedDownloadEvents.clear()
          DownloadQueueManager.addToQueue(eventsToDownload)
          emitToast("Added ${eventsToDownload.size} events to download queue")
        },
        onNavigateToDownloadQueue = {
          navigator.push(BatchDownloadScreen())
        },
        onBatchDownloadCancel = {
          appLogger.i("EVT-BATCH cancelled")
          batchDownloadMode = false
          selectedDownloadEvents.clear()
        },
        onBatchDeleteModeChange = { batchDeleteMode = it },
        onToggleSelectDelete = { event ->
          if (selectedDeleteEvents.any { e -> e.megaEventId == event.megaEventId }) {
            selectedDeleteEvents.removeAll { e -> e.megaEventId == event.megaEventId }
          } else {
            selectedDeleteEvents.add(event)
          }
        },
        onBatchDelete = {
          appLogger.i("EVT-DELETE start batch delete, count=${selectedDeleteEvents.size}, events=${selectedDeleteEvents.map { e -> e.megaEventId }}")
          showBatchDeleteDialog = true
        },
        onBatchDeleteConfirm = {
          val eventsToDelete = selectedDeleteEvents.toList()
          batchDeleteMode = false
          selectedDeleteEvents.clear()
          showBatchDeleteDialog = false
          viewModel.deleteEvents(eventsToDelete)
        },
        onBatchDeleteCancel = {
          appLogger.i("EVT-DELETE cancelled")
          batchDeleteMode = false
          selectedDeleteEvents.clear()
        },
        showBatchDeleteDialog = showBatchDeleteDialog,
        onDismissBatchDeleteDialog = { showBatchDeleteDialog = false },
        onDateChange = { newDateMillis ->
          viewModel.changeDate(newDateMillis.toString())
        },
        onShowDialog = { viewModel.showDialog(true) },
        onToggleSelect = { device ->
          viewModel.onToggleSelect(device)
          viewModel.resetTempSelection()
        },
        onEventClick = { event ->
          navigator.push(EventPlayScreen(event))
        },
        onDeleteEvent = { event ->
          viewModel.deleteEvent(event)
        },
        onRefresh = viewModel::getEventFlow
      )
    }
    if (showDialog) {
      DatePickerModal(
        selectedDateMillis = selectedDateMillis,
        onDismiss = { viewModel.showDialog(false) },
        onDateSelected = { date ->
          date?.let {
            viewModel.changeDate(it.toString())
          }
          viewModel.showDialog(false)
        },
        eventDates = eventDates.map {
          Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date
        }
      )
    }
  }

  @OptIn(ExperimentalMaterialApi::class, ExperimentalLayoutApi::class)
  @Composable
  fun MainContent(
    selectedDeviceList: List<MegaDevice>,
    megaEventList: List<MegaEvent>,
    error: String? = null,
    selectedDateMillis: Long,
    state: ModalBottomSheetState,
    scope: CoroutineScope,
    batchDownloadMode: Boolean = false,
    selectedDownloadEvents: List<MegaEvent> = emptyList(),
    batchDeleteMode: Boolean = false,
    selectedDeleteEvents: List<MegaEvent> = emptyList(),
    onBatchDownloadModeChange: (Boolean) -> Unit = {},
    onToggleSelectDownload: (MegaEvent) -> Unit = {},
    onBatchDownload: () -> Unit = {},
    onBatchDownloadCancel: () -> Unit = {},
    onNavigateToDownloadQueue: () -> Unit = {},
    onBatchDeleteModeChange: (Boolean) -> Unit = {},
    onToggleSelectDelete: (MegaEvent) -> Unit = {},
    onBatchDelete: () -> Unit = {},
    onBatchDeleteConfirm: () -> Unit = {},
    onBatchDeleteCancel: () -> Unit = {},
    showBatchDeleteDialog: Boolean = false,
    onDismissBatchDeleteDialog: () -> Unit = {},
    onDateChange: (Long) -> Unit,
    onShowDialog: () -> Unit,
    onToggleSelect: (MegaDevice) -> Unit,
    onEventClick: (MegaEvent) -> Unit,
    onDeleteEvent: (MegaEvent) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
  ) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    if (showBatchDeleteDialog) {
      ConfirmDialog(
        title = "Delete Events",
        description = "Are you sure you want to delete ${selectedDeleteEvents.size} event(s)?",
        onConfirm = onBatchDeleteConfirm,
        onCancel = onDismissBatchDeleteDialog
      )
    }

    Column(
      modifier = Modifier.fillMaxSize().navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(
        modifier = Modifier.fillMaxWidth()
          .background(Color.White)
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        DatePickerButton(
          selectedDate = selectedDateMillis,
          onDateChange = onDateChange,
          onClick = onShowDialog
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
          if (batchDownloadMode) {
            IconButton(
              onClick = onBatchDownload,
              enabled = selectedDownloadEvents.isNotEmpty()
            ) {
              Icon(Icons.Default.Download, contentDescription = "Download",
                tint = if (selectedDownloadEvents.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Gray)
            }
            IconButton(onClick = onBatchDownloadCancel) {
              Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Gray)
            }
          } else if (batchDeleteMode) {
            IconButton(
              onClick = onBatchDelete,
              enabled = selectedDeleteEvents.isNotEmpty()
            ) {
              Icon(Icons.Default.Delete, contentDescription = "Delete",
                tint = if (selectedDeleteEvents.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Gray)
            }
            IconButton(onClick = onBatchDeleteCancel) {
              Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Gray)
            }
          } else {
            IconButton(
              onClick = { showOverflowMenu = true }
            ) {
              Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(
              expanded = showOverflowMenu,
              onDismissRequest = { showOverflowMenu = false }
            ) {
              DropdownMenuItem(
                text = { Text("Filter") },
                onClick = {
                  showOverflowMenu = false
                  appLogger.i("EVT-MENU filter selected")
                  scope.launch { state.show() }
                }
              )
              DropdownMenuItem(
                text = { Text("Batch Download") },
                onClick = {
                  showOverflowMenu = false
                  appLogger.i("EVT-MENU batch download selected")
                  onBatchDownloadModeChange(true)
                }
              )
              DropdownMenuItem(
                text = { Text("Download Queue") },
                onClick = {
                  showOverflowMenu = false
                  appLogger.i("EVT-MENU download queue selected")
                  onNavigateToDownloadQueue()
                }
              )
              DropdownMenuItem(
                text = { Text("Edit / Delete") },
                onClick = {
                  showOverflowMenu = false
                  appLogger.i("EVT-MENU edit/delete selected")
                  onBatchDeleteModeChange(true)
                }
              )
            }
          }
        }
      }
      FlowRow(
        modifier = Modifier.sizeIn(maxHeight = 100.dp).verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        selectedDeviceList.forEach { device ->
          DeviceButton(
            text = device.iotDevice.deviceName,
            onClose = { onToggleSelect(device) }
          )
        }
      }
      val refreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = onRefresh
      )
      when {
        error != null -> {
          EventListPlaceholder(
            message = error.orEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
          )
        }
        !isRefreshing && megaEventList.isEmpty() -> {
          EventListPlaceholder(
            message = "当天无事件",
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
          )
        }
        else -> {
          EventList(
            refreshing = isRefreshing,
            refreshState = refreshState,
            modifier = Modifier.weight(1f),
            eventList = megaEventList,
            onClick = onEventClick,
            onDeleteEvent = onDeleteEvent,
            batchDownloadMode = batchDownloadMode,
            selectedEvents = selectedDownloadEvents,
            onToggleSelect = onToggleSelectDownload,
            batchDeleteMode = batchDeleteMode,
            selectedDeleteEvents = selectedDeleteEvents,
            onToggleSelectDelete = onToggleSelectDelete,
          )
        }
      }
    }
  }

  @Composable
  private fun EventListPlaceholder(
    message: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
  ) {
    Column(
      modifier = modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(text = message, color = Color.Gray)
      TextButton(onClick = onRefresh) {
        Text("刷新")
      }
    }
  }

  @OptIn(ExperimentalLayoutApi::class)
  @Composable
  fun FilterSheetContent(
    tempSelectedDeviceList: List<MegaDevice>,
    allDeviceList: List<MegaDevice>,
    onToggleSelectAllTemp: () -> Unit,
    onToggleSelectTemp: (MegaDevice) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    selectedCategory: String = "All",
    onCategoryChange: (String) -> Unit = {},
    selectedEventType: String = "All",
    onEventTypeChange: (String) -> Unit = {},
    selectedDetectionTypes: List<String> = emptyList(),
    onDetectionTypeToggle: (String) -> Unit = {}
  ) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
      // Header
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        TextButton(onClick = onCancel) {
          Text("Cancel", style = TextStyle(color = Color.Gray))
        }
        Text("Smart Filter", style = TextStyle(fontWeight = FontWeight.Bold))
        TextButton(
          onClick = onConfirm,
          content = {
            Text("Confirm", style = TextStyle(color = MaterialTheme.colorScheme.primary))
          }
        )
      }

      // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
      DisableOverscrollEffect {
        LazyColumn(
          contentPadding = PaddingValues(bottom = navBarPadding),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Category
          item {
            Text(
              "Category",
              style = TextStyle(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }
          item {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              listOf("All", "Security", "Care").forEach { category ->
                SelectedButton(
                  text = category,
                  selected = selectedCategory == category,
                  onClick = { onCategoryChange(category) }
                )
              }
            }
          }

          // Event Type
          item {
            Text(
              "Event Type",
              style = TextStyle(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }
          item {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              listOf("All", "Video", "Alert", "Alarm").forEach { eventType ->
                SelectedButton(
                  text = eventType,
                  selected = selectedEventType == eventType,
                  onClick = { onEventTypeChange(eventType) }
                )
              }
            }
          }

          // Detection Type
          item {
            Text(
              "Detection Type",
              style = TextStyle(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }
          item {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val detectionTypes = listOf("All", "Human", "Pet", "Vehicle", "Package", "Doorbell")
              detectionTypes.forEach { detectionType ->
                SelectedButton(
                  text = detectionType,
                  selected = if (detectionType == "All") selectedDetectionTypes.isEmpty() else selectedDetectionTypes.contains(detectionType),
                  onClick = { onDetectionTypeToggle(detectionType) }
                )
              }
            }
          }

          // Devices
          item {
            Text(
              "Device",
              style = TextStyle(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }
          item {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              SelectedButton(
                text = "All",
                selected = tempSelectedDeviceList.isEmpty(),
                onClick = onToggleSelectAllTemp
              )
              allDeviceList.forEach { device ->
                SelectedButton(
                  text = device.iotDevice.deviceName,
                  selected = tempSelectedDeviceList.contains(device),
                  onClick = { onToggleSelectTemp(device) }
                )
              }
            }
          }
        }
      }
    }
  }

  @Composable
  private fun SelectedButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
  ) {
    appLogger.i("MegaEventScreen/SelectedButton/$text/$selected")
    Button(
      onClick = onClick, modifier = Modifier,
      colors = ButtonDefaults.buttonColors(
        containerColor = if (selected) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.primary.copy(0.5f)
        }
      )
    ) {
      Text(text = text)
    }
  }

  @Composable
  fun DeviceButton(
    text: String,
    onClick: () -> Unit = {},
    onClose: () -> Unit
  ) {
    OutlinedButton(
      onClick = onClick,
      colors = ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = MaterialTheme.colorScheme.primary
      ),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(text = text)
        IconButton(
          onClick = onClose,
          modifier = Modifier.size(24.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = Color.Gray
          )
        }
      }
    }
  }
}
