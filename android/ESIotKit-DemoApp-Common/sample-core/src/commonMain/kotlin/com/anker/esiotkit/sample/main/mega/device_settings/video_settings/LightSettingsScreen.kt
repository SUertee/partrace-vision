package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_light_title
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.core.constants.ServiceConstants
import com.anker.esiotkit.core.iotmodel.default_service.SpotlightSettingService
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.support.rememberProperty
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.CommonSwitchRow
import com.anker.esiotkit.sample.view.PropertySlider
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import kotlin.math.roundToInt

/**
 * Light Settings Screen
 * Controls spotlight/floodlight features:
 * - Light switch (on/off)
 * - Brightness adjustment (0-100)
 * - Color temperature adjustment (1-5 or 0-100)
 * - Motion detection for lights
 *
 * @version 2026/03/17
 */
class LightSettingsScreen(
  private val viewModel: VideoSettingsViewModel
) : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_settings_video_light_title

  @Composable
  override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
    val megaDevice = remember { viewModel.megaDevice }
    appLogger.i("LightSettings/Content: enter, deviceSn=${megaDevice.iotDevice.deviceSn}, devicePn=${megaDevice.iotDevice.devicePn}")

    // Step 1: Resolve light switch property (ThingModel + P2P Service 双重检测)
    val hasLightSwitch = remember {
      val hasThingModel = megaDevice.hasProperty("light_switch")
        || megaDevice.hasProperty(ThingIdentifier.SPOTLIGHT_SWITCH)
      val hasP2P = megaDevice.iotDevice
        .getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING) != null
      val has = hasThingModel || hasP2P
      appLogger.i("LightSettings/propertyCheck: light_switch -> thingModel=$hasThingModel, p2p=$hasP2P, resolved=$has")
      has
    }
    // Step 2: Resolve brightness property (TD 属性或 P2P 能力，二者其一即显示)
    val brightnessId = remember { LightPropertyResolver.resolveBrightness(megaDevice) }
    val hasP2pBrightness = remember {
      megaDevice.iotDevice.getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING)
        ?.isSupportBrightness() == true
    }
    val hasSpotlightBrightness = remember { brightnessId != null || hasP2pBrightness }

    appLogger.i("LightSettings/propertyCheck: summary hasLightSwitch=$hasLightSwitch hasSpotlightBrightness=$hasSpotlightBrightness, brightnessId=$brightnessId, hasP2pBrightness=$hasP2pBrightness")

    // Step 3: Check other optional properties for render decision
    val colorTempId = remember { LightPropertyResolver.resolveColorTemp(megaDevice) }
    val hasP2pColorTemp = remember {
      megaDevice.iotDevice.getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING)
        ?.isSupportColorTemperature() == true
    }
    val hasSpotlightColorTemp = remember { colorTempId != null || hasP2pColorTemp }
    val hasMotionDetection = remember {
      val has = megaDevice.hasProperty("light_ctrl_pir_switch")
      appLogger.i("LightSettings/propertyCheck: light_ctrl_pir_switch -> resolved=$has")
      has
    }
    val hasLightDuration = remember {
      val has = megaDevice.hasProperty("light_ctrl_bright_duration")
      appLogger.i("LightSettings/propertyCheck: light_ctrl_bright_duration -> resolved=$has")
      has
    }
    val hasLightAlarm = remember {
      val has = megaDevice.hasProperty("light_alarm_switch")
      appLogger.i("LightSettings/propertyCheck: light_alarm_switch -> resolved=$has")
      has
    }

    appLogger.i("LightSettings/propertyCheck: allProps hasColorTemp=$hasSpotlightColorTemp hasMotion=$hasMotionDetection hasDuration=$hasLightDuration hasAlarm=$hasLightAlarm")

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .background(Color(0xFFF5F5F5))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Step 4: Decide empty vs normal state
      if (!hasLightSwitch && !hasSpotlightBrightness) {
        appLogger.w("LightSettings/render: EMPTY_STATE triggered — no light properties found")
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          androidx.compose.material3.Text(text = "⚠️", fontSize = 48.sp)
          androidx.compose.material3.Text(
            text = "Spotlight Settings Not Available",
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
          androidx.compose.material3.Text(
            text = "This device does not support spotlight features.",
            fontSize = 14.sp, color = Color.Gray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 20.sp
          )
        }
      } else {
        appLogger.i("LightSettings/render: NORMAL_STATE — rendering settings sections")

        // Step 5: Light Switch section
        if (hasLightSwitch) {
          appLogger.i("LightSettings/render: rendering [LightSwitch] section, lightSwitchState=${viewModel.lightSwitchState}")
          val switchId = if (megaDevice.hasProperty("light_switch")) "light_switch" else ThingIdentifier.SPOTLIGHT_SWITCH
          appLogger.i("LightSettings/render: [LightSwitch] using identifier=$switchId")
          PropertyThingContainer(megaDevice = megaDevice, identifier = switchId) {
            val lightSwitch = viewModel.lightSwitchState
            appLogger.i("LightSettings/render: [LightSwitch] switchValue=$lightSwitch")
            var showDisableDialog by remember { mutableStateOf(false) }

            if (showDisableDialog) {
              ConfirmDialog(
                title = "Turn off spotlight?",
                description = "The spotlight will not turn on automatically when motion is detected.",
                onConfirm = {
                  appLogger.i("LightSettings/action: setLightSwitch -> OFF confirmed")
                  viewModel.setLightSwitch(false)
                  showDisableDialog = false
                },
                onCancel = {
                  appLogger.i("LightSettings/action: setLightSwitch -> OFF cancelled")
                  showDisableDialog = false
                }
              )
            }

            Column(
              modifier = Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
            ) {
              CommonRow(
                title = "Spotlight",
                description = "Turn spotlight on or off",
                rightContent = {
                  Switch(checked = lightSwitch, onCheckedChange = {
                    appLogger.i("LightSettings/action: toggle switch -> ${if (it) "ON" else "OFF"}")
                    if (it) viewModel.setLightSwitch(true)
                    else showDisableDialog = true
                  })
                }
              )
            }
          }
        } else {
          appLogger.w("LightSettings/render: SKIP [LightSwitch] — hasLightSwitch=false")
        }

        // Step 6: Brightness section (specs validation delegated to LightPropertySlider)
        if (hasSpotlightBrightness) {
          appLogger.i("LightSettings/render: rendering [Brightness] section, lightSwitchState=${viewModel.lightSwitchState}, resolvedId=$brightnessId")
          if (viewModel.lightSwitchState) {
            Column(
              modifier = Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
              LightPropertySlider(megaDevice = megaDevice, identifier = brightnessId ?: "spotlight_brightness_level", title = "Brightness")
            }
          } else {
            appLogger.i("LightSettings/render: [Brightness] hidden — lightSwitchState=OFF")
          }
        } else {
          appLogger.w("LightSettings/render: SKIP [Brightness] — hasSpotlightBrightness=$hasSpotlightBrightness")
        }

        // Step 7: Color Temperature section (specs validation delegated to LightPropertySlider)
        if (hasSpotlightColorTemp) {
          appLogger.i("LightSettings/render: rendering [ColorTemperature] section, resolvedId=$colorTempId, lightSwitchState=${viewModel.lightSwitchState}")
          if (viewModel.lightSwitchState) {
            Column(
              modifier = Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
              LightPropertySlider(megaDevice = megaDevice, identifier = colorTempId ?: "light_color_temperature", title = "Color Temperature")
            }
          } else {
            appLogger.i("LightSettings/render: [ColorTemperature] hidden — lightSwitchState=OFF")
          }
        } else {
          appLogger.w("LightSettings/render: SKIP [ColorTemperature] — hasSpotlightColorTemp=$hasSpotlightColorTemp")
        }

        // Step 8: Motion Detection section
        appLogger.i("LightSettings/render: rendering [MotionDetection] section, hasProperty=$hasMotionDetection, lightSwitchState=${viewModel.lightSwitchState}")
        PropertyThingContainer(megaDevice = megaDevice, identifier = "light_ctrl_pir_switch") {
          if (viewModel.lightSwitchState) {
            val lightPirSwitch by megaDevice.rememberBooleanProperty("light_ctrl_pir_switch")
            appLogger.i("LightSettings/render: [MotionDetection] pirSwitch=$lightPirSwitch")
            Column(
              modifier = Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
            ) {
              CommonSwitchRow(
                title = "Motion Detection",
                description = "Turn on spotlight when motion is detected",
                state = lightPirSwitch,
                onClick = { enabled ->
                  appLogger.i("LightSettings/action: setLightMotionDetection -> $enabled")
                  viewModel.setLightMotionDetection(enabled)
                }
              )
            }
          } else {
            appLogger.i("LightSettings/render: [MotionDetection] hidden — lightSwitchState=OFF")
          }
        }

        // Step 9: Light Duration section
        appLogger.i("LightSettings/render: rendering [LightDuration] section, hasProperty=$hasLightDuration")
        PropertyThingContainer(megaDevice = megaDevice, identifier = "light_ctrl_bright_duration") {
          val lightPirSwitch by megaDevice.rememberBooleanProperty("light_ctrl_pir_switch")
          if (viewModel.lightSwitchState && lightPirSwitch) {
            Column(
              modifier = Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
              PropertySlider(megaDevice = megaDevice, identifier = "light_ctrl_bright_duration", title = "Light Duration (seconds)")
            }
          } else {
            appLogger.i("LightSettings/render: [LightDuration] hidden — lightOn=${viewModel.lightSwitchState} pirOn=$lightPirSwitch")
          }
        }

        // Step 10: Light Alarm section
        appLogger.i("LightSettings/render: rendering [LightAlarm] section, hasProperty=$hasLightAlarm, lightSwitchState=${viewModel.lightSwitchState}")
        PropertyThingContainer(megaDevice = megaDevice, identifier = "light_alarm_switch") {
          if (viewModel.lightSwitchState) {
            val lightAlarm by megaDevice.rememberBooleanProperty("light_alarm_switch")
            appLogger.i("LightSettings/render: [LightAlarm] alarmSwitch=$lightAlarm")
            Column(
              modifier = Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
            ) {
              CommonSwitchRow(
                title = "Light Alarm",
                description = "Flash spotlight when alarm is triggered",
                state = lightAlarm,
                onClick = { enabled ->
                  appLogger.i("LightSettings/action: setLightAlarm -> $enabled")
                  viewModel.setLightAlarm(enabled)
                }
              )
            }
          } else {
            appLogger.i("LightSettings/render: [LightAlarm] hidden — lightSwitchState=OFF")
          }
        }
      }
    }

    MegaLoading(viewModel.loading)
    appLogger.i("LightSettings/Content: render complete")
  }
}

