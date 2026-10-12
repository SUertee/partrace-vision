package com.anker.esiotkit.sample.main.mega.media

import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.live_stream.LiveMediaPlayer

/**
 * 播放器安全释放扩展函数。
 *
 * 解决 Bug #11095: stopPlay() + release() 两步调用之间的线程安全竞态。
 *
 * - Android: aar release() 内部已包含 stopPlay()，直接调 release() 即可
 * - iOS: aar release() 不含 stopPlay()，需先 stopPlay() 再等待 ijkmp_shutdown 后台线程完成后 release()
 *
 * 依据:
 * - Android IjkLivePlayerBridge.release$lambda$0 字节码: 第一步调 stopPlay()
 * - Android IjkEventPlayerBridge.release() 字节码: 第一步调 stopPlay()
 * - iOS LiveMediaPlayer.iosArm64.release(): 仅调 mediaPlayerDeinit()
 * - iOS EventMediaPlayer.iosArm64.release(): 仅调 historyMediaPlayerDeinit()
 * - Mega A2BLivePlayObject.mediaPlayerDeinit: 检查 videoPlayState 兜底
 * - SDK A2BObject.mediaPlayerDeinit: 缺少此兜底
 */
internal expect fun LiveMediaPlayer.stopAndRelease()

internal expect fun EventMediaPlayer.stopAndRelease()
