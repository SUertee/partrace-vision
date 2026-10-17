package com.anker.esiotkit.sample.main.mega.device_settings.general

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
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
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.view.BaseScreen
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_mounting_guide_title

/**
 * Mounting Guide Screen - Simplified Version
 * @version 2026/03/02
 */
class MountingGuideScreen(
  private val viewModel: DeviceGeneralViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_mounting_guide_title

  @Composable
  override fun Content() = BaseColumn {
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
          imageVector = Icons.Default.Construction,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          tint = MaterialTheme.colorScheme.primary
        )
      }

      // Title
      Text(
        text = "Mounting Guide",
        fontSize = 24.sp,
        color = Color.Black,
        textAlign = TextAlign.Center
      )

      // Description
      Text(
        text = "Find the perfect mounting spot with real-time signal strength feedback and installation tips.",
        fontSize = 16.sp,
        color = Color.Gray,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
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
            text = "🚧 Coming Soon",
            fontSize = 18.sp,
            color = Color(0xFFF57C00)
          )
          Text(
            text = "This feature requires:\n• Live video stream for preview\n• Real-time WiFi signal monitoring\n• Interactive installation wizard\n• Location-based recommendations",
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
            text = "• Real-time camera preview\n• WiFi signal strength indicator\n• Optimal mounting height suggestions\n• Field of view visualization\n• Step-by-step installation guide",
            fontSize = 14.sp,
            color = Color.Gray,
            lineHeight = 20.sp
          )
        }
      }

      // Installation Tips
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            color = Color(0xFFE3F2FD),
            shape = RoundedCornerShape(8.dp)
          )
          .padding(16.dp)
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "📋 Quick Tips:",
            fontSize = 16.sp,
            color = Color(0xFF1976D2)
          )
          Text(
            text = "• Mount at 7-10 feet height\n• Ensure WiFi signal is strong\n• Angle camera 15-30° downward\n• Avoid direct sunlight\n• Test view before final installation",
            fontSize = 14.sp,
            color = Color(0xFF424242),
            lineHeight = 20.sp
          )
        }
      }
    }
  }
}