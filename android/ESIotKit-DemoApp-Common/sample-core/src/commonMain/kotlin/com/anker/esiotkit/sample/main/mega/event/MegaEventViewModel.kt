package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.core.entities.event.EventType
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.event.model.EventCondition
import com.anker.esiotkit.manager.queryFlowWithFilter
import com.anker.esiotkit.mega.event.MegaEventManager
import com.anker.esiotkit.mega.event.queryFlow
import com.anker.esiotkit.network.constants.NetConstant
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.support.job.ESIotCancellable
import com.anker.esiotkit.support.ktx.toDateStart
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.utils.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * @version 2024/5/1
 */
class MegaEventViewModel(private val filterDeviceSn: String? = null) : ScreenModel, BaseScreenModel() {

  companion object {
    private const val MIN_REFRESH_VISIBLE_MS = 600L
    private const val DEVICE_LIST_READY_TIMEOUT_MS = 10_000L
    private const val STUCK_TIMEOUT_MS = 30_000L
    private const val RETRY_DELAY_MS = 1_500L
    private const val MAX_RETRY = 1
  }

  val selectedDeviceList = mutableStateListOf<MegaDevice>()
  // 所有设备列表（包括基站和子设备）
  val allDeviceList = MegaDeviceManager.deviceList
    .stateIn(this, SharingStarted.Eagerly, emptyList())
  // 保留父设备列表用于Event查询（Event API需要parentSn）
  private val allParentDeviceList = MegaDeviceManager.deviceList.map { list ->
    list.filter { it.deviceSn == it.parentSn }.distinctBy { it.parentSn }
  }.stateIn(this, SharingStarted.Eagerly, emptyList())
  private var startTime by mutableStateOf(currentTimeMillis().toString())
  var megaEventList by mutableStateOf(listOf<MegaEvent>())

  // Filter states
  var selectedCategory by mutableStateOf("All")
  var tempSelectedCategory by mutableStateOf("All")

  var selectedEventType by mutableStateOf("All")
  var tempSelectedEventType by mutableStateOf("All")

  var selectedDetectionTypes = mutableStateListOf<String>()
  var tempSelectedDetectionTypes = mutableStateListOf<String>()

  private var _showDialog by mutableStateOf(false)

  private var _selectedDateMillis = MutableStateFlow(startTime.toLong())
  val selectedDateMillis: StateFlow<Long> = _selectedDateMillis.asStateFlow()
  val showDialog: Boolean
    get() = _showDialog

  private val _eventDates = MutableStateFlow<List<Long>>(emptyList())
  val eventDates: StateFlow<List<Long>> = _eventDates.asStateFlow()

  // 临时选择列表（必须在init之前声明）
  var tempSelectedDeviceList = mutableStateListOf<MegaDevice>()

  // 必须在 init 之前声明：init → getEventFlow() → launch 协程可能内联执行，访问时需已初始化
  private val mutex = Mutex()

  init {
    // 如果指定了设备SN，使用协程等待设备列表加载后再设置筛选设备
    if (filterDeviceSn != null) {
      screenModelScope.launch {
        // 等待设备列表加载完成
        val deviceList = allDeviceList.first { it.isNotEmpty() }
        val targetDevice = deviceList.find { it.deviceSn == filterDeviceSn }
        if (targetDevice != null) {
          selectedDeviceList.clear()
          selectedDeviceList.add(targetDevice)
          tempSelectedDeviceList.clear()
          tempSelectedDeviceList.add(targetDevice)
          dataLogger.i("MegaEventViewModel: Auto-filtered device ${targetDevice.deviceSn}")
        }
        // 设备列表加载完成后开始查询Event数据
        getEventFlow()
      }
    } else {
      // 初始化临时选择列表
      tempSelectedDeviceList.addAll(selectedDeviceList)
      // 等待设备列表拉全后再查询，避免设备列表未就绪时查到空数据
      screenModelScope.launch {
        dataLogger.i("MegaEventViewModel/init/waitDeviceList/start: filterDeviceSn=null")
        val ready = withTimeoutOrNull(DEVICE_LIST_READY_TIMEOUT_MS) {
          allDeviceList.first { it.isNotEmpty() }
        }
        dataLogger.i("MegaEventViewModel/init/waitDeviceList/result: ready=${ready != null}, deviceCount=${allDeviceList.value.size}")
        getEventFlow()
      }
    }
  }

