package com.anker.esiotkit.sample.main.station.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StationBottomBar(selected: String, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
  Row(modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth().height(68.dp)
    .background(Color.White, CircleShape), verticalAlignment = Alignment.CenterVertically) {
    listOf(Triple("home", "首页", StationSymbol.Home), Triple("events", "事件", StationSymbol.Events), Triple("assistant", "AI追溯", StationSymbol.Chat), Triple("profile", "我的", StationSymbol.Person)).forEach { (route, label, symbol) ->
      Box(Modifier.weight(1f).fillMaxHeight().selectable(selected == route, role = Role.Tab, onClick = { onTab(route) })
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val color = if (selected == route) Color(0xFF50A7FA) else Color(0xFF555555)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          StationIcon(symbol, modifier = Modifier.size(24.dp), color = color)
          Text(label, color = color, fontSize = 10.sp, lineHeight = 13.sp)
        }
      }
    }
  }
}
