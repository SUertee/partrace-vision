package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.house.presenter.MegaHouseManager
import com.anker.esiotkit.net_api.response.HouseInfo
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * @version 2024/6/12
 */
@Composable
fun HouseList(
  modifier: Modifier = Modifier,
  houseList: List<HouseInfo>,
  currentHouseId: String,
  onHouseSelected: (String) -> Unit
) {
  // 使用 remember 缓存查找结果，避免每次重组都遍历列表
  val currentHouseName = remember(houseList, currentHouseId) {
    houseList.find { it.houseId == currentHouseId }?.houseName ?: "All Devices"
  }
  var expanded by remember { mutableStateOf(false) }

  Column(modifier = modifier) {
    Card(
      modifier = modifier.fillMaxWidth().padding(16.dp)
        .clickable {
          expanded = true
        },
      colors = CardDefaults.cardColors(containerColor = Color.White),
      shape = RoundedCornerShape(12.dp),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = currentHouseName,
          modifier = Modifier.weight(1f),
          style = TextStyle(fontSize = 16.sp)
        )
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = "",
          tint = Color.Gray
        )
      }
    }
    DropdownMenu(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .background(color = Color.White, shape = RoundedCornerShape(12.dp)),
      offset = DpOffset(0.dp, 4.dp),
      expanded = expanded,
      onDismissRequest = {
        expanded = false
      }
    ) {
      houseList.forEach { house ->
        val isSelected = currentHouseName == house.houseName
        DropdownMenuItem(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).background(
            color = if (isSelected) Color(0xFF005D8E) else Color.Transparent,
            shape = RoundedCornerShape(8.dp)
          ),
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth()
            ) {
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = Color.White,
                  modifier = Modifier.size(20.dp)
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = house.houseName,
                style = TextStyle(fontSize = 14.sp, color = if (isSelected) Color.White else Color.Black)
              )
            }
          },
          onClick = {
            expanded = false
            onHouseSelected(house.houseId)
          }
        )
      }
    }
  }
}