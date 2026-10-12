package com.anker.esiotkit.sample.main.mega.device_settings.audio_settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.net_api.response.thing.TDEnum
import com.anker.esiotkit.sample.main.mega.device_settings.AudioSettingsViewModel
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.rememberDerivedState
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRowItem
import com.anker.esiotkit.sample.view.CommonSwitchRow
import com.anker.esiotkit.sample.view.PropertyEnumSlider
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_audio_sound_detection_title

/**
 * Sound Detection Settings Screen
 * Configure sound detection features and sensitivity
 */
class SoundDetectionScreen(
  private val viewModel: AudioSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_audio_sound_detection_title

  @Composable
  override fun Content() = BaseColumn(
    scrollState = rememberScrollState()
  ) {
    val megaDevice = remember { viewModel.megaDevice }
    val soundProp = remember { megaDevice.getProperty("sound_detection_status") }
    val soundValue by megaDevice.rememberBooleanProperty("sound_detection_status")

    // Sound Detection Switch
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
        CommonSwitchRow(
          title = "Sound Detection",
          state = soundValue,
          onClick = {
            if (soundProp == null) {
              emitToast("Sound detection not supported by this device")
            } else {
              viewModel.setSoundDetectionSwitch(it)
            }
          }
        )
    }

    // Conditional sub-properties (only shown when enabled)
    if (soundValue) {
      // Sound Detection Type — 不依赖 getProperty() 是否返回 null（可能 get/unabled）
      // Selector 内部用 rememberStringProperty 从 StateFlow 缓存读取值
      SoundDetectionTypeSelector(
        megaDevice = megaDevice,
        identifier = "sound_detection_type",
        onSetType = { value -> viewModel.setSoundDetectionType(value) }
      )

      // Sensitivity Settings
      val sensitivityProp = remember { megaDevice.getProperty("sound_detection_sensitivity") }
      if (sensitivityProp != null) {
        PropertyEnumSlider(
          megaDevice = megaDevice,
          identifier = "sound_detection_sensitivity",
          title = "Detection Sensitivity",
        )
      }

      // Round-look (巡航联动) 开关
      // 对齐 Mega RN SoundDetectionScreen.tsx:181-205 RoundLookSwitch
      val roundLookProp = remember { megaDevice.getProperty("sound_detection_round_look_switch") }
      appLogger.i("SoundDetectionScreen/RoundLook: hasProperty=${roundLookProp != null}, deviceSn=${megaDevice.deviceSn}")
      if (roundLookProp != null) {
        val roundLookValue by megaDevice.rememberBooleanProperty("sound_detection_round_look_switch")
        CommonSwitchRow(
          title = "Round-look",
          state = roundLookValue,
          onClick = {
            appLogger.i("SoundDetectionScreen/RoundLook/onClick: newValue=${!roundLookValue}, currentValue=$roundLookValue, deviceSn=${megaDevice.deviceSn}")
            viewModel.setRoundLook(!roundLookValue)
          }
        )
      }
    }
    MegaLoading(viewModel.loading)
  }

  /**
   * Sound Detection Type Selector
   *
   * 值域说明（对齐 Mega RN SoundDectionViewModel.ts SoundType 枚举）:
   * - UI 枚举 value = ThingProperty 缓存值 = SoundType index: "0"=ALL, "1"=CRYING, "2"=LOUD
   * - setProperty 传入 index 值，JS 引擎自动路由到 P2P 位掩码 (128/256/2)
   * - UI 选中判断用 ThingProperty 缓存值（通过 rememberStringProperty 订阅 StateFlow）
   *
   * 枚举列表来源（对齐 Mega RN getSoundDetectionTypeList()）:
   * - P1: 从设备 TD enumList 动态获取（按 TD 注册的类型过滤）
   * - P2: TD enumList 不可用时使用默认列表 [ALL_SOUND, CRYING]
   */
  @Composable
  fun SoundDetectionTypeSelector(
    megaDevice: MegaDevice,
    identifier: String,
    onSetType: (String) -> Unit
  ) {
    var loading by state(false)

    // 从 ThingProperty StateFlow 读取当前值（对齐 Mega RN getProperty(soundDetectionType)）
    val value by megaDevice.rememberStringProperty(identifier)

    // P1: 从设备 TD enumList 动态获取支持的类型（对齐 Mega RN getSoundDetectionTypeList()）
    val tdEnumList = remember {
      val property = megaDevice.getProperty(identifier)
      val enumList = property?.td?.dataType?.specs?.enumList
      appLogger.i("SoundDetectionScreen/TypeSelector/tdEnumList: identifier=$identifier, enumListSize=${enumList?.size ?: 0}, deviceSn=${megaDevice.deviceSn}")
      enumList
    }

    // 构建有效枚举列表: TD enumList 优先，不可用时兜底默认列表
    val effectiveEnumList = remember(tdEnumList) {
      val tdResult = tdEnumList?.mapNotNull { tdEnum ->
        val enumValue = (tdEnum.value as? String) ?: (tdEnum.value as? Int)?.toString()
        val desc = tdEnum.desc ?: enumValue ?: ""
        if (enumValue != null) TDEnum(value = enumValue, desc = desc) else null
      } ?: emptyList()

      if (tdResult.isNotEmpty()) {
        appLogger.i("SoundDetectionScreen/TypeSelector/enumSource: source=TD, size=${tdResult.size}, values=${tdResult.map { it.value }}, deviceSn=${megaDevice.deviceSn}")
        tdResult
      } else {
        // P2: 兜底默认列表（对齐 Mega RN SoundType 枚举: ALL='0', CRYING='1'）
        val fallback = listOf(
          TDEnum(value = "0", desc = "All Sound"),
          TDEnum(value = "1", desc = "Crying"),
        )
        appLogger.w("SoundDetectionScreen/TypeSelector/enumSource: source=FALLBACK, size=${fallback.size}, reason=tdEnumList_empty_or_null, deviceSn=${megaDevice.deviceSn}")
        fallback
      }
    }

    appLogger.i("SoundDetectionScreen/TypeSelector: value=$value, enumListSize=${effectiveEnumList.size}, enumValues=${effectiveEnumList.map { it.value }}, deviceSn=${megaDevice.deviceSn}")

    val isSingleSelection by rememberDerivedState {
      effectiveEnumList.size == 2
    }

    fun setProperty(newValue: String) {
      appLogger.i("SoundDetectionScreen/TypeSelector/onSelect: newValue=$newValue, currentValue=$value, deviceSn=${megaDevice.deviceSn}")
      loading = true
      onSetType(newValue)
      loading = false
    }

    MegaLoading(loading)
    if (isSingleSelection) {
      SingleSelectSoundDetection(effectiveEnumList, value, onSelect = ::setProperty)
    } else {
      MultiSelectSoundDetection(effectiveEnumList, value, onSelect = ::setProperty)
    }
  }

  /**
   * Single selection mode for sound detection type
   */
  @Composable
  fun SingleSelectSoundDetection(
    enumList: List<TDEnum>,
    value: String,
    onSelect: (String) -> Unit
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
      enumList.forEachIndexed { index, tDEnum ->
        SoundDetectionRow(
          title = tDEnum.desc,
          isSelected = value == tDEnum.value,
          onClick = {
            onSelect(tDEnum.value)
          }
        )
        if (index < enumList.lastIndex) {
          Divider()
        }
      }
    }
  }

  /**
   * Multi-selection mode for sound detection type
   */
  @Composable
  fun MultiSelectSoundDetection(
    enumList: List<TDEnum>,
    value: String,
    onSelect: (String) -> Unit
  ) {
    val selectedList = remember(value) { value.split(",") }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
      enumList.forEachIndexed { index, tDEnum ->
        SoundDetectionRow(
          title = tDEnum.desc,
          isSelected = selectedList.contains(tDEnum.value),
          onClick = {
            if (selectedList.contains(tDEnum.value)) {
              // Deselect
              onSelect(selectedList.filter { it != tDEnum.value }.joinToString(","))
            } else {
              // Select
              onSelect((selectedList + tDEnum.value).sorted().joinToString(","))
            }
          }
        )
        if (index < enumList.lastIndex) {
          Divider()
        }
      }
    }
  }

  /**
   * Row item for sound detection selection
   */
  @Composable
  fun SoundDetectionRow(
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
