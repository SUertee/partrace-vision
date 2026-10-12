package com.anker.esiotkit.sample.main.mega.device_settings.general

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.support.TimeZoneResolver
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.OptionRowItem
import com.anker.esiotkit.sample.view.SearchBar
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_timezone_title

/**
 * @version 2024/10/25
 */

class TimeZoneScreen(@Transient private val viewModel: DeviceGeneralViewModel) :
  BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_timezone_title

  private var isSaveEnabled by mutableStateOf(false)
  private var selectTimeZone by mutableStateOf("")
  private var searchQuery by mutableStateOf("")
  private var loading by mutableStateOf(false)
  private var preselected by mutableStateOf(false)

  @Composable
  override fun RowScope.TitleRightIcon() {
    val scope = rememberCoroutineScope()
    TextButton(
      onClick = {
        val timezoneId = selectTimeZone.substringAfter(") ")
        appLogger.i("TZ|save/start: id=$timezoneId, sn=${viewModel.megaDevice.deviceSn}")
        scope.launch {
          loading = true
          viewModel.setTimezoneAwait(timezoneId)
          loading = false
          appLogger.i("TZ|save/done: back to TimeSettings")
          super.onBackClick()
        }
      },
      enabled = isSaveEnabled
    ) {
      Text(text = "Save", color = if (isSaveEnabled) MaterialTheme.colorScheme.primary else Color.Gray)
    }
    MegaLoading(loading)
  }

  @Composable
  override fun Content() = BaseColumn {
    val listState = rememberLazyListState()
    val currentTimezone = viewModel.timezone
    val timeList = viewModel.timeZoneList.toList()

    LaunchedEffect(currentTimezone, timeList.size) {
      appLogger.i("TZ|preselect/enter: raw=$currentTimezone, listSize=${timeList.size}, preselected=$preselected")
      if (timeList.isEmpty()) {
        appLogger.i("TZ|preselect/skip: list empty")
        return@LaunchedEffect
      }
      if (preselected) {
        appLogger.i("TZ|preselect/skip: already preselected=$selectTimeZone")
        return@LaunchedEffect
      }
      val targetId = TimeZoneResolver.resolveTimezoneId(currentTimezone)
      appLogger.i("TZ|preselect/resolved: raw=$currentTimezone -> targetId=$targetId")
      val matched = timeList.find { it.substringAfter(") ") == targetId }
      appLogger.i("TZ|preselect/result: matched=$matched, index=${if (matched != null) timeList.indexOf(matched) else -1}")
      if (matched != null) {
        preselected = true
        selectTimeZone = matched
        listState.scrollToItem(timeList.indexOf(matched))
      }
    }

    val filteredTimeZones = viewModel.timeZoneList.filter { it.contains(searchQuery, ignoreCase = true) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      SearchBar("", onSearch = { query -> searchQuery = query })
      LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().background(Color.White)) {
        items(filteredTimeZones) { timeZone ->
          timeZoneCard(timeZoneItem = timeZone, isSelected = selectTimeZone == timeZone, onClick = {
            if (selectTimeZone != timeZone) { selectTimeZone = timeZone; isSaveEnabled = true }
          })
        }
      }
    }
  }

  @Composable
  fun timeZoneCard(timeZoneItem: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OptionRowItem(title = timeZoneItem, isSelected = isSelected, onClick = { onClick() })
    HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
  }
}