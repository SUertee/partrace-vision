package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsViewModel
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_dashboard_title

/**
 * Dashboard Screen (太阳能充电统计)
 * 参考 BatteryCam SoloDashboardActivity
 *
 * 功能：
 * - 显示充电时长、充电效率、充电速率
 * - 柱状图显示充电数据（Day/Week 模式）
 * - 折线图显示电池电量趋势（7天/31天）
 * - Energy Tree 图标
 *
 * 注意：当前版本使用简化 UI，后续可集成 MPAndroidChart 或 Compose Charts 库
 *
 * @version 2026/03/17
 */
class DashboardScreen(
  private val viewModel: MegaDeviceSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_dashboard_title

  @Composable
  override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
    val megaDevice = remember { viewModel.megaDevice }
    val scrollState = rememberScrollState()

    var chargingTimeTab by remember { mutableStateOf(0) }  // 0: Day, 1: Week
    var batteryTrendTab by remember { mutableStateOf(0) }  // 0: 7 Days, 1: 31 Days

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scrollState)
        .background(Color(0xFFF5F5F5)),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // 充电统计卡片
      ChargingStatsCard(megaDevice)

      // 充电时长图表
      ChargingTimeChartCard(
        selectedTab = chargingTimeTab,
        onTabSelected = { chargingTimeTab = it }
      )

      // 电池电量趋势图表
      BatteryTrendChartCard(
        selectedTab = batteryTrendTab,
        onTabSelected = { batteryTrendTab = it }
      )

      // 充电提示
      ChargingTipsCard()
    }

    MegaLoading(viewModel.loading)
  }
}

/**
 * 充电统计卡片
 */
@Composable
fun ChargingStatsCard(
  megaDevice: com.anker.esiotkit.domain_thing.model.device.MegaDevice
) {
  // TODO: 从物模型或服务器获取实际数据
  val batteryLevel by megaDevice.rememberIntProperty("battery_value")

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .padding(16.dp)
  ) {
    // 标题
    Text(
      text = "Solar Charging Statistics",
      fontSize = 18.sp,
      fontWeight = FontWeight.Bold,
      color = Color.Black
    )

    Spacer(modifier = Modifier.height(16.dp))

    // 统计数据
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceAround
    ) {
      // 充电时长
      StatItem(
        icon = Icons.Default.WbSunny,
        value = "3.2h",  // TODO: 从服务器获取
        label = "Charging Time",
        iconTint = Color(0xFFFF9800)
      )

      // 充电效率
      StatItem(
        icon = Icons.Default.Bolt,
        value = "85%",  // TODO: 从服务器获取
        label = "Efficiency",
        iconTint = Color(0xFF4CAF50)
      )

      // 电池电量
      StatItem(
        icon = Icons.Default.BatteryFull,
        value = "$batteryLevel%",
        label = "Battery Level",
        iconTint = Color(0xFF005D8E)
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Energy Tree（根据充电量显示不同图标）
    EnergyTreeIndicator(chargingEnergy = 320)  // TODO: 从服务器获取
  }
}

/**
 * 统计项
 */
@Composable
fun StatItem(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  value: String,
  label: String,
  iconTint: Color
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = iconTint,
      modifier = Modifier.size(32.dp)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = value,
      fontSize = 20.sp,
      fontWeight = FontWeight.Bold,
      color = Color.Black
    )
    Text(
      text = label,
      fontSize = 12.sp,
      color = Color.Gray
    )
  }
}

/**
 * Energy Tree 指示器
 */
@Composable
fun EnergyTreeIndicator(chargingEnergy: Int) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(80.dp)
      .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // 使用 emoji 临时代替实际的 Energy Tree 图标
      Text(
        text = when {
          chargingEnergy >= 300 -> "🌳"  // 大树
          chargingEnergy >= 200 -> "🌿"  // 中等植物
          chargingEnergy >= 100 -> "🌱"  // 小苗
          else -> "🌾"  // 幼苗
        },
        fontSize = 40.sp
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Green Energy: ${chargingEnergy}Wh",
        fontSize = 13.sp,
        color = Color(0xFF4CAF50),
        fontWeight = FontWeight.Medium
      )
    }
  }
}

/**
 * 充电时长图表卡片
 */
@Composable
fun ChargingTimeChartCard(
  selectedTab: Int,
  onTabSelected: (Int) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
  ) {
    // Tab 切换
    TabRow(
      selectedTabIndex = selectedTab,
      containerColor = Color.White,
      contentColor = Color(0xFF005D8E)
    ) {
      Tab(
        selected = selectedTab == 0,
        onClick = { onTabSelected(0) },
        text = { Text("Day") }
      )
      Tab(
        selected = selectedTab == 1,
        onClick = { onTabSelected(1) },
        text = { Text("Week") }
      )
    }

    // 图表区域（简化版本 - 使用占位 UI）
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(250.dp)
        .padding(16.dp)
        .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "📊",
          fontSize = 48.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = if (selectedTab == 0) "24-hour Charging Chart" else "7-day Charging Chart",
          fontSize = 14.sp,
          color = Color.Gray
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Chart implementation coming soon",
          fontSize = 12.sp,
          color = Color.LightGray
        )
      }
    }
  }
}

/**
 * 电池电量趋势图表卡片
 */
@Composable
fun BatteryTrendChartCard(
  selectedTab: Int,
  onTabSelected: (Int) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
  ) {
    // 标题
    Text(
      text = "Battery Level Trend",
      fontSize = 16.sp,
      fontWeight = FontWeight.Medium,
      color = Color.Black,
      modifier = Modifier.padding(16.dp)
    )

    // Tab 切换
    TabRow(
      selectedTabIndex = selectedTab,
      containerColor = Color.White,
      contentColor = Color(0xFF005D8E)
    ) {
      Tab(
        selected = selectedTab == 0,
        onClick = { onTabSelected(0) },
        text = { Text("7 Days") }
      )
      Tab(
        selected = selectedTab == 1,
        onClick = { onTabSelected(1) },
        text = { Text("31 Days") }
      )
    }

    // 图表区域（简化版本 - 使用占位 UI）
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(250.dp)
        .padding(16.dp)
        .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "📈",
          fontSize = 48.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = if (selectedTab == 0) "7-day Battery Trend" else "31-day Battery Trend",
          fontSize = 14.sp,
          color = Color.Gray
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Chart implementation coming soon",
          fontSize = 12.sp,
          color = Color.LightGray
        )
      }
    }
  }
}

/**
 * 充电提示卡片
 */
@Composable
fun ChargingTipsCard() {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .padding(16.dp)
  ) {
    Text(
      text = "💡 Charging Tips",
      fontSize = 16.sp,
      fontWeight = FontWeight.Medium,
      color = Color.Black
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "• Position the solar panel to receive direct sunlight for optimal charging\n" +
        "• Clean the solar panel regularly to maintain efficiency\n" +
        "• Charging speed varies with weather conditions and sunlight intensity\n" +
        "• The camera can operate normally while charging",
      fontSize = 13.sp,
      color = Color.DarkGray,
      lineHeight = 20.sp
    )
  }
}