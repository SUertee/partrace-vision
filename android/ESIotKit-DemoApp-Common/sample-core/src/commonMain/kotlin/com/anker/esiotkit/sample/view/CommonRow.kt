package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material3.MaterialTheme

import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.ui.component.ConfirmDialog

@Composable
fun CommonRow(
  title: String,
  description: String? = null,
  content: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  rightContent: @Composable (() -> Unit) = {
    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
  },
  onClick: (() -> Unit)? = null
) {
  Column(
    modifier = modifier.fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .padding(16.dp)
  ) {
    Row(
      modifier = modifier.fillMaxWidth()
        .clickable { onClick?.let { it() } },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(
        modifier = Modifier.weight(1f).padding(end = 16.dp)
      ) {
        Text(
          text = title, fontSize = 16.sp
        )
        if (description != null) {
          Text(
            text = description, fontSize = 14.sp, color = Color.Gray
          )
        }
      }
      rightContent()
    }
    if (content != null) {
      Spacer(modifier = Modifier.height(16.dp))
      content()
    }

  }
}

@Composable
fun CommonSwitchRow(
  title: String,
  description: String? = null,
  content: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  onClick: (Boolean) -> Unit,
  state: Boolean,

) {
  CommonRow(
    title =title,
    description = description,
    content = content,
    modifier = modifier,
    rightContent = {
      Switch(
        checked = state,
        onCheckedChange = {
          onClick(it)
        }
      )
    }
  )
}

@Composable
fun CommonRowItem(
  title: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  rightContent: @Composable (() -> Unit) = {
    Icon(
      imageVector = Icons.AutoMirrored.Filled.ArrowForward,
      contentDescription = null,
      tint = if (enabled) Color.Unspecified else Color.LightGray
    )
  },
  onClick: (() -> Unit)? = null
) {
  val textColor = if (enabled) Color.Black else Color.LightGray
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clickable(enabled = enabled) { onClick?.let { it() } }
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      fontSize = 16.sp,
      color = textColor,
      modifier = Modifier.weight(1f).padding(end = 16.dp),
      lineHeight = 24.sp
    )
    rightContent()
  }
}

@Composable
fun OptionRowItem(
  title: String,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  CommonRowItem(
    title = title,
    rightContent = {
      if (isSelected) {
        androidx.compose.material3.Icon(
          imageVector = Icons.Default.Check,
          contentDescription = null,
        )
      }
    },
    onClick = onClick
  )
}

@Composable
fun CommonLeftOptionRow(
  title: String,
  description: String? = null,
  selected: Boolean,
  onClick: () -> Unit,
  rightIcon: @Composable (() -> Unit)? = null,
  onIconClick: (() -> Unit)? = null
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .clickable { onClick() }
      .padding(16.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    RadioButton(
      selected = selected,
      onClick = null
    )
    Column(
      modifier = Modifier.weight(1f).padding(start = 8.dp)
    ) {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      if (description != null) {
        Text(text = description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
      }
    }
    if (rightIcon != null) {
      IconButton(onClick = { onIconClick?.let { it() } }, modifier = Modifier.size(36.dp)) {
        rightIcon()
      }
    }
  }

}

@Composable
fun CommonRightOptionRow(
  title: String,
  description: String? = null,
  note: String? = null,
  selected: Boolean,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .clickable { onClick() }
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Column(
      modifier = Modifier.weight(1f).padding(end = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(text = title, fontSize = 16.sp)
      if (description != null) {
        Text(text = description, fontSize = 14.sp)
      }
      if (note != null) {
        Text(text = note, fontSize = 12.sp, color = Color.Gray)
      }
    }
    RadioButton(
      selected = selected,
      onClick = null
    )
  }
}
