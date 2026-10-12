package com.anker.esiotkit.sample.main.mega.device_settings.notification

import kotlin.jvm.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.sample.main.mega.device_settings.NotificationSettingsViewModel
import com.anker.esiotkit.sample.support.rememberProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRowItem
import com.anker.esiotkit.sample.view.emitToast
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_notification_modes_title

/**
 * @version 2024/10/25
 */

class ModesNotificationsScreen(
  // @Transient 仅对 Android/JVM 序列化生效；iOS 侧由 Content() 判空 + pop 兜底
  @Transient private val viewModel: NotificationSettingsViewModel?
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_notification_modes_title

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val vm = viewModel
    if (vm == null) {
      LaunchedEffect(Unit) { navigator.pop() }
      return@BaseColumn
    }
    val switchingNotification = vm.megaDevice.rememberProperty(ThingIdentifier.SWITCHING_NOTIFICATION)
    val switchingNotificationState by
      vm.megaDevice.rememberStringProperty(ThingIdentifier.SWITCHING_NOTIFICATION)
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "Push notification when:",
        fontSize = 14.sp,
        color = Color.Gray,
        modifier = Modifier.padding(vertical = 16.dp)
      )
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.White, RoundedCornerShape(8.dp))
      ) {
        ModesNotificationsRow(
          title = "Switch modes by schedule",
          isSelected = switchingNotificationState == "0",
          onClick = {
            scope.launch {
              switchingNotification?.set(ThingPayload.fromString("0"))
                ?.onSuccess {
                  emitToast("Set succeed")
                }?.onFailure { code, payload ->
                  emitToast("Set property failed: $code, $payload")
                }
            }
          }
        )
        HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        ModesNotificationsRow(
          title = "Switch modes from app",
          isSelected = vm.switchingNotification == "1",
          onClick = {
            vm.setSwitchingNotification("1")
          }
        )
      }
    }
    MegaLoading(vm.loading)
  }

  @Composable
  fun ModesNotificationsRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
  ) {
    CommonRowItem(
      title = title,
      rightContent = {
        Checkbox(
          checked = isSelected,
          onCheckedChange = { onClick() },
          colors = CheckboxDefaults.colors(
            checkedColor = MaterialTheme.colorScheme.primary
          )
        )
      },
      onClick = onClick
    )
  }
}