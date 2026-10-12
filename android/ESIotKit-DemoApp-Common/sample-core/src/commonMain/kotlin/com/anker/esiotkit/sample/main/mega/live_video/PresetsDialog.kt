package com.anker.esiotkit.sample.main.mega.live_video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Presets Dialog for PTZ camera positions
 * Allows users to save and jump to preset camera positions
 */

/**
 * Preset position item for dialog display
 */
data class PresetDialogItem(
  val id: String,
  val name: String,
  val isSet: Boolean = false
)

// Keep old name for backward compatibility
typealias PresetPosition = PresetDialogItem

@Composable
fun PresetsDialog(
  presets: List<PresetDialogItem>,
  onDismiss: () -> Unit,
  onPresetSelected: (PresetDialogItem) -> Unit,
  onPresetDeleted: (PresetDialogItem) -> Unit,
  onSaveCurrentPosition: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(16.dp))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = "Camera Presets",
        fontSize = 18.sp,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp)
      )

      // Only show presets that are set
      val setPresets = presets.filter { it.isSet }

      if (setPresets.isEmpty()) {
        Text(
          text = "No presets saved yet",
          fontSize = 14.sp,
          color = Color.Gray,
          modifier = Modifier.padding(vertical = 16.dp)
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
          items(setPresets) { preset ->
            PresetItem(
              preset = preset,
              onJump = {
                onPresetSelected(preset)
                onDismiss()
              },
              onDelete = { onPresetDeleted(preset) }
            )
            if (setPresets.indexOf(preset) < setPresets.size - 1) {
              HorizontalDivider()
            }
          }
        }
      }

      Button(
        onClick = {
          onSaveCurrentPosition()
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
      ) {
        Text("Save Current Position")
      }

      Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text("Close")
      }
    }
  }
}

@Composable
private fun PresetItem(
  preset: PresetDialogItem,
  onJump: () -> Unit,
  onDelete: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onJump)
      .padding(vertical = 8.dp, horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = "Jump",
        tint = MaterialTheme.colorScheme.primary
      )
      Text(
        text = preset.name,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 8.dp)
      )
    }
    IconButton(onClick = onDelete) {
      Icon(
        imageVector = Icons.Default.Delete,
        contentDescription = "Delete",
        tint = Color.Red
      )
    }
  }
}
