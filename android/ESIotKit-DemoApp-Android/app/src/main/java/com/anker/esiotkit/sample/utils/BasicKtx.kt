package com.anker.esiotkit.sample.utils

import android.content.res.Resources
import android.util.TypedValue
import com.anker.esiotkit.core.entities.device.ESIotDevice

/**
 * @version 2023/10/16
 */
fun Int.toDp() = TypedValue.applyDimension(
  TypedValue.COMPLEX_UNIT_DIP,
  this.toFloat(),
  Resources.getSystem().displayMetrics
)

fun ESIotDevice.getParamValue(type: Int): String? {
  return params.orEmpty().find { it.paramType == type }?.paramValue
}