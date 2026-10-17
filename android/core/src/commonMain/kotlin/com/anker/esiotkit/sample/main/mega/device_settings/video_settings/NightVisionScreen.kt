package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import kotlinx.serialization.Transient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.net_api.response.thing.TDDataType
import com.anker.esiotkit.net_api.response.thing.TDEnum
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonLeftOptionRow
import com.anker.esiotkit.support.log.appLogger
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_night_vision_title

/**
 * @version 2024/10/25
 */

class NightVisionScreen(@Transient private val viewModel: VideoSettingsViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_night_vision_title

  @Composable
  override fun Content() = BaseColumn {
    val modes = remember {
      val property = viewModel.megaDevice.getProperty(viewModel.nightVisionIdentifier)
      val enumList = property?.td?.dataType?.specs?.enumList
      val dataType = property?.td?.dataType?.type
      
      appLogger.i("NightVisionScreen/loadModes: ===== 服务端原始数据 =====")
      appLogger.i("NightVisionScreen/loadModes: identifier=${viewModel.nightVisionIdentifier}, dataType=$dataType")
      appLogger.i("NightVisionScreen/loadModes: enumListSize=${enumList?.size ?: 0}")
      
      if (enumList != null) {
        appLogger.i("NightVisionScreen/loadModes: 服务端返回的完整枚举列表:")
        enumList.forEachIndexed { index, tdEnum ->
          appLogger.i("NightVisionScreen/loadModes:   [$index] value=${tdEnum.value}, desc=${tdEnum.desc}")
        }
      }
      
      if (!enumList.isNullOrEmpty()) {
        appLogger.i("NightVisionScreen/loadModes: 最终显示的枚举列表:")
        enumList.forEachIndexed { index, tdEnum ->
          appLogger.i("NightVisionScreen/loadModes:   [$index] value=${tdEnum.value}, desc=${tdEnum.desc}")
        }
        
        enumList
      } else if (dataType == TDDataType.TYPE_BOOL) {
        appLogger.i("NightVisionScreen/loadModes: 使用默认 BOOL 类型枚举 (Off/On)")
        listOf(
          TDEnum(value = "0", desc = "Off"),
          TDEnum(value = "1", desc = "On"),
        )
      } else {
        appLogger.w("NightVisionScreen/loadModes: ⚠️ 服务端未返回枚举列表，使用默认枚举")
        listOf(
          TDEnum(value = "0", desc = "Off"),
          TDEnum(value = "1", desc = "B&W Night Vision"),
          TDEnum(value = "2", desc = "Color Night Vision"),
          TDEnum(value = "3", desc = "Auto"),
        )
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      modes.forEach { enum ->
        val modeValue = enum.value.toIntOrNull() ?: return@forEach
        CommonLeftOptionRow(
          title = mapNightVisionDisplayName(modeValue, enum.desc),
          selected = viewModel.nightVisionMode == modeValue,
          onClick = {
            appLogger.i("NightVisionScreen/onModeSelected: value=$modeValue, desc=${enum.desc}, currentMode=${viewModel.nightVisionMode}")
            viewModel.setNightVisionState(modeValue)
          }
        )
      }
    }
    MegaLoading(viewModel.loading)
  }

  private fun mapNightVisionDisplayName(value: Int, fallback: String): String = when (value) {
    100 -> "关闭"
    101 -> "红外开启"
    102 -> "红外自动"
    103 -> "聚光灯自动"
    104 -> "标准自动"
    105 -> "日间模式自动"
    106 -> "超级日间自动"
    else -> fallback.ifEmpty { "Mode $value" }
  }
}
