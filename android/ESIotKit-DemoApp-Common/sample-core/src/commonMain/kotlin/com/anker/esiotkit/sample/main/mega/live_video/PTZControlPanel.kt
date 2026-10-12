package com.anker.esiotkit.sample.main.mega.live_video

import kotlin.time.Clock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PTZDirection {
  UP, DOWN, LEFT, RIGHT, STOP
}

@Composable
fun PTZControlPanel(
  onDismiss: () -> Unit,
  onDirectionControl: (PTZDirection) -> Unit,   // 单击：cmdType=1
  onLongPressStart: ((PTZDirection) -> Unit)? = null,  // 长按开始：cmdType=2
  onLongPressStop: ((PTZDirection) -> Unit)? = null,   // 长按结束：cmdType=3
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
      .padding(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Close button at top-right
    Row(
      modifier = Modifier.width(180.dp),
      horizontalArrangement = Arrangement.End
    ) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = "Close",
        tint = Color.White,
        modifier = Modifier
          .size(22.dp)
          .clickable { onDismiss() }
      )
    }

    // Up
    PTZButton(direction = PTZDirection.UP, onTap = onDirectionControl,
      onLongPressStart = onLongPressStart, onLongPressStop = onLongPressStop) {
      Icon(Icons.Default.KeyboardArrowUp, "Up", tint = Color.White)
    }

    // Left + Right
    Row(
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(vertical = 4.dp)
    ) {
      PTZButton(direction = PTZDirection.LEFT, onTap = onDirectionControl,
        onLongPressStart = onLongPressStart, onLongPressStop = onLongPressStop) {
        Icon(Icons.Default.KeyboardArrowLeft, "Left", tint = Color.White)
      }
      Spacer(Modifier.width(64.dp))
      PTZButton(direction = PTZDirection.RIGHT, onTap = onDirectionControl,
        onLongPressStart = onLongPressStart, onLongPressStop = onLongPressStop) {
        Icon(Icons.Default.KeyboardArrowRight, "Right", tint = Color.White)
      }
    }

    // Down
    PTZButton(direction = PTZDirection.DOWN, onTap = onDirectionControl,
      onLongPressStart = onLongPressStart, onLongPressStop = onLongPressStop) {
      Icon(Icons.Default.KeyboardArrowDown, "Down", tint = Color.White)
    }

    // Hint
    Text(
      text = "Tap to move once · Hold to move continuously",
      fontSize = 11.sp,
      color = Color.White.copy(alpha = 0.7f),
      modifier = Modifier.padding(top = 8.dp)
    )
  }
}

// Keep backward-compatible name for MegaLiveVideoScreen
@Composable
fun PTZControlDialog(
  onDismiss: () -> Unit,
  onDirectionControl: (PTZDirection) -> Unit,
  onLongPressStart: ((PTZDirection) -> Unit)? = null,
  onLongPressStop: ((PTZDirection) -> Unit)? = null,
) {
  Box(
    contentAlignment = Alignment.Center
  ) {
    PTZControlPanel(
      onDismiss = onDismiss,
      onDirectionControl = onDirectionControl,
      onLongPressStart = onLongPressStart,
      onLongPressStop = onLongPressStop,
    )
  }
}

@Composable
private fun PTZButton(
  direction: PTZDirection,
  onTap: (PTZDirection) -> Unit,
  onLongPressStart: ((PTZDirection) -> Unit)? = null,
  onLongPressStop: ((PTZDirection) -> Unit)? = null,
  content: @Composable () -> Unit
) {
  var isPressed by remember { mutableStateOf(false) }

  Box(
    modifier = Modifier
      .size(52.dp)
      .background(
        if (isPressed) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.25f),
        CircleShape
      )
      .pointerInput(direction) {
        detectTapGestures(
          onPress = { _ ->
            isPressed = true
            val longPressThreshold = viewConfiguration.longPressTimeoutMillis
            val startTime = Clock.System.now().toEpochMilliseconds()
            // 等待手指抬起
            tryAwaitRelease()
            isPressed = false
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (elapsed >= longPressThreshold) {
              // 长按：已在 onLongPress 发出 start，这里发 stop
              onLongPressStop?.invoke(direction)
            } else {
              // 单击：发 cmdType=1
              onTap(direction)
            }
          },
          onLongPress = {
            // 长按开始：cmdType=2
            onLongPressStart?.invoke(direction)
          }
        )
      },
    contentAlignment = Alignment.Center
  ) {
    content()
  }
}
