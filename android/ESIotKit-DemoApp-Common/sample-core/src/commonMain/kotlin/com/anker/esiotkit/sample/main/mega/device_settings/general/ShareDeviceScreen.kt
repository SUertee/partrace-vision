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
import androidx.compose.material.icons.filled.Group
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
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_share_device_title

/**
 * Share Device Screen - Simplified Version
 * @version 2026/03/02
 */
class ShareDeviceScreen(
  private val viewModel: DeviceGeneralViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_share_device_title

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
          imageVector = Icons.Default.Group,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          tint = MaterialTheme.colorScheme.primary
        )
      }

      // Title
      Text(
        text = "Share Device",
        fontSize = 24.sp,
        color = Color.Black,
        textAlign = TextAlign.Center
      )

      // Description
      Text(
        text = "Share your device with family members and friends to let them view live streams and receive notifications.",
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
            text = "This feature requires:\n• Family/Home management system\n• User permission management\n• Invitation system (email/QR code)\n• Access level configuration\n• Multi-user synchronization",
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
            text = "• Share via email or QR code\n• Set access permissions (Admin/Guest)\n• Manage shared users list\n• Revoke access anytime\n• Shared devices sync across accounts",
            fontSize = 14.sp,
            color = Color.Gray,
            lineHeight = 20.sp
          )
        }
      }

      // Permission Info
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            color = Color(0xFFE8F5E9),
            shape = RoundedCornerShape(8.dp)
          )
          .padding(16.dp)
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "🔐 Access Levels:",
            fontSize = 16.sp,
            color = Color(0xFF2E7D32)
          )
          Text(
            text = "Admin: Full control including settings\nGuest: View live stream and recordings only",
            fontSize = 14.sp,
            color = Color(0xFF424242),
            lineHeight = 20.sp
          )
        }
      }
    }
  }
}