package com.anker.esiotkit.sample.main.station.pipeline

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** Keep source submission distinct from the existing complete-result manifest/files import. */
internal fun peopleSourceUploadRequest(baseUrl: String, bearerToken: String, manifest: String, video: File): Request {
  val body = MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("mode", "source")
    .addFormDataPart("manifest", manifest)
    .addFormDataPart("files", "source.mp4", video.asRequestBody("video/mp4".toMediaType()))
    .build()
  return Request.Builder().url("$baseUrl/api/v1/recordings")
    .header("Authorization", "Bearer $bearerToken").post(body).build()
}