  fun fetchEventDates(snList: List<MegaDevice>, startTime: Long, endTime: Long) {
    screenModelScope.launch {
      val dates = mutableListOf<Long>()
      MegaEventManager.getEventCalendarFlowBy(snList.map { it.parentSn }, startTime, endTime) { eventDates ->
        dates.addAll(eventDates)
        _eventDates.value = dates.distinct().sorted()
      }
    }
  }

  fun onToggleSelect(device: MegaDevice) {
    if (selectedDeviceList.contains(device)) {
      selectedDeviceList.remove(device)
    } else {
      selectedDeviceList.add(device)
    }
  }

  fun onToggleSelectTemp(device: MegaDevice) {
    if (tempSelectedDeviceList.contains(device)) {
      tempSelectedDeviceList.remove(device)
    } else {
      tempSelectedDeviceList.add(device)
    }
  }

  fun onToggleSelectAllTemp() {
    tempSelectedDeviceList.clear()
  }

  fun applyTempSelection() {
    selectedDeviceList.clear()
    selectedDeviceList.addAll(tempSelectedDeviceList)
    selectedCategory = tempSelectedCategory
    selectedEventType = tempSelectedEventType
    selectedDetectionTypes.clear()
    selectedDetectionTypes.addAll(tempSelectedDetectionTypes)
    dataLogger.i("MegaEventViewModel/applyTempSelection: category=$selectedCategory eventType=$selectedEventType detectionTypes=$selectedDetectionTypes deviceCount=${selectedDeviceList.size}")
    // Refresh event list with new filters
    getEventFlow()
  }

  fun resetTempSelection() {
    tempSelectedDeviceList.clear()
    tempSelectedDeviceList.addAll(selectedDeviceList)
    tempSelectedCategory = selectedCategory
    tempSelectedEventType = selectedEventType
    tempSelectedDetectionTypes.clear()
    tempSelectedDetectionTypes.addAll(selectedDetectionTypes)
  }

  fun onCategoryChange(category: String) {
    tempSelectedCategory = category
  }

  fun onEventTypeChange(eventType: String) {
    tempSelectedEventType = eventType
  }

  fun onDetectionTypeToggle(detectionType: String) {
    if (detectionType == "All") {
      tempSelectedDetectionTypes.clear()
    } else {
      if (tempSelectedDetectionTypes.contains(detectionType)) {
        tempSelectedDetectionTypes.remove(detectionType)
      } else {
        tempSelectedDetectionTypes.add(detectionType)
      }
    }
  }

  private fun mapDetectionTypes(types: List<String>): List<DetectionType> {
    return types.flatMap {
      when (it) {
        "Human" -> listOf(DetectionType.HUMAN, DetectionType.FACE)
        "Pet" -> listOf(DetectionType.PET)
        "Vehicle" -> listOf(DetectionType.CAR)
        "Package" -> listOf(DetectionType.PACKAGE_PUT, DetectionType.PACKAGE_TAKE)
        "Doorbell" -> listOf(DetectionType.RING)
        else -> {
          dataLogger.w("MegaEventViewModel/mapDetectionTypes: unknown type=$it")
          emptyList()
        }
      }
    }
  }

  private fun mapEventType(type: String): EventType = when (type) {
    "Video" -> EventType.VIDEO
    "Alert", "Alarm" -> EventType.ALERT
    else -> {
      if (type != "All") dataLogger.w("MegaEventViewModel/mapEventType: unknown type=$type")
      EventType.ALL
    }
  }

  private fun mapCategoryToDeviceSn(category: String): List<String> {
    val devices = allParentDeviceList.value
    return when (category) {
      "Security" -> devices.filter { it.category == NetConstant.CATEGORY_SECURITY }
      "Care" -> devices.filter { it.category != NetConstant.CATEGORY_SECURITY }
      else -> {
        if (category != "All") dataLogger.w("MegaEventViewModel/mapCategoryToDeviceSn: unknown category=$category")
        devices
      }
    }.map { it.deviceSn }
  }

  fun changeDate(startTime: String) {
    this.startTime = startTime
    _selectedDateMillis.value = startTime.toLong()
    eventJob?.cancel()
    loading = true
    megaEventList = emptyList()
    getEventFlow()
  }

  fun showDialog(value: Boolean) {
    _showDialog = value
  }

