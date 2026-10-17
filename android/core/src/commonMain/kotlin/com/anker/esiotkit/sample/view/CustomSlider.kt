package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.net_api.response.thing.TDEnum
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.ui.composable.simpleClickable

@Composable
fun CustomSlider(
  title: String? = null,
  range: IntRange,
  sliderValue: Int,
  onValueChange: (Int) -> Unit,
  showNumbersAbove: Boolean = true,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
  ) {
    title?.let { Text(it, fontSize = 14.sp, color = Color.Gray) }
    Spacer(modifier = Modifier.height(16.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      for(i in range) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.wrapContentWidth()
        ) {
          if (showNumbersAbove) {
            Text(
              text = i.toString(),
              fontSize = 16.sp,
              color = if (sliderValue == i) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
          }
          Box(
            modifier = Modifier
              .size(24.dp)
              .background(
                if (sliderValue == i) MaterialTheme.colorScheme.primary else Color.Gray.copy(0.1f),
                CircleShape
              )
              .clickable { onValueChange(i) },
            contentAlignment = Alignment.Center
          ) {
            if (sliderValue == i) {
              Box(
                modifier = Modifier
                  .size(16.dp)
                  .background(Color.White, CircleShape)
              )
            }
          }
        }
        if (i < range.last) {
          Box(
            modifier = Modifier.height(24.dp).weight(1f),
            contentAlignment = Alignment.Center
          ) {
            Divider(
              color = Color.Gray.copy(0.1f),
              modifier = Modifier.fillMaxWidth().height(2.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
fun EnumCustomSlider(
  title: String? = null,
  enumList: List<TDEnum>,
  value: String,
  onValueChange: (String) -> Unit,
  showNumbersAbove: Boolean = true,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth().padding(horizontal = 16.dp)
  ) {
    title?.let { Text(it, fontSize = 14.sp, style = MaterialTheme.typography.titleMedium) }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      enumList.forEachIndexed { index, enum ->
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.wrapContentWidth()
        ) {
          if (showNumbersAbove) {
            Text(
              text = enum.desc,
              fontSize = 16.sp,
              color = if (value == enum.value) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
          }
          Box(
            modifier = Modifier
              .size(24.dp)
              .background(
                if (value == enum.value) MaterialTheme.colorScheme.primary else Color.Gray.copy(0.1f),
                CircleShape
              )
              .simpleClickable { onValueChange(enum.value) },
            contentAlignment = Alignment.Center
          ) {
            if (value == enum.value) {
              Box(
                modifier = Modifier
                  .size(16.dp)
                  .background(Color.White, CircleShape)
              )
            }
          }
        }
        if (index < enumList.lastIndex) {
          Box(
            modifier = Modifier.height(24.dp).weight(1f),
            contentAlignment = Alignment.Center
          ) {
            Divider(
              color = Color.Gray.copy(0.1f),
              modifier = Modifier.fillMaxWidth().height(2.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
fun DetectionSensitivitySelector(
  megaDevice: MegaDevice,
  identifier: String,
) {
  PropertyThingContainer(
    megaDevice = megaDevice,
    identifier = ThingIdentifier.DETECTION_SENSITIVITY
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
        .padding(16.dp)
    ) {
      PropertyEnumSlider(
        megaDevice = megaDevice,
        identifier = identifier,
        title = "Detection Sensitivity",
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(text = "Low", fontSize = 14.sp, color = Color.Gray)
        Text(text = "High", fontSize = 14.sp, color = Color.Gray)
      }
    }
  }
}