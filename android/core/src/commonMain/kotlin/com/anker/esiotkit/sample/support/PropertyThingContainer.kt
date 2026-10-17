package com.anker.esiotkit.sample.support

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice

@Composable
fun PropertyThingsContainer(
  megaDevice: MegaDevice,
  identifiers: List<String>,
  any: Boolean,
  content: @Composable () -> Unit
) {
  val show = remember {
    if (any) {
      identifiers.any { megaDevice.hasProperty(it) }
    } else {
      identifiers.all { megaDevice.hasProperty(it) }
    }
  }
  if (show) {
    content()
  }
}

@Composable
fun PropertyThingContainer(
  modifier: Modifier = Modifier,
  megaDevice: MegaDevice,
  identifier: String,
  content: @Composable (identifier: String) -> Unit
) {
  val has = remember(identifier) {
    megaDevice.hasProperty(identifier)
  }
  if (has) {
    content(identifier)
  }
}

@Composable
fun ActionThingContainer(
  modifier: Modifier = Modifier,
  megaDevice: MegaDevice,
  identifier: String,
  content: @Composable () -> Unit
) {
  val has = remember(identifier) {
    megaDevice.hasAction(identifier)
  }
  if (has) {
    Column(modifier = modifier) {
      content()
    }
  }
}