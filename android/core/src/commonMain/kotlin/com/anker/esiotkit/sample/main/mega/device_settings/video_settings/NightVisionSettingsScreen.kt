package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.domain.NightVisionMode
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_night_vision_title

/**
 * Night Vision Settings Screen
 * 专门的夜视模式选择界面，参考 BatteryCam SoloNightVisionActivity
 *
 * 功能：
 * - 使用 HorizontalPager 滑动选择不同夜视模式
 * - 显示每个模式的效果图和详细说明
 * - 底部指示器显示当前选择
 * - 保存按钮应用选择
 *
 * @version 2026/03/17
 */
class NightVisionSettingsScreen(
  private val viewModel: VideoSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_night_vision_title

  @OptIn(ExperimentalFoundationApi::class)
  @Composable
  override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
    val navigator = LocalNavigator.currentOrThrow
    val megaDevice = remember { viewModel.megaDevice }
    val scope = rememberCoroutineScope()

    // 获取当前夜视模式
    val currentNightVision by megaDevice.rememberIntProperty(ThingIdentifier.NIGHTVISION_TYPE)

    // 夜视模式列表
    val nightVisionModes = remember {
      listOf(
        NightVisionModeItem(
          mode = NightVisionMode.COLOR,
          modeValue = 2,
          title = "MaxColor Vision",
          subtitle = "Full-color night vision with enhanced clarity",
          description = "Capture clear, full-color footage even in low light conditions. Ideal for identifying details like clothing colors and vehicle plates.",
          iconRes = "🌈" // 可以替换为实际图片
        ),
        NightVisionModeItem(
          mode = NightVisionMode.AUTO,
          modeValue = 3,
          title = "Smart Spotlights Vision",
          subtitle = "Automatic spotlights when motion detected",
          description = "Spotlights activate automatically when motion is detected, providing clear color footage. Saves power while ensuring security.",
          iconRes = "💡"
        ),
        NightVisionModeItem(
          mode = NightVisionMode.BLACK_WHITE,
          modeValue = 1,
          title = "Infrared B&W Vision",
          subtitle = "Traditional infrared black and white",
          description = "Classic infrared night vision provides reliable monitoring in complete darkness. Uses less power than color modes.",
          iconRes = "🌙"
        ),
        NightVisionModeItem(
          mode = NightVisionMode.OFF,
          modeValue = 0,
          title = "Disable",
          subtitle = "Turn off night vision",
          description = "Night vision will be disabled. Camera will only record during daytime or with sufficient lighting.",
          iconRes = "🚫"
        )
      )
    }

    // 找到当前选中的模式索引
    val initialPage = remember(currentNightVision) {
      nightVisionModes.indexOfFirst { it.modeValue == currentNightVision }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(
      initialPage = initialPage,
      pageCount = { nightVisionModes.size }
    )

    // 当前选中的模式
    val selectedMode by remember {
      derivedStateOf { nightVisionModes[pagerState.currentPage] }
    }

    // 是否有修改
    val hasChanged by remember {
      derivedStateOf { selectedMode.modeValue != currentNightVision }
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFFF5F5F5))
    ) {
      // ViewPager 区域
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      ) {
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxSize()
        ) { page ->
          NightVisionModeCard(
            mode = nightVisionModes[page],
            isSelected = page == pagerState.currentPage
          )
        }
      }

      // 指示器
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
      ) {
        nightVisionModes.forEachIndexed { index, mode ->
          PageIndicator(
            isSelected = index == pagerState.currentPage,
            mode = mode,
            onClick = {
              scope.launch {
                pagerState.animateScrollToPage(index)
              }
            }
          )
          if (index < nightVisionModes.size - 1) {
            Spacer(modifier = Modifier.width(8.dp))
          }
        }
      }

      // 保存按钮区域
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.White)
          .padding(16.dp)
      ) {
        // 显示当前是否为默认模式
        if (!hasChanged) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              tint = Color(0xFF4CAF50),
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Current Mode",
              fontSize = 14.sp,
              color = Color(0xFF4CAF50)
            )
          }
        }

        // 应用按钮
        Button(
          onClick = {
            viewModel.setNightVision(selectedMode.mode)
            navigator.pop()
          },
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
          enabled = hasChanged,
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF005D8E),
            disabledContainerColor = Color(0xFFE0E0E0)
          ),
          shape = RoundedCornerShape(24.dp)
        ) {
          Text(
            text = if (hasChanged) "Apply Changes" else "No Changes",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }
    }

    MegaLoading(viewModel.loading)
  }
}

/**
 * 夜视模式数据模型
 */
data class NightVisionModeItem(
  val mode: NightVisionMode,
  val modeValue: Int,
  val title: String,
  val subtitle: String,
  val description: String,
  val iconRes: String  // 临时使用 emoji，实际应使用图片资源
)

/**
 * 夜视模式卡片
 */
@Composable
fun NightVisionModeCard(
  mode: NightVisionModeItem,
  isSelected: Boolean
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 24.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    // 效果图区域（使用 emoji 临时替代）
    Box(
      modifier = Modifier
        .size(200.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF424242)),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = mode.iconRes,
        fontSize = 80.sp
      )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // 标题
    Text(
      text = mode.title,
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      color = Color.Black,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    // 副标题
    Text(
      text = mode.subtitle,
      fontSize = 16.sp,
      color = Color.Gray,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    // 详细描述
    Text(
      text = mode.description,
      fontSize = 14.sp,
      color = Color.DarkGray,
      textAlign = TextAlign.Center,
      lineHeight = 20.sp,
      modifier = Modifier.padding(horizontal = 16.dp)
    )
  }
}

/**
 * 页面指示器
 */
@Composable
fun PageIndicator(
  isSelected: Boolean,
  mode: NightVisionModeItem,
  onClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.clickable(onClick = onClick)
  ) {
    // 小图标
    Box(
      modifier = Modifier
        .size(if (isSelected) 48.dp else 40.dp)
        .clip(CircleShape)
        .background(
          if (isSelected) Color(0xFF005D8E) else Color(0xFFE0E0E0)
        )
        .border(
          width = if (isSelected) 2.dp else 0.dp,
          color = Color(0xFF005D8E),
          shape = CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = mode.iconRes,
        fontSize = if (isSelected) 20.sp else 16.sp
      )
    }

    if (isSelected) {
      Spacer(modifier = Modifier.height(4.dp))
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(Color(0xFF005D8E))
      )
    }
  }
}