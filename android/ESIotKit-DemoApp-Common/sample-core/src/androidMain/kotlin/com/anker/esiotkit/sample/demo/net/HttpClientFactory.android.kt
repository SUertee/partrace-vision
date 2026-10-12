package com.anker.esiotkit.sample.demo.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/** Android 实现：OkHttp 引擎 + 超时策略 */
actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
  expectSuccess = false
  install(HttpTimeout) {
    requestTimeoutMillis = 120_000L
    connectTimeoutMillis = 15_000L
    socketTimeoutMillis = 120_000L
  }
}
