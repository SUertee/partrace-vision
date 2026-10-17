package com.anker.esiotkit.sample.main.mega.app_settings.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import com.anker.esiotkit.sample.support.log.LogFileItem
import com.anker.esiotkit.sample.support.log.SampleLogManager
import com.anker.esiotkit.sample.support.platform.shareFile
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.BackHandler
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.sample.view.toastNotSupport
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_debug_log_management_title
import org.jetbrains.compose.resources.StringResource

class LogManagementScreen : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_debug_log_management_title

  @Composable
  override fun Content() {
    val viewModel = koinScreenModel<LogManagementViewModel>()
    val scope = rememberCoroutineScope()
    var loading by state(false)
    DisposableEffect(Unit) {
      onDispose {
        appLogger.i("LogManagementScreen/Content/onDispose")
        viewModel.selectedLogList.clear()
      }
    }
    BaseColumn {
      LogList(
        modifier = Modifier.padding().fillMaxSize(),
        logFileList = viewModel.logFileList
      )
    }
    if (viewModel.confirmDelete) {
      ConfirmDialog(
        title = "Delete Log",
        description = "Are you sure to delete the selected logs?",
        onConfirm = {
          scope.launch(Dispatchers.IO) {
            viewModel.confirmDelete = false
            loading = true
            viewModel.selectedLogList.forEach {
              SampleLogManager.deleteFile(it)
            }
            viewModel.logFileList.removeAll { item -> item.filePath in viewModel.selectedLogList }
            loading = false
            viewModel.cancelEdit()
          }
        },
        onCancel = {
          viewModel.cancelEdit()
        }
      )
    }
    MegaLoading(loading)
    BackHandler(
      enabled = viewModel.selectedLogList.isNotEmpty()
    ) {
      viewModel.selectedLogList.clear()
    }
  }

  @Composable
  override fun RowScope.TitleRightIcon() {
    val viewModel = koinScreenModel<LogManagementViewModel>()
    IconButton(
      onClick = {
        if (viewModel.logFileList.size >= 2) {
          viewModel.logFileList.takeLast(viewModel.logFileList.size - 1).let {
            viewModel.selectedLogList.addAll(it.map { item -> item.filePath })
          }
        }
        viewModel.confirmDelete = true
      },
      content = {
        Icon(
          imageVector = Icons.Default.DeleteSweep, contentDescription = "Delete All",
          tint = Color.Red
        )
      }
    )
    AnimatedVisibility(viewModel.editMode) {
      IconButton(
        onClick = {
          viewModel.confirmDelete = true
        },
        content = {
          Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete")
        }
      )
    }
  }

  @Composable
  fun LogList(modifier: Modifier, logFileList: List<LogFileItem>) {
    LazyColumn(modifier) {
      itemsIndexed(logFileList) { index, log ->
        LogItem(index, log)
        Divider()
      }
    }
  }

  @Composable
  fun LogItem(index: Int, log: LogFileItem) {
    val viewModel = koinScreenModel<LogManagementViewModel>()
    val color by derivedStateOf {
      if (viewModel.selectedLogList.contains(log.filePath)) {
        Color.LightGray
      } else {
        Color.White
      }
    }
    val isFirst = remember { index == 0 }
    val scope = rememberCoroutineScope()
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(color = color)
        .combinedClickable(
          onClick = {
            if (viewModel.editMode && !isFirst) {
              viewModel.toggleSelected(log.filePath)
            }
          },
          onLongClick = {
            if (!isFirst) {
              if (viewModel.editMode) {
                viewModel.selectedLogList.clear()
              } else {
                viewModel.toggleSelected(log.filePath)
              }
            }
          }
        )
        .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(text = log.fileName)
      }
      Text(text = log.size)
      Row {
        IconButton(onClick = {
          viewModel.selectedLogList.add(log.filePath)
          viewModel.confirmDelete = true
        }, enabled = !isFirst) {
          Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete")
        }
        IconButton(onClick = {
          scope.launch {
            shareFile(log.filePath)
            if (isFirst) {
              SampleLogManager.restartWriteLog()
              delay(1000)
              viewModel.logFileList.clear()
              viewModel.logFileList.addAll(SampleLogManager.getLogFileList())
            }
          }
        }) {
          Icon(imageVector = Icons.Outlined.Share, contentDescription = "Share")
        }
        IconButton(onClick = {
          toastNotSupport()
        }) {
          Icon(imageVector = Icons.Outlined.Upload, contentDescription = "Upload")
        }
      }
    }
  }

}