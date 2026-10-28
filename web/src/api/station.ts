import { query, request, requireId } from "./client";
import type { Recording, StationConfig, User } from "./types";

export async function getUser(signal?: AbortSignal): Promise<User> {
  return (await request<{ user: User }>("/api/v1/user", { signal })).user;
}

export async function getConfig(version: string, signal?: AbortSignal): Promise<StationConfig> {
  return (await request<{ config: StationConfig }>(`/api/v1/configs/${requireId(version)}`, { signal })).config;
}

/** Published recordings visible to the current credential, newest first. */
export async function listRecordings(signal?: AbortSignal, cameraId?: string): Promise<Recording[]> {
  const path = "/api/v1/recordings" + query({ camera_id: cameraId ? requireId(cameraId) : undefined });
  return (await request<{ recordings: Recording[] }>(path, { signal })).recordings;
}

export async function getRecording(id: string, signal?: AbortSignal): Promise<Recording> {
  return (await request<{ recording: Recording }>(`/api/v1/recordings/${requireId(id)}`, { signal })).recording;
}
