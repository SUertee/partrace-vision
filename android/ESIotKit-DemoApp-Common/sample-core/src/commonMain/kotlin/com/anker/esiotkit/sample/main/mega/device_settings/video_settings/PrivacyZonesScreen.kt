package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_privacy_zones_title

/**
 * Privacy Zones Screen - Entry Point
 * @version 2026/03/02
 */
class PrivacyZonesScreen(
  private val viewModel: VideoSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_privacy_zones_title

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Icon
      Box(
        modifier = Modifier
          .padding(top = 48.dp)
          .background(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(50.dp)
          )
          .padding(24.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Info,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          tint = MaterialTheme.colorScheme.primary
        )
      }

      // Title
      Text(
        text = "Privacy Zones",
        fontSize = 24.sp,
        color = Color.Black,
        textAlign = TextAlign.Center
      )

      // Description
      Text(
        text = "Set up privacy zones to block out areas you don't want to record or monitor.",
        fontSize = 16.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
      )

      // Configure Button
      CommonButton(
        text = "Configure Privacy Zones",
        onClick = {
          navigator.push(PrivacyZonesConfigScreen(viewModel = viewModel))
        }
      )

      // Coming Soon Box
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            color = Color(0xFFFFF3E0),
            shape = RoundedCornerShape(8.dp)
          )
          .padding(16.dp)
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "⚙️ Basic Configuration Available",
            fontSize = 18.sp,
            color = Color(0xFFF57C00)
          )
          Text(
            text = "You can now set privacy zones using coordinates.\n\nVisual editor with live preview is coming soon:\n• Live video stream integration\n• Interactive zone drawing tools\n• Real-time preview",
            fontSize = 14.sp,
            color = Color(0xFF6D4C41),
            lineHeight = 20.sp
          )
        }
      }

      // Feature Info
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            color = Color(0xFFF5F5F5),
            shape = RoundedCornerShape(8.dp)
          )
          .padding(16.dp)
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Feature Details:",
            fontSize = 16.sp,
            color = Color.Black
          )
          Text(
            text = "• Draw up to 4 privacy zones on video\n• Zones are blurred in recordings\n• Real-time preview while drawing\n• Save and edit existing zones",
            fontSize = 14.sp,
            color = Color.Gray,
            lineHeight = 20.sp
          )
        }
      }
    }
  }
}