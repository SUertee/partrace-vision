package com.anker.esiotkit.sample.main.mega.device_settings

import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.RawThingResponse
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.support.ThingPropertyState
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

abstract class SubSettingsViewModel(
  val megaDevice: MegaDevice,
  private val parent: BaseScreenModel
) {
  val loading: Boolean get() = parent.loading

  val thingDescription get() = megaDevice.getThingDescription()
  val properties get() = thingDescription?.propMap.orEmpty()
  val actions get() = thingDescription?.actionMap.orEmpty()
  val events get() = thingDescription?.eventMap.orEmpty()

  protected fun MegaDevice.propertyState(identifier: String) = ThingPropertyState(this, identifier, parent.viewModelScope)

  protected fun doOnParentDispose(block: () -> Unit) {
    parent.viewModelScope.coroutineContext[Job]?.invokeOnCompletion { block() }
  }

  protected fun launchWithLoading(
    context: CoroutineContext = parent.coroutineContext,
    block: suspend () -> Unit
  ) {
    parent.launchWithLoading(context = context, block = block)
  }

  protected fun RawThingResponse?.onNotSuccess() = apply {
    this?.onFailure { code, payload ->
      emitToast("Set failure $code, $payload")
      appLogger.e("Failed to set invoke or set: $code, $payload")
    } ?: kotlin.run {
      emitToast("Cannot find the Thing")
    }
  }
}
