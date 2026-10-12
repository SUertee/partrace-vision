package com.anker.esiotkit.sample.support

import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice

/**
 * @version 2024/10/25
 */
fun MegaDevice.propertyString(identifier: String, default: String = ""): String {
  return getProperty(identifier)?.get()?.asString() ?: default
}

fun MegaDevice.propertyInt(identifier: String, default: Int = 0): Int {
  return getProperty(identifier)?.get()?.asInt() ?: default
}

fun MegaDevice.propertyBoolean(identifier: String, default: Boolean = false): Boolean {
  return getProperty(identifier)?.get()?.asBoolean() ?: default
}
