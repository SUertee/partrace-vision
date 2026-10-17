package com.anker.esiotkit.sample.main.station.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.ui.components.StationBottomBar
import com.anker.esiotkit.sample.main.station.ui.components.StationIcon
import com.anker.esiotkit.sample.main.station.ui.components.StationSymbol
import com.anker.esiotkit.sample.main.station.records.StationReviewRecord
import com.anker.esiotkit.sample.main.station.records.StationReviewResult
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.station_brand_mark
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun ProfileScreen(
  onTab: (String) -> Unit,
  onSettings: () -> Unit,
  onConfigureService: () -> Unit
) {
  ReviewRecordsHost { result, onRetry, onReview ->
    ProfileContent(result, onTab, onSettings, onRetry, onConfigureService, onReview)
  }
}

/** Production supplies backend records; only the Compose preview supplies design examples. */
@Composable
internal fun ProfileContent(
  result: StationReviewResult?,
  onTab: (String) -> Unit = {},
  onSettings: () -> Unit = {},
  onRetry: () -> Unit = {},
  onConfigureService: () -> Unit = {},
  onReview: (StationReviewRecord) -> Unit = {}
) {
  var search by rememberSaveable { mutableStateOf("") }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  fun dismissKeyboard() {
    focusManager.clearFocus()
    keyboard?.hide()
  }
  Box(Modifier.fillMaxSize().imePadding()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
      Spacer(Modifier.height(28.dp))
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        StationBrandMark(Modifier.size(50.dp))
        Spacer(Modifier.width(12.dp))
        Text("驿站追迹", color = Color.White, fontSize = 30.sp, lineHeight = 40.sp,
          fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(onClick = { dismissKeyboard(); onSettings() }, modifier = Modifier.size(44.dp).semantics { contentDescription = "设置" }) {
          StationIcon(StationSymbol.Settings, modifier = Modifier.size(26.dp), color = Color.White)
        }
      }
      Spacer(Modifier.height(48.dp))
      ProfileRecordSearch(search, { search = it })
      Spacer(Modifier.height(16.dp))
      Text("全部", modifier = Modifier.padding(start = 32.dp).semantics { heading() },
        color = Color.Black, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(10.dp))
      ReviewRecordsList(result, search, onRetry, onConfigureService, onReview)
    }
    StationBottomBar("profile", { dismissKeyboard(); onTab(it) }, Modifier.align(Alignment.BottomCenter))
  }
}

@Composable
private fun ProfileRecordSearch(search: String, onSearchChanged: (String) -> Unit) {
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  fun finishSearch() {
    focusManager.clearFocus()
    keyboard?.hide()
  }
  BasicTextField(
    value = search,
    onValueChange = onSearchChanged,
    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
      .background(Color.White, CircleShape).border(1.dp, Color(0xFFE2E2E2), CircleShape)
      .semantics { contentDescription = "检索记录，支持事件编号、备注或时间" },
    singleLine = true,
    textStyle = TextStyle(color = Color(0xFF333333), fontSize = 20.sp, lineHeight = 26.sp),
    cursorBrush = Brush.verticalGradient(listOf(Color(0xFF50A7FA), Color(0xFF50A7FA))),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { finishSearch() }),
    decorationBox = { input ->
      Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(vertical = 5.dp)) {
          if (search.isEmpty()) Text("检索记录", color = Color(0xFF9C9C9C), fontSize = 20.sp, lineHeight = 26.sp)
          input()
        }
        if (search.isNotEmpty()) TextButton(onClick = { onSearchChanged("") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
          Text("清除", fontSize = 12.sp)
        }
        IconButton(onClick = { finishSearch() }, modifier = Modifier.size(40.dp).semantics { contentDescription = "搜索记录" }) {
          StationIcon(StationSymbol.Search, modifier = Modifier.size(24.dp), color = Color(0xFF50A7FA))
        }
      }
    }
  )
}

/** Original artwork embedded in the supplied Rectangle.svg, decoded without altering its pixels. */
@Composable
private fun StationBrandMark(modifier: Modifier) {
  Box(modifier.background(
    Brush.radialGradient(listOf(Color(0xFF69C4FF), Color(0xFF43A7ED))), CircleShape
  ), contentAlignment = Alignment.Center) {
    Image(painterResource(Res.drawable.station_brand_mark), contentDescription = null,
      modifier = Modifier.size(width = 30.dp, height = 34.dp), contentScale = ContentScale.Fit)
  }
}
