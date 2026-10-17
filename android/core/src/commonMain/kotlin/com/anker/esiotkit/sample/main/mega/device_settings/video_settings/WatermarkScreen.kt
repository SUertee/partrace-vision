package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.OptionRowItem
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_watermark_title

/**
 * @version 2024/10/25
 */

class WatermarkScreen(@Transient private val viewModel: VideoSettingsViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_watermark_title

  // 默认枚举选项（物模型未返回 enumList 时的兜底）
  private val defaultOptions = listOf(
    0 to "Off (No timestamp or logo)",
    1 to "Timestamp",
    2 to "Timestamp and logo",
    3 to "Logo",
  )

  @Composable
  override fun Content() = BaseColumn {
    // 优先从物模型 enumList 读取选项，兜底使用 defaultOptions
    val options = remember {
      val enumList = viewModel.megaDevice
        .getProperty(ThingIdentifier.WATERMARK_SET)
        ?.td?.dataType?.specs?.enumList
      if (!enumList.isNullOrEmpty()) {
        // T8414 设备不按 associatedValue 解释协议值，直接用 item.value
        val isT8414 = viewModel.megaDevice.deviceSn.startsWith("T8414")
        enumList.mapNotNull { item ->
          val displayValue = item.value.toIntOrNull()
            ?: return@mapNotNull null
          // 发送值：T8414 用 item.value，其他设备用 associatedValue（兼容原有协议映射）
          val sendValue = if (isT8414) {
            displayValue
          } else {
            item.associatedValue?.toIntOrNull() ?: displayValue
          }
          Triple(displayValue, sendValue, item.desc)
        }
      } else {
        defaultOptions.map { Triple(it.first, it.first, it.second) }
      }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
      options.forEachIndexed { index, (displayValue, sendValue, label) ->
        if (index > 0) {
          HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        OptionRowItem(
          title = label,
          isSelected = viewModel.watermarkState == displayValue,
          onClick = {
            viewModel.setWatermark(
              apiValue = sendValue,
              displayValue = displayValue,
              label = label
            )
          }
        )
      }
    }
    MegaLoading(viewModel.loading)
  }

}