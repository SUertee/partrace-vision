package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.model.DeviceListState
import com.anker.esiotkit.sample.model.DisplayDeviceItem
import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.StatusFilterValue
import com.anker.esiotkit.sample.repository.DeviceListRepository
import com.anker.esiotkit.sample.support.rememberOnlineStatus
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.view.DeviceAsyncImage
import com.anker.esiotkit.sample.view.DevicePlaceholderIcon
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.support.log.appLogger
import org.koin.compose.koinInject

// ========== Design tokens ==========
private val BrandBlue = Color(0xFF005D8E)
private val PageBg = Color(0xFFF2F4F7)
private val OnlineGreen = Color(0xFF0FBD58)
private val OfflineRed = Color(0xFFF05049)
private val OnlineBg = Color(0xFFECFDF3)
private val OfflineBg = Color(0xFFFEF2F2)
private val CardBg = Color.White
private val IconGray = Color(0xFF999999)
// Thumbnail dimensions — 精确 16:9（80×45），放大尺寸提升辨识度
private val ThumbWidth = 80.dp
private val ThumbHeight = 45.dp

// ========== Cover image cache ==========

private object CoverCache {
  private val cache = mutableMapOf<String, String?>()
  private val querying = mutableSetOf<String>()

  fun get(sn: String): String? = cache[sn]
  fun has(sn: String): Boolean = cache.containsKey(sn)
  fun isQuerying(sn: String): Boolean = querying.contains(sn)
  fun startQuery(sn: String) { querying.add(sn) }
  fun put(sn: String, path: String?) {
    cache[sn] = path
    querying.remove(sn)
  }
}

// ========== Shared: device display name ==========

@Composable
private fun rememberDeviceDisplayName(device: MegaDevice): String {
  val thingName by device.rememberStringProperty(ThingIdentifier.DEVICE_NAME)
  return remember(thingName, device.iotDevice.deviceName, device.deviceSn) {
    if (device.iotDevice.deviceName == device.deviceSn) device.deviceSn
    else thingName.ifEmpty { device.iotDevice.deviceName }
  }
}

// ========== Main container ==========

@Composable
fun DeviceListContainer(
  state: DeviceListState,
  onSearchQueryChange: (String) -> Unit,
  onStatusFilterChange: (StatusFilterValue) -> Unit,
  onSortOptionChange: (SortOption) -> Unit,
  onDeviceClick: (MegaDevice) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onEventClick: (String) -> Unit,
  onRetryLoad: () -> Unit,
  onRetryLoadChild: (String) -> Unit,
  onAddDeviceClick: () -> Unit,
  onRefresh: () -> Unit,
  onClearSearch: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(PageBg)
  ) {
    when {
      state.isLoading -> LoadingView()
      state.isError -> ErrorView(errorMessage = state.errorMessage, onRetry = onRetryLoad)
      state.isSearchNoResults -> SearchNoResultsView(onClearSearch = onClearSearch)
      state.isEmpty -> EmptyDevicesView(onAddDevice = onAddDeviceClick)
      else -> DeviceListContent(
        displayList = state.displayList,
        onDeviceClick = onDeviceClick,
        onSettingsClick = onSettingsClick,
        onEventClick = onEventClick,
        onRetryLoadChild = onRetryLoadChild,
      )
    }
  }
}

// ========== List (wrapped in DisableOverscrollEffect to fix scroll-then-click bug) ==========

@Composable
private fun DeviceListContent(
  displayList: List<DisplayDeviceItem>,
  onDeviceClick: (MegaDevice) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onEventClick: (String) -> Unit,
  onRetryLoadChild: (String) -> Unit,
) {
  DisableOverscrollEffect {
    androidx.compose.foundation.lazy.LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      items(
        count = displayList.size,
        key = { i -> displayList[i].id }
      ) { i ->
        when (val item = displayList[i]) {
          is DisplayDeviceItem.StationItem -> StationCard(
            station = item.station,
            children = item.children,
            childrenLoadFailed = item.childrenLoadFailed,
            childrenErrorMessage = item.childrenErrorMessage,
            onDeviceClick = onDeviceClick,
            onSettingsClick = onSettingsClick,
            onEventClick = onEventClick,
            onRetryLoadChild = onRetryLoadChild,
          )
          is DisplayDeviceItem.StandaloneItem -> StandaloneCard(
            device = item.device,
            onDeviceClick = onDeviceClick,
            onSettingsClick = onSettingsClick,
            onEventClick = onEventClick,
          )
        }
      }
    }
  }
}

