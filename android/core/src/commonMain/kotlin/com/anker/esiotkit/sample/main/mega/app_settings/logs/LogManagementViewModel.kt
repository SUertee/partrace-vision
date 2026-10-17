package com.anker.esiotkit.sample.main.mega.app_settings.logs

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.sample.base.BaseViewModel
import com.anker.esiotkit.sample.support.log.LogFileItem
import com.anker.esiotkit.sample.support.log.SampleLogManager

/**
 * @version 2025/2/20
 */
class LogManagementViewModel : BaseViewModel() {
  val selectedLogList = mutableStateListOf<String>()
  val editMode by derivedStateOf {
    selectedLogList.isNotEmpty()
  }
  val logFileList = mutableStateListOf<LogFileItem>()
  var confirmDelete by mutableStateOf(false)

  init {
    logFileList.addAll(SampleLogManager.getLogFileList())
  }

  fun cancelEdit() {
    selectedLogList.clear()
    confirmDelete = false
  }

  fun toggleSelected(path: String) {
    if (selectedLogList.contains(path)) {
      selectedLogList.remove(path)
    } else {
      selectedLogList.add(path)
    }
  }
}