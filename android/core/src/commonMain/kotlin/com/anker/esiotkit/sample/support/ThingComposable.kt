package com.anker.esiotkit.sample.support

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.RawThingResponse
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.domain_thing.model.thing.ThingProperty
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.net_api.response.thing.TDDataType
import com.anker.esiotkit.core.device.getAnyTimeDevicePropertyFlow
import com.anker.esiotkit.core.device.getDevicePropertyFlow
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * @version 2024/8/26
 */
class ThingPropertyState(
  megaDevice: MegaDevice,
  private val identifier: String,
  scope: CoroutineScope? = null
) {
  private val property = megaDevice.getProperty(identifier)
  private val deviceSn: String = megaDevice.deviceSn
  private val flow = MegaDeviceManager.getAnyTimeDevicePropertyFlow(megaDevice.deviceSn, identifier)
  var payload = mutableStateOf(property?.get() ?: ThingPayload.create())

  init {
    dataLogger.i("ThingPropertyState/init: deviceSn=$deviceSn, identifier=$identifier, initialPayload=${payload.value.asString()}")
    scope?.let { coroutineScope ->
      // 订阅物模型属性变化，自动更新 payload
      // 使用 getAnyTimeDevicePropertyFlow 确保：
      // 1. 设备属性变化时更新
      // 2. 设备列表刷新时也能获取最新值（解决 APP 重启后缓存为空的问题）
      flow.onEach { newPayload ->
        val currentValue = payload.value.asString()
        val newValue = newPayload.asString()
        // 防御性处理：如果 flow 推送空值但本地已有有效值，忽略空值更新，防止刷新列表时状态丢失
        if (newValue.isBlank() && currentValue.isNotBlank()) {
          dataLogger.w("ThingPropertyState/flow/ignored: deviceSn=$deviceSn, identifier=$identifier, currentValue=$currentValue, newValue=$newValue (empty update ignored)")
          return@onEach
        }
        payload.value = newPayload
        dataLogger.i("ThingPropertyState/flow/updated: deviceSn=$deviceSn, identifier=$identifier, oldValue=$currentValue, newValue=$newValue")
      }.launchIn(coroutineScope)
    }
  }

  suspend fun set(thingPayload: ThingPayload): RawThingResponse? {
    dataLogger.i("ThingPropertyState/set/start: deviceSn=$deviceSn, identifier=$identifier, currentPayload=${payload.value.asString()}, newPayload=${thingPayload.asString()}")
    return property?.set(thingPayload)?.await()?.onSuccess {
      // 设置成功后立即更新 payload，提供即时 UI 反馈
      // 如果设备返回的值不同，flow 会自动更新 payload（前提是提供了 scope）
      payload.value = thingPayload
      dataLogger.i("ThingPropertyState/set/success: deviceSn=$deviceSn, identifier=$identifier, payload=${thingPayload.asString()}")
    }?.onFailure { code, payload ->
      dataLogger.e("ThingPropertyState/set/failure: deviceSn=$deviceSn, identifier=$identifier, code=${code.code}, payload=$payload")
    }
  }

  fun asIntState(): State<Int> {
    return derivedStateOf {
      payload.value.asInt()
    }
  }

  @Composable
  fun asInt(): Int {
    val state by flow.collectAsState(payload.value)
    val value by remember {
      derivedStateOf {
        state.asInt()
      }
    }
    return value
  }

  fun asBooleanState(): State<Boolean> {
    return derivedStateOf {
      payload.value.asBoolean()
    }
  }

  @Composable
  fun asBoolean(): Boolean {
    val state by flow.collectAsState(payload.value)
    val value by remember {
      derivedStateOf {
        state.asBoolean()
      }
    }
    return value
  }

  fun asStringState(): State<String> {
    return derivedStateOf {
      payload.value.asString()
    }
  }

  @Composable
  fun asString(): String {
    val state by flow.collectAsState(payload.value)
    val value by remember {
      derivedStateOf {
        state.asString()
      }
    }
    return value
  }

  fun asFloatState(): State<Float> {
    return derivedStateOf {
      payload.value.asFloat()
    }
  }

  @Composable
  fun asFloat(): Float {
    val state by flow.collectAsState(payload.value)
    val value by remember {
      derivedStateOf {
        state.asFloat()
      }
    }
    return value
  }

  fun asDoubleState(): State<Double> {
    return derivedStateOf {
      payload.value.asDouble()
    }
  }

  @Composable
  fun asDouble(): Double {
    val state by flow.collectAsState(payload.value)
    val value by remember {
      derivedStateOf {
        state.asDouble()
      }
    }
    return value
  }

  fun asLongState(): State<Long> {
    return derivedStateOf {
      payload.value.asLong()
    }
  }

  @Composable
  fun asLong(): Long {
    val state by flow.collectAsState(payload.value)
    val value by remember {
      derivedStateOf {
        state.asLong()
      }
    }
    return value
  }
}

