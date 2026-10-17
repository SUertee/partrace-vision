package com.anker.esiotkit.sample.main.mega.device_settings.motion_detection

import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_motion_activity_zone_title
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.core.constants.ServiceConstants
import com.anker.esiotkit.core.entities.zone.ZonePot
import com.anker.esiotkit.core.entities.zone.ZonePoint as CoreZonePoint
import com.anker.esiotkit.core.iotmodel.default_service.ActiveZoneService
import kotlin.math.round
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.support.log.appLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Activity Zone Configuration Screen
 *
 * 正确 payload 格式（来自抓包）：
 * {
 *   "index": 0,
 *   "pots": [{"points": [{"x":0.0,"y":0.0}, ...6个顶点...]}]
 * }
 *
 * - 坐标系：0.0-1.0 归一化浮点数
 * - 多边形：6个顶点（六边形，顺时针）
 * - 发送方式：ThingPayload.from(json) 展开为 payload-value 对象
 */

@Serializable
data class ZonePoint(val x: Double, val y: Double)

@Serializable
data class ZonePotInfo(val points: List<ZonePoint>)

@Serializable
data class ActivityZonePayload(
    val index: Int,
    val pots: List<ZonePotInfo>,
)

/** 设备 get 返回的格式：[{"index":0,"pots":[{"points":[...]}]}] */
@Serializable
data class ActivityZoneGetEntry(
    val index: Int = 0,
    val pots: List<ZonePotInfo> = emptyList(),
)

/**
 * 解析设备返回的热区 JSON 字符串为顶点列表
 * 注意：T8214 等双摄设备的 activityzone_config 含 camera_id 字段，此处 ignoreUnknownKeys 忽略它，
 * 将所有 camera 的热区 flatMap 到一起。v2.2.7 仅处理 camera_id=0 的场景。
 */
fun parseActivityZones(raw: String): List<List<ZonePoint>> {
    if (raw.isBlank()) return emptyList()
    return try {
        val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
        // 尝试数组格式（get 返回）
        val entries = lenient.decodeFromString<List<ActivityZoneGetEntry>>(raw)
        entries.flatMap { entry -> entry.pots.map { it.points } }.filter { it.size == 6 }
    } catch (e1: Exception) {
        try {
            // 尝试对象格式（set 格式）
            val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
            val payload = lenient.decodeFromString<ActivityZonePayload>(raw)
            payload.pots.map { it.points }.filter { it.size == 6 }
        } catch (e2: Exception) {
            appLogger.w("parseActivityZones failed: ${e2.message}")
            emptyList()
        }
    }
}

/** 默认六边形顶点（覆盖整个画面，顺时针从左上开始） */
fun defaultHexagonPoints(): List<ZonePoint> = listOf(
    ZonePoint(0.25, 0.0),   // 上左
    ZonePoint(0.75, 0.0),   // 上右
    ZonePoint(1.0,  0.5),   // 右
    ZonePoint(0.75, 1.0),   // 下右
    ZonePoint(0.25, 1.0),   // 下左
    ZonePoint(0.0,  0.5),   // 左
)