@Composable
private fun LightPropertySlider(
  megaDevice: MegaDevice,
  identifier: String,
  title: String,
  modifier: Modifier = Modifier
) {
  val property = remember(identifier) { megaDevice.getProperty(identifier) }
  val isBrightness = identifier.contains("brightness", ignoreCase = true)
  val spotService = remember { megaDevice.iotDevice.getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING) }
  val p2pCapable = if (isBrightness) {
    spotService?.isSupportBrightness() == true
  } else {
    spotService?.isSupportColorTemperature() == true
  }
  if (property == null && !p2pCapable) {
    appLogger.w("LightPropertySlider: property '$identifier' not found and no P2P capability, deviceSn=${megaDevice.iotDevice.deviceSn}")
    return
  }
  val serviceCtRange = if (!isBrightness) {
    spotService?.takeIf { it.isSupportColorTemperature() }?.getColorTemperatureRange()
  } else {
    null
  }
  val specsMin = property?.td?.dataType?.specs?.min
  val specsMax = property?.td?.dataType?.specs?.max
  val specsMinValue = specsMin?.toIntOrNull()
  val specsMaxValue = specsMax?.toIntOrNull()
  val specsValid = specsMinValue != null && specsMaxValue != null
  var min: Float
  var max: Float
  // 对齐 Eufy 生产版 UX：色温 UI 0-5 档，协议层 0-100，换算系数 20（Bug#8497 真机确认）
  val ctUxLevels = serviceCtRange != null
  val ctFactor = if (ctUxLevels && serviceCtRange != null) {
    (serviceCtRange.last - serviceCtRange.first).toFloat() / 5f
  } else {
    1f
  }
  when {
    serviceCtRange != null -> {
      min = 0f
      max = 5f
      appLogger.i("LightPropertySlider: range source=P2P service UX levels 0-5 (protocol ${serviceCtRange.first}-${serviceCtRange.last}, factor=$ctFactor) for '$identifier', deviceSn=${megaDevice.iotDevice.deviceSn}")
    }
    specsValid -> {
      min = specsMinValue!!.toFloat()
      max = specsMaxValue!!.toFloat()
      appLogger.i("LightPropertySlider: specs valid for '$identifier', min=$min, max=$max, deviceSn=${megaDevice.iotDevice.deviceSn}")
    }
    else -> {
      min = 0f
      max = 100f
      appLogger.w("LightPropertySlider: specs missing for '$identifier', min='$specsMin' max='$specsMax', using defaults: min=$min, max=$max, deviceSn=${megaDevice.iotDevice.deviceSn}")
    }
  }
  if (isBrightness && spotService != null) {
    val clampedMin = maxOf(min, 1f)
    val clampedMax = minOf(max, 100f)
    if (clampedMin != min || clampedMax != max) {
      appLogger.w("LightPropertySlider: brightness clamped to P2P 1-100, orig=${min.toInt()}..${max.toInt()}, deviceSn=${megaDevice.iotDevice.deviceSn}")
    }
    min = clampedMin
    max = clampedMax
  }
  val initialValue = remember {
    val p2pValue = if (spotService != null) {
      if (isBrightness) spotService.getBrightness() else spotService.getColorTemperature()
    } else null
    val thingValue = megaDevice.getProperty(identifier)?.get()?.asInt()
    val resolved = p2pValue ?: thingValue ?: 0
    appLogger.i("LightPropertySlider: initial value, identifier=$identifier, p2pValue=$p2pValue, thingValue=$thingValue, resolved=$resolved, spotServiceFound=${spotService != null}, deviceSn=${megaDevice.iotDevice.deviceSn}")
    val display = if (ctUxLevels) resolved.toFloat() / ctFactor else resolved.toFloat()
    display.coerceIn(min, max)
  }
  var value by remember { mutableStateOf(initialValue) }
  var loading by state(false)
  MegaLoading(loading)
  Column(modifier = modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
    val displayValue by derivedStateOf { value.roundToInt() }
    Text(text = "$title: $displayValue", style = MaterialTheme.typography.titleMedium)
    Slider(
      value = value,
      onValueChange = {
        value = it
        appLogger.i("LightPropertySlider: onValueChange, identifier=$identifier, newValue=$it, displayValue=${it.roundToInt()}, deviceSn=${megaDevice.iotDevice.deviceSn}")
      },
      onValueChangeFinished = {
        val displayTarget = value.roundToInt().coerceIn(min.toInt(), max.toInt())
        val targetValue = if (ctUxLevels) (displayTarget * ctFactor).roundToInt() else displayTarget
        appLogger.i("LightPropertySlider: onValueChangeFinished, identifier=$identifier, displayValue=$displayTarget, targetValue=$targetValue, isBrightness=$isBrightness, deviceSn=${megaDevice.iotDevice.deviceSn}")
        loading = true
        if (spotService != null) {
          if (isBrightness) {
            appLogger.i("LightPropertySlider: sending P2P setBrightness, targetValue=$targetValue, cmd=1401, deviceSn=${megaDevice.iotDevice.deviceSn}")
            spotService.setBrightness(targetValue,
              onSuccess = {
                loading = false
                appLogger.i("LightPropertySlider: P2P setBrightness success, value=$targetValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
                emitToast("Set succeed")
              },
              onFailure = { code, _ ->
                loading = false
                value = initialValue
                appLogger.e("LightPropertySlider: P2P setBrightness failed, code=$code, value=$targetValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
                emitToast("Set property failed: $code")
              }
            )
          } else {
            appLogger.i("LightPropertySlider: sending P2P setColorTemperature, targetValue=$targetValue, cmd=1410, deviceSn=${megaDevice.iotDevice.deviceSn}")
            spotService.setColorTemperature(targetValue,
              onSuccess = {
                loading = false
                appLogger.i("LightPropertySlider: P2P setColorTemperature success, value=$targetValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
                emitToast("Set succeed")
              },
              onFailure = { code, _ ->
                loading = false
                value = initialValue
                appLogger.e("LightPropertySlider: P2P setColorTemperature failed, code=$code, value=$targetValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
                emitToast("Set property failed: $code")
              }
            )
          }
        } else {
          appLogger.i("LightPropertySlider: SpotlightSettingService not found, fallback to ThingModel, identifier=$identifier, targetValue=$targetValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
          property?.set(payload = ThingPayload.from(targetValue))
            ?.onSuccess {
              loading = false
              appLogger.i("LightPropertySlider: property set success via ThingModel, identifier=$identifier, value=$targetValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
              emitToast("Set succeed")
            }
            ?.onFailure { code, _ ->
              loading = false
              value = initialValue
              appLogger.e("LightPropertySlider: property set failed via ThingModel, identifier=$identifier, code=$code, value=$targetValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
              emitToast("Set property failed: $code")
            }
        }
      },
      valueRange = min..max,
      steps = (max - min).toInt().coerceAtLeast(1) - 1
    )
    Row(modifier = Modifier.fillMaxWidth()) {
      Text(text = min.toInt().toString(), style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.weight(1f))
      Text(text = max.toInt().toString(), style = MaterialTheme.typography.bodyMedium)
    }
  }
}
