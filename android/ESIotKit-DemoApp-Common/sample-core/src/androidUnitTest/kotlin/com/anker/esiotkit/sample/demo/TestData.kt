package com.anker.esiotkit.sample.demo

import java.io.File

/**
 * 测试数据目录解析（供 test_data 相关用例使用）。
 *
 * 优先级：
 *  1. 系统属性 `-DtestDataDir=<dir>`（Gradle 透传）；
 *  2. 工程相对目录 `test_data/`（默认）。
 *
 * 目录不存在时，相关用例通过 `assumeTrue` 自动跳过，不影响常规回归。
 */
internal object TestData {
  val dir: File get() = File(System.getProperty("testDataDir") ?: "test_data")
  val thumbnails: File get() = File(dir, "thumbnails")
  val videos: File get() = File(dir, "videos")
}