// ========== Standalone card ==========
// Layout mirrors prototype StandaloneCard:
//   overflow-hidden rounded-[16px] relative
//   StatusBadge absolute top-2 right-2
//   clock+gear absolute top-[32px] right-2 flex-row
//   content: px-4 py-[16px] pr-[52px]  thumbnail-xs | name+sn

@Composable
private fun StandaloneCard(
  device: MegaDevice,
  onDeviceClick: (MegaDevice) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onEventClick: (String) -> Unit,
) {
  val isOnline by device.rememberOnlineStatus()
  val displayName = rememberDeviceDisplayName(device)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .shadow(elevation = 3.dp, shape = RoundedCornerShape(16.dp), ambientColor = Color(0x1F000000), spotColor = Color(0x1F000000))
      .clip(RoundedCornerShape(16.dp))
      .background(CardBg)
      .clickable { onDeviceClick(device) }
  ) {
    // Content row: thumbnail + name/sn, leave right 52dp for buttons
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 52.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      DeviceThumbnail(device = device)
      Spacer(modifier = Modifier.width(12.dp))
      DeviceNameColumn(displayName = displayName, sn = device.deviceSn)
    }

    // Status badge — top-right corner
    StatusBadge(
      isOnline = isOnline,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = 8.dp, end = 8.dp)
    )

    // Action buttons: clock + gear, horizontal, below badge — 增加与胶囊的间距
    Row(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = 44.dp, end = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(28.dp)
          .clickable { onEventClick(device.deviceSn) },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = CardClockIcon,
          contentDescription = "Events",
          tint = IconGray,
          modifier = Modifier.size(16.dp)
        )
      }
      Box(
        modifier = Modifier
          .size(28.dp)
          .clickable { onSettingsClick(device) },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = CardGearIcon,
          contentDescription = "Settings",
          tint = IconGray,
          modifier = Modifier.size(16.dp)
        )
      }
    }
  }
}

// ========== Base station card ==========
// Layout mirrors prototype BaseStationCard:
//   overflow-hidden rounded-[16px] relative
//   StatusBadge absolute top-2 right-2
//   gear only, absolute top-[32px] right-2
//   content: px-4 py-[16px] pr-[52px] pb-[10px]  NO icon, just name+sn
//   children section: border-t, slightly tinted bg

@Composable
private fun StationCard(
  station: MegaDevice,
  children: List<MegaDevice>,
  childrenLoadFailed: Boolean,
  childrenErrorMessage: String,
  onDeviceClick: (MegaDevice) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onEventClick: (String) -> Unit,
  onRetryLoadChild: (String) -> Unit,
) {
  val isOnline by station.rememberOnlineStatus()
  val displayName = rememberDeviceDisplayName(station)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .shadow(elevation = 3.dp, shape = RoundedCornerShape(16.dp), ambientColor = Color(0x1F000000), spotColor = Color(0x1F000000))
      .clip(RoundedCornerShape(16.dp))
      .background(CardBg)
  ) {
    Column {
      // Station header (clickable)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onDeviceClick(station) }
      ) {
        // Text content, leave right 52dp for button
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, bottom = 10.dp, end = 52.dp)
        ) {
          Text(
            text = displayName,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1F1F1F),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = station.deviceSn,
            fontSize = 10.sp,
            color = Color(0xFF999999),
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
          )
        }

        // Badge top-right
        StatusBadge(
          isOnline = isOnline,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 8.dp, end = 8.dp)
        )

        // Settings gear only (no events for base station), below badge
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 44.dp, end = 6.dp)
            .size(28.dp)
            .clickable { onSettingsClick(station) },
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = CardGearIcon,
            contentDescription = "Settings",
            tint = IconGray,
            modifier = Modifier.size(16.dp)
          )
        }
      }

      // Children section — 设计稿无分割线，子卡片自带 shadow 与白底形成分层
      if (children.isNotEmpty() || childrenLoadFailed) {
        HorizontalDivider(color = Color(0x0F000000))
        Box(modifier = Modifier.background(Color(0x04000000))) {
          if (childrenLoadFailed) {
            ChildLoadErrorView(
              errorMessage = childrenErrorMessage,
              onRetry = { onRetryLoadChild(station.deviceSn) }
            )
          } else {
            Column {
              children.forEach { child ->
                ChildCard(
                  device = child,
                  onDeviceClick = onDeviceClick,
                  onSettingsClick = onSettingsClick,
                  onEventClick = onEventClick,
                )
              }
            }
          }
        }
      }
    }
  }
}

