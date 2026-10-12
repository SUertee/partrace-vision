package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.StatusFilterValue

private val BrandBlue = Color(0xFF005D8E)

/**
 * T-UI-01: 搜索行
 *
 * 布局：用户头像 | 搜索框（含清除按钮） | 添加设备按钮
 *
 * 状态过滤和排序保留在参数中以维持接口兼容，但不显示在此行。
 */
@Composable
fun SearchAndFilterRow(
  searchQuery: String,
  statusFilter: StatusFilterValue,
  sortOption: SortOption,
  onSearchQueryChange: (String) -> Unit,
  onStatusFilterChange: (StatusFilterValue) -> Unit,
  onSortOptionChange: (SortOption) -> Unit,
  onClearSearch: () -> Unit,
  onAddDeviceClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White)
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Avatar
    Icon(
      imageVector = Icons.Default.Person,
      contentDescription = "Profile",
      tint = Color.White,
      modifier = Modifier
        .size(36.dp)
        .clip(CircleShape)
        .background(BrandBlue)
        .padding(6.dp)
    )

    Spacer(modifier = Modifier.width(10.dp))

    // Search field
    TextField(
      value = searchQuery,
      onValueChange = onSearchQueryChange,
      modifier = Modifier.weight(1f),
      placeholder = {
        Text("Search name, SN, PN...", color = Color(0xFFAAAAAA), fontSize = 13.sp)
      },
      leadingIcon = {
        Icon(
          imageVector = Icons.Default.Search,
          contentDescription = null,
          tint = Color(0xFFAAAAAA),
          modifier = Modifier.size(18.dp)
        )
      },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = onClearSearch) {
            Icon(
              imageVector = Icons.Default.Clear,
              contentDescription = "Clear",
              tint = Color(0xFFAAAAAA),
              modifier = Modifier.size(16.dp)
            )
          }
        }
      },
      shape = RoundedCornerShape(20.dp),
      colors = TextFieldDefaults.colors(
        focusedContainerColor = Color(0xFFF2F4F7),
        unfocusedContainerColor = Color(0xFFF2F4F7),
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
      ),
      textStyle = TextStyle(fontSize = 13.sp),
      singleLine = true,
    )

    Spacer(modifier = Modifier.width(8.dp))

    // Add device icon button
    IconButton(
      onClick = onAddDeviceClick,
      modifier = Modifier
        .size(36.dp)
        .clip(CircleShape)
        .background(BrandBlue)
    ) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "Add Device",
        tint = Color.White,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}
