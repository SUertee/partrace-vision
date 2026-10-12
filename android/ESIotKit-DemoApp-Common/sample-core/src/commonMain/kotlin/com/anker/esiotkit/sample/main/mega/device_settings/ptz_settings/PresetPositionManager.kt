package com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings

import com.anker.esiotkit.support.platform.IotImage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton manager for preset position data
 * Shared between PresetPositionsScreen and MegaLiveVideoScreen
 */
object PresetPositionManager {

  /**
   * Event emitted when preset data changes
   * Contains the deviceSn that was modified
   */
  private val _presetChanges = MutableSharedFlow<String>(extraBufferCapacity = 1)
  val presetChanges = _presetChanges.asSharedFlow()

  /**
   * Notify listeners that preset data has changed for a device
   */
  suspend fun notifyPresetChanged(deviceSn: String) {
    _presetChanges.tryEmit(deviceSn)
  }

  /**
   * Cache for preset positions status per device
   * Key: deviceSn, Value: Map of presetIndex (1-4) to isSet status
   */
  private val presetStatusCache = mutableMapOf<String, MutableMap<Int, Boolean>>()

  /**
   * Cache for preset thumbnails per device
   * Key: deviceSn, Value: Map of presetIndex (1-4) to IotImage
   */
  private val thumbnailCache = mutableMapOf<String, MutableMap<Int, IotImage>>()

  /**
   * Cache for selected preset index per device
   * Key: deviceSn, Value: selected preset index (0-3)
   */
  private val selectedIndexCache = mutableMapOf<String, Int>()

  /**
   * Cache for default preset index per device
   * Key: deviceSn, Value: default preset index (0-3)
   */
  private val defaultIndexCache = mutableMapOf<String, Int>()

  /**
   * Initialize cache for a device
   */
  fun initDevice(deviceSn: String) {
    if (!presetStatusCache.containsKey(deviceSn)) {
      presetStatusCache[deviceSn] = mutableMapOf()
    }
    if (!thumbnailCache.containsKey(deviceSn)) {
      thumbnailCache[deviceSn] = mutableMapOf()
    }
  }

  /**
   * Check if a preset is set
   * @param deviceSn device serial number
   * @param presetIndex 1-based index (1-4)
   */
  fun isPresetSet(deviceSn: String, presetIndex: Int): Boolean {
    return presetStatusCache[deviceSn]?.get(presetIndex) == true
  }

  /**
   * Set preset status
   * @param deviceSn device serial number
   * @param presetIndex 1-based index (1-4)
   * @param isSet whether preset is set
   */
  fun setPresetStatus(deviceSn: String, presetIndex: Int, isSet: Boolean) {
    initDevice(deviceSn)
    presetStatusCache[deviceSn]?.put(presetIndex, isSet)
  }

  /**
   * Set preset status and notify listeners
   */
  suspend fun setPresetStatusAndNotify(deviceSn: String, presetIndex: Int, isSet: Boolean) {
    setPresetStatus(deviceSn, presetIndex, isSet)
    notifyPresetChanged(deviceSn)
  }

  /**
   * Get preset thumbnail
   * @param deviceSn device serial number
   * @param presetIndex 1-based index (1-4)
   */
  fun getThumbnail(deviceSn: String, presetIndex: Int): IotImage? {
    return thumbnailCache[deviceSn]?.get(presetIndex)
  }

  /**
   * Set preset thumbnail
   * @param deviceSn device serial number
   * @param presetIndex 1-based index (1-4)
   * @param image thumbnail image
   */
  fun setThumbnail(deviceSn: String, presetIndex: Int, image: IotImage) {
    initDevice(deviceSn)
    thumbnailCache[deviceSn]?.put(presetIndex, image)
  }

  /**
   * Remove preset thumbnail
   * @param deviceSn device serial number
   * @param presetIndex 1-based index (1-4)
   */
  fun removeThumbnail(deviceSn: String, presetIndex: Int) {
    thumbnailCache[deviceSn]?.remove(presetIndex)
  }

  /**
   * Remove preset thumbnail and notify listeners
   */
  suspend fun removeThumbnailAndNotify(deviceSn: String, presetIndex: Int) {
    removeThumbnail(deviceSn, presetIndex)
    notifyPresetChanged(deviceSn)
  }

  /**
   * Get selected preset index (0-based)
   */
  fun getSelectedIndex(deviceSn: String): Int {
    return selectedIndexCache[deviceSn] ?: 0
  }

  /**
   * Set selected preset index (0-based)
   */
  fun setSelectedIndex(deviceSn: String, index: Int) {
    selectedIndexCache[deviceSn] = index
  }

  /**
   * Get default preset index (0-based)
   */
  fun getDefaultIndex(deviceSn: String): Int {
    return defaultIndexCache[deviceSn] ?: 0
  }

  /**
   * Set default preset index (0-based)
   */
  fun setDefaultIndex(deviceSn: String, index: Int) {
    defaultIndexCache[deviceSn] = index
  }

  /**
   * Get list of all set preset indices (1-based) for a device
   */
  fun getSetPresets(deviceSn: String): List<Int> {
    return presetStatusCache[deviceSn]
      ?.filter { it.value }
      ?.keys
      ?.toList()
      ?: emptyList()
  }

  /**
   * Get preset count for a device
   */
  fun getPresetCount(deviceSn: String): Int {
    return presetStatusCache[deviceSn]?.count { it.value } ?: 0
  }

  /**
   * Clear all data for a device
   */
  fun clearDevice(deviceSn: String) {
    presetStatusCache.remove(deviceSn)
    thumbnailCache.remove(deviceSn)
    selectedIndexCache.remove(deviceSn)
    defaultIndexCache.remove(deviceSn)
  }
}
