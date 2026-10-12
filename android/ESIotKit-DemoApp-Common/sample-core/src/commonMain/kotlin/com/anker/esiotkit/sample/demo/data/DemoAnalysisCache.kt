package com.anker.esiotkit.sample.demo.data

import com.anker.esiotkit.domain.model.MegaEvent

/**
 * Demo 级内存缓存（进程内、无持久化）——避免演示中重复的 SDK/网络拉取。
 *
 * - 事件列表：按 key（如 `events:0`）缓存，进程内常驻、不按时间过期（重启自然失效）
 * - 缩略图：按 eventId 缓存 base64，保持插入序，上限 [MAX_THUMBNAIL_ENTRIES] 条
 */
object DemoAnalysisCache {

  private const val MAX_THUMBNAIL_ENTRIES = 200

  private val eventCache = mutableMapOf<String, List<MegaEvent>>()
  private val thumbnailCache = LinkedHashMap<String, String>()

  fun getEvents(key: String): List<MegaEvent>? = eventCache[key]

  fun putEvents(key: String, events: List<MegaEvent>) {
    eventCache[key] = events
  }

  fun getThumbnail(eventId: String): String? = thumbnailCache[eventId]

  fun putThumbnail(eventId: String, base64: String) {
    thumbnailCache.remove(eventId)
    thumbnailCache[eventId] = base64
    while (thumbnailCache.size > MAX_THUMBNAIL_ENTRIES) {
      val oldest = thumbnailCache.keys.firstOrNull() ?: break
      thumbnailCache.remove(oldest)
    }
  }

  fun clear() {
    eventCache.clear()
    thumbnailCache.clear()
  }
}
