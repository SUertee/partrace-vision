package com.anker.esiotkit.sample.demo.net

import io.ktor.client.HttpClient

/**
 * 平台 HTTP 客户端工厂（expect/actual）。
 * commonMain 不直接依赖具体引擎（保持 KMP 兼容）。
 */
expect fun createHttpClient(): HttpClient
