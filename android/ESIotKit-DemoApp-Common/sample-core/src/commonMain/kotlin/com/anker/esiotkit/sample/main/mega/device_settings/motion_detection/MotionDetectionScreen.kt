package com.anker.esiotkit.sample.main.mega.device_settings

import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_motion_title
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.core.device.getDevicePropertyFlow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.api.domain.DetectionTypeOption
import com.anker.esiotkit.domain_thing.api.domain.SelectionMode
import com.anker.esiotkit.domain_thing.api.model.ThingResult
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.main.mega.device_settings.motion_detection.ActivityZoneScreen
import com.anker.esiotkit.sample.main.mega.device_settings.motion_detection.MotionTestModeScreen
import com.anker.esiotkit.sample.main.mega.device_settings.settings_composable.NvrMotionDetectionComposable
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.CommonRowItem
import com.anker.esiotkit.sample.view.DetectionSensitivitySelector
import com.anker.esiotkit.sample.view.PropertySwitch
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.flow.Flow

/**
 * @version 2024/10/30
 */

class MotionDetectionScreen(
  private val viewModel: MegaDeviceSettingsViewModel
) : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_settings_motion_title

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val megaDevice = remember { viewModel.megaDevice }
    val motionDetectionStatus by megaDevice.rememberBooleanProperty(
      identifier = ThingIdentifier.MOTION_DETECTION_STATUS
    )
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        PropertySwitch(
          megaDevice = megaDevice,
          identifier = ThingIdentifier.MOTION_DETECTION_STATUS,
          showDisableDialog = true,
          title = "Motion Detection",
          disableDialogTitle = "Do you want to turn off the motion detection feature?",
          disableDialogDesc = "All of the detection features will be turned off; camera will not record video and push notifications.",
        )

        PropertyThingContainer(
          megaDevice = megaDevice,
          identifier = ThingIdentifier.NVR_MOTION_DETECTION
        ) {
          NvrMotionDetectionComposable(
            megaDevice = megaDevice
          )
        }

        if (motionDetectionStatus) {
          PropertyThingContainer(
            megaDevice = viewModel.megaDevice,
            identifier = ThingIdentifier.ACTIVITYZONE_CONFIG
          ) {
            CommonRow(
              title = "Activity Zone",
              description = "Define a specific area you want to detect.",
              onClick = {
                navigator.push(ActivityZoneScreen(viewModel = viewModel))
              }
            )
          }

          DetectionSensitivitySelector(
            megaDevice = megaDevice,
            identifier = ThingIdentifier.DETECTION_SENSITIVITY
          )

          DetectionTypeSection(
            megaDevice = megaDevice,
            deviceManager = viewModel.deviceManager,
            deviceSn = viewModel.megaDevice.deviceSn
          )

          PropertyThingContainer(
            megaDevice = viewModel.megaDevice,
            identifier = ThingIdentifier.DETECTION_SENSITIVITY_TEST_MODE
          ) {
            CommonRow(
              title = "Motion Test Mode",
              onClick = { navigator.push(MotionTestModeScreen(viewModel = viewModel)) }
            )
          }
        }
      }
    }
    MegaLoading(viewModel.loading)
  }

  class WriteGate {
    var inFlight = false
    var token = 0
  }

  /**
   * 域校验选择流（FIX-1 消费端落地：v2.2.7 SDK 二进制无 getSelectedDetectionTypesFlow，
   * 以相同契约在消费层实现——契约与日志格式对齐 v2.2.8 档案）：
   * 仅发射 token 全部位于 getDetectionTypeOptions() 值域内的选择；
   * 值域外的 emission（版本化变体 key 携带的其他域，如 detection_type_set__v1:"1,4"）被丢弃，
   * 消费方保留上一有效值。不做跨域映射。
   */
  private fun domainSelectedDetectionTypesFlow(
    md: com.anker.esiotkit.domain_thing.api.domain.MotionDetectionAPI
  ): Flow<List<String>> {
    val sn = deviceSnHolder
    val optionValues = md.getDetectionTypeOptions().map { it.value }.toSet()
    return MegaDeviceManager.getDevicePropertyFlow(sn, ThingIdentifier.DETECTION_TYPE_SET)
      .map { payload ->
        val raw = payload.asString()
        val tokens = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val inDomain = tokens.isNotEmpty() && tokens.all { it in optionValues }
        Triple(tokens, optionValues, inDomain)
      }
      .onEach { (tokens, values, inDomain) ->
        if (inDomain) {
          appLogger.i("MotionDetectionAPI/getSelectedDetectionTypesFlow/emit: tokens=$tokens, deviceSn=$sn")
        } else {
          appLogger.w("MotionDetectionAPI/getSelectedDetectionTypesFlow/dropped: tokens=$tokens not in option domain ($values), device keeps last valid selection, deviceSn=$sn")
        }
      }
      .filter { (_, _, inDomain) -> inDomain }
      .map { (tokens, _, _) -> tokens }
      .catch { e ->
        appLogger.e("MotionDetectionAPI/getSelectedDetectionTypesFlow/failure: flow terminated by exception: ${e.message}, deviceSn=$sn")
        emit(emptyList())
      }
  }

  // deviceSnHolder 由 DetectionTypeSection 组装（Compose remember 的 lambda 在首次组合时求值，需在 Composable 主体赋值后使用）
  private var deviceSnHolder: String = ""

  @Composable
  fun DetectionTypeSection(
    megaDevice: MegaDevice,
    deviceManager: ESIotDeviceManager,
    deviceSn: String
  ) {
    val md = remember(deviceSn) { deviceManager.motionDetection(deviceSn) }
    val options = remember(deviceSn) { md.getDetectionTypeOptions() }
    if (options.isEmpty()) {
      LaunchedEffect(deviceSn) {
        appLogger.w("MotionDetectionScreen/DetectionTypeSection/hidden: options empty, section not rendered, sn=$deviceSn")
      }
      return
    }
    val selectionMode = remember(deviceSn) { md.getSelectionMode() }

    // FIX-1：域校验选择流消费（替代 rememberStringProperty 直读 + flowValueRelevant 启发式）
    deviceSnHolder = deviceSn
    val domainSelectedTypes by remember(deviceSn) {
      domainSelectedDetectionTypesFlow(md)
    }.collectAsState(
      initial = remember(deviceSn) {
        md.getSelectedDetectionTypes().also {
          appLogger.i("MotionDetectionScreen/DetectionTypeSection/initialSelection: baseDoGet=$it, sn=$deviceSn")
        }
      }
    )
    val activeTypes = remember(deviceSn) { md.getActiveTypes() }
    val gate = remember(deviceSn) { WriteGate() }
    var inFlightUi by state(false)
    var confirmedOverride by state<List<String>?>(null)
    val selected = confirmedOverride ?: domainSelectedTypes.ifEmpty { activeTypes }
    LaunchedEffect(selected) {
      appLogger.i("MotionDetectionScreen/DetectionTypeSection/state: domainSelected=$domainSelectedTypes, activeTypes=$activeTypes, selected=$selected, mode=$selectionMode, confirmedOverride=$confirmedOverride, options=${options.map { "${it.value}:${it.label}" }}, sn=$deviceSn")
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(domainSelectedTypes) {
      if (domainSelectedTypes.isNotEmpty()) {
        appLogger.i("MotionDetectionScreen/DetectionTypeSection/flowOverrideClear: domainSelected=$domainSelectedTypes, cleared confirmedOverride=$confirmedOverride, sn=$deviceSn")
        confirmedOverride = null
      }
    }

    fun select(option: DetectionTypeOption, targetChecked: Boolean) {
      if (gate.inFlight) {
        appLogger.w("MotionDetectionScreen/DetectionTypeSection/select/dropped: inFlight write pending, click ignored, option=${option.value}, sn=$deviceSn")
        return
      }
      val newSelection = when {
        selectionMode == SelectionMode.SINGLE -> listOf(option.value)
        targetChecked -> (selected + option.value).distinct()
        else -> selected.filter { it != option.value }
      }
      appLogger.i("MotionDetectionScreen/DetectionTypeSection/select: option=${option.value}(${option.label}), targetChecked=$targetChecked, currentSelected=$selected, newSelection=$newSelection, mode=$selectionMode, sn=$deviceSn")
      if (newSelection == selected) {
        appLogger.i("MotionDetectionScreen/DetectionTypeSection/select/noChange: skip, sn=$deviceSn")
        return
      }
      val validation = md.validateDetectionTypes(newSelection)
      if (validation is ThingResult.Error) {
        emitToast(validation.message)
        appLogger.w("MotionDetectionScreen/DetectionTypeSection/validate/rejected: ${validation.message}, sn=$deviceSn")
        return
      }
      gate.inFlight = true
      gate.token++
      val myToken = gate.token
      inFlightUi = true
      appLogger.i("MotionDetectionScreen/DetectionTypeSection/loading: show (write in flight, interactive disabled), newSelection=$newSelection, sn=$deviceSn")
      // FIX-2：loading/禁交互严格跟随 API 结果（try/finally 单一复位；无 UI 5s 计时器，无双 toast）
      scope.launch {
        try {
          md.setSelectedDetectionTypes(newSelection)
            .onSuccess {
              if (gate.token == myToken) {
                gate.inFlight = false
                inFlightUi = false
              }
              appLogger.i("MotionDetectionScreen/DetectionTypeSection/setSuccess: newSelection=$newSelection, applied confirmedOverride, awaiting flow push, sn=$deviceSn")
              confirmedOverride = newSelection
              emitToast("Set succeed")
            }
            .onError { code, msg ->
              if (gate.token == myToken) {
                gate.inFlight = false
                inFlightUi = false
              }
              appLogger.e("MotionDetectionScreen/DetectionTypeSection/setFailure: code=$code, msg=$msg, newSelection=$newSelection, sn=$deviceSn")
              emitToast("Set failed: $code, $msg")
            }
        } finally {
          if (gate.token == myToken) {
            inFlightUi = false
            gate.inFlight = false
          }
          appLogger.i("MotionDetectionScreen/DetectionTypeSection/loading: hide (write settled), resultReceived, sn=$deviceSn")
        }
      }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
      options.forEachIndexed { index, option ->
        ModesDetectionRow(
          title = option.label,
          isSelected = option.value in selected,
          enabled = !inFlightUi,
          onClick = { select(option, option.value !in selected) }
        )
        if (index < options.lastIndex) {
          Divider()
        }
      }
    }
  }

  @Composable
  fun ModesDetectionRow(
    title: String,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
  ) {
    CommonRowItem(
      title = title,
      enabled = enabled,
      rightContent = {
        Checkbox(
          checked = isSelected,
          enabled = enabled,
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
