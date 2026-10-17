package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.sample.cache.DeviceCoverCache
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-REP-03: EventRepository 单元测试
 *
 * 测试 EventRepository 的核心功能：
 * - getLatestEvent 查询最新事件
 * - getEventList 分页查询
 * - getCoverUrl / refreshCoverUrl 封面图管理
 * - withTimeout 超时处理
 */
class EventRepositoryTest {

  @BeforeTest
  fun setup() {
    DeviceCoverCache.invalidateAll()
  }

  @AfterTest
  fun teardown() {
    DeviceCoverCache.invalidateAll()
  }

  // === getLatestEvent tests ===

  @Test
  fun `getLatestEvent should return null for device with no events`() = runBlocking {
    val result = EventRepository.getLatestEvent("device_no_events")
    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())
  }

  // === getEventList tests ===

  @Test
  fun `getEventList should return empty result for placeholder`() = runBlocking {
    val result = EventRepository.getEventList("device1", 0, 10)
    assertTrue(result.isSuccess)
    val pageResult = result.getOrNull()!!
    assertEquals(0, pageResult.events.size)
    assertFalse(pageResult.hasNextPage)
    assertEquals(0, pageResult.totalCount)
  }

  @Test
  fun `getEventList should handle page zero`() = runBlocking {
    val result = EventRepository.getEventList("device1", 0, 20)
    assertTrue(result.isSuccess)
  }

  @Test
  fun `getEventList should handle non-zero page`() = runBlocking {
    val result = EventRepository.getEventList("device1", 5, 10)
    assertTrue(result.isSuccess)
    val pageResult = result.getOrNull()!!
    assertEquals(0, pageResult.events.size)
  }

  // === getCoverUrl tests ===

  @Test
  fun `getCoverUrl should return null for uncached device`() {
    val result = EventRepository.getCoverUrl("uncached_device")
    assertNull(result)
  }

  @Test
  fun `getCoverUrl should return cached URL`() {
    DeviceCoverCache.put("device_cached", "https://example.com/thumb.jpg")

    val result = EventRepository.getCoverUrl("device_cached")
    assertEquals("https://example.com/thumb.jpg", result)
  }

  // === refreshCoverUrl tests ===

  @Test
  fun `refreshCoverUrl should return null when no events exist`() = runBlocking {
    val result = EventRepository.refreshCoverUrl("device_no_events")
    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())
  }

  // === getCoverUrlWithTimeout tests ===

  @Test
  fun `getCoverUrlWithTimeout should return cached URL on placeholder`() = runBlocking {
    // Placeholder returns null for latestEvent, so falls back to cache
    DeviceCoverCache.put("device_timeout", "https://cached.com/img.jpg")

    val result = EventRepository.getCoverUrlWithTimeout("device_timeout")
    // Should fall back to cached URL
    assertEquals("https://cached.com/img.jpg", result)
  }

  @Test
  fun `getCoverUrlWithTimeout should return null when no cache and no events`() = runBlocking {
    val result = EventRepository.getCoverUrlWithTimeout("no_cache_no_events")
    assertNull(result)
  }

  // === invalidateCover tests ===

  @Test
  fun `invalidateCover should remove cached URL`() {
    DeviceCoverCache.put("device_invalidate", "https://example.com/img.jpg")
    assertEquals("https://example.com/img.jpg", EventRepository.getCoverUrl("device_invalidate"))

    EventRepository.invalidateCover("device_invalidate")
    assertNull(EventRepository.getCoverUrl("device_invalidate"))
  }

  // === v2.2.7 增强：向后兼容性测试（不传 parentSn 时行为不变） ===

  @Test
  fun `getLatestEvent without parentSn returns null - backward compat`() = runBlocking {
    val result = EventRepository.getLatestEvent("any_sn")
    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())
  }

  @Test
  fun `getLatestEvent with blank parentSn returns null - backward compat`() = runBlocking {
    val result = EventRepository.getLatestEvent("any_sn", "")
    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())
  }

  @Test
  fun `refreshCoverUrl without parentSn returns null and does not write cache - backward compat`() = runBlocking {
    DeviceCoverCache.invalidateAll()
    val result = EventRepository.refreshCoverUrl("any_sn")
    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())
    assertNull(DeviceCoverCache.get("any_sn"))
  }

  @Test
  fun `getCoverUrlWithTimeout without parentSn falls back to cache - backward compat`() = runBlocking {
    DeviceCoverCache.invalidateAll()
    DeviceCoverCache.put("cached_sn", "cached_path")
    val result = EventRepository.getCoverUrlWithTimeout("cached_sn")
    assertEquals("cached_path", result)
  }

  @Test
  fun `refreshCoverUrlsBatch without parentSn does not write cache - backward compat`() = runBlocking {
    DeviceCoverCache.invalidateAll()
    EventRepository.refreshCoverUrlsBatch(listOf("sn1", "sn2", "sn3"))
    assertNull(DeviceCoverCache.get("sn1"))
    assertNull(DeviceCoverCache.get("sn2"))
    assertNull(DeviceCoverCache.get("sn3"))
  }

  // === v2.2.7 增强：DI 失败路径测试（parentSn 有值但 SDK 未初始化） ===

  @Test
  fun `getLatestEvent with parentSn but no DI returns failure`() = runBlocking {
    val result = EventRepository.getLatestEvent("sn_no_di", "parent_sn")
    assertTrue(result.isFailure)
  }

  @Test
  fun `refreshCoverUrl with parentSn but no DI returns null and does not cache`() = runBlocking {
    DeviceCoverCache.invalidateAll()
    val result = EventRepository.refreshCoverUrl("sn_no_di", "parent_sn")
    assertTrue(result.isSuccess)
    assertNull(result.getOrNull())
    assertNull(DeviceCoverCache.get("sn_no_di"))
  }

  @Test
  fun `getCoverUrlWithTimeout with parentSn but no DI falls back to cache`() = runBlocking {
    DeviceCoverCache.invalidateAll()
    DeviceCoverCache.put("sn_no_di", "cached_fallback")
    val result = EventRepository.getCoverUrlWithTimeout("sn_no_di", "parent_sn")
    assertEquals("cached_fallback", result)
  }

  @Test
  fun `getCoverUrlWithTimeout with parentSn but no DI and no cache returns null`() = runBlocking {
    DeviceCoverCache.invalidateAll()
    val result = EventRepository.getCoverUrlWithTimeout("sn_no_cache", "parent_sn")
    assertNull(result)
  }

  // === Data class tests ===

  @Test
  fun `LatestEvent data class should hold correct values`() {
    val event = EventRepository.LatestEvent(
      eventId = "evt_001",
      thumbnailUrl = "https://example.com/thumb.jpg",
      eventType = "motion",
      timestamp = 1700000000L
    )

    assertEquals("evt_001", event.eventId)
    assertEquals("https://example.com/thumb.jpg", event.thumbnailUrl)
    assertEquals("motion", event.eventType)
    assertEquals(1700000000L, event.timestamp)
  }

  @Test
  fun `EventPageResult data class should hold correct values`() {
    val events = listOf(
      EventRepository.EventItem(
        eventId = "evt_1",
        deviceSn = "device1",
        deviceName = "Camera 1",
        thumbnailUrl = "https://example.com/1.jpg",
        eventType = "motion",
        timestamp = 1700000000L,
        duration = 5000L
      )
    )
    val pageResult = EventRepository.EventPageResult(
      events = events,
      hasNextPage = true,
      totalCount = 25
    )

    assertEquals(1, pageResult.events.size)
    assertTrue(pageResult.hasNextPage)
    assertEquals(25, pageResult.totalCount)
    assertEquals("Camera 1", pageResult.events[0].deviceName)
  }

  @Test
  fun `EventItem data class should hold correct values`() {
    val item = EventRepository.EventItem(
      eventId = "evt_002",
      deviceSn = "T8410DEVICE",
      deviceName = "Front Door",
      thumbnailUrl = null,
      eventType = "human",
      timestamp = 1700001000L,
      duration = 3000L
    )

    assertEquals("evt_002", item.eventId)
    assertEquals("T8410DEVICE", item.deviceSn)
    assertEquals("Front Door", item.deviceName)
    assertNull(item.thumbnailUrl)
    assertEquals("human", item.eventType)
    assertEquals(3000L, item.duration)
  }
}
