package com.anker.esiotkit.sample.cache

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-REP-04 / T-TST-03: DeviceCoverCache 单元测试
 */
class DeviceCoverCacheTest {

  @AfterTest
  fun teardown() {
    DeviceCoverCache.invalidateAll()
  }

  // === Basic put/get tests ===

  @Test
  fun `get should return cached URL after put`() {
    DeviceCoverCache.put("device1", "https://example.com/cover.jpg")
    assertEquals("https://example.com/cover.jpg", DeviceCoverCache.get("device1"))
  }

  @Test
  fun `get should return null for uncached device`() {
    assertNull(DeviceCoverCache.get("nonexistent"))
  }

  @Test
  fun `put should update existing entry`() {
    DeviceCoverCache.put("device1", "url1")
    DeviceCoverCache.put("device1", "url2")
    assertEquals("url2", DeviceCoverCache.get("device1"))
  }

  // === putAll tests ===

  @Test
  fun `putAll should batch insert entries`() {
    val devices = mapOf("d1" to "url1", "d2" to "url2", "d3" to "url3")
    DeviceCoverCache.putAll(devices)
    assertEquals("url1", DeviceCoverCache.get("d1"))
    assertEquals("url2", DeviceCoverCache.get("d2"))
    assertEquals("url3", DeviceCoverCache.get("d3"))
    assertEquals(3, DeviceCoverCache.size())
  }

  // === invalidate tests ===

  @Test
  fun `invalidate should remove single entry`() {
    DeviceCoverCache.put("device1", "url1")
    DeviceCoverCache.invalidate("device1")
    assertNull(DeviceCoverCache.get("device1"))
    assertEquals(0, DeviceCoverCache.size())
  }

  @Test
  fun `invalidateAll should clear all entries`() {
    DeviceCoverCache.put("d1", "url1")
    DeviceCoverCache.put("d2", "url2")
    DeviceCoverCache.invalidateAll()
    assertEquals(0, DeviceCoverCache.size())
    assertNull(DeviceCoverCache.get("d1"))
    assertNull(DeviceCoverCache.get("d2"))
  }

  // === LRU eviction tests ===

  @Test
  fun `should evict LRU entry when exceeding max capacity`() {
    // Fill cache to max capacity (100)
    for (i in 0 until 100) {
      DeviceCoverCache.put("device_$i", "url_$i")
    }
    assertEquals(100, DeviceCoverCache.size())

    // Add one more, should evict the first (LRU) entry
    DeviceCoverCache.put("device_new", "url_new")
    assertEquals(100, DeviceCoverCache.size())

    // First inserted entry (device_0) should be evicted
    assertNull(DeviceCoverCache.get("device_0"))

    // Other entries should still be present
    assertTrue(DeviceCoverCache.get("device_99") != null)
  }

  @Test
  fun `accessing entry should update LRU order`() {
    // Fill cache
    for (i in 0 until 100) {
      DeviceCoverCache.put("device_$i", "url_$i")
    }

    // Access device_0 to update its access time
    DeviceCoverCache.get("device_0")

    // Add new entry, should evict device_1 (now the LRU)
    DeviceCoverCache.put("device_new", "url_new")

    // device_0 should still be present (was accessed recently)
    assertTrue(DeviceCoverCache.get("device_0") != null)

    // device_1 should be evicted
    assertNull(DeviceCoverCache.get("device_1"))
  }

  // === size tests ===

  @Test
  fun `size should return correct count`() {
    assertEquals(0, DeviceCoverCache.size())
    DeviceCoverCache.put("d1", "url1")
    assertEquals(1, DeviceCoverCache.size())
    DeviceCoverCache.put("d2", "url2")
    assertEquals(2, DeviceCoverCache.size())
  }
}
