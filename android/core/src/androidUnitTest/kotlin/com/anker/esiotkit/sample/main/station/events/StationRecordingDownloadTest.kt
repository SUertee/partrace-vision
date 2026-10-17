package com.anker.esiotkit.sample.main.station.events

import com.anker.esiotkit.sample.main.station.agent.TraceApiFailure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StationRecordingDownloadTest {
  @get:Rule val temporary = TemporaryFolder()

  // Only a transport fixture: decoding and seeking belong to the Android player.
  private val video = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109, 0, 0, 0, 0,
    105, 115, 111, 109, 109, 112, 52, 50)

  private class Fixture(private val respond: (HttpExchange) -> Unit) : AutoCloseable {
    val paths = CopyOnWriteArrayList<String>()
    val methods = CopyOnWriteArrayList<String>()
    val authorization = CopyOnWriteArrayList<String>()
    val accept = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange ->
        paths.add(exchange.requestURI.toString())
        methods.add(exchange.requestMethod)
        authorization.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
        accept.add(exchange.requestHeaders.getFirst("Accept").orEmpty())
        try { respond(exchange) } finally { exchange.close() }
      }
      start()
    }
    val origin get() = "http://127.0.0.1:${server.address.port}"
    override fun close() { server.stop(0) }
  }

  private fun HttpExchange.reply(type: String, value: ByteArray, chunked: Boolean = false) {
    responseHeaders.add("Content-Type", type)
    // A zero response length asks HttpServer to use chunked transfer encoding.
    sendResponseHeaders(200, if (chunked || value.isEmpty()) 0 else value.size.toLong())
    responseBody.use { it.write(value) }
  }

  private suspend fun rejected(block: suspend () -> Unit): TraceApiFailure {
    try { block() } catch (failure: TraceApiFailure) { return failure }
    throw AssertionError("Expected TraceApiFailure")
  }

  private suspend fun assertCacheEmpty(directory: File) {
    // Cancellation returns promptly; an OkHttp callback may still be closing its file.
    withTimeout(2_000) {
      while (directory.walkTopDown().any { it.isFile }) delay(10)
    }
    assertFalse(directory.walkTopDown().any { it.isFile })
  }

  @Test fun `recording downloads with a fixed authorized GET and release deletes its private file`() = runBlocking {
    val cache = temporary.newFolder("success")
    Fixture { it.reply("video/mp4", video) }.use { fixture ->
      val playback = downloadStationRecording(cache, fixture.origin, "viewer-token", "rec_1") as AndroidStationRecordingPlayback
      try {
        assertTrue(playback.file.isFile)
        assertTrue(playback.file.canonicalPath.startsWith(cache.canonicalPath + File.separator))
        assertArrayEquals(video, playback.file.readBytes())
        assertEquals(listOf("/api/v1/recordings/rec_1/media"), fixture.paths.toList())
        assertEquals(listOf("GET"), fixture.methods.toList())
        assertEquals(listOf("Bearer viewer-token"), fixture.authorization.toList())
        assertEquals(listOf("video/mp4"), fixture.accept.toList())
      } finally { playback.release() }
      playback.release()
      assertCacheEmpty(cache)
    }
  }

  @Test fun `wrong media type and empty responses are rejected without retained files`() = runBlocking {
    for ((name, type, bytes) in listOf(Triple("mime", "image/png", video), Triple("empty", "video/mp4", byteArrayOf()))) {
      val cache = temporary.newFolder(name)
      Fixture { it.reply(type, bytes) }.use { fixture ->
        val failure = rejected { downloadStationRecording(cache, fixture.origin, "viewer-token", "rec_1") }
        assertEquals(200, failure.status)
        assertCacheEmpty(cache)
      }
    }
  }

  @Test fun `known and chunked bodies exceeding the byte limit are rejected and removed`() = runBlocking {
    for (chunked in listOf(false, true)) {
      val cache = temporary.newFolder(if (chunked) "chunked" else "known")
      Fixture { it.reply("video/mp4", video, chunked) }.use { fixture ->
        rejected { downloadStationRecording(cache, fixture.origin, "viewer-token", "rec_1", maxBytes = 16L) }
        assertCacheEmpty(cache)
      }
    }
  }

  @Test fun `redirects are rejected without contacting the destination or retaining files`() = runBlocking {
    val cache = temporary.newFolder("redirect")
    Fixture { it.reply("video/mp4", video) }.use { destination ->
      Fixture { exchange ->
        exchange.responseHeaders.add("Location", destination.origin + "/redirected")
        exchange.sendResponseHeaders(302, -1)
      }.use { fixture ->
        val failure = rejected { downloadStationRecording(cache, fixture.origin, "viewer-token", "rec_1") }
        assertEquals(302, failure.status)
        assertTrue(destination.paths.isEmpty())
        assertEquals(listOf("Bearer viewer-token"), fixture.authorization.toList())
        assertCacheEmpty(cache)
      }
    }
  }

  @Test fun `cancelling a streaming download cancels its coroutine and removes the partial file`() = runBlocking {
    val cache = temporary.newFolder("cancelled")
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    Fixture { exchange ->
      exchange.responseHeaders.add("Content-Type", "video/mp4")
      exchange.sendResponseHeaders(200, 0)
      runCatching {
        exchange.responseBody.use { output ->
          output.write(video)
          output.flush()
          entered.complete(Unit)
          release.await(5, TimeUnit.SECONDS)
          output.write(video)
        }
      }
    }.use { fixture ->
      val download = async { downloadStationRecording(cache, fixture.origin, "viewer-token", "rec_1") }
      try {
        withTimeout(2_000) { entered.await() }
        download.cancelAndJoin()
        assertTrue(download.isCancelled)
        assertCacheEmpty(cache)
      } finally { release.countDown() }
    }
  }
}
