package com.anker.esiotkit.sample.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.sample.view.CommonOutfittedButton
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * @version 2024/8/5
 */
@Composable
fun PagerIndicator(
  modifier: Modifier = Modifier,
  currentPage: Int,
  list: List<PagerHeader>,
  onHeaderClick: (Int) -> Unit
) {
  Row(modifier = modifier.padding(vertical = 8.dp)) {
    list.forEachIndexed { index, pagerHeader ->
      PagerIndicatorItem(currentPage, index, pagerHeader, onHeaderClick)
    }
  }
}

@Composable
private fun PagerIndicatorItem(
  currentPage: Int,
  index: Int,
  pagerHeader: PagerHeader,
  onHeaderClick: (Int) -> Unit
) {
  Box(modifier = Modifier.padding(horizontal = 4.dp)) {
    val color by animateColorAsState(
      if (currentPage == index) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
      }
    )
    // Spacer(Modifier.matchParentSize().background(color = color, shape = RoundedCornerShape(16.dp)))
    CommonOutfittedButton(text = pagerHeader.title, color = color, modifier = Modifier.padding(8.dp)) {
      onHeaderClick(index)
    }
  }
}

data class PagerHeader(
  val title: String
)

@Preview
@Composable
fun PagerIndicatorPreview() {
  val headers = listOf(
    PagerHeader("Header 1"),
    PagerHeader("Header 2"),
    PagerHeader("Header 3")
  )
  PagerIndicator(
    currentPage = 1,
    list = headers,
    onHeaderClick = {}
  )
}