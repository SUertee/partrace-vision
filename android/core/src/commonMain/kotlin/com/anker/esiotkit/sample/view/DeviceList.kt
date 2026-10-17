package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.core.entities.device.ESIotDevice
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.core.support.ktx.getSensorList
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.main.SampleAppEvent
import com.anker.esiotkit.data_center.export.LocalEventRepository
import com.anker.esiotkit.event.domain.repository.HistoryRecordSearchParams
import com.anker.esiotkit.sample.support.model.MegaPlayType
import com.anker.esiotkit.sample.support.rememberOnlineStatus
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.DisableOverscrollEffect
import com.anker.esiotkit.sample.ui.component.EmptyStateView
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.utils.DateUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.koinInject
import kotlin.coroutines.resume

/**
 * @version 2024/6/7
 */

// 全局缓存，避免重复查询同一设备
private object DeviceCoverCache {
  private val cache = mutableMapOf<String, String?>()
  private val querying = mutableSetOf<String>()
  private var _version = androidx.compose.runtime.mutableIntStateOf(0)
  val version: Int get() = _version.intValue

  fun get(deviceSn: String): String? = cache[deviceSn]
  fun has(deviceSn: String): Boolean = cache.containsKey(deviceSn)

  fun isQuerying(deviceSn: String): Boolean = querying.contains(deviceSn)

  fun startQuery(deviceSn: String) {
    querying.add(deviceSn)
  }

  fun put(deviceSn: String, path: String?) {
    cache[deviceSn] = path
    querying.remove(deviceSn)
  }

  fun clear() {
    cache.clear()
    querying.clear()
    _version.intValue++
  }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DeviceList(
  modifier: Modifier,
  refreshing: Boolean,
  deviceList: List<MegaDevice>,
  refreshState: PullRefreshState,
  onClick: (MegaDevice, MegaPlayType) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onDebugSettingsClick: (MegaDevice) -> Unit = {},
) {
  // 刷新完成时清除封面图缓存，触发重新获取
  var wasRefreshing by remember { mutableStateOf(false) }
  LaunchedEffect(refreshing) {
    if (wasRefreshing && !refreshing) {
      // 刷新完成（从 true 变 false），清缓存
      DeviceCoverCache.clear()
      appLogger.i("DeviceList", "Refresh completed, cover cache cleared (version=${DeviceCoverCache.version})")
    }
    wasRefreshing = refreshing
  }

  Box(
    modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)
      .pullRefresh(refreshState),
    contentAlignment = Alignment.TopCenter
  ) {
    if (deviceList.isEmpty() && !refreshing) {
      EmptyStateView(message = "No devices found")
    } else {
      DisableOverscrollEffect {
        LazyColumn(
          modifier = Modifier.fillMaxSize().clipToBounds(),
        ) {
          val coverVersion = DeviceCoverCache.version
          items(deviceList, key = { it.deviceSn }) { device ->
            DeviceItem(device, coverVersion, onClick, onSettingsClick, onDebugSettingsClick)
          }
        }
      }
    }
    PullRefreshIndicator(refreshing, refreshState, Modifier.align(Alignment.TopCenter))
  }
}

@Preview()
@Composable
fun DeviceItemPreview() {
  val megaDevice =
    MegaDevice(ESIotDevice(deviceSn = "123456", parentSn = "123456", deviceName = "deviceName"))
  DeviceItem(
    device = megaDevice, onClick = { _, _ -> }, onSettingsClick = {}
  )
}

