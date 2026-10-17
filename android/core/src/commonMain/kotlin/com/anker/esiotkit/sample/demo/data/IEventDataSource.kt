package com.anker.esiotkit.sample.demo.data

import com.anker.esiotkit.domain.model.MegaEvent

/**
 * SDK 数据层：封装 ESIotKit 的事件查询 / 缩略图 / 视频下载 / 设备在线状态。
 *
 * 所有实现内部保证在主线程安全与 IO 调度（回调式 SDK API 统一包装为 suspend）。
 */
interface IEventDataSource {

  /**
   * 查询指定日期（相对今天，0=今天，-1=昨天）的事件列表。
   *
   * @param dayOffset 相对今天的天数偏移
   * @param deviceSn  设备 SN（空串 = 查询全部安防设备）
   */
  suspend fun fetchTodayEvents(dayOffset: Int = 0, deviceSn: String = ""): List<MegaEvent>

  /** 获取事件缩略图 Base64；失败返回 null（触发降级） */
  suspend fun fetchThumbnailBase64(event: MegaEvent): String?

  /** 下载事件视频到本地，返回本地文件路径；失败返回 null（触发降级） */
  suspend fun downloadEventVideo(event: MegaEvent): String?

  /** 设备是否在线（查询异常时按在线处理，避免阻断演示） */
  suspend fun isDeviceOnline(deviceSn: String): Boolean
}