class ActivityZoneScreen(
    private val viewModel: MegaDeviceSettingsViewModel
) : BaseScreen() {
    override val title: org.jetbrains.compose.resources.StringResource get() = Res.string.eslot_settings_motion_activity_zone_title

    @Composable
    override fun Content() = BaseColumn {
        // 从物模型读取当前热区数据并回显
        val zoneList = remember {
            val sn = viewModel.megaDevice.deviceSn
            appLogger.i("ActivityZoneScreen/Load/start: deviceSn=$sn")
            val raw = viewModel.megaDevice
                .getProperty(ThingIdentifier.ACTIVITYZONE_CONFIG)
                ?.get()?.asString() ?: ""
                mutableStateListOf<List<ZonePoint>>().also { list ->
                list.addAll(parseActivityZones(raw))
                appLogger.i("ActivityZoneScreen/Load/success: source=thing_model, zoneCount=${list.size}, rawLen=${raw.length}, deviceSn=$sn, zones=${list.mapIndexed { i, pts -> "zone${i}=${pts.map { "(${it.x},${it.y})" }}" }}")
                list.forEachIndexed { i, pts ->
                    pts.firstOrNull { it.x < 0.01 || it.y < 0.01 }?.let {
                        appLogger.w("ActivityZoneScreen/Load/anomaly: reason=coordinate_drift_suspected, zone=$i, point=${it.x},${it.y}, deviceSn=$sn")
                    }
                }
            }
        }

        // Bug #7758 同构修复：ThingModel set 流程不回写属性缓存，缓存为空时降级读本地 P2P param 1204
        LaunchedEffect(Unit) {
            if (zoneList.isEmpty()) {
                appLogger.i("ActivityZoneScreen/Load/fallback: reason=thing_model_cache_blank, trying=p2p_6042, deviceSn=${viewModel.megaDevice.deviceSn}")
                loadActivityZonesFromP2PParam(zoneList)
            }
        }

        var saveInFlight by remember { mutableStateOf(false) }
        val saveScope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Define hexagonal detection areas (max 2 zones).\n" +
                        "Coordinates: 0.0–1.0 (normalized, e.g. 0.5 = 50%)",
                fontSize = 13.sp,
                color = Color.Gray,
                lineHeight = 18.sp
            )

            zoneList.forEachIndexed { index, points ->
                HexagonZoneItem(
                    index = index + 1,
                    points = points,
                    onDelete = { zoneList.removeAt(index) },
                    onUpdate = { updated -> zoneList[index] = updated }
                )
            }

            if (zoneList.size < 2) {
                Button(
                    onClick = { zoneList.add(defaultHexagonPoints()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" Add Zone")
                }
            }

            Button(
                onClick = onClick@{
                    // Bug #7756: 写读同通道（P2P 6042 直写，设备原样存储），避免 TD 写转换的 +1 千分比偏移
                    val sn = viewModel.megaDevice.deviceSn
                    if (saveInFlight) {
                        appLogger.w("ActivityZoneScreen/Save/rejected: reason=in_flight, deviceSn=$sn")
                        return@onClick
                    }
                    appLogger.i("ActivityZoneScreen/Save/start: zoneCount=${zoneList.size}, loading=${viewModel.loading}, deviceSn=$sn, zones=${zoneList.mapIndexed { i, pts -> "zone${i}=${pts.map { "(${it.x},${it.y})" }}" }}")
                    val service = try {
                        viewModel.megaDevice.iotDevice
                            .getService<ActiveZoneService>(ServiceConstants.ACTIVE_ZONE)
                    } catch (e: Exception) {
                        appLogger.e("ActivityZoneScreen/Save/failure: reason=service_lookup_error, error=${e.message}, deviceSn=$sn")
                        null
                    }
                    if (service != null) {
                        saveInFlight = true
                        saveScope.launch {
                            delay(5000)
                            if (saveInFlight) {
                                saveInFlight = false
                                appLogger.w("ActivityZoneScreen/Save/failure: reason=inflight_timeout, deviceSn=$sn")
                                emitToast("Device response timeout, please retry")
                            }
                        }
                        appLogger.i("ActivityZoneScreen/Save/branch: path=p2p_service, deviceSn=$sn")
                        val pots = zoneList.map { pts ->
                            ZonePot(pts.map { CoreZonePoint(it.x.toFloat(), it.y.toFloat()) })
                        }
                        service.setZone(
                            pots,
                            onSuccess = {
                                saveInFlight = false
                                appLogger.i("ActivityZoneScreen/Save/success: path=p2p_service, zoneCount=${zoneList.size}, deviceSn=$sn")
                                emitToast("Activity zones saved successfully")
                            },
                            onFailure = { result, payload ->
                                saveInFlight = false
                                appLogger.e("ActivityZoneScreen/Save/failure: path=p2p_service, result=$result, error=$payload, deviceSn=$sn")
                                emitToast("Failed to save activity zones: $result")
                            }
                        )
                    } else {
                        appLogger.i("ActivityZoneScreen/Save/fallback: reason=service_not_registered, deviceSn=$sn")
                        val json = buildPayload(zoneList)
                        viewModel.videoSettings.setActivityZoneConfig(json)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text("💡 Hexagon Zone Tips:", fontSize = 14.sp, color = Color(0xFF1976D2))
                Text(
                    "• 6 vertices define a hexagonal detection area\n" +
                    "• Range: 0.0–1.0 (1.0 = full width/height)\n" +
                    "• Default covers the full frame\n" +
                    "• Adjust vertices to narrow the detection area",
                    fontSize = 12.sp,
                    color = Color(0xFF424242),
                    lineHeight = 16.sp
                )
            }
        }

        MegaLoading(viewModel.loading)
    }

    private fun loadActivityZonesFromP2PParam(zoneList: MutableList<List<ZonePoint>>) {
        val service = viewModel.megaDevice.iotDevice
            .getService<ActiveZoneService>(ServiceConstants.ACTIVE_ZONE)
        if (service == null) {
            appLogger.e("ActivityZoneScreen/Load/failure: reason=service_not_registered, deviceSn=${viewModel.megaDevice.deviceSn}")
            return
        }
        val zones = service.primaryZone
        val fallbackPoints = zones.mapNotNull { pot ->
            // 千分比精度取整：消除 Float(0.251f)→Double 展开的 0.25099998... 长尾小数
            pot.points.map { pt ->
                ZonePoint(
                    round((pt.x * 1000).toDouble()) / 1000.0,
                    round((pt.y * 1000).toDouble()) / 1000.0
                )
            }.takeIf { it.size == 6 }
        }
        appLogger.i("ActivityZoneScreen/Load/success: source=p2p_6042, zoneCount=${fallbackPoints.size}, deviceSn=${viewModel.megaDevice.deviceSn}, zones=${fallbackPoints.mapIndexed { i, pts -> "zone${i}=${pts.map { "(${it.x},${it.y})" }}" }}")
        if (zoneList.isEmpty()) {
            zoneList.addAll(fallbackPoints)
        }
    }

    /**
     * 构造热区 payload（与抓包格式完全一致）：
     * {"index":0,"pots":[{"points":[{x,y}×6]}]}
     * 通过 ThingPayload.from(json) 展开为 payload-value 对象
     */
    private fun buildPayload(zones: List<List<ZonePoint>>): String {
        val pots = zones.map { pts -> ZonePotInfo(pts) }
        val payload = ActivityZonePayload(index = 0, pots = pots)
        return Json.encodeToString(payload)
    }

    @Composable
    fun HexagonZoneItem(
        index: Int,
        points: List<ZonePoint>,
        onDelete: () -> Unit,
        onUpdate: (List<ZonePoint>) -> Unit
    ) {
        val ptStates = remember(points) {
            points.map { mutableStateOf(it) }.toMutableList()
        }
        val pointLabels = listOf("Top-Left", "Top-Right", "Right", "Bottom-Right", "Bottom-Left", "Left")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zone $index (Hexagon)", fontSize = 16.sp)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ptStates.forEachIndexed { i, ptState ->
                var xStr by remember { mutableStateOf(ptState.value.x.toString()) }
                var yStr by remember { mutableStateOf(ptState.value.y.toString()) }

                Text(
                    text = "Point ${i + 1}: ${pointLabels.getOrElse(i) { "P${i+1}" }}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = xStr,
                        onValueChange = { v ->
                            xStr = v
                            v.toDoubleOrNull()?.coerceIn(0.0, 1.0)?.let { x ->
                                ptState.value = ptState.value.copy(x = x)
                                onUpdate(ptStates.map { it.value })
                            }
                        },
                        label = { Text("X [0.0-1.0]") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = yStr,
                        onValueChange = { v ->
                            yStr = v
                            v.toDoubleOrNull()?.coerceIn(0.0, 1.0)?.let { y ->
                                ptState.value = ptState.value.copy(y = y)
                                onUpdate(ptStates.map { it.value })
                            }
                        },
                        label = { Text("Y [0.0-1.0]") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        }
    }
}
