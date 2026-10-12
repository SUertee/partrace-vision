package com.anker.esiotkit.sample.main.mega.device_settings.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.domain.NotificationMode
import com.anker.esiotkit.domain_thing.api.domain.NotificationModeOption
import com.anker.esiotkit.sample.main.mega.device_settings.NotificationSettingsViewModel
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRightOptionRow
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.CommonSwitchRow
import com.anker.esiotkit.support.log.appLogger
import kotlin.jvm.Transient
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_notification_title

/**
 * @version 2024/10/25
 */

class NotificationScreen(
  // @Transient 仅对 Android/JVM 序列化生效；iOS 侧由下方判空 + pop 兜底
  @Transient private val viewModel: NotificationSettingsViewModel?
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_notification_title

  @Composable
  override fun Content() = BaseColumn(scrollState = rememberScrollState()) {
    val navigator = LocalNavigator.currentOrThrow
    val vm = viewModel
    if (vm == null) {
      LaunchedEffect(Unit) { navigator.pop() }
      return@BaseColumn
    }

    val motionAlertTones = listOf(
      "Default", "Silent", "Bell", "Change", "Ding", "Notify", "Security", "Success", "Type"
    )
    val ringAlertTones = listOf(
      "Default", "Silent", "Beacon", "Chord", "Christmas", "Circuit", "Clock", "Ding", "Hillside", "Presto"
    )

    PropertyThingContainer(
      megaDevice = vm.megaDevice,
      identifier = ThingIdentifier.NOTIFICATION_TYPE
    ) {
      NotificationSettings(
        selectedMode = vm.currentNotificationMode,
        options = vm.notificationModeOptions(),
        onClick = { vm.setNotificationMode(it) })
    }
    Spacer(modifier = Modifier.height(8.dp))
    PropertyThingContainer(
      megaDevice = vm.megaDevice,
      identifier = ThingIdentifier.NOTIFICATION_MOTION_ALERT
    ) {
      AlertTonesSettings(state = motionAlertTones[vm.motionAlerts], onClick = {
        navigator.push(AppAlertTonesScreen(vm, motionAlertTones))
      })
    }
    Spacer(modifier = Modifier.height(8.dp))
    PropertyThingContainer(
      megaDevice = vm.megaDevice,
      identifier = ThingIdentifier.NOTIFICATION_RING_ALERT
    ) {
      RingAlertsSettings(state = ringAlertTones[vm.ringAlerts], onClick = {
        navigator.push(AppAlertTonesScreen(vm, ringAlertTones, isDoorbell = true))
      })
    }
    Spacer(modifier = Modifier.height(8.dp))
    PropertyThingContainer(
      megaDevice = vm.megaDevice,
      identifier = ThingIdentifier.NOTIFICATION_RING_ONOFF
    ) {
      CommonSwitchRow(
        title = "Doorbell Ring",
        state = vm.doorbellRing,
        onClick = { vm.setDoorbellRing(it) }
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    PropertyThingContainer(
      megaDevice = vm.megaDevice,
      identifier = ThingIdentifier.NOTIFICATION_MOTION_ONOFF
    ) {
      CommonSwitchRow(
        title = "Motion Detect",
        state = vm.motionDetect,
        onClick = { vm.setMotionDetect(it) }
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (vm.isNotificationJumpSupported()) {
      val jumpLabel = if (vm.notificationJump == 0) "The related history event" else "Live view"
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.White, RoundedCornerShape(8.dp))
          .clickable {
            appLogger.i("NotificationScreen/onJumpClick: deviceSn=${vm.megaDevice.deviceSn}")
            navigator.push(NotificationJumpSettingsScreen(vm))
          }
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Notification Jump",
          fontSize = 16.sp,
          modifier = Modifier.weight(1f).padding(end = 16.dp)
        )
        Text(text = jumpLabel, color = MaterialTheme.colorScheme.primary)
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary
        )
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    PropertyThingContainer(
      megaDevice = vm.megaDevice,
      identifier = ThingIdentifier.SWITCHING_NOTIFICATION
    ) {
      SwitchingNotificationSettings(onClick = { navigator.push(ModesNotificationsScreen(vm)) })
    }
    MegaLoading(vm.loading)
  }

  @Composable
  fun NotificationSettings(
    selectedMode: NotificationMode?,
    options: List<NotificationModeOption>,
    onClick: (NotificationMode) -> Unit
  ) {
    Text(
      text = "Content extension of notifications",
      fontSize = 14.sp,
      color = Color.Gray
    )
    options
      .sortedBy { NOTIFICATION_MODE_DISPLAY_ORDER.indexOf(it.mode) }
      .forEach { option ->
        val copy = NOTIFICATION_MODE_COPY[option.mode]
        CommonRightOptionRow(
          title = option.title,
          description = copy?.description,
          note = copy?.note,
          selected = selectedMode == option.mode,
          onClick = { onClick(option.mode) }
        )
      }
  }

  @Composable
  fun AlertTonesSettings(state: String, onClick: () -> Unit) {
    Text(
      text = "App Alert Tones",
      fontSize = 14.sp,
      color = Color.Gray
    )
    CommonRow(
      title = "Motion Alerts",
      onClick = onClick,
      rightContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(text = state, color = MaterialTheme.colorScheme.primary)
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
          )
        }
      }
    )
  }

  @Composable
  fun RingAlertsSettings(state: String, onClick: () -> Unit) {
    CommonRow(
      title = "Ring Alerts",
      onClick = onClick,
      rightContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(text = state, color = MaterialTheme.colorScheme.primary)
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
          )
        }
      }
    )
  }

  @Composable
  fun SwitchingNotificationSettings(onClick: () -> Unit) {
    Text(
      text = "Security modes switching notification",
      fontSize = 14.sp,
      color = Color.Gray
    )
    CommonRow(
      title = "Switching Notification",
      onClick = onClick,
    )
  }

}

private val NOTIFICATION_MODE_DISPLAY_ORDER = listOf(
  NotificationMode.MOST_EFFICIENT,
  NotificationMode.FULL_EFFECT,
  NotificationMode.INCLUDE_THUMBNAIL,
)

private data class NotificationModeCopy(
  val description: String,
  val note: String? = null,
)

private val NOTIFICATION_MODE_COPY = mapOf(
  NotificationMode.MOST_EFFICIENT to NotificationModeCopy(
    description = "Get notification without delay, only text included.",
  ),
  NotificationMode.FULL_EFFECT to NotificationModeCopy(
    description = "Get text notification first, then thumbnail included if available",
    note = "Note: In this mode, footage preview thumbnails will be temporarily stored in the cloud to deliver a better experience for event notifications.",
  ),
  NotificationMode.INCLUDE_THUMBNAIL to NotificationModeCopy(
    description = "Get full notification included text and thumbnail. (If available)",
    note = "Note: In this mode, footage preview thumbnails will be temporarily stored in the cloud to deliver a better experience for event notifications.",
  ),
)
