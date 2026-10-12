@file:OptIn(InternalIotApi::class)

package com.anker.esiotkit.sample.main.mega.live_video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.iotmodel.default_service.DualCameraService
import com.anker.esiotkit.core.constants.ESResult
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.compat.ESIotCompatSwitch
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.api.domain.AlarmType
import com.anker.esiotkit.domain_thing.api.domain.PTZDirection
import com.anker.esiotkit.domain_thing.api.domain.NightVisionMode
import com.anker.esiotkit.domain_thing.api.model.ThingResult
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.media.RecordState
import com.anker.esiotkit.media.TalkState
import com.anker.esiotkit.media.VideoInfo
import com.anker.esiotkit.media.live_stream.LiveChannelOrder
import com.anker.esiotkit.media.live_stream.LiveMediaPlayer
import com.anker.esiotkit.media.live_stream.LiveParams
import com.anker.esiotkit.media.live_stream.LivePlayListener
import com.anker.esiotkit.media.live_stream.LiveType
import com.anker.esiotkit.media.live_stream.LivePlayerState
import com.anker.esiotkit.media.live_stream.StitchType
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.main.mega.media.stopAndRelease
import com.anker.esiotkit.sample.model.isStandalone
import com.anker.esiotkit.sample.model.isStation
import com.anker.esiotkit.sample.support.ThingPropertyState
import com.anker.esiotkit.sample.support.model.MegaPlayType
import com.anker.esiotkit.sample.support.platform.saveImage
import com.anker.esiotkit.sample.support.platform.saveVideo
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.security_device.domain.entities.CameraType
import com.anker.esiotkit.security_device.p2p.P2PPingManager
import com.anker.esiotkit.support.InternalIotApi
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.IotImage
import com.anker.esiotkit.support.platform.createFile
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath

/**
 * @version 2024/9/3
 */
