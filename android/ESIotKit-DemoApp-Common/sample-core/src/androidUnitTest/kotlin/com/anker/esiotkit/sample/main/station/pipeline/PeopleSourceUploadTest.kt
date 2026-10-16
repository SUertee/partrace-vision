package com.anker.esiotkit.sample.main.station.pipeline

import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PeopleSourceUploadTest {
  @Test
  fun `source request targets recordings with explicit mode and unchanged manifest and file bytes`() {
    // This byte fixture tests multipart encoding only; actual media validation belongs to the pipeline.
    val fixture = byteArrayOf(0, 1, 2, 3, -1)
    val file = File.createTempFile("people-source-contract", ".bin")
    try {
      file.writeBytes(fixture)
      val manifest = """{"job_id":"stable-job","camera_id":"camera","config_version":"config-v1","sdk_event_id":"native/event:1+?=","event_type":"people","duration_ms":1234}"""
      val request = peopleSourceUploadRequest("https://backend.example", "test-backend-token", manifest, file)
      assertEquals("POST", request.method)
      assertEquals("/api/v1/recordings", request.url.encodedPath)
      assertEquals("Bearer test-backend-token", request.header("Authorization"))
      val body = request.body as MultipartBody
      assertEquals(MultipartBody.FORM, body.type)
      assertEquals(3, body.parts.size)
      assertEquals("form-data; name=\"mode\"", body.parts[0].headers?.get("Content-Disposition"))
      assertEquals("source", Buffer().also { body.parts[0].body.writeTo(it) }.readUtf8())
      assertEquals("form-data; name=\"manifest\"", body.parts[1].headers?.get("Content-Disposition"))
      assertEquals(Json.parseToJsonElement(manifest), Json.parseToJsonElement(Buffer().also { body.parts[1].body.writeTo(it) }.readUtf8()))
      assertEquals("form-data; name=\"files\"; filename=\"source.mp4\"", body.parts[2].headers?.get("Content-Disposition"))
      assertEquals("video/mp4", body.parts[2].body.contentType().toString())
      assertContentEquals(fixture, Buffer().also { body.parts[2].body.writeTo(it) }.readByteArray())
      assertFalse(body.parts.any { it.headers?.get("Content-Disposition")?.contains("name=\"video\"") == true })
    } finally { file.delete() }
  }
}
