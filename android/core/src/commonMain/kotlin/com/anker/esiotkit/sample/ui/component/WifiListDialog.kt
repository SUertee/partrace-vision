package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.anker.esiotkit.domain.add_device.wifi.model.WifiNetwork
import com.anker.esiotkit.support.log.appLogger

@Composable
fun WifiListDialog(
  wifiList: List<WifiNetwork>,
  loading: Boolean,
  onPick: (String) -> Unit,
  onDismiss: () -> Unit
) {
  LaunchedEffect(Unit) {
    appLogger.i("WifiListDialog/onShow: count=${wifiList.size}")
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color.White,
      modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("Select Wi-Fi", fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))

        when {
          loading -> {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          wifiList.isEmpty() -> {
            Text("No Wi-Fi networks found, please enter manually", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
          }
          else -> {
            LazyColumn(modifier = Modifier.height(320.dp)) {
              items(wifiList, key = { it.ssid }) { network ->
                Row(
                  modifier = Modifier.fillMaxWidth().clickable {
                    appLogger.i("WifiListDialog/onPick: ssid=${network.ssid}")
                    onPick(network.ssid)
                  }.padding(vertical = 12.dp, horizontal = 8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  WifiSignalIcon(level = network.signalLevel, modifier = Modifier.size(20.dp))
                  Spacer(modifier = Modifier.width(12.dp))
                  Text(network.ssid, fontSize = 16.sp, modifier = Modifier.weight(1f))
                  if (!network.isOpen) {
                    Icon(Icons.Default.Lock, contentDescription = "Secured", tint = Color.Gray, modifier = Modifier.size(18.dp))
                  }
                }
              }
            }
          }
        }

        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
          shape = RoundedCornerShape(24.dp),
          colors = ButtonDefaults.buttonColors(Color.Gray)
        ) {
          Text("Cancel", color = Color.White, fontSize = 16.sp)
        }
      }
    }
  }
}