// ========== Child device card ==========
// Layout mirrors prototype ChildDeviceCard:
//   py-[6px] px-[10px] outer
//   inner: py-[16px] px-[12px] pr-[52px] rounded-xl bg-white relative
//   StatusBadge absolute top-2 right-2
//   clock+gear absolute top-[32px] right-2 flex-row
//   thumbnail xs (56×31) | name+sn

@Composable
private fun ChildCard(
  device: MegaDevice,
  onDeviceClick: (MegaDevice) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onEventClick: (String) -> Unit,
) {
  val isOnline by device.rememberOnlineStatus()
  val displayName = rememberDeviceDisplayName(device)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp, horizontal = 10.dp)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp), ambientColor = Color(0x29000000), spotColor = Color(0x29000000))
        .clip(RoundedCornerShape(12.dp))
        .background(CardBg)
        .clickable { onDeviceClick(device) }
    ) {
      // Content row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 12.dp, top = 16.dp, bottom = 16.dp, end = 52.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        DeviceThumbnail(device = device)
        Spacer(modifier = Modifier.width(12.dp))
        DeviceNameColumn(displayName = displayName, sn = device.deviceSn, nameSize = 13)
      }

      // Badge
      StatusBadge(
        isOnline = isOnline,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 8.dp, end = 8.dp)
      )

      // clock + gear horizontal — 与胶囊留出间距
      Row(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = 44.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clickable { onEventClick(device.deviceSn) },
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = CardClockIcon,
            contentDescription = "Events",
            tint = IconGray,
            modifier = Modifier.size(16.dp)
          )
        }
        Box(
          modifier = Modifier
            .size(28.dp)
            .clickable { onSettingsClick(device) },
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = CardGearIcon,
            contentDescription = "Settings",
            tint = IconGray,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }
}

// ========== Shared composables ==========

@Composable
private fun DeviceThumbnail(device: MegaDevice) {
  val deviceManager = koinInject<ESIotDeviceManager>()
  var displayPath by remember { mutableStateOf(CoverCache.get(device.deviceSn)) }

  LaunchedEffect(device.deviceSn) {
    if (displayPath != null) return@LaunchedEffect
    try {
      val cachedPath = deviceManager.picture(device.deviceSn).getCachedCoverPath()
      if (cachedPath != null) {
        displayPath = cachedPath
        CoverCache.put(device.deviceSn, cachedPath)
        return@LaunchedEffect
      }
      val path = deviceManager.picture(device.deviceSn).getThumbnailPath()
      displayPath = path
      CoverCache.put(device.deviceSn, path)
    } catch (e: Exception) {
      appLogger.w("DeviceThumbnail: load failed for ${device.deviceSn}: ${e.message}")
      CoverCache.put(device.deviceSn, null)
    }
  }

  // coverUpdated 接口暂注释，待接口恢复后取消注释
  // LaunchedEffect(device.deviceSn) {
  //   deviceManager.picture(device.deviceSn).coverUpdated.collect { sn ->
  //     if (sn == device.deviceSn) {
  //       displayPath = deviceManager.picture(sn).getCachedCoverPath()
  //       CoverCache.put(sn, displayPath)
  //       appLogger.i("DeviceThumbnail: reloaded via coverUpdated, deviceSn=$sn, path=$displayPath")
  //     }
  //   }
  // }

  Box(
    modifier = Modifier
      .size(width = ThumbWidth, height = ThumbHeight)
      .clip(RoundedCornerShape(6.dp))
      .background(Color(0xFFF2F4F7)),
    contentAlignment = Alignment.Center
  ) {
    if (displayPath != null) {
      DeviceAsyncImage(
        parentSn = device.deviceSn,
        imagePath = displayPath!!,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      Icon(
        imageVector = DevicePlaceholderIcon,
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

@Composable
private fun DeviceNameColumn(displayName: String, sn: String, nameSize: Int = 14) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = displayName,
      fontSize = nameSize.sp,
      fontWeight = FontWeight.SemiBold,
      color = Color(0xFF1F1F1F),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
    Text(
      text = sn,
      fontSize = 10.sp,
      color = Color(0xFF999999),
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 2.dp)
    )
  }
}

@Composable
private fun StatusBadge(isOnline: Boolean, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .background(
        color = if (isOnline) OnlineBg else OfflineBg,
        shape = RoundedCornerShape(1000.dp)
      )
      .padding(horizontal = 6.dp, vertical = 0.5.dp)
  ) {
    Text(
      text = if (isOnline) "Online" else "Offline",
      fontSize = 9.sp,
      fontWeight = FontWeight.SemiBold,
      color = if (isOnline) OnlineGreen else OfflineRed
    )
  }
}

// ========== State views ==========

@Composable
private fun EmptyDevicesView(onAddDevice: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
      Box(
        modifier = Modifier
          .size(80.dp)
          .background(Color(0xFFF2F4F7), RoundedCornerShape(40.dp)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = null,
          tint = Color(0xFFC2C2C2),
          modifier = Modifier.size(32.dp)
        )
      }
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = "No devices yet",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F1F1F)
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Add your first device to get started.",
        fontSize = 12.sp,
        color = Color(0xFF999999)
      )
      Spacer(modifier = Modifier.height(24.dp))
      Button(
        onClick = onAddDevice,
        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Add Device", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun LoadingView() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator(color = BrandBlue, modifier = Modifier.size(32.dp))
      Spacer(modifier = Modifier.height(12.dp))
      Text("Loading devices...", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF999999))
    }
  }
}

