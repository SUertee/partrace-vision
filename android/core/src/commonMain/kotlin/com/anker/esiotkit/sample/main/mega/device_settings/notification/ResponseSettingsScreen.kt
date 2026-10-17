package com.anker.esiotkit.sample.main.mega.device_settings.notification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.sample.main.mega.device_settings.NotificationSettingsViewModel
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.PropertySlider
import com.anker.esiotkit.sample.view.emitToast
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_notification_response_title

/**
 * Voice response data class
 */
@Serializable
data class VoiceResponse(
  val id: String,
  val message: String,
  val audioUrl: String = ""
)

/**
 * Response Settings Screen
 * Configure auto-response and voice messages
 */
class ResponseSettingsScreen(
  private val viewModel: NotificationSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_notification_response_title

  @Composable
  override fun Content() = BaseColumn {
    val megaDevice = remember { viewModel.megaDevice }

    var voiceResponses by remember {
      mutableStateOf(listOf(
        VoiceResponse("1", "I'll be right there"),
        VoiceResponse("2", "Leave package at door"),
        VoiceResponse("3", "Not home, please come back later")
      ))
    }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Auto-Response Enable
      val autoResponseProp = remember { megaDevice.getProperty("auto_response_enable") }
      val autoResponseEnabled by megaDevice.rememberBooleanProperty("auto_response_enable")

      CommonButton(
        text = "Auto-Response: ${if (autoResponseEnabled) "ON" else "OFF"}",
        onClick = {
          if (autoResponseProp == null) {
            emitToast("Auto-response not supported by this device")
          } else {
            viewModel.setAutoResponseEnable(!autoResponseEnabled)
          }
        }
      )

      // Auto-Response Delay (conditional)
      if (autoResponseEnabled) {
        val delayProp = remember { megaDevice.getProperty("auto_response_delay") }
        if (delayProp != null) {
          PropertySlider(
            megaDevice = megaDevice,
            identifier = "auto_response_delay",
            title = "Response Delay (seconds)"
          )
        }
      }

      // Voice Response Management
      Text(
        text = "Voice Responses",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp)
      )

      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        items(voiceResponses) { response ->
          VoiceResponseItem(
            response = response,
            onPlay = { /* Play audio preview */ },
            onDelete = {
              voiceResponses = voiceResponses.filter { it.id != response.id }
              viewModel.updateVoiceResponses(voiceResponses)
            }
          )
        }
      }

      CommonButton(
        text = "Add Voice Response",
        onClick = { showAddDialog = true }
      )
    }

    if (showAddDialog) {
      VoiceResponseDialog(
        onDismiss = { showAddDialog = false },
        onSave = { message ->
          val newId = (voiceResponses.size + 1).toString()
          voiceResponses = voiceResponses + VoiceResponse(newId, message)
          viewModel.updateVoiceResponses(voiceResponses)
          showAddDialog = false
        }
      )
    }

    MegaLoading(viewModel.loading)
  }

  @Composable
  fun VoiceResponseItem(
    response: VoiceResponse,
    onPlay: () -> Unit,
    onDelete: () -> Unit
  ) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = response.message,
          modifier = Modifier.weight(1f)
        )
        Row {
          IconButton(onClick = onPlay) {
            Icon(Icons.Default.PlayArrow, "Play")
          }
          IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete")
          }
        }
      }
    }
  }

  @Composable
  fun VoiceResponseDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
  ) {
    var message by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Add Voice Response") },
      text = {
        OutlinedTextField(
          value = message,
          onValueChange = { message = it },
          label = { Text("Response Message") },
          placeholder = { Text("Enter message...") }
        )
      },
      confirmButton = {
        TextButton(onClick = { onSave(message) }) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) {
          Text("Cancel")
        }
      }
    )
  }
}