  fun deleteEvent(event: MegaEvent) {
    dataLogger.i("MegaEventViewModel/deleteEvent/id:${event.megaEventId}")
    MegaEventManager.deleteEventBy(
      megaEventIdList = listOf(event.megaEventId),
      onFinish = { successIds, failedIds ->
        if (successIds.isNotEmpty()) {
          dataLogger.i("MegaEventViewModel/deleteEvent/success")
          getEventFlow()
        }
        if (failedIds.isNotEmpty()) {
          dataLogger.w("MegaEventViewModel/deleteEvent/failed:${failedIds}")
        }
      }
    )
  }

  fun deleteEvents(events: List<MegaEvent>) {
    val ids = events.map { it.megaEventId }
    dataLogger.i("MegaEventViewModel/deleteEvents/count:${ids.size}, ids=$ids")
    if (ids.isEmpty()) {
      dataLogger.w("MegaEventViewModel/deleteEvents/skipped: empty list")
      return
    }
    MegaEventManager.deleteEventBy(
      megaEventIdList = ids,
      onFinish = { successIds, failedIds ->
        dataLogger.i("MegaEventViewModel/deleteEvents/result: success=${successIds.size}, failed=${failedIds.size}")
        if (successIds.isNotEmpty()) {
          dataLogger.i("MegaEventViewModel/deleteEvents/removing from local list: before=${megaEventList.size}, removing=${successIds.size}")
          megaEventList = megaEventList.filter { it.megaEventId !in successIds }
          dataLogger.i("MegaEventViewModel/deleteEvents/local list updated: after=${megaEventList.size}")
          dataLogger.i("MegaEventViewModel/deleteEvents/triggering background refresh")
          getEventFlow()
        }
        if (failedIds.isNotEmpty()) {
          dataLogger.w("MegaEventViewModel/deleteEvents/failed:${failedIds}")
        }
      }
    )
  }

  private var job: ESIotCancellable? = null
  private var eventJob: Job? = null
  fun getEvent() {
    loading = true
    val snList = selectedDeviceList.ifEmpty { allParentDeviceList.value }.filter {
      it.category == NetConstant.CATEGORY_SECURITY
    }.map { it.parentSn }
    val dateTime = DateUtil.timeMillisToDateTimeString(startTime.toLong())
    val dateStart = DateUtil.dateTimeStringToTimeMillis(dateTime.toDateStart())
    val dateEnd = dateStart + (24 * 60 * 60 * 1000)
    val condition = EventCondition(
      _parentSnList = snList,
      _startTime = dateStart,
      _endTime = dateEnd
    )
    job?.cancel()
    job = MegaEventManager.query(
      condition, true, 0,
      onSuccess = {
        megaEventList = it
        loading = false
      }, onFailure = { code, msg ->
        loading = false
      }
    )
  }

  fun getEventFlow() {
    eventJob?.cancel()
    eventJob = launch(name = "getEventFlow") {
      loading = true
      error = null
      val refreshStartTime = currentTimeMillis()
      val snList = selectedDeviceList.ifEmpty { allParentDeviceList.value }.filter {
        it.category == NetConstant.CATEGORY_SECURITY
      }.map { it.parentSn }

      val deviceSnList = if (selectedDeviceList.isEmpty()) {
        mapCategoryToDeviceSn(selectedCategory)
      } else {
        selectedDeviceList.map { it.deviceSn }
      }

      val detectionTypes = mapDetectionTypes(selectedDetectionTypes)
      val eventType = mapEventType(selectedEventType)
      dataLogger.i("MegaEventViewModel/getEventFlow/start: startTime=$startTime parentSnList=$snList deviceSnList=$deviceSnList category=$selectedCategory detectionTypes=$selectedDetectionTypes->$detectionTypes eventType=$selectedEventType->$eventType")

      var attempt = 0
      while (attempt <= MAX_RETRY) {
        dataLogger.i("MegaEventViewModel/getEventFlow/fetchOnce/start: attempt=$attempt snCount=${snList.size} deviceCount=${deviceSnList.size}")
        when (fetchOnce(snList, deviceSnList, detectionTypes, eventType, refreshStartTime)) {
          FetchOutcome.Emitted -> return@launch
          FetchOutcome.Fatal -> return@launch
          FetchOutcome.Stuck -> {
            if (attempt < MAX_RETRY) {
              dataLogger.w("MegaEventViewModel/getEventFlow/stuck: no emission after ${STUCK_TIMEOUT_MS}ms, retry=${attempt + 1}")
              attempt++
              delay(RETRY_DELAY_MS)
            } else {
              dataLogger.w("MegaEventViewModel/getEventFlow/stuck: retries exhausted, giving up")
              loading = false
              error = "加载超时，请下拉刷新"
              return@launch
            }
          }
        }
      }
    }
  }

