package com.anker.esiotkit.sample.main.mega.device_settings.general

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.ui.component.ButtonDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.round
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_local_storage_title

/**
 * @version 2024/10/28
 */

class LocalStorageScreen(@Transient private val viewModel: DeviceGeneralViewModel) :
  BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_local_storage_title

  @Composable
  override fun Content() = BaseColumn {
    val storageInfo = viewModel.storageInfo
    val usedStorage = storageInfo?.let { it.totalSpace - it.freeSpace } ?: 100
    val totalStorage = storageInfo?.totalSpace ?: (7 * 1024)
    val usedStorageText = "$usedStorage MB"
    val totalStorageText = remember { formatTotalStorage(totalStorage.toDouble()) }
    val usedPercentage = if (totalStorage > 0) (usedStorage.toFloat() / totalStorage).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.padding(vertical = 16.dp)) {
      StorageInfoDisplay(
        usedStorageText = usedStorageText,
        totalStorageText = totalStorageText,
        usedPercentage = usedPercentage
      )
      Spacer(modifier = Modifier.weight(1f))
      StorageActions(
        formatStorage = { viewModel.formatStorage() },
        clearStorage = { viewModel.clearStorage() }
      )
    }
    MegaLoading(viewModel.loading)
  }

  fun formatTotalStorage(totalStorage: Double): String {
    val gigabytes = totalStorage / 1024.0
    val roundedGigabytes = round(gigabytes * 10) / 10.0
    return "$roundedGigabytes GB"
  }

  @Composable
  fun StorageInfoDisplay(
    usedStorageText: String,
    totalStorageText: String,
    usedPercentage: Float
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
        .padding(16.dp)
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(
            text = "Used $usedStorageText",
            fontSize = 16.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
          )
          Text(
            text = "Total: $totalStorageText",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 8.dp)
          )
        }
        LinearProgressIndicator(
          progress = usedPercentage,
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
          color = Color(0xFFFFA726),
          backgroundColor = Color.LightGray
        )
        Row {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(16.dp)
                .background(Color(0xFFFFA726), shape = RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Video", fontSize = 14.sp, color = Color.Gray)
          }
          Spacer(modifier = Modifier.width(32.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(16.dp)
                .background(Color.LightGray, shape = RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Available", fontSize = 14.sp, color = Color.Gray)
          }
        }
      }
    }
  }

  @Composable
  fun StorageActions(
    formatStorage: () -> Unit,
    clearStorage: () -> Unit
  ) {
    var showFormatDialog by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    if (showFormatDialog) {
      ButtonDialog(
        dialogText = "Formatting local memory will remove all of the data stored in it, and it may take a long time. Are you sure you want to continue?",
        onConfirm = {
          formatStorage()
          showFormatDialog = false
        },
        onCancel = { showFormatDialog = false },
        buttonText = "Confirm"
      )
    }
    if (showClearDialog) {
      ButtonDialog(
        dialogText = "Do you want to delete all of the video clips stored on this device?",
        onConfirm = {
          clearStorage()
          showClearDialog = false
        },
        onCancel = {
          showClearDialog = false
        },
        buttonText = "Confirm"
      )
    }
    Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      OutlinedButton(
        onClick = { showFormatDialog = true },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Format", color = MaterialTheme.colorScheme.primary)
      }
      Button(
        onClick = { showClearDialog = true },
        modifier = Modifier.fillMaxWidth()
      ) {
        Text("Clear", color = Color.White)
      }
    }
  }
}
