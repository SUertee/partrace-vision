package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_privacy_zones_title
import org.jetbrains.compose.resources.StringResource
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.support.log.appLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Privacy Zones Configuration Screen
 *
 * payload 格式：
 * {
 *   "index": 0,
 *   "pots": [{"points": [{"x":0.0,"y":0.0}, {"x":1.0,"y":1.0}]}]
 * }
 *
 * - 坐标系：0.0-1.0 归一化浮点数
 * - 矩形：2个顶点（左上 + 右下）
 * - 发送方式：ThingPayload.from(json) 展开为 payload-value 对象
 */

@Serializable
data class PrivacyZonePoint(val x: Double, val y: Double)

@Serializable
data class PrivacyZonePot(val points: List<PrivacyZonePoint>)

@Serializable
data class PrivacyZonePayload(
    val index: Int = 0,
    val pots: List<PrivacyZonePot>,
)

/** 设备 get 返回的格式：[{"index":0,"pots":[{"points":[...]}]}] */
@Serializable
data class PrivacyZoneGetEntry(
    val index: Int = 0,
    val pots: List<PrivacyZonePot> = emptyList(),
)

/** 矩形区域，用左上角和右下角两点描述，坐标为 0.0-1.0 */
data class RectZone(
    val x1: Double = 0.0,  // 左上 X
    val y1: Double = 0.0,  // 左上 Y
    val x2: Double = 1.0,  // 右下 X
    val y2: Double = 1.0,  // 右下 Y
) {
    /** 转为两点列表（左上 + 右下） */
    fun toPoints() = listOf(
        PrivacyZonePoint(x1, y1),  // 左上
        PrivacyZonePoint(x2, y2),  // 右下
    )
}

/**
 * 从 pot 的 points 还原 RectZone（取第 0 个为左上、第 1 个为右下）
 */
fun PrivacyZonePot.toRectZone(): RectZone? {
    if (points.size < 2) return null
    return RectZone(
        x1 = points[0].x,
        y1 = points[0].y,
        x2 = points[1].x,
        y2 = points[1].y,
    )
}

/**
 * 解析设备返回的隐私区 JSON 字符串为 RectZone 列表
 * 支持两种格式：
 * - get 返回：[{"index":0,"pots":[{"points":[...]}]}]
 * - set 格式：{"sensor_id":0,"index":0,"pots":[{"points":[...]}]}
 */
fun parsePrivacyZones(raw: String): List<RectZone> {
    if (raw.isBlank()) {
        appLogger.i("parsePrivacyZones: raw is blank, returning empty list")
        return emptyList()
    }
    appLogger.i("parsePrivacyZones: raw=$raw")
    return try {
        val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
        // 尝试解析为数组格式（get 返回）
        val entries = lenient.decodeFromString<List<PrivacyZoneGetEntry>>(raw)
        val zones = entries.flatMap { entry -> entry.pots.mapNotNull { it.toRectZone() } }
        appLogger.i("parsePrivacyZones: parsed as array format, zoneCount=${zones.size}")
        zones
    } catch (e1: Exception) {
        try {
            // 尝试解析为对象格式（set 格式）
            val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
            val payload = lenient.decodeFromString<PrivacyZonePayload>(raw)
            val zones = payload.pots.mapNotNull { it.toRectZone() }
            appLogger.i("parsePrivacyZones: parsed as object format, zoneCount=${zones.size}")
            zones
        } catch (e2: Exception) {
            appLogger.w("parsePrivacyZones failed: arrayFormatError=${e1.message}, objectFormatError=${e2.message}, raw=$raw")
            emptyList()
        }
    }
}