  private enum class FetchOutcome { Emitted, Stuck, Fatal }

  // 单次拉取，返回本次是否有过发射；无任何发射视为 SDK 拉取悬挂
  private suspend fun fetchOnce(
    snList: List<String>,
    deviceSnList: List<String>,
    detectionTypes: List<DetectionType>,
    eventType: EventType,
    refreshStartTime: Long
  ): FetchOutcome {
    val filteredFlow = queryFlowWithFilter(
      startTime = startTime.toLong(),
      deviceSnList = deviceSnList,
      detectionType = detectionTypes,
      eventType = eventType,
    )

    val unfilteredFlow: Flow<List<MegaEvent>>
    try {
      unfilteredFlow = MegaEventManager.queryFlow(
        startTime = startTime.toLong(),
        parentSnList = snList,
        queryAllDataAtStart = true
      )
    } catch (e: Exception) {
      dataLogger.w("MegaEventViewModel/getEventFlow/error: queryFlow failed, ${e.message}")
      loading = false
      error = "加载失败，请下拉刷新"
      return FetchOutcome.Fatal
    }

    val filteredMutex = Mutex()
    var filteredReceived by mutableStateOf(false)
    var emitted = false

    val result = withTimeoutOrNull(STUCK_TIMEOUT_MS) {
      channelFlow {
        launch {
          filteredFlow.collect {
            filteredMutex.withLock { filteredReceived = true }
            emitted = true
            dataLogger.i("MegaEventViewModel/fetchOnce/filteredEmit: size=${it.safeSize()}")
            send(it)
          }
        }
        launch {
          unfilteredFlow.collect {
            emitted = true
            dataLogger.i("MegaEventViewModel/fetchOnce/unfilteredEmit: size=${it.safeSize()} filteredReceived=$filteredReceived")
            if (!filteredReceived) send(it)
          }
        }
      }.collectLatest {
        try {
          val elapsed = currentTimeMillis() - refreshStartTime
          if (elapsed < MIN_REFRESH_VISIBLE_MS) {
            val waitMs = MIN_REFRESH_VISIBLE_MS - elapsed
            dataLogger.i("MegaEventViewModel/getEventFlow/minDelay: elapsed=${elapsed}ms, waiting=${waitMs}ms")
            delay(MIN_REFRESH_VISIBLE_MS - elapsed)
          }
          loading = false
          mutex.withLock {
            val snapshot = it.snapshotSafe()
            dataLogger.i("MegaEventViewModel/getEventFlow/result: size=${snapshot.size} filteredReceived=${detectionTypes.isNotEmpty() || eventType != EventType.ALL}")
            megaEventList = snapshot
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          dataLogger.w("MegaEventViewModel/getEventFlow/collectError: ${e::class.simpleName}: ${e.message}")
          loading = false
          error = "加载失败，请下拉刷新"
        }
      }
    }
    dataLogger.i("MegaEventViewModel/fetchOnce/end: emitted=$emitted timeout=${result == null}")
    return if (emitted) FetchOutcome.Emitted else FetchOutcome.Stuck
  }

  // 上游 queryFlow 发出的是活跃可变列表（SDK 回调线程持续修改），
  // 直接 toList() 拷贝会 ConcurrentModificationException（已实测闪退），防御性重试快照
  private fun List<MegaEvent>.snapshotSafe(): List<MegaEvent> {
    repeat(3) { attempt ->
      try {
        return toList()
      } catch (e: ConcurrentModificationException) {
        dataLogger.w("MegaEventViewModel/getEventFlow/snapshot: concurrent modification, retry=${attempt + 1}")
      }
    }
    dataLogger.w("MegaEventViewModel/getEventFlow/snapshot: retries exhausted, using empty snapshot")
    return emptyList()
  }

  // 仅用于日志打印的 size 安全读取：上游可变列表被并发修改时，size 读取也可能抛 CME
  private fun List<MegaEvent>.safeSize(): Int {
    return try {
      size
    } catch (e: ConcurrentModificationException) {
      -1
    }
  }
}
