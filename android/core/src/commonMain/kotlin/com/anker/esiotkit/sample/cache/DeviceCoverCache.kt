package com.anker.esiotkit.sample.cache

import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlin.time.Duration.Companion.minutes
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * T-REP-04: 设备封面图内存缓存
 *
 * LRU 淘汰策略，TTL 30 分钟，最大 100 条目。
 * KMP 兼容实现（不使用 LinkedHashMap accessOrder）。
 *
 * 线程安全：通过 kotlinx.atomicfu.locks.synchronized 保证并发安全（KMP 跨平台）。
 */
object DeviceCoverCache {

  private const val MAX_SIZE = 100
  private val TTL_MS = 30.minutes.inWholeMilliseconds

  private data class CacheEntry(
    val url: String,
    val createdAt: Long,
    var lastAccessTime: Long
  )

  private val cache = mutableMapOf<String, CacheEntry>()
  private val accessOrder = mutableListOf<String>()
  private val lock = SynchronizedObject()

  /**
   * 获取缓存 URL，过期返回 null
   */
  fun get(deviceId: String): String? = synchronized(lock) {
    val entry = cache[deviceId] ?: return@synchronized null
    val now = currentTimeMillis()

    // Check TTL
    if (now - entry.createdAt > TTL_MS) {
      cache.remove(deviceId)
      accessOrder.remove(deviceId)
      return@synchronized null
    }

    // Update access time for LRU
    entry.lastAccessTime = now
    accessOrder.remove(deviceId)
    accessOrder.add(deviceId)

    entry.url
  }

  /**
   * 存入缓存，LRU 淘汰
   */
  fun put(deviceId: String, url: String) = synchronized(lock) {
    val now = currentTimeMillis()

    if (cache.containsKey(deviceId)) {
      // Update existing entry
      cache[deviceId] = CacheEntry(url, now, now)
      accessOrder.remove(deviceId)
      accessOrder.add(deviceId)
    } else {
      // Evict if at capacity
      if (cache.size >= MAX_SIZE) {
        evictLruLocked()
      }

      cache[deviceId] = CacheEntry(url, now, now)
      accessOrder.add(deviceId)
    }
  }

  /**
   * 批量存入缓存
   */
  fun putAll(devices: Map<String, String>) = synchronized(lock) {
    devices.forEach { (deviceId, url) ->
      putLocked(deviceId, url)
    }
  }

  /**
   * 清除单个条目
   */
  fun invalidate(deviceId: String) = synchronized(lock) {
    cache.remove(deviceId)
    accessOrder.remove(deviceId)
    Unit
  }

  /**
   * 清空全部
   */
  fun invalidateAll() = synchronized(lock) {
    cache.clear()
    accessOrder.clear()
  }

  /**
   * 清除过期条目
   */
  fun evictExpired() = synchronized(lock) {
    val now = currentTimeMillis()
    val expiredIds = cache.filterValues { entry ->
      now - entry.createdAt > TTL_MS
    }.keys.toList()

    expiredIds.forEach { id ->
      cache.remove(id)
      accessOrder.remove(id)
    }
  }

  /**
   * 获取缓存大小（调试用）
   */
  fun size(): Int = synchronized(lock) { cache.size }

  // === Private methods ===

  private fun putLocked(deviceId: String, url: String) {
    val now = currentTimeMillis()

    if (cache.containsKey(deviceId)) {
      cache[deviceId] = CacheEntry(url, now, now)
      accessOrder.remove(deviceId)
      accessOrder.add(deviceId)
    } else {
      if (cache.size >= MAX_SIZE) {
        evictLruLocked()
      }
      cache[deviceId] = CacheEntry(url, now, now)
      accessOrder.add(deviceId)
    }
  }

  private fun evictLruLocked() {
    val lruId = accessOrder.firstOrNull() ?: return
    cache.remove(lruId)
    accessOrder.removeAt(0)
  }
}
