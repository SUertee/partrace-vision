package com.anker.esiotkit.sample.main.mega.media

import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.live_stream.LiveMediaPlayer
import com.anker.esiotkit.support.log.appLogger

/**
 * Android 实现: aar release() 内部已包含 stopPlay()，直接调 release()。
 *
 * 依据:
 * - IjkLivePlayerBridge.release() 通过 runOnMainThread 执行 lambda，
 *   lambda 内第一步调 stopPlay()（含 releaseSP 同步 stop + 异步 release）
 * - IjkEventPlayerBridge.release() 第一步直接调 stopPlay()
 *
 * 调用层不需要先调 stopPlay()，否则会与 release() 内部的 stopPlay() 形成竞态。
 */
internal actual fun LiveMediaPlayer.stopAndRelease() {
    appLogger.i("MediaLifecycle/LiveMediaPlayer/stopAndRelease/Android: calling release() directly (aar release() contains stopPlay)")
    release()
}

internal actual fun EventMediaPlayer.stopAndRelease() {
    appLogger.i("MediaLifecycle/EventMediaPlayer/stopAndRelease/Android: calling release() directly (aar release() contains stopPlay)")
    release()
}
