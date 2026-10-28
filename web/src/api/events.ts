import { ApiError, query, request, requireId, send } from "./client";
import type { EventBundle, EventList, Evidence, Query } from "./types";

export async function listEvents(scope: Query, signal?: AbortSignal): Promise<EventList> {
  const path = "/api/v1/events" + query({
    recording_id: requireId(scope.recording_id),
    camera_id: requireId(scope.camera_id),
    zone_id: requireId(scope.zone_id),
    start_offset_ms: scope.start_offset_ms,
    end_offset_ms: scope.end_offset_ms,
  });
  return request<EventList>(path, { signal });
}

export async function getEvent(id: string, signal?: AbortSignal): Promise<EventBundle> {
  return request<EventBundle>(`/api/v1/events/${requireId(id)}`, { signal });
}

export async function getEvidence(id: string, signal?: AbortSignal): Promise<Evidence> {
  return (await request<{ evidence: Evidence }>(`/api/v1/evidence/${requireId(id)}`, { signal })).evidence;
}

const MAX_IMAGE_BYTES = 16 * 1024 * 1024;

/** Keyframe bytes need the Bearer header, so they are read here and shown as a blob URL. */
export async function getEvidenceImage(id: string, signal?: AbortSignal): Promise<Blob> {
  const response = await send(`/api/v1/evidence/${requireId(id)}/media`, { signal, accept: "image/png, image/jpeg" });
  const type = response.headers.get("content-type")?.split(";")[0].trim();
  if (type !== "image/png" && type !== "image/jpeg") throw new ApiError(response.status, "invalid_media");
  const blob = await response.blob();
  if (blob.size > MAX_IMAGE_BYTES) throw new ApiError(response.status, "response_too_large");
  return blob;
}
