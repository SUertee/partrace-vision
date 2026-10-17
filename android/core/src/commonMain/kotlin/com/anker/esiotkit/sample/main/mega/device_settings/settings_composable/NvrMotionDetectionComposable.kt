package com.anker.esiotkit.sample.main.mega.device_settings.settings_composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.enableAlpha
import com.anker.esiotkit.sample.ui.composable.simpleClickable
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.json.jsonObjectToNiceMap
import com.anker.esiotkit.support.json.jsonToObject
import com.anker.esiotkit.support.ktx.getBoolean
import com.anker.esiotkit.support.ktx.getString
import com.anker.esiotkit.support.ktx.toInt
import com.anker.esiotkit.support.log.appLogger
import com.composables.core.Icon
import com.composeunstyled.Text
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * @version 2025/8/20
 */
// 0:HumanRecognize
// 1:Human
// 2:Vehicle
// 3:Pet
// 4:All Other Motion
//{
//   "ai_switch": 1, 总开关
//   "smart_detection": "0,1,2,3",
//   "ai_notification": "0,1,2,3",
//   "devlist": [
//     {
//       "channel": 0,
//       "ai_switch": 1,
//       "ai_notification": "",
//       "ai_use_global": 1,
//       "ai_detection_type": "0,1,2,3"
//     },
//     {
//       "channel": 1,
//       "ai_switch": 1,
//       "ai_notification": "",
//       "ai_use_global": 1,
//       "ai_detection_type": "1,2,3"
//     }
//   ]
// }
@Composable
fun NvrMotionDetectionComposable(
  modifier: Modifier = Modifier,
  megaDevice: MegaDevice,
) {
  var loading by state(false)
  MegaLoading(loading)
  val value by megaDevice.rememberStringProperty(ThingIdentifier.NVR_MOTION_DETECTION)
  val property = remember { megaDevice.getProperty(ThingIdentifier.NVR_MOTION_DETECTION) }
  val json by remember {
    derivedStateOf {
      value.jsonToObject()
    }
  }
  val isOpen by remember { derivedStateOf { json.getBoolean("ai_switch") } }
  val smartDetectionOptions by remember {
    derivedStateOf {
      val smartDetectionList = json.getString("smart_detection").split(",")
      val aiNotificationList = json.getString("ai_notification").split(",")
      List(5) {
        it
      }.map {
        NvrMotionDetectionOption(
          title = when (it) {
            0 -> "Human Recognize"
            1 -> "Human"
            2 -> "Vehicle"
            3 -> "Pet"
            else -> "All Other Motion"
          },
          detectionIndex = it,
          smartDetection = smartDetectionList.contains(it.toString()),
          aiNotification = aiNotificationList.contains(it.toString())
        )
      }
    }
  }
  Column {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text(text = "Motion Detection", style = MaterialTheme.typography.titleLarge)
      Spacer(modifier = Modifier.weight(1f))
      Switch(
        checked = isOpen,
        onCheckedChange = { checked ->
          property?.let {
            loading = true
            val json = json.jsonObjectToNiceMap().toMutableMap()
            json["ai_switch"] = checked.toInt()
            it.set(ThingPayload.from(json)).onSuccess {
              loading = false
              emitToast("Set succeed")
            }.onFailure { code, payload ->
              loading = false
              emitToast("Set property failed: $code, $payload")
              appLogger.w("NvrMotionDetectionComposable/setProperty failed: $code, $payload")
            }
          }
        }
      )
    }
    smartDetectionOptions.forEach { option ->
      appLogger.v { "NvrMotionDetectionComposable/NvrMotionDetectionComposable/option:$option" }
      NvrMotionDetectionItem(
        enabled = isOpen,
        title = option.title,
        smartDetection = option.smartDetection,
        aiNotification = option.aiNotification,
        onSmartDetectionChange = { isChecked ->
          property?.let {
            loading = true
            option.smartDetection = isChecked
            val json = json.jsonObjectToNiceMap().toMutableMap()
            json["smart_detection"] =
              smartDetectionOptions.filter { it.smartDetection }.map { it.detectionIndex }.joinToString(",")
            it.set(ThingPayload.from(json)).onSuccess {
              loading = false
              emitToast("Set succeed")
            }.onFailure { code, payload ->
              loading = false
              option.smartDetection = !isChecked
              emitToast("Set property failed: $code, $payload")
              appLogger.w("NvrMotionDetectionComposable/setProperty failed: $code, $payload")
            }
          }
        },
        onAiNotificationChange = { isChecked ->
          property?.let {
            loading = true
            option.aiNotification = isChecked
            val json = json.jsonObjectToNiceMap().toMutableMap()
            json["ai_notification"] =
              smartDetectionOptions.filter { it.aiNotification }.map { it.detectionIndex }.joinToString(",")
            it.set(ThingPayload.from(json)).onSuccess {
              loading = false
              emitToast("Set succeed")
            }.onFailure { code, payload ->
              loading = false
              option.aiNotification = !isChecked
              emitToast("Set property failed: $code, $payload")
              appLogger.w("NvrMotionDetectionComposable/setProperty failed: $code, $payload")
            }
          }
        }
      )
    }
  }

}

@Composable
private fun NvrMotionDetectionItem(
  enabled: Boolean,
  title: String,
  smartDetection: Boolean,
  aiNotification: Boolean,
  onSmartDetectionChange: (Boolean) -> Unit,
  onAiNotificationChange: (Boolean) -> Unit,
) {
  Card(modifier = Modifier.enableAlpha(enabled).padding(8.dp)) {
    Box {
      if (!enabled) {
        Spacer(
          modifier = Modifier.fillMaxWidth().height(intrinsicSize = IntrinsicSize.Max).height(44.dp)
            .background(color = Color.LightGray.copy(alpha = 0.3f)).zIndex(1f)
            .clickable {}
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth().padding(4.dp).zIndex(0.5f),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.weight(1f))
        Icon(
          imageVector = Icons.Default.Videocam,
          contentDescription = "",
          tint = if (smartDetection) MaterialTheme.colorScheme.primary else Color.Gray,
          modifier = Modifier.size(36.dp).simpleClickable {
            onSmartDetectionChange(!smartDetection)
          },
        )
        Icon(
          imageVector = Icons.Default.Notifications,
          contentDescription = "",
          tint = if (aiNotification) MaterialTheme.colorScheme.primary else Color.Gray,
          modifier = Modifier.size(36.dp).simpleClickable {
            onAiNotificationChange(!aiNotification)
          },
        )
      }
    }
  }
}

@Preview
@Composable
fun NvrMotionDetectionItemPreview() {
  NvrMotionDetectionItem(
    enabled = true,
    title = "NVR Motion Detection",
    smartDetection = true,
    aiNotification = false,
    onSmartDetectionChange = {},
    onAiNotificationChange = {}
  )
}

private data class NvrMotionDetectionOption(
  val title: String,
  val detectionIndex: Int,
  var smartDetection: Boolean,
  var aiNotification: Boolean,
)