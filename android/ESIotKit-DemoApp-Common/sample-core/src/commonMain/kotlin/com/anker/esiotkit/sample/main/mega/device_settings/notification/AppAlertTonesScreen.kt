package com.anker.esiotkit.sample.main.mega.device_settings.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
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
import com.anker.esiotkit.sample.view.CommonRowItem
import com.anker.esiotkit.support.log.appLogger
import kotlin.jvm.Transient
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_notification_alert_tones_title

/**
 * @version 2024/10/25
 */

class AppAlertTonesScreen(
  // @Transient 仅对 Android/JVM 序列化生效；iOS 侧由下方判空 + pop 兜底
  @Transient private val viewModel: NotificationSettingsViewModel?,
  private val tones: List<String>,
  private val isDoorbell: Boolean = false
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_notification_alert_tones_title

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val vm = viewModel
    if (vm == null) {
      LaunchedEffect(Unit) { navigator.pop() }
      return@BaseColumn
    }
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
      itemsIndexed(
        items = tones,
        key = { index, _ -> index }) { index, tone ->
        if(isDoorbell) {
          AppAlertTonesOptionRow(
            title = tone,
            isSelected = index == vm.ringAlerts,
            onClick = {
              appLogger.i("[ALTONE] AppAlertTonesScreen CLICK: isDoorbell=true, index=$index, tone=$tone")
              vm.selectRingAlert(index)
            }
          )
        } else {
          AppAlertTonesOptionRow(
            title = tone,
            isSelected = index == vm.motionAlerts,
            onClick = {
              appLogger.i("[ALTONE] AppAlertTonesScreen CLICK: isDoorbell=false, index=$index, tone=$tone")
              vm.selectMotionAlert(index)
            }
          )
        }
      }
    }
    MegaLoading(vm.loading)
  }

  @Composable
  fun AppAlertTonesOptionRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
  ) {
    CommonRowItem(
      title = title,
      rightContent = {
        if (isSelected) {
          Icon(imageVector = Icons.Default.Check, contentDescription = null)
        }
      },
      onClick = onClick
    )
    HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))

  }
}