@Composable
private fun ErrorView(errorMessage: String, onRetry: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
      Text(text = "Failed to load devices", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OfflineRed)
      if (errorMessage.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = errorMessage, fontSize = 12.sp, color = Color(0xFF999999))
      }
      Spacer(modifier = Modifier.height(20.dp))
      Button(
        onClick = onRetry,
        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
        shape = RoundedCornerShape(12.dp)
      ) {
        Text("Retry", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun SearchNoResultsView(onClearSearch: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
      Text(text = "No results found", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = "Try adjusting your search.", fontSize = 12.sp, color = Color(0xFF999999))
      Spacer(modifier = Modifier.height(16.dp))
      TextButton(onClick = onClearSearch) {
        Text("Clear Search", color = BrandBlue, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun ChildLoadErrorView(errorMessage: String, onRetry: () -> Unit) {
  Column(modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 12.dp, vertical = 8.dp)) {
    Text(
      text = errorMessage.ifEmpty { "Failed to load cameras." },
      fontSize = 12.sp,
      color = OfflineRed
    )
    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
      Text("Retry", fontSize = 12.sp, color = BrandBlue)
    }
  }
}

// ========== Public card-list entry point (HTML 设计稿对齐) ==========
// 接受扁平 List<MegaDevice>，内部构建层级，复用 StationCard / StandaloneCard / ChildCard 渲染。
// 用于 MegaMainScreen.DevicePage，替换旧版扁平卡片列表。

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DeviceCardList(
  modifier: Modifier = Modifier,
  refreshing: Boolean,
  refreshState: PullRefreshState,
  deviceList: List<MegaDevice>,
  onDeviceClick: (MegaDevice) -> Unit,
  onSettingsClick: (MegaDevice) -> Unit,
  onEventClick: (String) -> Unit,
) {
  val displayList = remember(deviceList) {
    DeviceListRepository.buildHierarchicalList(deviceList)
  }
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(PageBg)
      .pullRefresh(refreshState)
  ) {
    if (displayList.isEmpty() && !refreshing) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        EmptyStateView(message = "No devices found")
      }
    } else {
      DeviceListContent(
        displayList = displayList,
        onDeviceClick = onDeviceClick,
        onSettingsClick = onSettingsClick,
        onEventClick = onEventClick,
        onRetryLoadChild = {},
      )
    }
    PullRefreshIndicator(
      refreshing = refreshing,
      state = refreshState,
      modifier = Modifier.align(Alignment.TopCenter)
    )
  }
}

// ========== Lucide-style stroked icons（匹配设计稿 clock / gear）==========

private fun lucideStrokedIcon(name: String, vararg pathData: String): ImageVector {
  val builder = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
  )
  for (pd in pathData) {
    val nodes = PathParser().parsePathString(pd).toNodes()
    builder.addPath(
      pathData = nodes,
      fill = null,
      stroke = SolidColor(Color.Black),
      strokeLineWidth = 2f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round
    )
  }
  return builder.build()
}

/** 与设计稿 Lucide clock 一致：圆 + 时针分针折线 */
val CardClockIcon: ImageVector by lazy {
  lucideStrokedIcon(
    "CardClock",
    "M 12 2 A 10 10 0 1 0 12 22 A 10 10 0 1 0 12 2 Z",
    "M 12 6 L 12 12 L 16 14"
  )
}

/** 与设计稿 Lucide settings gear 一致：中心圆 + 齿轮外轮廓 */
val CardGearIcon: ImageVector by lazy {
  lucideStrokedIcon(
    "CardGear",
    "M 12 9 A 3 3 0 1 0 12 15 A 3 3 0 1 0 12 9 Z",
    "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82 1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"
  )
}
