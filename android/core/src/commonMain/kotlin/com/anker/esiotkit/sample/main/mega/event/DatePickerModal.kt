package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * @version 2024/6/12
 */
@Composable
fun DatePickerButton(
  modifier: Modifier = Modifier,
  selectedDate: Long,
  onDateChange: (Long) -> Unit,
  onClick: () -> Unit
) {
  val formattedDate = formatDate(selectedDate)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier = modifier
      .background(Color.White)
      .padding(vertical = 4.dp)
  ) {
    IconButton(
      onClick = { onDateChange(selectedDate - 24 * 60 * 60 * 1000) } // 减少一天
    ) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Day")
    }

    OutlinedButton(
      onClick =  onClick,
      modifier = modifier
        .height(36.dp)
        .clip(RoundedCornerShape(18.dp)),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
      Text(text = formattedDate)
    }

    IconButton(
      onClick = { onDateChange(selectedDate + 24 * 60 * 60 * 1000) } // 增加一天
    ) {
      Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Day")
    }
  }
}

@Composable
fun DatePickerModal(
  modifier: Modifier = Modifier,
  selectedDateMillis: Long,
  onDismiss: () -> Unit,
  onDateSelected: (Long?) -> Unit,
  eventDates: List<LocalDate>
) {
  var tempSelectedDate by remember { mutableStateOf(getStartOfDayMillis(selectedDateMillis) ) }
  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = {
        onDateSelected(tempSelectedDate)
        onDismiss()
      }) {
        Text("OK")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  ) {
    CustomDatePicker(
      modifier = modifier,
      selectedDateMillis = selectedDateMillis,
      onDateSelected = {
          date -> tempSelectedDate = date
      },
      selectedDate = tempSelectedDate, eventDates = eventDates
    )
  }
}

@Composable
fun CustomDatePicker(
  modifier: Modifier = Modifier,
  selectedDateMillis: Long?,
  onDateSelected: (Long) -> Unit,
  selectedDate: Long?,
  eventDates: List<LocalDate>
) {
  val currentMonth = remember {
    mutableStateOf(selectedDateMillis?.let {
      Instant.fromEpochMilliseconds(selectedDateMillis).toLocalDateTime(TimeZone.currentSystemDefault()).monthNumber
    } ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).monthNumber)
  }
  val currentYear = remember {
    mutableStateOf(selectedDateMillis?.let {
      Instant.fromEpochMilliseconds(selectedDateMillis).toLocalDateTime(TimeZone.currentSystemDefault()).year
    } ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year)
  }
  val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

  Column(
    modifier = modifier
      .padding(16.dp)
      .background(MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium)
      .padding(16.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = modifier.fillMaxWidth()
    ) {
      IconButton(onClick = {
        if (currentMonth.value == 1) {
          currentMonth.value = 12
          currentYear.value -= 1
        } else {
          currentMonth.value -= 1
        }
      }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Month")
      }
      Text(
        text = "${
          Month(currentMonth.value).name.lowercase().replaceFirstChar { it.uppercase() }
        } ${currentYear.value}",
        style = MaterialTheme.typography.titleMedium
      )
      IconButton(onClick = {
        if (currentMonth.value == 12) {
          currentMonth.value = 1
          currentYear.value += 1
        } else {
          currentMonth.value += 1
        }
      }) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Month")
      }
    }
    Spacer(modifier = modifier.height(16.dp))
    val daysInMonth = getDaysInMonth(currentYear.value, currentMonth.value)
    val firstDayOfWeek:Int = LocalDate(currentYear.value, currentMonth.value, 1).dayOfWeek.isoDayNumber % 7
    Column {
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth()
      ) {
        listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
          Text(
            text = day,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.weight(1f),
            textAlign = TextAlign.Center
          )
        }
      }
      for(week in 0..5) {
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = modifier.fillMaxWidth()
        ) {
          for(day in 0..6) {
            val dayOfMonth = week * 7 + day - firstDayOfWeek + 1
            if (dayOfMonth > 0 && dayOfMonth <= daysInMonth) {
              val date = LocalDate(currentYear.value, currentMonth.value, dayOfMonth)
              val hasEvent = eventDates.contains(date)
              val isSelected =
                date.toEpochMilliseconds() == selectedDate

              val isToday = date == today
              Box(
                modifier = modifier
                  .padding(4.dp)
                  .size(34.dp)
                  .clickable {
                    onDateSelected(date.toEpochMilliseconds())
                  }
                  .then(
                    if (isSelected) modifier.background(
                      MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                      shape = CircleShape
                    ) else modifier
                  )
                  .then(
                    if (isToday) modifier.border(
                      BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                      shape = CircleShape
                    ) else modifier
                  ),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = dayOfMonth.toString(),
                  color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black
                )
                if (hasEvent) {
                  Box(
                    modifier = modifier
                      .size(4.dp)
                      .background(MaterialTheme.colorScheme.primary, CircleShape)
                      .align(Alignment.BottomCenter)
                  )
                }
              }
            } else {
              Box(
                modifier = modifier
                  .padding(4.dp)
                  .size(36.dp)
              )
            }
          }
        }
      }
    }
  }
}

fun getDaysInMonth(year: Int, month: Int): Int {
  return when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> throw IllegalArgumentException("Invalid month: $month")
  }
}

fun isLeapYear(year: Int): Boolean {
  return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

fun LocalDate.toEpochMilliseconds(): Long {
  return this.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
}

fun getStartOfDayMillis(timestamp: Long): Long {
  val instant = Instant.fromEpochMilliseconds(timestamp)
  val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
  val startOfDay = localDateTime.date.atStartOfDayIn(TimeZone.currentSystemDefault())
  return startOfDay.toEpochMilliseconds()
}

fun formatDate(timestamp: Long): String {
  val instant = Instant.fromEpochMilliseconds(timestamp)
  val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
  val month = localDateTime.monthNumber // 获取月份的数字表示
  val day = localDateTime.dayOfMonth
  val year = localDateTime.year
  return "$month 月 $day, $year"
}