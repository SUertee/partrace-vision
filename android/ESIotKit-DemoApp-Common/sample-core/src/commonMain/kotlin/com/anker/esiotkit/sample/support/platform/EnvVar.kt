package com.anker.esiotkit.sample.support.platform

/**
 * 运行时读取环境变量（供 RouterConfig key 注入用）。
 *
 * - Android/JVM：System.getenv
 * - iOS(Native)：posix.getenv（App 内通常为 null，真机 key 走 RouterConfig.API_KEY 常量）
 */
expect fun platformGetenv(name: String): String?
