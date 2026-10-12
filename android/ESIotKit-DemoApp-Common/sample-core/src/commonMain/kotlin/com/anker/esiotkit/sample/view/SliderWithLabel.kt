package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SliderWithLabel(
  value: Int,
  valueRange: IntRange,
  onValueChange: (Int) -> Unit,
  onValueChangeFinished: ((Int) -> Unit)? = null,
  label: (Int) -> String,
  minValue: Int,
  maxValue: Int
) {
  var sliderPosition by remember { mutableStateOf(value.toFloat()) }
  var currentValue by remember { mutableStateOf(value) }
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .padding(16.dp)
  ) {
    Box(
      modifier = Modifier.fillMaxWidth()
    ) {
      Text(
        text = currentValue.toString(),
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.CenterStart).offset(
          x = calculateOffset(sliderPosition, valueRange.first.toFloat()..valueRange.last.toFloat()),
          y = (-20).dp
        )
      )
      Slider(
        value = sliderPosition,
        onValueChange = {
          sliderPosition = it
          currentValue = it.roundToInt().coerceIn(valueRange)
        },
        onValueChangeFinished = {
          onValueChange(currentValue)
        },
        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        steps = valueRange.last - valueRange.first,
        colors = SliderDefaults.colors(
          thumbColor = MaterialTheme.colorScheme.primary,
          activeTrackColor = MaterialTheme.colorScheme.primary,
          inactiveTrackColor = Color.Gray
        ),
        modifier = Modifier.fillMaxWidth()
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = label(minValue),
        fontSize = 16.sp,
      )
      Text(
        text = label(maxValue),
        fontSize = 16.sp,
      )
    }

  }
  LaunchedEffect(value) {
    sliderPosition = value.toFloat()
    currentValue = value
    onValueChangeFinished?.let { it(value) }
  }
}

@Composable
fun calculateOffset(value: Float, valueRange: ClosedFloatingPointRange<Float>): Dp {
  val range = valueRange.endInclusive - valueRange.start
  val percentage = (value - valueRange.start) / range
  return with(LocalDensity.current) { (percentage * 280.dp.toPx()).toDp() }
}