@Composable
fun DeviceItem(
  device: MegaDevice,
  coverVersion: Int = 0,
  onClick: (MegaDevice, MegaPlayType) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onDebugSettingsClick: (MegaDevice) -> Unit = {},
) {
  val localEventRepository = koinInject<LocalEventRepository>()

  // 使用 remember 缓存这些计算，避免每次重组重新计算
  val isParent = remember(device.deviceSn, device.parentSn) { device.deviceSn == device.parentSn }
  val isStationDevice = remember(device.deviceSn) { device.deviceSn.isStation() }
  val paddingStart = remember(isParent) { if (isParent) 0.dp else 16.dp }
  val paddingTop = remember(isParent) { if (isParent) 8.dp else 2.dp }
  val isOnline by device.rememberOnlineStatus()
  val onlineStatus = remember(isOnline) {
    if (isOnline) "Online" else "Offline"
  }
  val onlineColor = remember(isOnline) {
    if (isOnline) Color.Green else Color.Red
  }

  // 封面图获取逻辑：查询最新Event（全局缓存，刷新时重新获取）
  var coverImagePath by remember { mutableStateOf(DeviceCoverCache.get(device.deviceSn)) }

  // key 包含 coverVersion，刷新后缓存清除、version 变化，LaunchedEffect 重新执行
  LaunchedEffect(device.deviceSn, coverVersion) {
    // 已有缓存，直接使用
    if (DeviceCoverCache.has(device.deviceSn)) {
      coverImagePath = DeviceCoverCache.get(device.deviceSn)
      return@LaunchedEffect
    }

    // 正在查询中，跳过
    if (DeviceCoverCache.isQuerying(device.deviceSn)) {
      return@LaunchedEffect
    }

    // 开始查询
    DeviceCoverCache.startQuery(device.deviceSn)

    val eventImagePath = withTimeoutOrNull(5000) {
      suspendCancellableCoroutine<String?> { continuation ->
        val parentSn = device.parentSn
        val today = DateUtil.getToday()

        val params = HistoryRecordSearchParams(parentSn, today, today)
          .setDeviceSnList(listOf(device.deviceSn))
          .setCount(1)

        localEventRepository.getHistoryRecord(
          isRefresh = true,
          params = params
        ) { historyResult ->
          val records = historyResult.recordList
          if (records.isNotEmpty()) {
            val latestRecord = records.first()
            val picturePath = latestRecord.thumb_path
            if (picturePath.isNotEmpty()) {
              // 清理设备SN前缀
              val cleanPath = when {
                picturePath.startsWith("${device.deviceSn}-") ->
                  picturePath.removePrefix("${device.deviceSn}-")
                picturePath.startsWith("${device.deviceSn}~") ->
                  picturePath.removePrefix("${device.deviceSn}~")
                else -> picturePath
              }
              appLogger.i("DeviceItem", "Device ${device.deviceSn}: cover='$cleanPath'")
              continuation.resume(cleanPath)
            } else {
              continuation.resume(null)
            }
          } else {
            appLogger.w("DeviceItem", "Device ${device.deviceSn}: no events found for cover")
            continuation.resume(null)
          }
        }
      }
    }

    // 存入缓存
    DeviceCoverCache.put(device.deviceSn, eventImagePath)
    coverImagePath = eventImagePath
  }
  Card(
    colors = CardDefaults.cardColors(containerColor = Color.White),
    shape = RoundedCornerShape(12.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier.padding(start = paddingStart).padding(vertical = paddingTop)
      .fillMaxWidth()
      .clickable {
        onClick(device, MegaPlayType.Normal)
      }
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // 设备封面图区域 - 仅摄像头设备显示，基站不显示
        if (!isStationDevice) {
          Box(
            modifier = Modifier
              .size(width = 100.dp, height = 75.dp)
              .padding(end = 12.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(Color(0xFFF5F5F5)),
            contentAlignment = Alignment.Center
          ) {
            if (!coverImagePath.isNullOrEmpty()) {
              DeviceAsyncImage(
                parentSn = device.deviceSn,
                imagePath = coverImagePath!!,
                modifier = Modifier.fillMaxSize()
              )
            } else {
              // 默认占位图标
              Icon(
                imageVector = DevicePlaceholderIcon,
                contentDescription = "Device",
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp)
              )
            }
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          val thingName by device.rememberStringProperty(ThingIdentifier.DEVICE_NAME)
          val displayName = if (device.iotDevice.deviceName == device.deviceSn) {
            // User hasn't manually renamed, always show SN
            device.deviceSn
          } else {
            // User has renamed, show the custom name
            thingName.ifEmpty { device.iotDevice.deviceName }
          }
          Text(
            text = displayName,
            fontSize = 16.sp,
            color = Color.Black
          )
          Text(
            text = device.deviceSn,
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp),
          )
        }

        Column(
          horizontalAlignment = Alignment.End,
          modifier = Modifier
        ) {
          // Event图标和设置图标改成水平排列
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
          ) {
            // Event图标 - 仅子设备（摄像头）显示，基站不显示
            if (!isStationDevice) {
              Icon(
                imageVector = EventsIcon,
                contentDescription = "Events",
                tint = Color.Gray,
                modifier = Modifier.size(24.dp).clickable {
                  SampleAppEvent.emit(SampleAppEvent.ShowDeviceEvents(device.deviceSn))
                }
              )
            }
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Settings",
              tint = Color.Gray,
              modifier = Modifier.size(24.dp).clickable {
                onDebugSettingsClick(device)
              }
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(onlineColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = onlineStatus,
              fontSize = 12.sp,
              color = Color.Gray
            )
          }
        }
      }
      // 传感器列表
      val sensorList = remember(device.deviceSn) { device.getSensorList() }
      if (sensorList.isNotEmpty()) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
          sensorList.forEach {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(vertical = 2.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(Color(0xFF005D8E))
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Sensor: ${it.deviceSn}",
                fontSize = 12.sp,
                color = Color.Gray
              )
            }
          }
        }
      }

    }
  }
}

val EventsIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "Events",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1024f,
    viewportHeight = 1024f
  ).apply {
    path(fill = SolidColor(Color.Black)) {
      moveTo(511.163f, 32.07f)
      curveTo(246.799f, 32.07f, 32.486f, 246.384f, 32.486f, 510.754f)
      curveTo(32.486f, 775.118f, 246.8f, 989.431f, 511.163f, 989.431f)
      curveTo(775.533f, 989.431f, 989.847f, 775.117f, 989.847f, 510.754f)
      curveTo(989.847f, 246.384f, 775.533f, 32.07f, 511.163f, 32.07f)
      close()
      moveTo(748.823f, 748.407f)
      curveTo(743.335f, 753.895f, 736.142f, 756.64f, 728.956f, 756.64f)
      curveTo(721.763f, 756.64f, 714.571f, 753.895f, 709.084f, 748.407f)
      lineTo(483.071f, 522.388f)
      lineTo(483.071f, 236.557f)
      curveTo(483.071f, 221.028f, 495.661f, 208.452f, 511.169f, 208.452f)
      curveTo(526.684f, 208.452f, 539.274f, 221.029f, 539.274f, 236.557f)
      lineTo(539.274f, 499.119f)
      lineTo(748.823f, 708.668f)
      curveTo(759.798f, 719.631f, 759.798f, 737.437f, 748.823f, 748.407f)
      close()
    }
  }.build()
}

val DevicePlaceholderIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "DevicePlaceholder",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1024f,
    viewportHeight = 1024f
  ).apply {
    // Camera lens
    path(fill = SolidColor(Color(0xFF7E8285))) {
      moveTo(687.52f, 328.47f)
      curveTo(683.38f, 267.24f, 633.8f, 221f, 569.23f, 219.03f)
      curveTo(503.27f, 216.98f, 452.79f, 272.39f, 450.74f, 332.55f)
      curveTo(448.58f, 395.73f, 506.44f, 444.1f, 569.23f, 446.07f)
      curveTo(633.7f, 448.07f, 683.35f, 395.19f, 687.51f, 336.68f)
      curveTo(687.7f, 335.37f, 687.8f, 334f, 687.76f, 332.54f)
      curveTo(687.81f, 331.11f, 687.71f, 329.76f, 687.52f, 328.47f)
      close()
      moveTo(569.23f, 402.43f)
      curveTo(528.46f, 403.69f, 497.52f, 369.72f, 496.29f, 332.55f)
      curveTo(494.96f, 293.44f, 530.44f, 263.82f, 569.23f, 262.64f)
      curveTo(610.03f, 261.38f, 640.94f, 295.38f, 642.2f, 332.55f)
      curveTo(642.2f, 332.62f, 642.22f, 332.69f, 642.22f, 332.76f)
      curveTo(640.75f, 371.74f, 609.96f, 401.17f, 569.23f, 402.43f)
      close()
    }
    // Alert circle
    path(fill = SolidColor(Color(0xFFD0D7DA))) {
      moveTo(767.21f, 507.08f)
      curveTo(662.04f, 507.08f, 576.78f, 592.34f, 576.78f, 697.51f)
      curveTo(576.78f, 802.68f, 662.04f, 887.94f, 767.21f, 887.94f)
      curveTo(872.38f, 887.94f, 957.64f, 802.68f, 957.64f, 697.51f)
      curveTo(957.64f, 592.34f, 872.47f, 507.08f, 767.21f, 507.08f)
      close()
      moveTo(767.2f, 570.55f)
      curveTo(781.22f, 570.54f, 792.6f, 581.91f, 792.6f, 595.93f)
      lineTo(792.6f, 595.96f)
      lineTo(779.9f, 735.61f)
      lineTo(754.51f, 735.61f)
      lineTo(741.81f, 595.96f)
      curveTo(741.82f, 581.93f, 753.18f, 570.55f, 767.2f, 570.55f)
      close()
      moveTo(768.36f, 811.75f)
      curveTo(767.98f, 811.76f, 767.6f, 811.76f, 767.23f, 811.75f)
      curveTo(753.21f, 811.44f, 742.1f, 799.82f, 742.41f, 785.8f)
      curveTo(742.71f, 772.22f, 753.64f, 761.28f, 767.22f, 760.98f)
      curveTo(781.24f, 760.66f, 792.86f, 771.77f, 793.18f, 785.79f)
      curveTo(793.49f, 799.82f, 782.38f, 811.44f, 768.36f, 811.75f)
      close()
    }
    // Camera body
    path(fill = SolidColor(Color(0xFF7E8285))) {
      moveTo(576.64f, 822.02f)
      lineTo(161.7f, 822.02f)
      curveTo(149.97f, 822.02f, 138.71f, 823.1f, 127.88f, 817.45f)
      curveTo(110.36f, 808.38f, 111.33f, 789.67f, 111.33f, 773.19f)
      lineTo(111.33f, 640.24f)
      lineTo(293.84f, 498.5f)
      lineTo(539.73f, 704.72f)
      curveTo(539.66f, 702.32f, 539.55f, 699.93f, 539.55f, 697.51f)
      curveTo(539.55f, 681.47f, 541.23f, 665.82f, 544.38f, 650.72f)
      lineTo(310.63f, 454.64f)
      curveTo(300.52f, 446.15f, 288.54f, 446.72f, 278.39f, 454.64f)
      lineTo(111.32f, 584.42f)
      lineTo(111.32f, 210.68f)
      curveTo(111.32f, 167.5f, 169.86f, 177.22f, 197.82f, 177.22f)
      lineTo(775.84f, 177.22f)
      curveTo(802.72f, 177.22f, 835.68f, 172f, 862.02f, 179.27f)
      curveTo(895.12f, 188.37f, 883.75f, 258.36f, 883.75f, 282.54f)
      lineTo(883.75f, 501.93f)
      curveTo(900.05f, 511.66f, 915.04f, 523.35f, 928.37f, 536.7f)
      lineTo(928.37f, 288.8f)
      curveTo(928.37f, 256.06f, 934.88f, 212.99f, 922.61f, 181.68f)
      curveTo(903.04f, 131.88f, 855.18f, 132.6f, 811.28f, 132.6f)
      lineTo(145.33f, 132.6f)
      curveTo(101f, 132.6f, 69.05f, 167.86f, 66.74f, 210.65f)
      curveTo(66.27f, 219.29f, 66.74f, 228.07f, 66.74f, 236.7f)
      lineTo(66.74f, 624.91f)
      curveTo(66.47f, 626.61f, 66.47f, 628.31f, 66.74f, 629.99f)
      lineTo(66.74f, 771.61f)
      curveTo(66.74f, 787.44f, 66.52f, 802.34f, 72.5f, 817.56f)
      curveTo(84.66f, 848.58f, 115.03f, 866.64f, 147.7f, 866.64f)
      lineTo(614.82f, 866.64f)
      curveTo(601.44f, 853.49f, 589.58f, 838.83f, 576.64f, 822.02f)
      close()
    }
  }.build()
}