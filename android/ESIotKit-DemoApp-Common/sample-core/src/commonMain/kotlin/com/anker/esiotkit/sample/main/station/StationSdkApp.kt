package com.anker.esiotkit.sample.main.station

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.core.device.getAnyTimeDevicePropertyFlow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.sample.main.SampleAppEvent
import com.anker.esiotkit.sample.main.initializeSampleDependencies
import com.anker.esiotkit.sample.main.mega.MegaMainViewModel
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsScreen
import com.anker.esiotkit.sample.main.mega.event.DownloadWorkerHost
import com.anker.esiotkit.sample.main.mega.live_video.MegaLiveVideoScreen
import com.anker.esiotkit.sample.main.restoreSampleBusinessSession
import com.anker.esiotkit.sample.main.sampleAppEvent
import com.anker.esiotkit.sample.main.station.pipeline.PeoplePipelineHost
import com.anker.esiotkit.sample.main.station.pipeline.PeoplePipelineSettingsScreen
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.model.MegaPlayType
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.composable.PlatformNavigatorContent
import com.anker.esiotkit.sample.view.DeviceAsyncImage
import com.anker.esiotkit.sample.view.MegaToaster
import com.anker.esiotkit.sample.view.currentNavigator
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.IoDispatcher
import com.anker.esiotkit.support.di.esKoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.map
import org.koin.compose.KoinContext
import org.koin.compose.koinInject

private const val DeviceRequestTimeoutMillis = 15_000L

/** Platform callbacks live in composition, so Voyager never saves Activity closures in a Screen. */
private data class StationPlatformCallbacks(
  val onSdkTools: () -> Unit,
  val onNotificationSettings: () -> Unit,
  val nowMillis: () -> Long,
  val initialCampusMute: Long,
  val initialVenueMute: Long,
  val onScreenChanged: (String) -> Unit,
  val onMuteChanged: (String, Long) -> Unit,
  val needsLogin: Boolean,
  val initialPage: String
)

private val LocalStationPlatform = staticCompositionLocalOf<StationPlatformCallbacks> {
  error("StationSdkApp must provide platform callbacks")
}

/** The designed station shell backed by the currently authenticated SDK device list. */
@Composable
fun StationSdkApp(
  onSdkTools: () -> Unit,
  onNotificationSettings: () -> Unit,
  nowMillis: () -> Long,
  initialCampusMute: Long = 0L,
  initialVenueMute: Long = 0L,
  onScreenChanged: (String) -> Unit = {},
  onMuteChanged: (station: String, until: Long) -> Unit = { _, _ -> }
) {
  initializeSampleDependencies()
  // The backend assistant has its own authorization and survives an SDK logout.
  var lastStationPage by rememberSaveable { mutableStateOf("home") }
  var authenticated by remember {
    mutableStateOf(runCatching { restoreSampleBusinessSession() }.getOrDefault(false))
  }
  val platform = StationPlatformCallbacks(
    onSdkTools, onNotificationSettings, nowMillis, initialCampusMute, initialVenueMute,
    { page ->
      if (page in setOf("home", "assistant", "messages", "reviews", "profile")) lastStationPage = page
      onScreenChanged(page)
    }, onMuteChanged, needsLogin = !authenticated, initialPage = lastStationPage
  )
  KoinContext(esKoin) {
    MaterialTheme {
      CompositionLocalProvider(LocalStationPlatform provides platform) {
        key(authenticated) {
        Navigator(
          screen = StationHomeScreen,
          key = if (authenticated) "station-devices" else "station-sign-in",
          disposeBehavior = NavigatorDisposeBehavior(),
          onBackPressed = { true }
        ) { navigator ->
          LaunchedEffect(navigator, authenticated) {
            if (!authenticated) navigator.replaceAll(StationHomeScreen)
          }
          LaunchedEffect(navigator) {
            sampleAppEvent.collect { event ->
              if (event is SampleAppEvent.Logout) {
                InfoCache.tokenValid = false
                authenticated = false
                navigator.popUntilRoot()
                emitToast("登录已失效，请重新登录后查看设备。")
              }
            }
          }
          SideEffect {
            currentNavigator = navigator
          }
          LaunchedEffect(navigator.lastItem.key, authenticated) {
            if (authenticated && navigator.lastItem.key != StationHomeScreen.key) onScreenChanged("sdk")
          }
          // Never render a restored SDK screen before the session has been validated.
          if (authenticated) PlatformNavigatorContent(navigator) else StationHomeScreen.Content()
          DownloadWorkerHost()
        }
        PeoplePipelineHost(sdkAuthenticated = authenticated)
        }
      }
      MegaToaster()
    }
  }
}

private object StationHomeScreen : Screen {
  @Composable
  override fun Content() {
    val platform = LocalStationPlatform.current
    val navigator = LocalNavigator.currentOrThrow
    SideEffect { currentNavigator = navigator }
    if (platform.needsLogin) {
      StationContent(
        platform = platform,
        state = StationDeviceState(needsLogin = true),
        onRefresh = platform.onSdkTools,
        onOpen = { platform.onSdkTools() },
        onSettings = { platform.onSdkTools() },
        preview = { _, modifier -> PreviewMessage("请先登录", modifier) }
      )
      return
    }

    // Session restoration happens in StationSdkApp before this model can start SDK requests.
    val viewModel = rememberScreenModel { MegaMainViewModel() }
    ConnectedStationContent(platform, viewModel)
  }
}