class MegaLiveStreamViewModel(
  private val megaDevice: MegaDevice,
  private val deviceManager: ESIotDeviceManager
) : BaseScreenModel() {
  // P2P Ping Manager for battery device keep-alive
  private val pingManager: P2PPingManager = esInject()

  companion object {
    private const val TAG = "MegaLiveStreamViewModel"

    /**
     * Device SN prefixes for special handling
     * Reference: BatteryCam/common/utils/SnUtils.java
     */
    private const val SN_PREFIX_8115 = "T8115"  // Solo Camera 8115
    private const val SN_PREFIX_817L = "T817L"  // Solo Camera 817L
    private const val SN_PREFIX_8417 = "T8417"  // Video Doorbell / standalone camera
    private const val SN_PREFIX_8E00 = "T8E00"  // NVR moving camera (supports zoom)
    private const val SN_PREFIX_8P00 = "T8P00"  // NVR fixed camera (no zoom)
    private const val SN_PREFIX_8P10 = "T8P10"  // NVR fixed camera (no zoom)
    private const val SN_PREFIX_8N00 = "T8N00"  // NVR base station (PoE NVR)

    // Device thing model alarm type values from set_alarm_type enumList
    // 与物模型 value 对应，非 SDK AlarmType.typeValue（后者为 1/2/3/4）
    private const val DEVICE_ALARM_TYPE_SOUND_ONLY = 2
    private const val DEVICE_ALARM_TYPE_LIGHT_ONLY = 22
    private const val DEVICE_ALARM_TYPE_SOUND_AND_LIGHT = 23

    private const val ON_VIDEO_INFO_LOG_INTERVAL_MS = 2000L

    // 对齐 mega PresetPositionsSettingModel: NORMAL_SET=0, FORCE_SET=1
    private const val SETTING_STATE_NORMAL = 0
    private const val SETTING_STATE_FORCE = 1
  }

  private var onVideoInfoLastLogTime = 0L

  private var isForeverPingActive = false
  private var playerReleased = false
  private var prePlaySplitModeApplied = false
  private var prePlaySplitModeInProgress = false

  var liveMediaPlayer = LiveMediaPlayer(megaDevice)
    private set

  fun recreatePlayerIfNeeded() {
    if (playerReleased) {
      dataLogger.i("$TAG LiveDebug: [RECREATE] rebuilding LiveMediaPlayer")
      liveMediaPlayer = LiveMediaPlayer(megaDevice)
      playerReleased = false
      setupPlayerListener()
    }
  }
  private val liveStreamAPI = deviceManager.liveStream(megaDevice.deviceSn)

  /**
   * Callback for screenshot capture, used by PresetPositionsScreen
   */
  var onCutImageCallback: ((IotImage) -> Unit)? = null

  /**
   * Check if device requires special handling based on SN prefix
   */
  private fun isDeviceWithSpecialHandling(snPrefix: String): Boolean {
    return megaDevice.deviceSn.startsWith(snPrefix)
  }

  /**
   * Check if device is a moving camera (supports zoom)
   */
  private fun isMovingCamera(): Boolean {
    return isDeviceWithSpecialHandling(SN_PREFIX_8E00)
  }

  /**
   * Check if device is a fixed camera (no zoom)
   */
  private fun isFixedCamera(): Boolean {
    return isDeviceWithSpecialHandling(SN_PREFIX_8P00) || isDeviceWithSpecialHandling(SN_PREFIX_8P10)
  }

  /**
   * Check if device is a fixed NVR camera (no zoom, includes T8N00 series)
   */
  private fun isFixedNvrCamera(): Boolean {
    return isDeviceWithSpecialHandling(SN_PREFIX_8N00) ||
           isDeviceWithSpecialHandling(SN_PREFIX_8P00) ||
           isDeviceWithSpecialHandling(SN_PREFIX_8P10)
  }

  /**
   * 从设备 thing model 读 AI Tracking 当前开关，作为 trackingStatus 初始值。
   * 兼容两种 identifier：ai_tracking_status / motion_track_status。
   * 兼容两种值类型：布尔 true/false 和字符串 "1"/"0"（T8414 的 motion_track_status 存为 "1"）。
   */
  private val motionTrackState = ThingPropertyState(megaDevice, "motion_track_status", screenModelScope)

  private fun initialTrackingStatus(): MegaLiveFunctionStatus {
    val raw = motionTrackState.payload.value.asString()
    appLogger.i("$TAG initialTrackingStatus: rawValue='$raw', deviceSn=${megaDevice.deviceSn}")
    val enabled = raw.equals("true", ignoreCase = true) || raw == "1" || raw == "1.0"
    return if (enabled) MegaLiveFunctionStatus.Running else MegaLiveFunctionStatus.Idle
  }

  /**
   * 从设备 thing model 读 Spotlight 当前开关，作为 lightStatus 初始值。
   */
  private fun initialLightStatus(): MegaLiveFunctionStatus {
    val enabled = megaDevice.getProperty(ThingIdentifier.SPOTLIGHT_SWITCH)?.get()?.asBoolean()
      ?: false
    return if (enabled) MegaLiveFunctionStatus.Running else MegaLiveFunctionStatus.Idle
  }

  private fun logAvailableProperties() {
    val thingDescription = megaDevice.getThingDescription()
    val properties = thingDescription?.propMap.orEmpty()
    val actions = thingDescription?.actionMap.orEmpty()
    val toolbarListProperty = megaDevice.getProperty(ThingIdentifier.LIVE_BOTTOM_TOOLBAR_LIST)
    val enumList = toolbarListProperty?.td?.dataType?.specs?.enumList

    val features = listOf(
      "Record" to supportsRecord, "Screenshot" to supportsScreenshot,
      "Talk" to supportsTalk, "Mute" to supportsMute,
      "NightVision" to supportsNightVision, "Alarm" to supportsAlarm,
      "Light" to supportsLight, "Tracking" to supportsTracking,
      "Calibration" to supportsCalibration, "360" to supports360,
      "QuickResponse" to supportsQuickResponse, "Preset" to supportsPreset,
      "PTZ" to supportsPTZControl,
    )
    val supported = features.filter { it.second }.joinToString(",") { it.first }
    val toolbar = enumList?.joinToString(",") { it.value } ?: "N/A"

    appLogger.d(
      "$TAG init: sn=${megaDevice.deviceSn} pn=${megaDevice.devicePn} " +
        "props=${properties.size} actions=${actions.size} toolbar=[$toolbar] supported=[$supported]"
    )
  }
  private var isPlaying by mutableStateOf(false)
  private var isPlayFinished by mutableStateOf(false)
  // 镜头切换过渡覆盖（对齐 Mega LiveStreamVideoView.setBGVisibility:1776-1784 的闪屏处理机制）:
  // postStartRestoreMode 切副摄瞬间设备出过渡帧（约 1s 绿屏），覆盖层贴住切换前最后一帧，
  // 收到新帧分辨率回调（markResolutionReady）后解除，兜底 3s 超时防覆盖层卡死
  var lensSwitchCover by mutableStateOf<IotImage?>(null)
    private set
  private var lensSwitchCoverJob: kotlinx.coroutines.Job? = null
  private var isBuffering by mutableStateOf(true)
  private var coverCaptured = false
  private var recordStatus: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private var talkStatus: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private var muteStatus: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private var alarmStatus: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private var alarmCountdown by mutableIntStateOf(0)
  private var alarmTypeName by mutableStateOf("")
  private var lastAlarmType by mutableIntStateOf(0)
  private var alarmCountdownJob: kotlinx.coroutines.Job? = null
  var showAlarmDialog by mutableStateOf(false)
  // 从设备 thing model 读初始状态，避免 UI 与设备真实开关方向相反导致的"误关"。
  // 例如 NVR 子设备开机时 AI Tracking 已是开启 (ai_tracking_status=1)，
  // 若初始化为 Idle 用户以为是关的去点一下，反而把它关掉。
  private var lightStatus: MegaLiveFunctionStatus by mutableStateOf(initialLightStatus())
  private var trackingStatus: MegaLiveFunctionStatus by mutableStateOf(initialTrackingStatus())
  private var calibrationStatus: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private var round360Status: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private var panoramaStatus: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private var controlPositionStatus: MegaLiveFunctionStatus by mutableStateOf(MegaLiveFunctionStatus.Idle)
  private val _panoramaResultPath = mutableStateOf<String?>(null)
  val panoramaResultPath: String? get() = _panoramaResultPath.value
private var rate by mutableStateOf(0)
private var recordTime by mutableStateOf(0L)
  private var quickResponseList by mutableStateOf<List<QuickResponseItem>>(emptyList())
  private var supportedVideoModes by mutableStateOf<List<VideoModeOption>>(emptyList())
  private var currentVideoMode by mutableStateOf<Int?>(null)
  var aspectRatio by mutableStateOf(16 / 9f)

  // Feature support detection based on live_bottom_toolbar_list
  private data class FeatureSupport(
    val supportsRecord: Boolean = false,
    val supportsScreenshot: Boolean = false,
    val supportsTalk: Boolean = false,
    val supportsMute: Boolean = false,
    val supportsNightVision: Boolean = false,
    val supportsAlarm: Boolean = false,
    val supportsLight: Boolean = false,
    val supportsTracking: Boolean = false,
    val supportsCalibration: Boolean = false,
    val supports360: Boolean = false,
    val supportsPanorama: Boolean = false,
    val supportsQuickResponse: Boolean = false,
    val supportsPreset: Boolean = false,
    val supportsPTZControl: Boolean = false,
  val supportsSingleDualSwitch: Boolean = false,
  )

  /**
   * Bottom menu enumeration values mapping
   * Reference: BatteryCam/component_video/constans/BottomMenu.kt
   */
  private object BottomMenuValue {
    const val RECORD = "0"           // 录制
    const val SCREENSHOT = "1"       // 截图
    const val INTERCOM = "2"         // 对讲
    const val PANTILI = "3"          // 云台
    const val SOUND = "4"            // 声音/静音
    const val MEDIA_ROUND_LOOK = "6" // 360°巡航
    const val AI_TRACKING = "7"      // AI追踪
    const val NIGHT_VISION = "8"     // 夜视
    const val PLAYBACK = "9"         // 回放
    const val EVENT = "10"           // 事件
    const val CALIBRATION = "12"     // 校准
    const val LIGHT = "13"           // 灯光
    const val REPLY = "15"           // 快捷回复
    const val PRESETS = "16"         // 预置点
    const val AI_BOX = "17"          // AI框
    const val CAM_GUN_BALL_LINKAGE = "18" // 枪球联动
    const val PTZ_CONTROL_ENTRANCE = "19" // 云台控制入口
    const val SINGLE_DUAL_SWITCH = "20"   // 单双摄切换
    const val LIVE_TRACKING = "21"        // 实时追踪
    const val OPEN_SMART_LOCK = "24"      // 开关锁
  }

  private fun detectFeatureSupport(): FeatureSupport {
    val toolbarListProperty = megaDevice.getProperty(ThingIdentifier.LIVE_BOTTOM_TOOLBAR_LIST)
    val enumList = toolbarListProperty?.td?.dataType?.specs?.enumList

    if (enumList.isNullOrEmpty()) {
      appLogger.w("$TAG detectFeatureSupport: live_bottom_toolbar_list not available, using fallback detection")
      // Fallback: check individual properties if toolbar list not available
      return FeatureSupport(
        supportsRecord = megaDevice.getProperty(ThingIdentifier.LIVE_SUPPORT_RECORD)?.get()?.asBoolean() ?: true,
        supportsScreenshot = megaDevice.getProperty(ThingIdentifier.LIVE_SUPPORT_SCREENSHOT)?.get()?.asBoolean() ?: true,
        supportsTalk = megaDevice.getProperty(ThingIdentifier.MICROPHONE_ON_OFF) != null
                    || megaDevice.getAction(ThingIdentifier.START_INTERCOM) != null,
        supportsMute = megaDevice.getProperty(ThingIdentifier.LIVE_SUPPORT_MUTE)?.get()?.asBoolean() ?: true,
        supportsNightVision = megaDevice.getProperty(ThingIdentifier.NIGHTVISION_TYPE) != null,
        supportsAlarm = megaDevice.getAction(ThingIdentifier.SET_ALARM_TYPE) != null
                     || megaDevice.hasProperty(ThingIdentifier.SET_ALARM_TYPE),
        supportsLight = megaDevice.getProperty(ThingIdentifier.SPOTLIGHT_SWITCH) != null,
        supportsTracking = megaDevice.getProperty(ThingIdentifier.AI_TRACKING_STATUS) != null
                        || megaDevice.getProperty("motion_track_status") != null,
        supportsCalibration = megaDevice.getAction(ThingIdentifier.PTZ_CALIBRATE) != null,
        supports360 = megaDevice.getAction(ThingIdentifier.SET_ROUND_LOOK_360) != null,
        supportsPanorama = deviceManager.isFeatureSupported(
            com.anker.esiotkit.domain_thing.api.domain.Feature.PANORAMA,
            megaDevice.deviceSn
        ),
        supportsQuickResponse = megaDevice.getAction(ThingIdentifier.PLAY_QUICKRESPONSE_P2P) != null
                             || megaDevice.getAction(ThingIdentifier.PLAY_QUICKRESPONSE) != null,
        supportsPreset = megaDevice.getAction(ThingIdentifier.QUERY_PRESET_POSITIONS) != null,
        supportsPTZControl = megaDevice.getAction(ThingIdentifier.PTZ_ACTION_CONTROL) != null,
      )
    }

    // Parse toolbar list from Thing Model
    val features = enumList.map { it.value }.toSet()
    appLogger.i("$TAG detectFeatureSupport: toolbar list values = $features")

    val hasPtzAction = megaDevice.hasAction(ThingIdentifier.PTZ_ACTION_CONTROL)
    val supportsPTZ = (features.contains(BottomMenuValue.PANTILI) || features.contains(BottomMenuValue.PTZ_CONTROL_ENTRANCE)) && hasPtzAction
    appLogger.i("$TAG detectFeatureSupport: PRESET_DIAG " +
      "sn=${megaDevice.deviceSn} pn=${megaDevice.devicePn} " +
      "hasToolbar16=${features.contains(BottomMenuValue.PRESETS)} " +
      "hasPtzAction=$hasPtzAction supportsPTZ=$supportsPTZ")

    // Base feature support from toolbar list
    var support360 = features.contains(BottomMenuValue.MEDIA_ROUND_LOOK)

    // Apply device-specific overrides
    // Reference: BatteryCam/component_video/viewholder/BaseLiveBottomMenuViewHolder.kt:463
    if (isDeviceWithSpecialHandling(SN_PREFIX_8115) || isDeviceWithSpecialHandling(SN_PREFIX_817L)) {
      // Temporarily disable panorama function for 8115/817L
      appLogger.w("$TAG Panorama disabled for device ${megaDevice.deviceSn} (known limitation)")
      support360 = false
    }

    return FeatureSupport(
      supportsRecord = features.contains(BottomMenuValue.RECORD),
      supportsScreenshot = features.contains(BottomMenuValue.SCREENSHOT),
      supportsTalk = features.contains(BottomMenuValue.INTERCOM),
      supportsMute = features.contains(BottomMenuValue.SOUND),
      supportsNightVision = features.contains(BottomMenuValue.NIGHT_VISION),
      supportsAlarm = megaDevice.hasProperty(ThingIdentifier.SET_ALARM_TYPE),
      supportsLight = features.contains(BottomMenuValue.LIGHT),
      supportsTracking = features.contains(BottomMenuValue.AI_TRACKING) || features.contains(BottomMenuValue.LIVE_TRACKING),
      supportsCalibration = features.contains(BottomMenuValue.CALIBRATION),
      supports360 = support360,
      supportsPanorama = deviceManager.isFeatureSupported(
        com.anker.esiotkit.domain_thing.api.domain.Feature.PANORAMA,
        megaDevice.deviceSn
      ),
      supportsQuickResponse = features.contains(BottomMenuValue.REPLY),
      supportsPreset = features.contains(BottomMenuValue.PRESETS),
      supportsPTZControl = supportsPTZ,
      supportsSingleDualSwitch = features.contains(BottomMenuValue.SINGLE_DUAL_SWITCH)
        || liveStreamAPI.supportsDualCamera(),
    )
    if (featureSupport.supportsSingleDualSwitch) {
      appLogger.i("$TAG detectFeatureSupport: dual camera enabled, toolbar=${features.contains(BottomMenuValue.SINGLE_DUAL_SWITCH)}, sdk=${liveStreamAPI.supportsDualCamera()}, sn=${megaDevice.deviceSn}")
    }
  }

  private val featureSupport = detectFeatureSupport()

  init {
    logL2Params()
  }

  private fun logL2Params() {
    try {
      val sn = megaDevice.deviceSn
      val iotDevice = megaDevice.iotDevice
      val talkBack = iotDevice.getService<com.anker.esiotkit.core.iotmodel.default_service.TalkBackService>("talk_back_control")
      val ledStatus = iotDevice.getService<com.anker.esiotkit.core.iotmodel.default_service.LedStatusService>("led_on_off")
      val motionType = iotDevice.getService<com.anker.esiotkit.core.iotmodel.default_service.MotionDetectionTypeService>("motion_detection_type")
      val activeZone = iotDevice.getService<com.anker.esiotkit.core.iotmodel.default_service.ActiveZoneService>("activityzone_config")
      val privacyZone = iotDevice.getService<com.anker.esiotkit.core.iotmodel.default_service.PrivacyZoneService>("set_privacy_zone")

      appLogger.i("L2_PARAMS/TalkBack: isFullDuplex=${talkBack?.isFullDuplex}, sn=$sn")
      appLogger.i("L2_PARAMS/LedStatus: getLedSwitch=${ledStatus?.getLedSwitch()}, sn=$sn")
      appLogger.i("L2_PARAMS/MotionDetectionType: type=${motionType?.getMotionDetectionType()}, aiRaw=${motionType?.getAIDetectionTypeRaw()}, sn=$sn")
      appLogger.i("L2_PARAMS/ActiveZone: zoneMaxNum=${activeZone?.zoneMaxNum}, zoneSideNum=${activeZone?.zoneSideNum}, sn=$sn")
      appLogger.i("L2_PARAMS/PrivacyZone: zoneMaxNum=${privacyZone?.zoneMaxNum}, zoneSideNum=${privacyZone?.zoneSideNum}, sn=$sn")
    } catch (e: Exception) {
      appLogger.e("L2_PARAMS/error: ${e.message}")
    }
  }

  private val supportsRecord = featureSupport.supportsRecord
  private val supportsScreenshot = featureSupport.supportsScreenshot
  private val supportsTalk = featureSupport.supportsTalk
  private val supportsMute = featureSupport.supportsMute
  private val supportsNightVision = featureSupport.supportsNightVision
  private var nightVisionMode by mutableIntStateOf(
    megaDevice.getProperty(ThingIdentifier.NIGHTVISION_TYPE)?.get()?.asInt() ?: 0
  )
  private val nightVisionAPI by lazy { deviceManager.nightVision(megaDevice.deviceSn) }
  val nightVisionModeOptions by lazy { nightVisionAPI.getModeOptions() }
  var nightVisionDialogVisible by mutableStateOf(false)

  private fun resolveNightVisionModeName(mode: Int): String {
    val property = megaDevice.getProperty(ThingIdentifier.NIGHTVISION_TYPE)
    val enumList = property?.td?.dataType?.specs?.enumList
    if (!enumList.isNullOrEmpty()) {
      return enumList.firstOrNull { it.value.toIntOrNull() == mode }?.desc ?: "Mode $mode"
    }
    return when (mode) {
      0 -> "Off"
      1 -> "On"
      else -> "Mode $mode"
    }
  }
  private val supportsAlarm = featureSupport.supportsAlarm
  private val supportsLight = featureSupport.supportsLight
  private val supportsTracking = featureSupport.supportsTracking
  private val supportsCalibration = featureSupport.supportsCalibration
  private val supports360 = featureSupport.supports360
  private val supportsPanorama: Boolean by lazy {
    deviceManager.panorama(megaDevice.deviceSn).isSupported()
  }
  private val supportsQuickResponse = featureSupport.supportsQuickResponse
  private val supportsPreset = featureSupport.supportsPreset
  private val supportsPTZControl = featureSupport.supportsPTZControl
  private val supportsSingleDualSwitch = featureSupport.supportsSingleDualSwitch

  fun getAlarmEnumList(): List<com.anker.esiotkit.net_api.response.thing.TDEnum> {
    // Try action TD first (inputData has enumList), then property TD
    val action = findAlarmAction()
    val actionEnumList = action?.td?.inputData?.specs?.enumList
    val rawList = if (!actionEnumList.isNullOrEmpty()) actionEnumList
    else {
      val property = findAlarmProperty()
      property?.td?.dataType?.specs?.enumList.orEmpty()
    }

    // Solo 设备（无 homebase）过滤掉 homebase alarm（value=4）
    // isStandalone: deviceSn == parentSn 且非基站，即独立工作的摄像头（如 T8W11）
    val isSoloDevice = megaDevice.isStandalone() && !megaDevice.isStation()
    return if (isSoloDevice) {
      rawList.filter { it.value.toIntOrNull() != 4 }
    } else {
      rawList
    }
  }

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    MegaLiveStreamUiState(
      isPlaying = isPlaying,
      isBuffering = isBuffering,
      rate = rate,
      recordTime = recordTime,
      recordStatus = recordStatus,
      talkStatus = talkStatus,
      muteStatus = muteStatus,
      alarmStatus = alarmStatus,
      alarmCountdown = alarmCountdown,
      alarmTypeName = alarmTypeName,
      lightStatus = lightStatus,
      trackingStatus = trackingStatus,
      calibrationStatus = calibrationStatus,
      round360Status = round360Status,
      panoramaStatus = panoramaStatus,
      controlPositionStatus = controlPositionStatus,
      panoramaResultPath = _panoramaResultPath.value,
      supportsRecord = supportsRecord,
      supportsScreenshot = supportsScreenshot,
      supportsTalk = supportsTalk,
      supportsMute = supportsMute,
      supportsNightVision = supportsNightVision,
      nightVisionMode = nightVisionMode,
      nightVisionModeName = resolveNightVisionModeName(nightVisionMode),
      supportsAlarm = supportsAlarm,
      supportsLight = supportsLight,
      supportsTracking = supportsTracking,
      supportsCalibration = supportsCalibration,
      supports360 = supports360,
      supportsPanorama = supportsPanorama,
      supportsQuickResponse = supportsQuickResponse,
      quickResponseList = quickResponseList,
      supportsPreset = supportsPreset,
      supportsPTZControl = supportsPTZControl,
      supportsSingleDualSwitch = supportsSingleDualSwitch,
      supportedVideoModes = supportedVideoModes,
      currentVideoMode = currentVideoMode,
    )
  }

  init {
    dataLogger.i("$TAG LiveDebug: [INIT] hashCode=${this.hashCode()} sn=${megaDevice.deviceSn}")
    logAvailableProperties()
    setupPlayerListener()
  }

  private fun setupPlayerListener() {
    liveMediaPlayer.setPlayListener(object : LivePlayListener {
      override fun onPlayState(status: LivePlayerState, message: String) {
        dataLogger.i("$TAG LiveDebug: [PLAY_STATE] status=$status isPlaying(before)=$isPlaying")
        when (status) {
          LivePlayerState.Loading -> {
            this@MegaLiveStreamViewModel.isBuffering = true
          }
          LivePlayerState.Playing -> {
            isPlaying = true
            isBuffering = false
            if (!coverCaptured) {
              coverCaptured = true
              appLogger.i("$TAG play: auto-capture cover on first frame, deviceSn=${megaDevice.deviceSn}")
              captureForCover()
            }
          }
          LivePlayerState.Failed -> {
            isPlaying = false
            isPlayFinished = true
            isBuffering = false
            resolutionReady = false
            captureCoverNow("Failed")
            // Stop forever ping when stream fails
            stopForeverPingForBatteryDevice()
          }
          LivePlayerState.Timeout -> {
            isPlaying = false
            isPlayFinished = true
            isBuffering = false
            resolutionReady = false
            captureCoverNow("Timeout")
            // Stop forever ping when stream times out
            stopForeverPingForBatteryDevice()
          }
          LivePlayerState.Stop -> {
            isPlaying = false
            isPlayFinished = true
            resolutionReady = false
            captureCoverNow("Stop")
            // Stop forever ping when stream stops
            stopForeverPingForBatteryDevice()
          }
          LivePlayerState.Idle -> {}
        }
      }

      override fun onSpeakState(status: TalkState, message: String) {
        dataLogger.i("MegaLiveStreamViewModel/onSpeakState/status:$status, message:$message")
        talkStatus = when (status) {
          TalkState.Idle -> MegaLiveFunctionStatus.Idle
          TalkState.Loading -> MegaLiveFunctionStatus.Loading
          TalkState.Talking -> MegaLiveFunctionStatus.Running
          TalkState.Failed -> MegaLiveFunctionStatus.Error
          TalkState.Timeout -> MegaLiveFunctionStatus.Error
        }
      }

      override fun onRecordState(status: RecordState, timeStamp: Long, message: String) {
        dataLogger.i("MegaLiveStreamViewModel/onRecordState/status:$status, timeStamp:$timeStamp, message:$message")
        when (status) {
          RecordState.Idle -> {}
          RecordState.Recording -> recordTime = timeStamp
          RecordState.Success -> saveRecord()
          RecordState.Failed -> {}
          RecordState.Timeout -> {}
        }
      }

      override fun onMuteState(isMute: Boolean) {
        muteStatus = if (isMute) {
          MegaLiveFunctionStatus.Running
        } else {
          MegaLiveFunctionStatus.Idle
        }
      }

      // 双业务分流（评审 P0-1）：所有保存由调用点消费 cutImage() 返回值完成（双端确定、无竞态）；
      // iOS cutImage 会同步 fire 本回调，这里仅记日志，避免二次保存/重复进相册
      override fun onCutImage(image: IotImage) {
        dataLogger.i("MegaLiveStreamViewModel/onCutImage: deviceSn=${megaDevice.deviceSn}, result:$image")
      }

      override fun onRate(rate: Int) {
        this@MegaLiveStreamViewModel.rate = rate
      }

      override fun onResolution(width: Int, height: Int) {
        // 硬闸门：证实解码管线已产出有效分辨率帧，封面抓图的前置条件
        markResolutionReady(width, height)
        val aspectRatio = width / height.toFloat()
        if (this@MegaLiveStreamViewModel.aspectRatio != aspectRatio && aspectRatio > 0) {
          this@MegaLiveStreamViewModel.aspectRatio = aspectRatio
        }
      }

      override fun onVideoInfo(info: VideoInfo) {
        if (info.width <= 0 || info.height <= 0) return
        val aspectRatio = info.width / info.height.toFloat()
        if (this@MegaLiveStreamViewModel.aspectRatio != aspectRatio && aspectRatio > 0) {
          this@MegaLiveStreamViewModel.aspectRatio = aspectRatio
        }
        val now = currentTimeMillis()
        if (now - onVideoInfoLastLogTime < ON_VIDEO_INFO_LOG_INTERVAL_MS) return
        onVideoInfoLastLogTime = now
        dataLogger.i("MegaLiveStreamViewModel/onVideoInfo: ${info.width}x${info.height}")
      }
    })
  }

  fun action(action: MegaLiveStreamAction) {
    dataLogger.i("$TAG action: received=${action::class.simpleName}")
    when (action) {
      is MegaLiveStreamAction.Play -> play(action.playType)
      MegaLiveStreamAction.Stop -> stop()
      MegaLiveStreamAction.Exit -> exit()
      MegaLiveStreamAction.Capture -> capture()
      MegaLiveStreamAction.Record -> record()
      MegaLiveStreamAction.Mute -> mute()
      MegaLiveStreamAction.NightVision -> nightVision()
      is MegaLiveStreamAction.SetNightVisionMode -> updateNightVisionMode(action.mode)
      MegaLiveStreamAction.Talk -> talk()
      is MegaLiveStreamAction.TriggerAlarm -> triggerManualAlarm(action.type)
      MegaLiveStreamAction.StopAlarm -> stopManualAlarm()
      MegaLiveStreamAction.Light -> toggleLight()
      MegaLiveStreamAction.AITracking -> toggleAITracking()
      MegaLiveStreamAction.Calibration -> calibration()
      MegaLiveStreamAction.Round360 -> {
        dataLogger.i("$TAG action: Round360 dispatched (NOT panorama)")
        round360()
      }
      is MegaLiveStreamAction.Panorama -> {
        dataLogger.i("$TAG action: Panorama dispatched, platformContext=${action.platformContext}")
        panorama(action.platformContext)
      }
    is MegaLiveStreamAction.SingleDualSwitch -> switchSingleDualView(action.isDualView)
    is MegaLiveStreamAction.SwitchVideoMode -> switchVideoMode(action.mode)
    is MegaLiveStreamAction.LoadQuickResponseList -> loadQuickResponseList()
    is MegaLiveStreamAction.LoadSupportedVideoModes -> loadSupportedVideoModes()
    }
  }

  private fun play(playType: MegaPlayType) {
    dataLogger.i("$TAG LiveDebug: [PLAY] entry isPlaying=$isPlaying playType=$playType")
    if (isPlaying) {
      appLogger.i("$TAG play: already playing, skip restart")
      return
    }
    if (playerReleased) {
      dataLogger.i("$TAG LiveDebug: [PLAY] player was released, recreating")
      liveMediaPlayer = LiveMediaPlayer(megaDevice)
      playerReleased = false
      setupPlayerListener()
    }
    coverCaptured = false
    // 评审修正：每次起流重置硬闸门，防同实例换设备/重播时静默跳过抓图
    resolutionReady = false
    isPlayFinished = false
    lensSwitchCoverJob?.cancel()
    lensSwitchCover = null
    val deviceSn = megaDevice.deviceSn
    val devicePn = megaDevice.devicePn
    val parentSn = megaDevice.iotDevice.parentSn.orEmpty()
    val deviceChannel = megaDevice.iotDevice.deviceChannel
    val liveParams = LiveParams()

    val isNvrChild = parentSn.startsWith(SN_PREFIX_8N00) ||
      deviceSn.startsWith(SN_PREFIX_8N00) ||
      devicePn.startsWith(SN_PREFIX_8E00) ||
      devicePn.startsWith(SN_PREFIX_8P00) ||
      devicePn.startsWith(SN_PREFIX_8P10)
    if (isNvrChild) {
      val channel = if (deviceChannel >= 0) deviceChannel else 0
      liveParams.channelOrderList = listOf(LiveChannelOrder(0, channel, 0))
      liveParams.audio_chn = channel
      liveParams.stitch_mode = StitchType.Stitch_Std_1X1
      liveParams.station_video_type = 0
      liveParams.liveType = LiveType.Normal
    } else {
      liveParams.liveType = LiveType.Hotspot
    }

    when (playType) {
      MegaPlayType.MainCamera -> liveParams.cameraType = CameraType.CAMERA_TYPE_MAIN
      MegaPlayType.SubCamera -> liveParams.cameraType = CameraType.CAMERA_TYPE_SECOND
      else -> {
        val sn = megaDevice.deviceSn
        if (supportsSingleDualSwitch) {
          val isSoloCam = sn.startsWith("T8170") || sn.startsWith("T8416") || sn.startsWith("T8425")
          val service = megaDevice.iotDevice.getService<DualCameraService>("dual_camera")
          val savedMode = service?.getCurrentMode()
          val cameraType = if (savedMode == DualCameraService.MODE_SINGLE_SECOND) {
            CameraType.CAMERA_TYPE_SECOND
          } else {
            CameraType.CAMERA_TYPE_MAIN
          }
          liveParams.cameraType = cameraType
          if (isSoloCam) {
            // 设备冷启动副摄未就绪，直接请求副摄出流会 25s 超时（实测）；
            // 统一用主摄参数出流，Playing 后由 postStartRestoreMode 切换到用户保存的镜头
            liveParams.cameraType = CameraType.CAMERA_TYPE_MAIN
            dataLogger.i("$TAG play/dualCameraParams: savedMode=$savedMode cameraType=MAIN(forced) type=soloCam sn=$sn")
          } else {
            // 门铃出流不设 channelOrderList/stitch_mode（commit 6e7a1bc 验证通过形态）；
            // 实测设置后 T8214 js_fallback 路径 RequestTimeout 无法取流（09-14 22:07）
            dataLogger.i("$TAG play/dualCameraParams: savedMode=$savedMode cameraType=$cameraType type=doorbell sn=$sn")
          }
        }
      }
    }

    val cameraOnOff = megaDevice.getProperty(ThingIdentifier.CAMERA_ON_OFF)
    if (cameraOnOff != null) {
        val rawValue = cameraOnOff.get().asString()
        if (rawValue == "0" || rawValue.equals("false", ignoreCase = true)) {
            appLogger.w(
                "$TAG play: blocked by camera_on_off, deviceSn=$deviceSn, value=$rawValue"
            )
            emitToast("Camera is turned off. Enable Camera On/Off in Device Settings.")
            return
        }
    }

    appLogger.i(
      "$TAG play: sn=$deviceSn ch=$deviceChannel type=$playType" +
        " liveType=${liveParams.liveType} camType=${liveParams.cameraType}" +
        " audioCh=${liveParams.audio_chn} stitch=${liveParams.stitch_mode}" +
        (if (isNvrChild) " nvrCh=$deviceChannel" else "")
    )
    startForeverPingForBatteryDevice()
    val sn0 = megaDevice.deviceSn
    val isSoloCam = sn0.startsWith("T8170") || sn0.startsWith("T8416") || sn0.startsWith("T8425")
    val soloCamSavedMode = if (isSoloCam && supportsSingleDualSwitch) {
      megaDevice.iotDevice.getService<DualCameraService>("dual_camera")?.getCurrentMode()
    } else null
    try {
      liveMediaPlayer.startPlay(liveParams)
      if (isSoloCam && supportsSingleDualSwitch && soloCamSavedMode == DualCameraService.MODE_SINGLE_SECOND) {
        launch {
          try {
            dataLogger.i("$TAG play/postStartRestoreMode/wait: mode=secondary sn=$sn0")
            // SoloCam 冷连接出流需 25s+ 才到 Playing（播放器内部超时 setPlayTimeout(3000,25000)）
            // 等待循环必须覆盖整个超时窗口，Playing 到达后立即恢复镜头，终态（Failed/Timeout/Stop）时放弃
            var waitCount = 0
            while (!isPlaying && !isPlayFinished && waitCount < 155) {
              delay(200)
              waitCount++
            }
            if (!isPlaying) {
              appLogger.w("$TAG play/postStartRestoreMode/skip: isPlaying=$isPlaying isPlayFinished=$isPlayFinished waitCount=$waitCount, abort restore sn=$sn0")
              return@launch
            }
            // 切换前截取当前主摄帧作为过渡覆盖，盖住设备切换期间的绿屏过渡帧
            val coverFrame = cutImageSafe()
            if (coverFrame != null) {
              lensSwitchCover = coverFrame
              dataLogger.i("$TAG play/lensSwitchCover/show: coverCaptured sn=$sn0")
            } else {
              dataLogger.i("$TAG play/lensSwitchCover/skip: cutImage returned null, no cover sn=$sn0")
            }
            lensSwitchCoverJob?.cancel()
            lensSwitchCoverJob = launch {
              delay(3000)
              if (lensSwitchCover != null) {
                appLogger.w("$TAG play/lensSwitchCover/timeout: 3s no resolution callback, remove cover sn=$sn0")
                lensSwitchCover = null
              }
            }
            dataLogger.i("$TAG play/postStartRestoreMode: mode=${DualCameraService.MODE_SINGLE_SECOND} sn=$sn0 waitCount=$waitCount")
            when (val r = liveStreamAPI.setVideoPlayMode(DualCameraService.MODE_SINGLE_SECOND, restore = 0)) {
              is ThingResult.Success -> dataLogger.i("$TAG play/postStartRestoreMode/success: mode=${DualCameraService.MODE_SINGLE_SECOND} sn=$sn0")
              is ThingResult.Error -> appLogger.e("$TAG play/postStartRestoreMode/failure: code=${r.code} sn=$sn0")
            }
          } catch (e: Exception) {
            appLogger.e("$TAG play/postStartRestoreMode/exception: ${e.message} sn=$sn0")
          }
        }
      } else if (!isSoloCam && supportsSingleDualSwitch && currentVideoMode != null && currentVideoMode != DualCameraService.MODE_DEFAULT) {
        dataLogger.i("$TAG play/postStartRestoreMode/skip: doorbell, device mode preserved, sn=$sn0")
      }
    } catch (e: Exception) {
      appLogger.e("$TAG play/startPlayFailed: sn=$deviceSn error=${e.message}")
    }
  }

  private fun stop() {
    liveMediaPlayer.stopPlay()
    if (talkStatus.running) {
      liveMediaPlayer.stopTalk()
    }
  }

  private fun exit() {
    dataLogger.i("$TAG LiveDebug: [EXIT] hashCode=${this.hashCode()} isPlaying=$isPlaying")
    playerReleased = true
    prePlaySplitModeApplied = false
    prePlaySplitModeInProgress = false
    resolutionReady = false
    liveMediaPlayer.stopAndRelease()
    coverCaptured = false
    lensSwitchCoverJob?.cancel()
    lensSwitchCover = null
    // Ensure forever ping is stopped when exiting live stream
    stopForeverPingForBatteryDevice()
  }

  // 用户主动截图 → 系统相册（保留现状）+ 有效帧同步更新封面
  private fun capture() {
    if (!resolutionReady) {
      appLogger.w("$TAG capture: skipped, resolution not ready, deviceSn=${megaDevice.deviceSn}")
      emitToast("Stream is loading, please wait")
      return
    }
    val image = cutImageSafe()
    if (image == null) {
      appLogger.w("$TAG capture: cutImage returned null")
      return
    }
    saveImage(image)
    appLogger.i("$TAG capture: manual screenshot saved to album, deviceSn=${megaDevice.deviceSn}")
    screenModelScope.launch { saveCoverIfValid(image) }
  }

  // 预置位抓图：仅沙盒封面 + 内存回调，不进相册
  fun captureForPreset() {
    if (!resolutionReady) {
      appLogger.w("$TAG captureForPreset: skipped, resolution not ready")
      return
    }
    val image = cutImageSafe()
    if (image == null) {
      appLogger.w("$TAG captureForPreset: skipped, cutImage busy or null")
      return
    }
    appLogger.i("$TAG captureForPreset: captured, sandbox only, deviceSn=${megaDevice.deviceSn}")
    screenModelScope.launch { saveCoverIfValid(image) }
    onCutImageCallback?.invoke(image)
  }

  // ===== 封面抓图（仅沙盒、无感、fail-closed） =====
  private var resolutionReady = false
  private val cutMutex = kotlinx.coroutines.sync.Mutex()

  fun markResolutionReady(width: Int, height: Int) {
    if (width > 0 && height > 0) {
      resolutionReady = true
      // 副摄新帧到达（分辨率回调），解除过渡覆盖层；3s 兜底超时由 lensSwitchCoverJob 处理
      if (lensSwitchCover != null) {
        lensSwitchCoverJob?.cancel()
        lensSwitchCover = null
        dataLogger.i("$TAG play/lensSwitchCover/hide: new frame resolution ${width}x${height} sn=${megaDevice.deviceSn}")
      }
    }
  }

  // 抓图互斥（评审修正）：防并发 cutImage 导致原子写 rename 冲突
  // 流未就绪检查（Bug#9355）：resolutionReady 在 onResolution 回调中置 true，
  // 表示解码管线已产出有效分辨率帧。流未就绪时 cutImage 访问空帧缓冲区触发 EXC_BAD_ACCESS。
  private fun cutImageSafe(): IotImage? {
    if (!resolutionReady) {
      appLogger.w("$TAG cutImageSafe: skipped, resolution not ready")
      return null
    }
    if (!cutMutex.tryLock()) return null
    return try {
      liveMediaPlayer.cutImage()
    } finally {
      cutMutex.unlock()
    }
  }

  private suspend fun cutImageSafeSuspend(): IotImage? = cutMutex.withLock { liveMediaPlayer.cutImage() }

  // 同步抓帧 + 异步保存：流结束/状态回调场景，消除与 stopPlay/release 的竞态
  private fun captureCoverNow(source: String = "unknown") {
    if (!resolutionReady) {
      appLogger.i("$TAG captureCoverNow: skipped, resolution not ready, source=$source")
      return
    }
    val image = cutImageSafe()
    if (image == null) {
      appLogger.w("$TAG captureCoverNow: cutImage returned null, source=$source")
      return
    }
    screenModelScope.launch { saveCoverIfValid(image) }
  }

  private fun captureForCover() {
    if (!resolutionReady) {
      appLogger.i("$TAG captureForCover: skipped, resolution not ready")
      return
    }
    screenModelScope.launch {
      try {
        val image = cutImageSafeSuspend()
        if (image == null) {
          appLogger.w("$TAG captureForCover: cutImage returned null")
          return@launch
        }
        saveCoverIfValid(image)
      } catch (e: Exception) {
        appLogger.w("$TAG captureForCover failed: ${e.message}")
      }
    }
  }

  private suspend fun saveCoverIfValid(image: IotImage) {
    try {
      deviceManager.picture(megaDevice.deviceSn).saveCoverImage(image)
    } catch (e: Exception) {
      appLogger.w("$TAG saveCoverIfValid failed: ${e.message}")
    }
  }

  private fun record() {
    launch {
      val running = recordStatus.running
      recordStatus = MegaLiveFunctionStatus.Loading
      delay(1000)
      if (running) {
        recordStatus = MegaLiveFunctionStatus.Idle
        stopRecord()
      } else {
        if (!resolutionReady) {
          recordStatus = MegaLiveFunctionStatus.Idle
          appLogger.w("$TAG record: skipped, resolution not ready, deviceSn=${megaDevice.deviceSn}")
          emitToast("Stream is loading, please wait")
          return@launch
        }
        recordStatus = MegaLiveFunctionStatus.Running
        startRecord()
      }
    }
  }

  var recordFilePath: String = ""
  private fun startRecord() {
    recordFilePath = getDocumentFilePath().removeSuffix("/") + "/${currentTimeMillis()}.mp4"
    if (!platformFileSystem.exists(recordFilePath.toPath())) {
      platformFileSystem.createFile(recordFilePath.toPath())
    }
    liveMediaPlayer.startRecord(
      filePath = recordFilePath
    )
  }

  private fun stopRecord() {
    liveMediaPlayer.stopRecord()
  }

  private fun saveRecord() {
    saveVideo(recordFilePath) { success ->
      platformFileSystem.delete(recordFilePath.toPath())
      if (success) {
        emitToast("Record success")
      } else {
        emitToast("Record failed")
      }
    }
  }

  private fun mute() {
    if (liveMediaPlayer.isMute()) {
      muteStatus = MegaLiveFunctionStatus.Idle
      liveMediaPlayer.setMute(false)
    } else {
      muteStatus = MegaLiveFunctionStatus.Running
      liveMediaPlayer.setMute(true)
    }
  }

  private fun nightVision() {
    launch {
      val options = nightVisionAPI.getModeOptions()
      if (options.isNotEmpty()) {
        appLogger.i("$TAG nightVision: using NightVisionAPI, options=${options.size}, sn=${megaDevice.deviceSn}")
        when (val result = nightVisionAPI.getMode()) {
          is ThingResult.Success -> nightVisionMode = result.data
          is ThingResult.Error -> appLogger.e("$TAG nightVision: getMode failed, code=${result.code}")
        }
        if (options.size > 2) {
          nightVisionDialogVisible = true
        } else {
          val currentKey = nightVisionMode
          val newKey = if (currentKey == options[0].key) options[1].key else options[0].key
          appLogger.i("$TAG nightVision: toggle $currentKey -> $newKey")
          updateNightVisionMode(newKey)
        }
        return@launch
      }

      val property = megaDevice.getProperty(ThingIdentifier.NIGHTVISION_TYPE)
      val currentValue = property?.get()?.asInt() ?: 0
      val newMode = if (currentValue == 0) NightVisionMode.BLACK_WHITE else NightVisionMode.OFF

      appLogger.i("$TAG nightVision: current=$currentValue, setting to ${newMode.value}")

      val result = liveStreamAPI.setNightVision(newMode)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Night vision ${if (newMode == NightVisionMode.BLACK_WHITE) "enabled" else "disabled"}")
          appLogger.i("$TAG nightVision: set to ${newMode.value} success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG nightVision: failed, code=${result.code}, msg=${result.message}")
          emitToast("Failed to change night vision: ${result.message}")
        }
      }
    }
  }

  private fun updateNightVisionMode(mode: Int) {
    launch {
      nightVisionDialogVisible = false

      if (nightVisionModeOptions.isNotEmpty()) {
        val modeName = nightVisionModeOptions
            .firstOrNull { it.key == mode }?.displayName ?: "Mode $mode"
        appLogger.i("$TAG updateNightVisionMode: using NightVisionAPI, setting to $mode ($modeName)")

        when (val result = nightVisionAPI.setMode(mode)) {
          is ThingResult.Success -> {
            emitToast("Night vision: $modeName")
            appLogger.i("$TAG updateNightVisionMode: set to $mode ($modeName) success")
            nightVisionMode = mode
          }
          is ThingResult.Error -> {
            appLogger.e("$TAG updateNightVisionMode: failed, code=${result.code}, msg=${result.message}")
            emitToast("Failed to change night vision: ${result.message}")
            when (val restore = nightVisionAPI.getMode()) {
              is ThingResult.Success -> nightVisionMode = restore.data
              is ThingResult.Error -> appLogger.e("$TAG updateNightVisionMode: restore failed, code=${restore.code}")
            }
          }
        }
        return@launch
      }

      val modeName = resolveNightVisionModeName(mode)
      appLogger.i("$TAG updateNightVisionMode: setting to $mode ($modeName)")

      val property = megaDevice.getProperty(ThingIdentifier.NIGHTVISION_TYPE)
      if (property == null) {
        appLogger.e("$TAG updateNightVisionMode: property not found")
        emitToast("Night vision not supported by this device")
        return@launch
      }

      val isBoolType = property.td?.dataType?.type ==
        com.anker.esiotkit.net_api.response.thing.TDDataType.TYPE_BOOL
      val payload = if (isBoolType) {
        com.anker.esiotkit.domain_thing.model.interaction.ThingPayload.fromBoolean(mode != 0)
      } else {
        com.anker.esiotkit.domain_thing.model.interaction.ThingPayload.fromInt(mode)
      }

      property.set(payload).onSuccess {
        emitToast("Night vision: $modeName")
        appLogger.i("$TAG updateNightVisionMode: set to $mode ($modeName) success")
        nightVisionMode = mode
      }.onFailure { code, _ ->
        appLogger.e("$TAG updateNightVisionMode: failed, code=$code")
        emitToast("Failed to change night vision")
      }
    }
  }

  /**
   * Unified error handling for Thing Model interactions
   */
  private fun handlePropertyNotFound(featureName: String) {
    appLogger.e("$TAG $featureName: property/action not found")
    emitToast("$featureName not supported by this device")
  }

  private fun handleOperationFailure(featureName: String, result: ESResult, payload: Map<String, Any>) {
    val code = result.code
    val msg = payload["msg"]?.toString() ?: "Unknown error"
    appLogger.e("$TAG $featureName: failed, code=$code, msg=$msg")
    emitToast("Failed to $featureName: $msg")
  }

  private fun talk() {
    // Speaker 关闭时对讲无法播放声音，拦截并提示
    val speakerOn = megaDevice.getProperty(ThingIdentifier.SPEAKER_ON_OFF)?.get()?.asBoolean() != false
    if (!speakerOn) {
      emitToast("Speaker is turned off. Please enable it in Audio Settings.")
      return
    }
    val enableMic = megaDevice.getProperty(ThingIdentifier.MICROPHONE_ON_OFF)?.get()?.asBoolean() != false
    if (!enableMic) {
      emitToast("The device mic is turned off")
      return
    }
    if (talkStatus.running) {
      liveMediaPlayer.stopTalk()
    } else {
      liveMediaPlayer.startTalk()
    }
  }

  /**
   * Find alarm action or property. Action is preferred (property may not be writable).
   */
  private fun findAlarmAction() = megaDevice.getAction(ThingIdentifier.SET_ALARM_TYPE)

  private fun findAlarmProperty() = megaDevice.getProperty(ThingIdentifier.SET_ALARM_TYPE)

  /**
   * Convert device type value to SDK AlarmType enum
   * type 是物模型 enumList 的 value，即设备实际 type 字段值（2/22/23）
   */
  private fun intToAlarmType(type: Int): AlarmType =
    AlarmType.entries.firstOrNull { it.typeValue == type } ?: AlarmType.SOUND_AND_LIGHT

  private fun isSoundBasedAlarm(type: Int): Boolean {
    val property = findAlarmProperty()
    val enumList = property?.td?.dataType?.specs?.enumList
    val desc = enumList?.firstOrNull { it.value.toIntOrNull() == type }?.desc.orEmpty()
    return desc.contains("sound", ignoreCase = true)
  }

  private fun triggerManualAlarm(type: Int) {
    launch {
      val speakerOn = megaDevice.getProperty(ThingIdentifier.SPEAKER_ON_OFF)?.get()?.asBoolean() != false
      // 仅sound 相关会被 speakerOn 影响
      if (!speakerOn && isSoundBasedAlarm(type)) {
        emitToast("Speaker is turned off. Sound alarm is unavailable.")
        return@launch
      }

      alarmStatus = MegaLiveFunctionStatus.Loading
      lastAlarmType = type

      // Resolve type name from TD enumList (property TD has the enum info)
      val property = findAlarmProperty()
      val enumList = property?.td?.dataType?.specs?.enumList
      val alarmDesc = enumList?.firstOrNull { it.value.toIntOrNull() == type }?.desc.orEmpty()
      alarmTypeName = alarmDesc.ifBlank {
        when (type) {
          DEVICE_ALARM_TYPE_SOUND_ONLY       -> "Sound Only"
          DEVICE_ALARM_TYPE_LIGHT_ONLY       -> "Light Only"
          DEVICE_ALARM_TYPE_SOUND_AND_LIGHT  -> "Sound and Light"
          else -> "Alarm"
        }
      }

      // Log property availability for debugging
      val hasSetAlarmType = megaDevice.hasProperty(ThingIdentifier.SET_ALARM_TYPE)
      appLogger.i("$TAG triggerManualAlarm: type=$type ($alarmTypeName), hasSetAlarmType=$hasSetAlarmType")

      val alarmType = intToAlarmType(type)
      val result = liveStreamAPI.triggerAlarm(alarmType)
      when (result) {
        is ThingResult.Success -> {
          appLogger.i("$TAG triggerManualAlarm: success")
          onAlarmTriggered()
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG triggerManualAlarm: failed, code=${result.code}, msg=${result.message}")
          emitToast("Failed to trigger alarm: ${result.message}")
          alarmStatus = MegaLiveFunctionStatus.Error
          launch { delay(1000); alarmStatus = MegaLiveFunctionStatus.Idle }
        }
      }
    }
  }

  private fun onAlarmTriggered() {
    alarmStatus = MegaLiveFunctionStatus.Running
    appLogger.i("$TAG onAlarmTriggered: success, starting 30s countdown")

    alarmCountdownJob?.cancel()
    alarmCountdown = 30
    alarmCountdownJob = launch {
      while (alarmCountdown > 0) {
        delay(1000)
        alarmCountdown--
      }
      stopManualAlarm()
    }
  }

  private fun stopManualAlarm() {
    launch {
      alarmCountdownJob?.cancel()
      alarmCountdownJob = null
      alarmCountdown = 0

      appLogger.i("$TAG stopManualAlarm: type=$lastAlarmType, open=false")

      val alarmType = intToAlarmType(lastAlarmType)
      val result = liveStreamAPI.triggerAlarm(alarmType, open = false)
      when (result) {
        is ThingResult.Success -> {
          appLogger.i("$TAG stopManualAlarm: success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG stopManualAlarm: failed, code=${result.code}, msg=${result.message}")
        }
      }

      alarmStatus = MegaLiveFunctionStatus.Idle
      alarmTypeName = ""
    }
  }

  private fun toggleLight() {
    launch {
      lightStatus = MegaLiveFunctionStatus.Loading
      delay(500)

      // Read current value from device and toggle
      val property = megaDevice.getProperty(ThingIdentifier.SPOTLIGHT_SWITCH)
      val currentValue = property?.get()?.asBoolean() ?: false
      val newValue = !currentValue

      appLogger.i("$TAG toggleLight: current=$currentValue, setting to $newValue")

      val result = liveStreamAPI.toggleLight(newValue)
      when (result) {
        is ThingResult.Success -> {
          lightStatus = if (newValue) MegaLiveFunctionStatus.Running else MegaLiveFunctionStatus.Idle
          emitToast("Light turned ${if (newValue) "on" else "off"}")
          appLogger.i("$TAG toggleLight: ${if (newValue) "on" else "off"} success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG toggleLight: failed, code=${result.code}, msg=${result.message}")
          emitToast("Failed to toggle light: ${result.message}")
          lightStatus = MegaLiveFunctionStatus.Error
          launch {
            delay(1000)
            lightStatus = MegaLiveFunctionStatus.Idle
          }
        }
      }
    }
  }

  private fun toggleAITracking() {
    launch {
      val currentValue = trackingStatus == MegaLiveFunctionStatus.Running
      val newValue = !currentValue
      trackingStatus = MegaLiveFunctionStatus.Loading

      appLogger.i("$TAG toggleAITracking: current=$currentValue, setting to $newValue")

      val result = liveStreamAPI.toggleAITracking(newValue)
      when (result) {
        is ThingResult.Success -> {
          trackingStatus = if (newValue) MegaLiveFunctionStatus.Running else MegaLiveFunctionStatus.Idle
          emitToast("AI Tracking ${if (newValue) "enabled" else "disabled"}")
          appLogger.i("$TAG toggleAITracking: ${if (newValue) "enabled" else "disabled"} success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG toggleAITracking: failed, code=${result.code}, msg=${result.message}")
          emitToast("Failed to toggle AI tracking: ${result.message}")
          trackingStatus = if (currentValue) MegaLiveFunctionStatus.Running else MegaLiveFunctionStatus.Idle
        }
      }
    }
  }

  private fun calibration() {
    launch {
      calibrationStatus = MegaLiveFunctionStatus.Loading

      val result = liveStreamAPI.calibrate()
      when (result) {
        is ThingResult.Success -> {
          emitToast("Calibration started")
          appLogger.i("$TAG calibration: success")
          launch {
            delay(3000) // Calibration takes time
            calibrationStatus = MegaLiveFunctionStatus.Idle
          }
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG calibration: failed, code=${result.code}, msg=${result.message}")
          emitToast("Calibration failed: ${result.message}")
          calibrationStatus = MegaLiveFunctionStatus.Error
          launch {
            delay(1000)
            calibrationStatus = MegaLiveFunctionStatus.Idle
          }
        }
      }
    }
  }

  private fun round360() {
    launch {
      round360Status = MegaLiveFunctionStatus.Loading

      val result = liveStreamAPI.round360()
      when (result) {
        is ThingResult.Success -> {
          emitToast("360° Round Look started")
          appLogger.i("$TAG round360: success")
          round360Status = MegaLiveFunctionStatus.Running
          launch {
            delay(10000)
            round360Status = MegaLiveFunctionStatus.Idle
          }
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG round360: failed, code=${result.code}, msg=${result.message}")
          emitToast("360° round look failed: ${result.message}")
          round360Status = MegaLiveFunctionStatus.Error
          launch { delay(1000); round360Status = MegaLiveFunctionStatus.Idle }
        }
      }
    }
  }

  private fun panorama(platformContext: Any) {
    emitToast("PANORAMA_ENTRY: panorama() called")
    if (!supportsPanorama) {
      appLogger.w("$TAG panorama: not supported, deviceSn=${megaDevice.deviceSn}")
      return
    }
    panoramaStatus = MegaLiveFunctionStatus.Loading
    dataLogger.i("$TAG panorama: start, deviceSn=${megaDevice.deviceSn}")

    deviceManager.panorama(megaDevice.deviceSn).generate(
        platformContext,
        object : com.anker.esiotkit.domain_thing.api.domain.PanoramaCallback {
      override fun onProgress(current: Int, total: Int) {
        appLogger.i("$TAG panorama: progress $current/$total, deviceSn=${megaDevice.deviceSn}")
      }

      override fun onComplete(filePath: String) {
        appLogger.i("$TAG panorama: complete, filePath=$filePath, deviceSn=${megaDevice.deviceSn}")
        emitToast("Panorama completed: $filePath")
        _panoramaResultPath.value = filePath
        panoramaStatus = MegaLiveFunctionStatus.Idle
      }

      override fun onError(code: Int, message: String) {
        appLogger.e("$TAG panorama: error, code=$code, message=$message, deviceSn=${megaDevice.deviceSn}")
        emitToast("Panorama failed: $message")
        panoramaStatus = MegaLiveFunctionStatus.Error
        launch { delay(1000); panoramaStatus = MegaLiveFunctionStatus.Idle }
      }

      override fun onCancelled() {
        appLogger.w("$TAG panorama: cancelled, deviceSn=${megaDevice.deviceSn}")
        panoramaStatus = MegaLiveFunctionStatus.Idle
      }
    })
  }

  fun dismissPanoramaResult() {
      _panoramaResultPath.value = null
  }

  fun panoramaControlPosition(xPercent: Int, yPercent: Int) {
    if (panoramaStatus == MegaLiveFunctionStatus.Loading) {
      appLogger.w("$TAG/panoramaControlPosition/blocked: reason=generating, deviceSn=${megaDevice.deviceSn}")
      return
    }
    if (controlPositionStatus == MegaLiveFunctionStatus.Loading) {
      appLogger.w("$TAG/panoramaControlPosition/blocked: reason=in_flight, deviceSn=${megaDevice.deviceSn}")
      return
    }

    appLogger.i("$TAG/panoramaControlPosition/start: x=$xPercent%, y=$yPercent%, deviceSn=${megaDevice.deviceSn}")
    controlPositionStatus = MegaLiveFunctionStatus.Loading

    launch {
      val result = deviceManager.panorama(megaDevice.deviceSn).controlPosition(xPercent, yPercent)
      if (result.isError) {
        val err = result as com.anker.esiotkit.domain_thing.api.model.ThingResult.Error
        appLogger.e("$TAG/panoramaControlPosition/failure: msg=${err.message}, deviceSn=${megaDevice.deviceSn}")
        controlPositionStatus = MegaLiveFunctionStatus.Error
        emitToast("Navigate failed: ${err.message}")
        launch { delay(1000); controlPositionStatus = MegaLiveFunctionStatus.Idle }
      } else {
        appLogger.i("$TAG/panoramaControlPosition/success: x=$xPercent%, y=$yPercent%, deviceSn=${megaDevice.deviceSn}")
        controlPositionStatus = MegaLiveFunctionStatus.Running
        emitToast("Navigating to ($xPercent%, $yPercent%)")
        launch { delay(2000); controlPositionStatus = MegaLiveFunctionStatus.Idle }
      }
    }
  }

  private fun switchSingleDualView(isDualView: Boolean) {
    launch {
      val videoType = if (isDualView) 12 else 0
      appLogger.i("$TAG switchSingleDualView: isDualView=$isDualView, videoType=$videoType")

      val result = liveStreamAPI.setVideoPlayMode(videoType, restore = 0)
      when (result) {
        is ThingResult.Success -> {
          appLogger.i("$TAG switchSingleDualView: success, videoType=$videoType")
          emitToast(if (isDualView) "Switched to dual camera view" else "Switched to single camera view")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG switchSingleDualView: failed, code=${result.code}, message=${result.message}")
          emitToast("Failed to switch view mode: ${result.message}")
        }
      }
    }
  }

  private fun switchVideoMode(mode: Int) {
    launch {
      dataLogger.i("$TAG switchVideoMode/start: mode=$mode, currentMode=$currentVideoMode, isPlaying=$isPlaying, sn=${megaDevice.deviceSn}")
      // Bug #11484: 改调 changeVideoMode 履行 VideoModeChangeResult 消费契约
      // (LiveStreamAPI.kt: streamRestartRequired=true 时调用方必须重启播放器，
      //  否则设备已切换视频源但解码器仍按旧帧格式解析 → 绿屏/超时)
      val result = liveStreamAPI.changeVideoMode(mode, restore = 0)
      when (result) {
        is ThingResult.Success -> {
          val change = result.data
          dataLogger.i("$TAG switchVideoMode/success: previousMode=${change.previousMode}, newMode=${change.newMode}, restartRequired=${change.streamRestartRequired}, sn=${megaDevice.deviceSn}")
          currentVideoMode = change.newMode
          emitToast("Switched to ${videoModeLabel(change.newMode)}")
          if (change.streamRestartRequired && isPlaying && isDualDoorbellDevice()) {
            // 门铃双摄: 不杀流重建（对齐 6e7a1bc 已验证行为）。stopPlay→play 重建会使设备
            // 合成管线切换与流重建叠加，实测引发 P2P 通道反复 Loading、后续命令 RequestTimeout
            // (09-14 23:22 日志)。设备收到模式命令后自行切换合成管线，流短暂 Loading 后恢复新形态
            dataLogger.i("$TAG switchVideoMode/skipped: reason=doorbell_no_restart, mode=$mode, sn=${megaDevice.deviceSn}")
          } else if (change.streamRestartRequired && isPlaying) {
            restartStreamAfterModeChange(change.newMode)
          } else if (!change.streamRestartRequired) {
            dataLogger.i("$TAG switchVideoMode/skipped: reason=same_mode, mode=$mode, sn=${megaDevice.deviceSn}")
          } else {
            dataLogger.i("$TAG switchVideoMode/skipped: reason=not_playing, mode=$mode, isPlaying=$isPlaying, sn=${megaDevice.deviceSn}")
          }
        }
        is ThingResult.Error -> {
          dataLogger.i("$TAG switchVideoMode/failure: code=${result.code}, msg=${result.message}, mode=$mode, sn=${megaDevice.deviceSn}")
          emitToast("Failed: ${result.message}")
        }
      }
    }
  }

  /**
   * Bug #11484: 双摄模式切换后的客户端流重建。
   *
   * 仅销毁/重建客户端解码器实例（红线合规）：
   * 不发送设备重启指令、不断开 P2P 会话、不触碰设备状态。
   *
   * 依据: VideoModeChangeResult 契约要求 streamRestartRequired=true 时
   * 调用方重启播放器；SoloCam 0↔1 对应不同物理镜头，帧格式不同，
   * 解码器必须重新初始化。stopPlay → 等设备切源稳定 → 复用 play()
   * （play 内部按 open_live_stream 出流，一体机由设备按已保存的 view_mode 出流）。
   */
  private suspend fun restartStreamAfterModeChange(newMode: Int) {
    dataLogger.i("$TAG restartStreamAfterModeChange/start: newMode=$newMode, isPlaying=$isPlaying, sn=${megaDevice.deviceSn}")
    // 1. 停止当前流（销毁旧解码器上下文）
    // 仅 SoloCam 物理镜头切换走此路径（门铃不重建，见 switchVideoMode 分治）
    liveMediaPlayer.stopPlay()
    // 2. 等待设备切换视频源稳定 + Stop 状态回调落地（isPlaying=false）
    //    500ms 对齐既有 postStartRestoreMode 延迟先例（8ee4204），兼容播放器实例释放异步
    delay(500)
    // 3. 复用 play() 重建流；play 开头 isPlaying 幂等保护依赖 Stop 回调已落地
    try {
      play(MegaPlayType.Normal)
      dataLogger.i("$TAG restartStreamAfterModeChange/success: newMode=$newMode, sn=${megaDevice.deviceSn}")
    } catch (e: Exception) {
      dataLogger.i("$TAG restartStreamAfterModeChange/failure: error=${e.message}, newMode=$newMode, sn=${megaDevice.deviceSn}")
    }
  }

  private fun isDualDoorbellDevice(): Boolean {
    val sn = megaDevice.deviceSn
    return sn.startsWith("T8213") || sn.startsWith("T8203") ||
      sn.startsWith("T8214") || sn.startsWith("T8215") ||
      sn.startsWith("T8217") || sn.startsWith("T8224") ||
      sn.startsWith("T8223")
  }


  private fun loadSupportedVideoModes() {
    launch {
      val result = liveStreamAPI.getSupportedVideoModes()
      if (result is ThingResult.Success) {
        val modes = result.data.map { VideoModeOption(it, videoModeLabel(it)) }
        appLogger.i("$TAG loadSupportedVideoModes: modes=$modes")
        supportedVideoModes = modes
      }
      val modeResult = liveStreamAPI.getVideoPlayMode()
      if (modeResult is ThingResult.Success) {
        currentVideoMode = modeResult.data
        appLogger.i("$TAG loadSupportedVideoModes: currentMode=$currentVideoMode")
      }
    }
  }

  fun sendQuickResponse(responseId: String, message: String) {
    launch {
      appLogger.i("$TAG sendQuickResponse: id=$responseId, message=$message")

      val voiceId = responseId.toIntOrNull() ?: return@launch

      val result = liveStreamAPI.sendQuickResponse(voiceId)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Quick response sent")
          appLogger.i("$TAG sendQuickResponse: success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG sendQuickResponse: failed, code=${result.code}, msg=${result.message}")
          emitToast("Failed to send quick response: ${result.message}")
        }
      }
    }
  }

  fun loadQuickResponseList() {
    val service = megaDevice.iotDevice.getService<com.anker.esiotkit.core.iotmodel.default_service.QuickResponseService>(
        com.anker.esiotkit.core.constants.ServiceConstants.QUICK_RESPONSE) ?: return
    launch {
      appLogger.i("$TAG loadQuickResponseList: start, deviceSn=${megaDevice.deviceSn}")
      service.getQuickResponseList(
        success = { list ->
          val items = list?.map { QuickResponseItem(it.voiceId.toString(), it.desc.ifEmpty { "Voice ${it.voiceId}" }) } ?: emptyList()
          appLogger.i("$TAG loadQuickResponseList: success, count=${items.size}")
          this@MegaLiveStreamViewModel.quickResponseList = items
        },
        onFailure = { code, msg ->
          appLogger.e("$TAG loadQuickResponseList: failed, code=$code, msg=$msg")
          this@MegaLiveStreamViewModel.quickResponseList = emptyList()
        }
      )
    }
  }

  fun jumpToPreset(presetId: String) {
    launch {
      appLogger.i("$TAG/jumpToPreset/start: presetId=$presetId, deviceSn=${megaDevice.deviceSn}")

      val index = presetId.toIntOrNull() ?: return@launch

      val result = liveStreamAPI.jumpToPreset(index)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Jumping to preset position")
          appLogger.i("$TAG/jumpToPreset/success: presetId=$presetId, deviceSn=${megaDevice.deviceSn}")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG/jumpToPreset/failure: presetId=$presetId, code=${result.code}, msg=${result.message}, deviceSn=${megaDevice.deviceSn}")
          emitToast("Failed to jump to preset: ${result.message}")
        }
      }
    }
  }

  data class PresetForceConfirmState(val index: Int)

  /** 预置位保存被 PIR 角度检查拒绝(-502)后待用户确认，非 null 时 UI 显示确认弹窗 */
  var pendingPresetForceConfirm by mutableStateOf<PresetForceConfirmState?>(null)
    private set

  fun saveCurrentPreset(presetId: String, presetName: String) {
    launch {
      appLogger.i("$TAG saveCurrentPreset: id=$presetId, name=$presetName")

      val index = presetId.toIntOrNull() ?: return@launch

      val result = liveStreamAPI.savePreset(index, SETTING_STATE_NORMAL)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Preset saved successfully")
          appLogger.i("$TAG saveCurrentPreset: success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG saveCurrentPreset: failed, code=${result.code}, msg=${result.message}")
          if (result.code == ThingResult.ERROR_PRESET_ANGLE_UNAVAILABLE) {
            pendingPresetForceConfirm = PresetForceConfirmState(index)
          } else {
            emitToast("Failed to save preset: ${result.message}")
          }
        }
      }
    }
  }

  fun confirmPresetForceSave() {
    val state = pendingPresetForceConfirm ?: return
    pendingPresetForceConfirm = null
    launch {
      appLogger.i("$TAG saveCurrentPreset FORCE_SET: index=${state.index}")
      val result = liveStreamAPI.savePreset(state.index, SETTING_STATE_FORCE)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Preset saved successfully")
          appLogger.i("$TAG saveCurrentPreset FORCE_SET: success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG saveCurrentPreset FORCE_SET: failed, code=${result.code}, msg=${result.message}")
          emitToast("Failed to save preset: ${result.message}")
        }
      }
    }
  }

  fun dismissPresetForceConfirm() {
    pendingPresetForceConfirm = null
  }

  fun deletePreset(presetId: String) {
    launch {
      appLogger.i("$TAG deletePreset: presetId=$presetId")

      val index = presetId.toIntOrNull() ?: return@launch

      val result = liveStreamAPI.deletePreset(index)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Preset deleted successfully")
          appLogger.i("$TAG deletePreset: success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG deletePreset: failed, code=${result.code}, msg=${result.message}")
          emitToast("Failed to delete preset: ${result.message}")
        }
      }
    }
  }

  /**
   * Query preset positions from device
   * Updates PresetPositionManager with the device data
   * @param onComplete callback with list of set preset indices (1-based)
   */
  fun queryPresetPositions(onComplete: ((List<Int>) -> Unit)? = null) {
    launch {
      appLogger.i("$TAG queryPresetPositions: starting query")
      val action = megaDevice.getAction(ThingIdentifier.QUERY_PRESET_POSITIONS)
      if (action == null) {
        appLogger.e("$TAG queryPresetPositions: action not found")
        onComplete?.invoke(emptyList())
        return@launch
      }
      action.invoke(com.anker.esiotkit.domain_thing.model.interaction.ThingPayload.from(mapOf("payload-value" to emptyMap<String, Any>())))?.await()
        ?.onSuccess { payload ->
          val jsonString = payload.asJson()
          appLogger.i("$TAG queryPresetPositions: response=$jsonString")
          val setPresets = parsePresetPositions(jsonString)
          appLogger.i("$TAG queryPresetPositions: parsed presets=$setPresets")
          val deviceSn = megaDevice.deviceSn
          com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.initDevice(deviceSn)
          for (i in 0..3) {
            com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.setPresetStatus(deviceSn, i, false)
          }
          setPresets.forEach { index ->
            com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.setPresetStatus(deviceSn, index, true)
          }
          onComplete?.invoke(setPresets)
        }?.onFailure { code, msg ->
          appLogger.e("$TAG queryPresetPositions failed: code=$code, msg=$msg")
          onComplete?.invoke(emptyList())
        }
    }
  }

  /**
   * Parse preset positions from JSON response
   * Handles multiple possible response formats
   */
  /**
   * 从设备响应中提取已设置的预置位索引列表（0-based，与设备协议一致）
   *
   * 设备实际返回格式（T8170 日志确认）：
   *   {"p2p":{"mapOf":{"data":[{"index":0,"enable":0,...},{"index":1,"enable":1,...},...]}}}
   * payload.asJson() 返回完整嵌套 JSON，需逐层剥离后取 data 数组。
   * index 字段为 0-based，enable=1 表示已设置。
   */
  private fun parsePresetPositions(jsonString: String): List<Int> {
    appLogger.i("$TAG parsePresetPositions: input=$jsonString")

    if (jsonString.isBlank() || jsonString == "null" || jsonString == "{}") {
      appLogger.i("$TAG parsePresetPositions: empty response")
      return emptyList()
    }

    return try {
      val jsonParser = Json { ignoreUnknownKeys = true }
      val root = jsonParser.parseToJsonElement(jsonString)

      // 从任意深度的 JSON 对象中递归寻找 "data" 数组
      fun findDataArray(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonArray? {
        if (element !is kotlinx.serialization.json.JsonObject) return null
        element.jsonObject["data"]?.let { v ->
          if (v is kotlinx.serialization.json.JsonArray) return v
        }
        for (child in element.jsonObject.values) {
          findDataArray(child)?.let { return it }
        }
        return null
      }

      // 优先命中设备真实格式：任意层嵌套下的 "data" 数组
      // {"p2p":{"mapOf":{"data":[{"index":0,"enable":0},{"index":1,"enable":1},...]}}}
      val dataArray = findDataArray(root)
      if (dataArray != null) {
        val result = dataArray.mapNotNull { item ->
          val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
          val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
          val enable = obj["enable"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
          // 设备 index 0-based，enable=1 表示已设置，直接返回 0-based index
          if (enable == 1) index else null
        }
        appLogger.i("$TAG parsePresetPositions: parsed device format (0-based): $result")
        return result
      }

      // Fallback：兼容其他可能的格式
      when {
        root is kotlinx.serialization.json.JsonArray -> {
          val result = root.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
          appLogger.i("$TAG parsePresetPositions: fallback parsed as root array: $result")
          result
        }
        root is kotlinx.serialization.json.JsonObject -> {
          val obj = root.jsonObject

          // Try keys as preset indices: {"1": true, "2": false, "3": true}
          val keyBasedResult = obj.keys.mapNotNull { key ->
            val index = key.toIntOrNull()
            if (index != null) {
              val isSet = obj[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
              if (isSet) index else null
            } else null
          }
          if (keyBasedResult.isNotEmpty()) {
            appLogger.i("$TAG parsePresetPositions: parsed key-based format: $keyBasedResult")
            return keyBasedResult
          }

          appLogger.w("$TAG parsePresetPositions: no matching format found in object")
          emptyList()
        }
        else -> {
          appLogger.w("$TAG parsePresetPositions: unexpected element type")
          emptyList()
        }
      }
    } catch (e: Exception) {
      appLogger.e("$TAG parsePresetPositions: failed to parse: $jsonString, error: ${e.message}")
      emptyList()
    }
  }

  /**
   * PTZ Control with proper data structure
   * Reference: BatteryCam/component_video/screenfuncview/OperateSlidePtzView.kt
   *
   * Direction values:
   * - "LEFT", "RIGHT", "UP", "DOWN" for movement
   * - "STOP" for stopping movement
   */
  fun controlPTZ(direction: String) {
    launch {
      appLogger.i("$TAG controlPTZ: direction=$direction")

      val ptzDirection = when (direction.uppercase()) {
        "LEFT" -> PTZDirection.LEFT
        "RIGHT" -> PTZDirection.RIGHT
        "UP" -> PTZDirection.UP
        "DOWN" -> PTZDirection.DOWN
        else -> {
          appLogger.w("$TAG controlPTZ: invalid direction=$direction")
          return@launch
        }
      }

      // 2.2.0 客户调用方式: liveStreamAPI.controlPTZ(direction)
      // 新旧 SDK 签名一致，无需分支。SDK 内部统一走 Thing Model PTZ_ACTION_CONTROL。
      val result = liveStreamAPI.controlPTZ(ptzDirection)
      when (result) {
        is ThingResult.Success -> {
          appLogger.d("$TAG controlPTZ: success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG controlPTZ: failed, code=${result.code}, msg=${result.message}")
          emitToast("PTZ control failed: ${result.message}")
        }
      }
    }
  }

  fun startPTZMovement(direction: String) {
    launch {
      appLogger.i("$TAG startPTZMovement: direction=$direction")

      val ptzDirection = when (direction.uppercase()) {
        "LEFT" -> PTZDirection.LEFT
        "RIGHT" -> PTZDirection.RIGHT
        "UP" -> PTZDirection.UP
        "DOWN" -> PTZDirection.DOWN
        else -> {
          appLogger.w("$TAG startPTZMovement: invalid direction=$direction")
          return@launch
        }
      }

      // 2.2.0 客户调用方式: liveStreamAPI.startPTZMovement(direction)
      val result = liveStreamAPI.startPTZMovement(ptzDirection)
      when (result) {
        is ThingResult.Success -> {
          appLogger.d("$TAG startPTZMovement: success")
        }
        is ThingResult.Error -> {
          appLogger.e("$TAG startPTZMovement: failed, code=${result.code}, msg=${result.message}")
          emitToast("PTZ control failed: ${result.message}")
        }
      }
    }
  }

  fun stopPTZMovement(direction: String) {
    launch {
      appLogger.i("$TAG stopPTZMovement: direction=$direction")

      val ptzDirection = when (direction.uppercase()) {
        "LEFT" -> PTZDirection.LEFT
        "RIGHT" -> PTZDirection.RIGHT
        "UP" -> PTZDirection.UP
        "DOWN" -> PTZDirection.DOWN
        else -> {
          appLogger.w("$TAG stopPTZMovement: invalid direction=$direction")
          return@launch
        }
      }

      if (ESIotCompatSwitch.isNewApiCompatEnabled()) {
        // 新 API: 带方向参数
        val result = liveStreamAPI.stopPTZMovement(ptzDirection)
        if (result is ThingResult.Error) {
          appLogger.e("$TAG stopPTZMovement: failed, code=${result.code}, msg=${result.message}")
        }
      } else {
        // 2.2.0 客户调用方式: liveStreamAPI.stopPTZMovement() 无参
        // SDK 兼容层内部发 rotateType=0, cmdType=3（设备自动识别方向并停止）
        @Suppress("DEPRECATION")
        val result = liveStreamAPI.stopPTZMovement()
        if (result is ThingResult.Error) {
          appLogger.e("$TAG stopPTZMovement: failed, code=${result.code}, msg=${result.message}")
        }
      }
    }
  }

  private fun startForeverPingForBatteryDevice() {
    if (isForeverPingActive) return
    isForeverPingActive = true
    try {
      appLogger.i("$TAG startForeverPing: deviceSn=${megaDevice.deviceSn}")
      pingManager.startForeverPing(megaDevice.iotDevice)
    } catch (e: Exception) {
      isForeverPingActive = false
      appLogger.e("$TAG startForeverPing: error=${e.message}")
    }
  }

  private fun stopForeverPingForBatteryDevice() {
    if (!isForeverPingActive) return
    isForeverPingActive = false
    try {
      appLogger.i("$TAG stopForeverPing: deviceSn=${megaDevice.deviceSn}")
      pingManager.stopForeverPing(megaDevice.iotDevice)
    } catch (e: Exception) {
      appLogger.e("$TAG stopForeverPing: error=${e.message}")
    }
  }

}

data class MegaLiveStreamUiState(
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val rate: Int = 0,
  val recordTime: Long = 0,
  // Function status
  val muteStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val recordStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val talkStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val alarmStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val alarmCountdown: Int = 0,
  val alarmTypeName: String = "",
  val lightStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val trackingStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val calibrationStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val round360Status: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val panoramaStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val controlPositionStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
  val panoramaResultPath: String? = null,
  // Feature support flags (from live_bottom_toolbar_list)
  val supportsRecord: Boolean = true,
  val supportsScreenshot: Boolean = true,
  val supportsTalk: Boolean = true,
  val supportsMute: Boolean = true,
  val supportsNightVision: Boolean = true,
  val nightVisionMode: Int = 0,
  val nightVisionModeName: String = "Off",
  val supportsAlarm: Boolean = false,
  val supportsLight: Boolean = false,
  val supportsTracking: Boolean = false,
  val supportsCalibration: Boolean = false,
  val supports360: Boolean = false,
  val supportsPanorama: Boolean = false,
  val supportsQuickResponse: Boolean = false,
  val quickResponseList: List<QuickResponseItem> = emptyList(),
  val supportsPreset: Boolean = false,
  val supportsPTZControl: Boolean = false,
  val supportsSingleDualSwitch: Boolean = false,
  val supportedVideoModes: List<VideoModeOption> = emptyList(),
  val currentVideoMode: Int? = null,
)

sealed interface MegaLiveStreamAction {
  class Play(val playType: MegaPlayType) : MegaLiveStreamAction
  data object Stop : MegaLiveStreamAction
  data object Exit : MegaLiveStreamAction
  data object Capture : MegaLiveStreamAction
  data object Record : MegaLiveStreamAction
  data object Talk : MegaLiveStreamAction
  data object Mute : MegaLiveStreamAction
  data object NightVision : MegaLiveStreamAction
  data class SetNightVisionMode(val mode: Int) : MegaLiveStreamAction
  data class TriggerAlarm(val type: Int) : MegaLiveStreamAction
  data object StopAlarm : MegaLiveStreamAction
  data object Light : MegaLiveStreamAction
  data object AITracking : MegaLiveStreamAction
  data object Calibration : MegaLiveStreamAction
  data object Round360 : MegaLiveStreamAction
  data class Panorama(val platformContext: Any) : MegaLiveStreamAction
  data class SingleDualSwitch(val isDualView: Boolean) : MegaLiveStreamAction
  data class SwitchVideoMode(val mode: Int) : MegaLiveStreamAction
  data object LoadQuickResponseList : MegaLiveStreamAction
  data object LoadSupportedVideoModes : MegaLiveStreamAction
}

data class VideoModeOption(val mode: Int, val label: String)

fun videoModeLabel(mode: Int): String = when (mode) {
  0 -> "Main Camera"
  1 -> "Secondary Camera"
  2 -> "Picture-in-Picture \u2014 Sub Top-Left"
  3 -> "Picture-in-Picture \u2014 Sub Top-Right"
  4 -> "Picture-in-Picture \u2014 Sub Bottom-Left"
  5 -> "Picture-in-Picture \u2014 Sub Bottom-Right"
  6 -> "Picture-in-Picture \u2014 Main Top-Left"
  7 -> "Picture-in-Picture \u2014 Main Top-Right"
  8 -> "Picture-in-Picture \u2014 Main Bottom-Left"
  9 -> "Picture-in-Picture \u2014 Main Bottom-Right"
  12 -> "Split View"
  14 -> "Mirror"
  else -> "Mode $mode"
}

sealed interface MegaLiveFunctionStatus {
  data object Idle : MegaLiveFunctionStatus
  data object Loading : MegaLiveFunctionStatus
  data object Running : MegaLiveFunctionStatus
  data object Error : MegaLiveFunctionStatus

  val running: Boolean
    get() = this == Running
  val loading: Boolean
    get() = this == Loading
}