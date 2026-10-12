@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.event.model.EventCondition
import com.anker.esiotkit.manager.MegaEventManager
import com.anker.esiotkit.media.VideoInfo
import com.anker.esiotkit.media.event_stream.EventDownloadState
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.event_stream.EventPlayListener
import com.anker.esiotkit.media.event_stream.EventPlayerState
import com.anker.esiotkit.network.constants.NetConstant
import com.anker.esiotkit.sample.main.mega.media.stopAndRelease
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.EventStreamPlayView
import com.anker.esiotkit.support.ktx.toDateStart
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.platform.ensureDirectoryExists
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import com.anker.esiotkit.support.platform.toByteArray
import com.anker.esiotkit.support.utils.DateUtil
import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.coroutines.resume

// ─── Data classes ───

data class CaptureResult(
    val event: MegaEvent,
    val device: MegaDevice?,
    val thumbFileName: String?,
    val thumbStatus: String,
    val videoFileName: String?,
    val videoStatus: String,
)

enum class CaptureState { Idle, Running, Done }

// Request for Composable to create a player
data class PlayerRequest(
    val event: MegaEvent,
    val device: MegaDevice,
    val deferred: CompletableDeferred<EventMediaPlayer?>,
)

// ─── ViewModel ───

class AutoCaptureViewModel : ScreenModel {

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _results = MutableStateFlow<List<CaptureResult>>(emptyList())
    val results: StateFlow<List<CaptureResult>> = _results.asStateFlow()

    // ViewModel → Composable: request a player for an event
    private val _playerRequest = MutableStateFlow<PlayerRequest?>(null)
    val playerRequest: StateFlow<PlayerRequest?> = _playerRequest.asStateFlow()

    private fun log(msg: String) {
        val ts = DateUtil.timeMillisToTimeString(currentTimeMillis())
        val line = "[$ts] $msg"
        _logs.value = _logs.value + line
        dataLogger.i("AUTO-CAPTURE: $msg")
    }

    fun startCapture() {
        if (_state.value == CaptureState.Running) return
        _state.value = CaptureState.Running
        _logs.value = emptyList()
        _results.value = emptyList()
        scope.launch {
            try {
                doCapture()
                _state.value = CaptureState.Done
            } catch (e: Exception) {
                log("FATAL ERROR: ${e.message}")
                _state.value = CaptureState.Done
            }
        }
    }

    /**
     * Request the Composable to create an EventMediaPlayer for the given event/device.
     * The Composable creates the player, mounts it in EventStreamPlayView, and completes the deferred.
     */
    private suspend fun requestPlayer(event: MegaEvent, device: MegaDevice): EventMediaPlayer? {
        val deferred = CompletableDeferred<EventMediaPlayer?>()
        _playerRequest.value = PlayerRequest(event, device, deferred)
        // Wait for Composable to create and provide the player (timeout 10s)
        return withTimeoutOrNull(10_000) { deferred.await() }.also {
            _playerRequest.value = null
        }
    }

