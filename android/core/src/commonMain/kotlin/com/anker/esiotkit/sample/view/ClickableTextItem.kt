package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ClickableTextItem(
  text: String,
  onClick: () -> Unit,
  color: Color = Color.LightGray
) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth() // 让 Text 充满父容器
            .background(color) // 设置背景颜色
            .padding(6.dp) // 设置内边距
            .clickable { onClick() }, // 添加点击事件
        color = Color.Black // 文字颜色
    )
}