@Composable
private fun ConnectedStationContent(platform: StationPlatformCallbacks, viewModel: MegaMainViewModel) {
  val navigator = LocalNavigator.currentOrThrow
  val sdkState by viewModel.uiState.collectAsState()
  var refreshVersion by remember { mutableStateOf(0) }
  var loading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(viewModel, refreshVersion) {
    loading = true
    errorMessage = null
    if (refreshVersion > 0) viewModel.refreshDevice()
    val request = viewModel.getJob("refreshDevice")
    try {
      withTimeout(DeviceRequestTimeoutMillis) { request?.join() }
      if (viewModel.error != null) errorMessage = "设备列表加载失败，请重试。"
    } catch (_: TimeoutCancellationException) {
      viewModel.cancelJob("refreshDevice")
      viewModel.loading = false
      errorMessage = "设备列表加载超时，请检查网络后重试。"
    } finally {
      loading = false
    }
  }

  val cards = sdkState.deviceList.map { device ->
    key(device.deviceSn) {
      val thingName by device.rememberStringProperty(ThingIdentifier.DEVICE_NAME)
      val onlineFlow = remember(device.deviceSn) {
        MegaDeviceManager.getAnyTimeDevicePropertyFlow(device.deviceSn, ThingIdentifier.DEVICE_IS_ONLINE)
          .map { it.asBoolean() }
      }
      val online by onlineFlow.collectAsState(
        initial = device.getProperty(ThingIdentifier.DEVICE_IS_ONLINE)?.get()?.asBoolean()
      )
      StationDeviceCard(
        sn = device.deviceSn,
        name = thingName.trim().ifEmpty {
          device.iotDevice.deviceName.trim().ifEmpty { device.deviceSn }
        },
        online = online,
        isStation = device.deviceSn.isStation()
      )
    }
  }

  fun openSettings(sn: String) {
    if (MegaDeviceManager.getDeviceBy(sn) == null) {
      emitToast("设备信息已变化，请刷新列表。")
      return
    }
    navigator.push(MegaDeviceSettingsScreen(sn))
  }

  StationContent(
    platform = platform,
    state = StationDeviceState(devices = cards, loading = loading, errorMessage = errorMessage),
    onRefresh = { if (!loading) refreshVersion++ },
    onOpen = { sn ->
      val device = MegaDeviceManager.getDeviceBy(sn)
      if (device == null) emitToast("设备信息已变化，请刷新列表。")
      else if (sn.isStation()) openSettings(sn)
      else navigator.push(MegaLiveVideoScreen(sn, MegaPlayType.Normal))
    },
    onSettings = ::openSettings,
    preview = { card, modifier -> StationDevicePreview(card, refreshVersion, modifier) }
  )
}

@Composable
private fun StationContent(
  platform: StationPlatformCallbacks,
  state: StationDeviceState,
  onRefresh: () -> Unit,
  onOpen: (String) -> Unit,
  onSettings: (String) -> Unit,
  preview: @Composable (StationDeviceCard, Modifier) -> Unit
) {
  val navigator = LocalNavigator.currentOrThrow
  StationApp(
    onSdkTools = platform.onSdkTools,
    onNotificationSettings = platform.onNotificationSettings,
    nowMillis = platform.nowMillis,
    initialPage = platform.initialPage,
    initialCampusMute = platform.initialCampusMute,
    initialVenueMute = platform.initialVenueMute,
    onScreenChanged = platform.onScreenChanged,
    onMuteChanged = platform.onMuteChanged,
    deviceState = state,
    onRefreshDevices = onRefresh,
    onOpenDevice = onOpen,
    onDeviceSettings = onSettings,
    devicePreview = preview,
    onPeopleSettings = { navigator.push(PeoplePipelineSettingsScreen) }
  )
}

@Composable
private fun StationDevicePreview(card: StationDeviceCard, refreshVersion: Int, modifier: Modifier) {
  val manager = koinInject<ESIotDeviceManager>()
  var path by remember(card.sn) { mutableStateOf<String?>(null) }
  var loading by remember(card.sn) { mutableStateOf(true) }
  var imageFailed by remember(card.sn) { mutableStateOf(false) }

  LaunchedEffect(card.sn, refreshVersion, card.online) {
    loading = true
    imageFailed = false
    try {
      withTimeout(DeviceRequestTimeoutMillis) {
        val cached = withContext(IoDispatcher) { manager.picture(card.sn).getCachedCoverPath() }
        path = cached?.takeIf { it.isNotBlank() }
        if (card.online != false) {
          val thumbnail = withContext(IoDispatcher) { manager.picture(card.sn).getThumbnailPath() }
          thumbnail?.takeIf { it.isNotBlank() }?.let { path = it }
        }
      }
    } catch (_: TimeoutCancellationException) {
      // A usable cached frame remains a recent preview when refreshing it times out.
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // Empty, offline and unavailable previews stay explicit instead of inventing a frame.
    } finally {
      loading = false
    }
  }

  Box(modifier.background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
    val previewPath = path
    if (previewPath != null && !imageFailed) {
      DeviceAsyncImage(parentSn = card.sn, imagePath = previewPath, modifier = Modifier.fillMaxSize(),
        onLoadError = { imageFailed = true })
    } else {
      Text(
        text = when {
          imageFailed -> "最近预览暂不可用"
          loading -> "正在加载最近预览…"
          card.online == false -> "设备离线 · 暂无预览"
          else -> "暂无最近预览"
        },
        color = Color(0xFFCCCCCC),
        fontSize = 14.sp
      )
    }
  }
}

@Composable
private fun PreviewMessage(message: String, modifier: Modifier) {
  Box(modifier.background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
    Text(message, color = Color(0xFFCCCCCC), fontSize = 14.sp)
  }
}