class PrivacyZonesConfigScreen(
    private val viewModel: VideoSettingsViewModel
) : BaseScreen() {
    override val title: StringResource get() = Res.string.eslot_settings_video_privacy_zones_title

    @Composable
    override fun Content() = BaseColumn {
        // v2.4: 对齐 Mega，走 PrivacyMaskAPI.getZones() 只读 ThingModel 属性缓存
        val zoneList = remember { mutableStateListOf<RectZone>() }
        LaunchedEffect(Unit) {
            appLogger.i("PrivacyZonesConfig/load: start via PrivacyMaskAPI, deviceSn=${viewModel.megaDevice.deviceSn}")
            viewModel.loadPrivacyZones { zones ->
                appLogger.i("PrivacyZonesConfig/load: loaded ${zones.size} privacy zones via PrivacyMaskAPI, deviceSn=${viewModel.megaDevice.deviceSn}")
                zoneList.clear()
                zoneList.addAll(zones)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Define rectangular areas to block from recording.\n" +
                        "Coordinates: 0.0–1.0 (normalized, e.g. 0.5 = 50%)",
                fontSize = 13.sp,
                color = Color.Gray,
                lineHeight = 18.sp
            )

            zoneList.forEachIndexed { idx, zone ->
                RectZoneItem(
                    index = idx + 1,
                    zone = zone,
                    onDelete = { zoneList.removeAt(idx) },
                    onUpdate = { updated -> zoneList[idx] = updated }
                )
            }

            if (zoneList.size < viewModel.maxPrivacyZoneCount) {
                Button(
                    onClick = { zoneList.add(RectZone(0.25, 0.25, 0.75, 0.75)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" Add Privacy Zone")
                }
            }

            Button(
                onClick = {
                    val allPots = zoneList.map { zone ->
                        PrivacyZonePot(zone.toPoints())
                    }
                    val json = Json { encodeDefaults = true }.encodeToString(
                        PrivacyZonePayload(index = 0, pots = allPots)
                    )
                    appLogger.i("PrivacyZonesConfig/save: saving ${zoneList.size} zones, json=$json, deviceSn=${viewModel.megaDevice.deviceSn}")
                    viewModel.setPrivacyZoneConfig(json)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFE0B2), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text("🔒 Privacy Zone Tips:", fontSize = 14.sp, color = Color(0xFFE65100))
                Text(
                    "• Enter top-left (x1,y1) and bottom-right (x2,y2)\n" +
                    "• Range: 0.0–1.0 (1.0 = full width/height)\n" +
                    "• Example: x1=0.25, y1=0.25, x2=0.75, y2=0.75 = center 50%",
                    fontSize = 12.sp,
                    color = Color(0xFF424242),
                    lineHeight = 16.sp
                )
            }
        }

        MegaLoading(viewModel.loading)
    }

    @Composable
    fun RectZoneItem(
        index: Int,
        zone: RectZone,
        onDelete: () -> Unit,
        onUpdate: (RectZone) -> Unit
    ) {
        var x1Str by remember { mutableStateOf(zone.x1.toString()) }
        var y1Str by remember { mutableStateOf(zone.y1.toString()) }
        var x2Str by remember { mutableStateOf(zone.x2.toString()) }
        var y2Str by remember { mutableStateOf(zone.y2.toString()) }
        var cur by remember { mutableStateOf(zone) }

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
                Text("Privacy Zone $index (Rectangle)", fontSize = 16.sp)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Top-Left Corner", fontSize = 12.sp, color = Color.Gray)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = x1Str,
                    onValueChange = { v ->
                        x1Str = v
                        v.toDoubleOrNull()?.coerceIn(0.0, 1.0)?.let { x ->
                            cur = cur.copy(x1 = x); onUpdate(cur)
                        }
                    },
                    label = { Text("X1 [0.0-1.0]") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = y1Str,
                    onValueChange = { v ->
                        y1Str = v
                        v.toDoubleOrNull()?.coerceIn(0.0, 1.0)?.let { y ->
                            cur = cur.copy(y1 = y); onUpdate(cur)
                        }
                    },
                    label = { Text("Y1 [0.0-1.0]") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Text("Bottom-Right Corner", fontSize = 12.sp, color = Color.Gray)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = x2Str,
                    onValueChange = { v ->
                        x2Str = v
                        v.toDoubleOrNull()?.coerceIn(0.0, 1.0)?.let { x ->
                            cur = cur.copy(x2 = x); onUpdate(cur)
                        }
                    },
                    label = { Text("X2 [0.0-1.0]") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = y2Str,
                    onValueChange = { v ->
                        y2Str = v
                        v.toDoubleOrNull()?.coerceIn(0.0, 1.0)?.let { y ->
                            cur = cur.copy(y2 = y); onUpdate(cur)
                        }
                    },
                    label = { Text("Y2 [0.0-1.0]") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }
    }
}
