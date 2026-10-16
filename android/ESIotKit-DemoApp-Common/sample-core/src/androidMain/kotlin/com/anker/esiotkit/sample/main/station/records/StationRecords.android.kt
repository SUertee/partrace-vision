package com.anker.esiotkit.sample.main.station.records

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.anker.esiotkit.sample.main.station.agent.AndroidTraceAgentStore
import com.anker.esiotkit.sample.main.station.agent.TraceAgentClient
import com.anker.esiotkit.sample.main.station.agent.TraceApiFailure
import com.anker.esiotkit.sample.main.station.agent.objectValue
import com.anker.esiotkit.sample.main.station.agent.objects
import com.anker.esiotkit.sample.main.station.agent.requireTraceId
import com.anker.esiotkit.sample.main.station.agent.string
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@Composable
actual fun rememberStationNotificationsEnabled(): Boolean {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var enabled by remember(context) { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
  DisposableEffect(context, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  return enabled
}

@Composable
actual fun rememberStationReviewSource(): StationReviewSource {
  val context = LocalContext.current.applicationContext
  return remember(context) { AndroidStationReviewSource(AndroidTraceAgentStore(context)) }
}

/** Reuses only the independently configured backend origin and Bearer; it never invokes an Agent/model endpoint. */
private class AndroidStationReviewSource(private val store: AndroidTraceAgentStore) : StationReviewSource {
  override suspend fun load(): StationReviewResult = withContext(Dispatchers.IO) {
    try {
      val config = store.read()
      if (!config.configured) return@withContext StationReviewResult.NotConfigured
      val response = TraceAgentClient(config.baseUrl, config.bearerToken).get("/api/v1/review-records")
      StationReviewResult.Loaded(response.objects("reviews").map(::review))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { StationReviewResult.Failed(reviewFailure(failure)) }
  }

  override suspend fun detail(id: String): StationReviewDetailResult = withContext(Dispatchers.IO) {
    try {
      requireTraceId(id)
      val config = store.read()
      if (!config.configured) return@withContext StationReviewDetailResult.Failed("请先在服务设置中连接后端。")
      val response = TraceAgentClient(config.baseUrl, config.bearerToken).get("/api/v1/review-records/$id")
      val value = review(response.objectValue("review") ?: throw TraceApiFailure(0, "invalid_response"))
      if (value.id != id) throw TraceApiFailure(0, "invalid_response")
      val evidence = response.objects("evidence").map { requireTraceId(it.string("evidence_id") ?: "") }
      StationReviewDetailResult.Loaded(StationReviewDetail(value, evidence))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { StationReviewDetailResult.Failed(reviewFailure(failure)) }
  }

  private fun review(value: JsonObject): StationReviewRecord {
    val id = requireTraceId(value.string("review_id") ?: "")
    val eventId = requireTraceId(value.string("event_id") ?: "")
    val timestamp = value.string("created_at") ?: throw TraceApiFailure(0, "invalid_response")
    val displayTime = runCatching { Instant.parse(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }
      .getOrElse { throw TraceApiFailure(0, "invalid_response") }
    val query = value.objectValue("query") ?: throw TraceApiFailure(0, "invalid_response")
    val decision = when (value.string("decision")) {
      "confirmed" -> "已确认"
      "dismissed" -> "已排除"
      "uncertain" -> "待确认"
      else -> throw TraceApiFailure(0, "invalid_response")
    }
    return StationReviewRecord(id, eventId, displayTime, decision, value.string("note"),
      requireTraceId(value.string("reviewer_id") ?: ""),
      requireTraceId(query.string("recording_id") ?: ""), requireTraceId(query.string("zone_id") ?: ""))
  }
}

private fun reviewFailure(failure: Exception): String = when {
  failure is TraceApiFailure && failure.status == 401 -> "后端凭据已失效，请更新服务设置。"
  failure is TraceApiFailure && failure.status == 403 -> "当前账号没有读取这些审核记录的权限。"
  failure is TraceApiFailure && failure.status == 404 -> "记录不存在或已无法访问。"
  failure is TraceApiFailure && failure.code == "invalid_response" -> "后端返回的数据格式不正确，请稍后重试。"
  failure is TraceApiFailure && failure.code == "network_unavailable" -> "无法连接后端，请检查网络和服务地址。"
  else -> "读取失败，请检查后端服务后重试。"
}
