package com.anker.esiotkit.sample.main.mega.device_settings.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.main.mega.device_settings.NotificationSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.OptionRowItem
import kotlin.jvm.Transient
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_notification_jump_title

class NotificationJumpSettingsScreen(
  @Transient private val viewModel: NotificationSettingsViewModel?
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_notification_jump_title

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val vm = viewModel
    if (vm == null) {
      LaunchedEffect(Unit) { navigator.pop() }
      return@BaseColumn
    }

    LazyColumn {
      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
        ) {
          OptionRowItem(
            title = "The related history event",
            isSelected = vm.notificationJump == 0,
            onClick = {
              vm.setNotificationJump(0)
            }
          )
          HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
          OptionRowItem(
            title = "Live view",
            isSelected = vm.notificationJump == 1,
            onClick = {
              vm.setNotificationJump(1)
            }
          )
        }
      }
    }
    MegaLoading(vm.loading)
  }
}