    private suspend fun doCapture() {
        // ─── Step 0: Prepare directories ───
        log("Step 0: Preparing directories...")
        val captureDir = getDocumentFilePath().toPath().resolve("test_data")
        val thumbDir = captureDir.resolve("thumbnails")
        val videoDir = captureDir.resolve("videos")
        platformFileSystem.ensureDirectoryExists(thumbDir)
        platformFileSystem.ensureDirectoryExists(videoDir)
        log("Output dir: $captureDir")

        // ─── Step 1: Query today's events ───
        log("Step 1: Querying today's events...")
        val now = currentTimeMillis()
        val dateTime = DateUtil.timeMillisToDateTimeString(now)
        val dateStart = DateUtil.dateTimeStringToTimeMillis(
            try { dateTime.toDateStart() } catch (_: Exception) { dateTime }
        )
        val dateEnd = dateStart + 24L * 60 * 60 * 1000

        val allDevices = MegaDeviceManager.deviceList.value
        val securityStations = allDevices
            .filter { it.deviceSn == it.parentSn }
            .filter { it.category == NetConstant.CATEGORY_SECURITY }

        log("Found ${securityStations.size} security stations")
        val snList = securityStations.map { it.parentSn }.distinct()

        var events: List<MegaEvent> = emptyList()

        // Try today first, expand to 7 days if < 10 events
        for (daysBack in 0..7) {
            val startMs = dateStart - daysBack * 24L * 60 * 60 * 1000
            val endMs = if (daysBack == 0) dateEnd else startMs + 24L * 60 * 60 * 1000
            val condition = EventCondition(
                _parentSnList = snList,
                _startTime = startMs,
                _endTime = endMs
            )
            log("Querying events: daysBack=$daysBack, snCount=${snList.size}")
            val result = suspendCancellableCoroutine<List<MegaEvent>> { cont ->
                MegaEventManager.query(
                    condition, refresh = true, lastEventTime = 0,
                    onSuccess = { events ->
                        log("Query returned ${events.size} events")
                        if (cont.isActive) cont.resume(events)
                    },
                    onFailure = { code, msg ->
                        log("Query failed: code=$code msg=$msg")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                )
            }
            events = events + result
            if (events.size >= 10) break
            log("Only ${events.size} events so far, expanding search range...")
        }

        // Deduplicate by megaEventId and take first 15
        events = events.distinctBy { it.megaEventId }.take(15)
        log("Total unique events: ${events.size}")

        if (events.isEmpty()) {
            log("ERROR: No events found! Aborting.")
            return
        }

        // ─── Step 2: Capture thumbnails (≥10) ───
        log("Step 2: Capturing thumbnails (target >=10)...")
        val results = mutableListOf<CaptureResult>()
        var thumbCount = 0

        for (event in events) {
            if (thumbCount >= 10) break

            val device = MegaDeviceManager.getDeviceBy(event.deviceSn)
            var thumbFileName: String? = null
            var thumbStatus = "SKIPPED"

            val picture = event.thumbnail
            val path = picture?.picturePath ?: ""
            val sn = picture?.parentSn ?: ""

            if (path.isNotBlank() && sn.isNotBlank()) {
                // Try getEventImageBase64
                var base64: String? = null
                try {
                    base64 = suspendCancellableCoroutine<String?> { cont ->
                        MegaEventManager.getEventImageBase64(
                            path, sn,
                            onSuccess = { b64 -> if (cont.isActive) cont.resume(b64) },
                            onFailure = { code, msg ->
                                log("thumb fail (base64): code=$code msg=$msg")
                                if (cont.isActive) cont.resume(null)
                            }
                        )
                    }
                } catch (e: Exception) {
                    log("thumb exception (base64): ${e.message}")
                }

                if (base64 != null && base64.isNotEmpty()) {
                    try {
                        val bytes = base64.decodeBase64Bytes()
                        thumbFileName = "thumb_${thumbCount + 1}_${event.megaEventId}.jpg"
                        val thumbPath = thumbDir.resolve(thumbFileName)
                        platformFileSystem.sink(thumbPath).buffer().use { it.write(bytes) }
                        thumbStatus = "OK(base64, ${bytes.size} bytes)"
                        thumbCount++
                        log("thumb_$thumbCount: $thumbFileName ($thumbStatus)")
                    } catch (e: Exception) {
                        thumbStatus = "DECODE_FAIL: ${e.message}"
                        log("thumb decode failed: ${e.message}")
                    }
                }

                // Fallback: try getEventImage (bytes version)
                if (thumbStatus.startsWith("SKIPPED") || thumbStatus.startsWith("DECODE_FAIL")) {
                    try {
                        val bytes = suspendCancellableCoroutine<ByteArray?> { cont ->
                            MegaEventManager.getEventImage(
                                path, sn,
                                onSuccess = { esBytes -> if (cont.isActive) cont.resume(esBytes.toByteArray()) },
                                onFailure = { code, msg ->
                                    log("thumb fail (bytes): code=$code msg=$msg")
                                    if (cont.isActive) cont.resume(null)
                                }
                            )
                        }
                        if (bytes != null && bytes.isNotEmpty()) {
                            thumbFileName = "thumb_${thumbCount + 1}_${event.megaEventId}.jpg"
                            val thumbPath = thumbDir.resolve(thumbFileName)
                            platformFileSystem.sink(thumbPath).buffer().use { it.write(bytes) }
                            thumbStatus = "OK(bytes, ${bytes.size} bytes)"
                            thumbCount++
                            log("thumb_$thumbCount: $thumbFileName ($thumbStatus)")
                        }
                    } catch (e: Exception) {
                        log("thumb fallback exception: ${e.message}")
                    }
                }

                // Fallback 2: try other pictures in event.pictures list
                if ((thumbStatus.startsWith("SKIPPED") || thumbStatus.startsWith("DECODE_FAIL") || thumbStatus.startsWith("FAIL")) && event.pictures.isNotEmpty()) {
                    for (pic in event.pictures) {
                        if (pic.picturePath.isBlank() || pic.parentSn.isBlank()) continue
                        try {
                            val b64 = suspendCancellableCoroutine<String?> { cont ->
                                MegaEventManager.getEventImageBase64(
                                    pic.picturePath, pic.parentSn,
                                    onSuccess = { v -> if (cont.isActive) cont.resume(v) },
                                    onFailure = { _, _ -> if (cont.isActive) cont.resume(null) }
                                )
                            }
                            if (b64 != null && b64.isNotEmpty()) {
                                val bytes = b64.decodeBase64Bytes()
                                thumbFileName = "thumb_${thumbCount + 1}_${event.megaEventId}.jpg"
                                val thumbPath = thumbDir.resolve(thumbFileName)
                                platformFileSystem.sink(thumbPath).buffer().use { it.write(bytes) }
                                thumbStatus = "OK(alt-pic, ${bytes.size} bytes)"
                                thumbCount++
                                log("thumb_$thumbCount: $thumbFileName ($thumbStatus)")
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                thumbStatus = "NO_THUMBNAIL_DATA"
                log("event ${event.megaEventId}: no thumbnail path/sn")
            }

            results.add(CaptureResult(
                event = event,
                device = device,
                thumbFileName = thumbFileName,
                thumbStatus = thumbStatus,
                videoFileName = null,
                videoStatus = "PENDING",
            ))
        }
        log("Thumbnails captured: $thumbCount / ${events.size} events")

        // ─── Step 3: Download videos (≥10) ───
        log("Step 3: Downloading videos (target >=10)...")
        var videoCount = 0

        for (i in results.indices) {
            if (videoCount >= 10) break
            val result = results[i]
            val event = result.event
            if (event.videoPath.isBlank()) {
                results[i] = result.copy(videoStatus = "NO_VIDEO_PATH")
                continue
            }
            val device = result.device ?: MegaDeviceManager.getDeviceBy(event.deviceSn)
            if (device == null) {
                results[i] = result.copy(videoStatus = "DEVICE_NOT_FOUND")
                continue
            }

            var videoFileName: String? = null
            var videoStatus = "FAILED"

            try {
                // Request player from Composable
                val player = requestPlayer(event, device)
                if (player == null) {
                    results[i] = result.copy(videoStatus = "PLAYER_NULL")
                    continue
                }

                var downloadPath = ""
                var hasStartedDownload = false
                var sdkReportedPath: String? = null

                val downloadResult = withTimeoutOrNull(120_000L) {
                    suspendCancellableCoroutine<Pair<Boolean, String?>> { cont ->
                        player.setPlayListener(object : EventPlayListener {
                            override fun onPlayState(status: EventPlayerState, message: String) {
                                if (status == EventPlayerState.Playing && !hasStartedDownload) {
                                    hasStartedDownload = true
                                    try { player.pause() } catch (_: Exception) {}
                                    try { player.setMute(true) } catch (_: Exception) {}
                                    videoFileName = "video_${videoCount + 1}_${event.megaEventId}.mp4"
                                    downloadPath = videoDir.resolve(videoFileName!!).toString()
                                    try { player.startDownload(path = downloadPath) } catch (e: Exception) {
                                        log("startDownload exception: ${e.message}")
                                    }
                                }
                            }
                            override fun onDownloadState(status: EventDownloadState, progress: Float, message: String) {
                                when (status) {
                                    EventDownloadState.Success -> {
                                        sdkReportedPath = message.ifEmpty { null }
                                        if (cont.isActive) cont.resume(true to sdkReportedPath)
                                    }
                                    EventDownloadState.Failed -> {
                                        if (cont.isActive) cont.resume(false to null)
                                    }
                                    EventDownloadState.Timeout -> {
                                        if (cont.isActive) cont.resume(false to null)
                                    }
                                    else -> {}
                                }
                            }
                            override fun onMuteState(isMute: Boolean) {}
                            override fun onProgress(progress: Float) {}
                            override fun onVideoInfo(info: VideoInfo) {}
                        })
                        try { player.startPlay() } catch (e: Exception) {
                            log("startPlay exception: ${e.message}")
                        }
                        cont.invokeOnCancellation {
                            try { player.stopAndRelease() } catch (_: Exception) {}
                        }
                    }
                }

                if (downloadResult != null && downloadResult.first) {
                    val sdkPath = downloadResult.second
                    val finalPath = if (sdkPath != null && platformFileSystem.exists(sdkPath.toPath())) {
                        val targetPath = videoDir.resolve(videoFileName!!)
                        if (sdkPath.toPath() != targetPath) {
                            platformFileSystem.source(sdkPath.toPath()).use { src ->
                                platformFileSystem.sink(targetPath).buffer().use { sink ->
                                    sink.writeAll(src)
                                }
                            }
                        }
                        targetPath.toString()
                    } else if (platformFileSystem.exists(downloadPath.toPath())) {
                        downloadPath
                    } else {
                        null
                    }

                    if (finalPath != null) {
                        videoStatus = "OK"
                        videoCount++
                        log("video_$videoCount: $videoFileName ($videoStatus)")
                    } else {
                        videoStatus = "FILE_NOT_FOUND"
                        log("video download reported success but file not found")
                    }
                } else {
                    videoStatus = if (downloadResult == null) "TIMEOUT" else "FAILED"
                    log("video download: $videoStatus for ${event.megaEventId}")
                }

                try { player.stopAndRelease() } catch (_: Exception) {}
            } catch (e: Exception) {
                videoStatus = "EXCEPTION: ${e.message}"
                log("video exception: ${e.message}")
            }

            results[i] = result.copy(
                videoFileName = videoFileName,
                videoStatus = videoStatus,
            )
        }
        log("Videos downloaded: $videoCount / ${events.size} events")

        // ─── Step 4: Generate events.txt ───
        log("Step 4: Generating events.txt...")
        _results.value = results

        val sb = StringBuilder()
        sb.appendLine("=== Auto Capture Report ===")
        sb.appendLine("Capture Time: ${DateUtil.timeMillisToDateTimeString(currentTimeMillis())}")
        sb.appendLine("Total Events Queried: ${events.size}")
        sb.appendLine("Thumbnails Captured: $thumbCount")
        sb.appendLine("Videos Downloaded: $videoCount")
        sb.appendLine()

        // Device info
        sb.appendLine("=== Device Info ===")
        for (device in securityStations) {
            sb.appendLine("  SN: ${device.deviceSn}")
            sb.appendLine("  PN: ${device.devicePn}")
            sb.appendLine("  Name: ${device.iotDevice.deviceName}")
            sb.appendLine("  Category: ${device.category}")
            sb.appendLine("  ParentSN: ${device.parentSn}")
            sb.appendLine()
        }
        val eventDeviceSns = events.map { it.deviceSn }.distinct()
        for (sn in eventDeviceSns) {
            val dev = MegaDeviceManager.getDeviceBy(sn) ?: continue
            if (dev.deviceSn == dev.parentSn) continue
            sb.appendLine("  SN: ${dev.deviceSn}")
            sb.appendLine("  PN: ${dev.devicePn}")
            sb.appendLine("  Name: ${dev.iotDevice.deviceName}")
            sb.appendLine("  Category: ${dev.category}")
            sb.appendLine("  ParentSN: ${dev.parentSn}")
            sb.appendLine()
        }

        // Events info
        sb.appendLine("=== Events ===")
        for ((index, result) in results.withIndex()) {
            val event = result.event
            val timeStr = try { event.startTime.substringAfter(" ") } catch (_: Exception) { "N/A" }
            val typeStr = detectionTypeToString(result.event.thumbnail?.detectionType ?: 0)
            val thumbName = result.thumbFileName ?: "N/A"
            val videoName = result.videoFileName ?: "N/A"

            sb.appendLine("event_${index + 1}: time=$timeStr, type=$typeStr, thumbnail=$thumbName, video=$videoName")
            sb.appendLine("  eventId=${event.megaEventId}")
            sb.appendLine("  deviceSn=${event.deviceSn}")
            sb.appendLine("  deviceName=${event.deviceName}")
            sb.appendLine("  startTimestamp=${event.startTimestamp}")
            sb.appendLine("  endTimestamp=${event.endTimestamp}")
            sb.appendLine("  duration=${event.endTimestamp - event.startTimestamp}ms")
            sb.appendLine("  videoPath=${event.videoPath}")
            sb.appendLine("  thumbStatus=${result.thumbStatus}")
            sb.appendLine("  videoStatus=${result.videoStatus}")
            sb.appendLine()
        }

        val eventsTxtPath = captureDir.resolve("events.txt")
        platformFileSystem.sink(eventsTxtPath).buffer().use { sink ->
            sink.write(sb.toString().encodeToByteArray())
        }
        log("events.txt written to: $eventsTxtPath")
        log("=== CAPTURE COMPLETE: $thumbCount thumbnails, $videoCount videos ===")
    }

    private fun detectionTypeToString(detectionType: Int): String = when (detectionType) {
        DetectionType.HUMAN.value, DetectionType.FACE.value -> "BODY"
        DetectionType.CAR.value -> "VEHICLE"
        DetectionType.CRY.value -> "CRYING"
        DetectionType.PACKAGE_PUT.value, DetectionType.PACKAGE_TAKE.value -> "PACKAGE"
        DetectionType.PET.value -> "PET"
        DetectionType.SOUND.value -> "SOUND"
        DetectionType.MOTION.value -> "MOTION"
        DetectionType.RING.value, DetectionType.ANSWERED_RING.value, DetectionType.MISSED_RING.value -> "RING"
        DetectionType.ALERT.value -> "ALERT"
        else -> "UNKNOWN($detectionType)"
    }
}

// ─── Screen ───

object AutoCaptureScreen : BaseScreen() {
    @Composable
    override fun dynamicTitle(): String = "Auto Capture"

    @Composable
    override fun Content() = BaseColumn(scrollState = rememberScrollState()) {
        val viewModel = rememberScreenModel { AutoCaptureViewModel() }
        val state by viewModel.state.collectAsState()
        val logs by viewModel.logs.collectAsState()
        val results by viewModel.results.collectAsState()
        val playerReq by viewModel.playerRequest.collectAsState()

        // Player management: when ViewModel requests a player, create it and mount in EventStreamPlayView
        var activePlayer by remember { mutableStateOf<EventMediaPlayer?>(null) }
        // The deferred to complete when onVideoViewReady fires
        var pendingDeferred by remember { mutableStateOf<CompletableDeferred<EventMediaPlayer?>?>(null) }

        LaunchedEffect(playerReq) {
            val req = playerReq
            if (req != null) {
                // Create player on main thread
                val player = try {
                    EventMediaPlayer(req.event, req.device)
                } catch (e: Exception) {
                    dataLogger.i("AUTO-CAPTURE: EventMediaPlayer creation failed: ${e.message}")
                    null
                }
                if (player != null) {
                    // Set activePlayer to trigger EventStreamPlayView composition
                    // The deferred will be completed in onVideoViewReady callback
                    pendingDeferred = req.deferred
                    activePlayer = player
                } else {
                    // Player creation failed, complete immediately
                    if (!req.deferred.isCompleted) req.deferred.complete(null)
                }
            } else {
                // Clear player after request is consumed
                activePlayer?.let { p ->
                    try { p.stopAndRelease() } catch (_: Exception) {}
                }
                activePlayer = null
                pendingDeferred = null
            }
        }

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CommonButton(
                text = if (state == CaptureState.Running) "Running..." else "Start Auto Capture",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.startCapture() }
            )
        }

        // Status
        Text(
            text = "Status: ${state.name}",
            fontSize = 16.sp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        if (state == CaptureState.Running) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp).padding(top = 4.dp),
                strokeWidth = 2.dp
            )
        }

        // Progress log
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(
                text = "Progress Log:",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                logs.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.contains("ERROR") || line.contains("FAIL") || line.contains("FATAL"))
                            Color.Red
                        else if (line.contains("OK") || line.contains("COMPLETE"))
                            Color(0xFF2E7D32)
                        else Color.Black,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }

        // Results summary
        if (results.isNotEmpty()) {
            Text(
                text = "Results (${results.size} events):",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            results.forEach { r ->
                val event = r.event
                val timeStr = try { event.startTime.substringAfter(" ") } catch (_: Exception) { "?" }
                Text(
                    text = "  ${event.deviceName} @ $timeStr | thumb=${r.thumbStatus.take(20)} | video=${r.videoStatus.take(20)}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }

        // Hidden 1dp player host — used for video download
        activePlayer?.let { player ->
            EventStreamPlayView(
                modifier = Modifier.size(1.dp),
                player = player,
                onVideoViewReady = {
                    dataLogger.i("AUTO-CAPTURE: player video view ready")
                    // Complete the deferred NOW — the AndroidView factory has finished
                    // and player.initVideoView(frameLayout) has been called
                    pendingDeferred?.let { d ->
                        if (!d.isCompleted) d.complete(player)
                    }
                }
            )
        }
    }
}
