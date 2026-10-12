package com.anker.esiotkit.sample.view

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.net_api.response.thing.TDDataType
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.support.rememberProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

/**
 * @version 2025/8/19
 */
@Preview
@Composable
fun PropertyControllerPreview() {
  PropertyController(dataType = TDDataType.TYPE_BOOL)
}

@Composable
fun PropertyController(
  // megaDevice: MegaDevice,
  // identifier: String,
  dataType: String = TDDataType.TYPE_BOOL, // Default to TYPE_BOOL for example
) {
  // val property = remember { megaDevice.getProperty(identifier) } ?: return
  // val td = remember { property.td } ?: return
  // val dataType = td.dataType?.type ?: return
  when (dataType) {
    TDDataType.TYPE_BOOL -> {}
    TDDataType.TYPE_INT -> {}
    TDDataType.TYPE_DOUBLE -> {}
    TDDataType.TYPE_TEXT -> {}
    TDDataType.TYPE_TENUM -> {}
    TDDataType.TYPE_ENUM -> {}
  }
}

@Composable
fun PropertySwitch(
  megaDevice: MegaDevice,
  identifier: String,
  showDisableDialog: Boolean = false,
  title: String,
  description: String? = null,
  disableDialogTitle: String = "Are you sure to disable this feature?",
  disableDialogDesc: String = "Disabling this feature will turn off all related functionalities.",
  modifier: Modifier = Modifier,
) {
  PropertyThingContainer(
    megaDevice = megaDevice,
    identifier = identifier,
  ) {
    val propertyValue by megaDevice.rememberBooleanProperty(identifier)
    var loading by state(false)

    fun setProperty(value: Boolean) {
      appLogger.i("PropertySwitch/setProperty/start: id=$identifier, value=$value, sn=${megaDevice.deviceSn}")
      loading = true
      val property = megaDevice.getProperty(identifier)
      if (property == null) {
        appLogger.w("PropertySwitch/setProperty/propertyNull: id=$identifier, sn=${megaDevice.deviceSn}")
        loading = false
        return
      }
      property.set(ThingPayload.from(value))
        ?.onSuccess {
          loading = false
          appLogger.i("PropertySwitch/setProperty/success: id=$identifier, value=$value, sn=${megaDevice.deviceSn}")
          emitToast("Set succeed")
        }?.onFailure { code, payload ->
          loading = false
          appLogger.w("PropertySwitch/setProperty/failure: id=$identifier, code=$code, payload=$payload, currentValue=$propertyValue, sn=${megaDevice.deviceSn}")
          emitToast("Set property failed: $code, $payload")
        }
    }

    var showMotionDialog by remember { mutableStateOf(false) }
    if (showMotionDialog) {
      ConfirmDialog(
        title = disableDialogTitle,
        description = disableDialogDesc,
        onConfirm = {
          setProperty(false)
          showMotionDialog = false
        },
        onCancel = { showMotionDialog = false }
      )
    }
    MegaLoading(loading)
    CommonRow(
      modifier = modifier,
      title = title,
      description = description,
      rightContent = {
        Switch(
          checked = propertyValue,
          onCheckedChange = {
            if (it) {
              setProperty(true)
            } else {
              if (showDisableDialog) {
                showMotionDialog = true
              } else {
                setProperty(false)
              }
            }
          }
        )
      }
    )
  }
}

@Composable
fun PropertyEnumSlider(
  megaDevice: MegaDevice,
  identifier: String,
  title: String,
  modifier: Modifier = Modifier,
) {
  val property = megaDevice.rememberProperty(identifier) ?: return
  val enumList = property.td?.dataType?.specs?.enumList ?: emptyList()
  val currentValue by megaDevice.rememberStringProperty(identifier)
  var loading by state(false)
  MegaLoading(loading)
  EnumCustomSlider(
    modifier = modifier,
    title = title,
    enumList = enumList,
    value = currentValue,
    onValueChange = { newValue ->
      appLogger.d { "PropertyControlComposable/PropertyEnumSlider/newValue:$newValue" }
      loading = true
      // enum.value 是 String，但 JS thing model 通常期望 Int 类型
      // ThingPayload.from(String) → {"payload-value":"4"}（字符串），JS 无法正确映射
      // ThingPayload.from(Int)   → {"payload-value":4}（整数），JS 正确解析
      val payload = newValue.toIntOrNull()?.let { ThingPayload.from(it) } ?: ThingPayload.from(newValue)
      property.set(payload)
        .onSuccess {
          loading = false
          emitToast("Set succeed")
        }.onFailure { code, payload ->
          loading = false
          emitToast("Set property failed: $code, $payload")
          appLogger.w("PropertyEnumSlider/setProperty failed: $code, $payload")
        }
    }
  )
}

@Composable
fun PropertySlider(
  megaDevice: MegaDevice,
  identifier: String,
  title: String,
  modifier: Modifier = Modifier,
  onValueChange: ((Int) -> Unit)? = null
) {
  PropertyThingContainer(
    megaDevice = megaDevice,
    identifier = identifier
  ) { identifier ->
    var loading by state(false)
    val property = megaDevice.rememberProperty(identifier)
    if (property?.td?.dataType?.type == TDDataType.TYPE_IENUM || property?.td?.dataType?.type == TDDataType.TYPE_ENUM) {
      dataLogger.v { "PropertyControlComposable/PropertySlider/${property?.td?.dataType}" }
      PropertyEnumSlider(megaDevice, identifier, title, modifier)
    } else {
      val specsMin = property?.td?.dataType?.specs?.min
      val specsMax = property?.td?.dataType?.specs?.max
      if (specsMin.isNullOrBlank() || specsMax.isNullOrBlank()) {
        appLogger.w("PropertySlider: specs invalid for '$identifier', " +
            "min='$specsMin' max='$specsMax' — hiding slider, device likely doesn't support adjustment")
        return@PropertyThingContainer
      }
      MegaLoading(loading)
      Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val alarmVolume by megaDevice.rememberIntProperty(identifier)
        var value by state(alarmVolume.toFloat())
        val currentValue by derivedStateOf {
          value.roundToInt()
        }
        Text(text = "$title: $currentValue", style = MaterialTheme.typography.titleMedium)
        val min = remember {
          property?.td?.dataType?.specs?.min?.toInt()?.toFloat() ?: 0f
        }
        val max = remember {
          property?.td?.dataType?.specs?.max?.toInt()?.toFloat() ?: 100f
        }
        val interactionSource = remember { MutableInteractionSource() }
        val isDrag by interactionSource.collectIsDraggedAsState()
        LaunchedEffect(currentValue, isDrag) {
          if (currentValue != alarmVolume && !isDrag) {
            onValueChange?.invoke(currentValue)
            property?.also {
              loading = true
            }?.set(payload = ThingPayload.from(currentValue))
              ?.onSuccess {
                loading = false
                emitToast("Set succeed")
              }?.onFailure { code, payload ->
                loading = false
                emitToast("Set property failed: $code, $payload")
                appLogger.w("PropertySlider/setProperty failed: $code, $payload")
              }
          }
        }
        Slider(
          value = value,
          onValueChange = {
            value = it
          },
          valueRange = min..max,
          steps = (max - min).toInt() - 1,
          interactionSource = interactionSource
        )
        Row(modifier = Modifier.fillMaxWidth()) {
          Text(text = min.toInt().toString(), style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.weight(1f))
          Text(text = max.toInt().toString(), style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}