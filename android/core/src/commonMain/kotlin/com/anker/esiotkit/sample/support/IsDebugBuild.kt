package com.anker.esiotkit.sample.support

/**
 * 是否为 Debug 构建。
 *
 * Android 端读取模块自身的 BuildConfig.DEBUG；iOS 端读取 Kotlin/Native 的
 * Platform.isDebugBinary（对应 Xcode Debug/Release 编译配置）。
 *
 * 补充说明：该符号原本在 commit 37ca0ec 中被引用，但当时未提交对应的
 * expect/actual 定义文件，导致 Release 编译（含 Xcode archive）失败。
 */
expect val isDebugBuild: Boolean