@Composable
fun MegaDevice.rememberProperty(identifier: String): ThingProperty? {
  return remember { getProperty(identifier) }
}

@Composable
fun MegaDevice.rememberIntProperty(identifier: String): State<Int> {
  val property =
    remember { MegaDeviceManager.getDevicePropertyFlow(deviceSn, identifier).map { it.asInt() } }
  val state = property.collectAsState(getProperty(identifier)?.get()?.asInt() ?: 0)
  return state
}

@Composable
fun MegaDevice.rememberBooleanProperty(identifier: String): State<Boolean> {
  val property =
    remember { MegaDeviceManager.getDevicePropertyFlow(deviceSn, identifier).map { it.asBoolean() } }
  val state = property.collectAsState(getProperty(identifier)?.get()?.asBoolean() ?: false)
  return state
}

@Composable
fun MegaDevice.rememberStringProperty(identifier: String): State<String> {
  val property =
    remember { MegaDeviceManager.getDevicePropertyFlow(deviceSn, identifier).map { it.asString() } }
  val state = property.collectAsState(getProperty(identifier)?.get()?.asString() ?: "")
  return state
}

@Composable
fun MegaDevice.rememberFloatProperty(identifier: String): State<Float> {
  val property =
    remember { MegaDeviceManager.getDevicePropertyFlow(deviceSn, identifier).map { it.asFloat() } }
  val state = property.collectAsState(getProperty(identifier)?.get()?.asFloat() ?: 0.0f)
  return state
}

@Composable
fun MegaDevice.rememberDoubleProperty(identifier: String): State<Double> {
  val property =
    remember { MegaDeviceManager.getDevicePropertyFlow(deviceSn, identifier).map { it.asDouble() } }
  val state = property.collectAsState(getProperty(identifier)?.get()?.asDouble() ?: 0.0)
  return state
}

@Composable
fun MegaDevice.rememberLongProperty(identifier: String): State<Long> {
  val property =
    remember { MegaDeviceManager.getDevicePropertyFlow(deviceSn, identifier).map { it.asLong() } }
  val state = property.collectAsState(getProperty(identifier)?.get()?.asLong() ?: 0L)
  return state
}

@Composable
fun MegaDevice.rememberThingEvent(identifier: String): ThingPayload {
  var event by remember { mutableStateOf(ThingPayload.create()) }
  DisposableEffect(Unit) {
    getEvent(identifier)?.addListener(name = "ThingEventComposable") {
      event = it
    }
    onDispose {
      getEvent(identifier)?.removeListenerBy("ThingEventComposable")
    }
  }
  return event
}

@Composable
fun MegaDevice.rememberThingPropertyValue(identifier: String): ThingPayload {
  return remember { runCatching { getProperty(identifier) }.getOrNull()?.get() ?: ThingPayload.create() }
}

@Composable
fun MegaDevice.rememberOnlineStatus(): State<Boolean> {
  // 使用 remember 缓存属性查找，避免每次重组都调用 getProperty
  val property = remember(deviceSn) { getProperty(ThingIdentifier.DEVICE_IS_ONLINE) }
  val state = property?.thingPayloadFlow?.collectAsState()
    ?: return remember { derivedStateOf { false } }
  return remember(state) {
    derivedStateOf {
      state.value.asBoolean()
    }
  }
}

/**
 * 根据属性的数据类型创建正确格式的 ThingPayload
 *
 * @param property 物模型属性
 * @param value 字符串值（将根据数据类型自动转换）
 * @return 正确格式的 ThingPayload
 */
fun createPayloadByPropertyType(property: ThingProperty?, value: String): ThingPayload {
  val dataType = property?.td?.dataType?.type
  return when (dataType) {
    TDDataType.TYPE_INT, TDDataType.TYPE_ENUM ->
      ThingPayload.fromInt(value.toIntOrNull() ?: 0)
    TDDataType.TYPE_IENUM ->
      ThingPayload.fromString(value)
    TDDataType.TYPE_BOOL ->
      ThingPayload.fromBoolean(value.toBooleanStrictOrNull() ?: (value == "1" || value.equals("true", ignoreCase = true)))
    TDDataType.TYPE_DOUBLE ->
      ThingPayload.from(value.toDoubleOrNull() ?: 0.0)
    else -> ThingPayload.fromString(value)
  }
}