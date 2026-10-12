package com.anker.esiotkit.sample.ui.component

import androidx.compose.runtime.Composable
import com.anker.esiotkit.net_api.response.thing.TDDataType
import com.anker.esiotkit.net_api.response.thing.TDProperty

/**
 * @version 2024/8/23
 */
@Composable
fun PropertyComponent(property: TDProperty) {
  when (property.dataType?.type) {
    TDDataType.TYPE_INT -> {}
    TDDataType.TYPE_DOUBLE -> {}
    TDDataType.TYPE_TEXT -> {}
    TDDataType.TYPE_ENUM -> {}
    TDDataType.TYPE_TENUM -> {}
    TDDataType.TYPE_BOOL -> {}
  }
}