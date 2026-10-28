import { query, request, requireId } from "./client";
import type { Review, ReviewBundle } from "./types";

export async function listReviews(signal?: AbortSignal, recordingId?: string): Promise<Review[]> {
  const path = "/api/v1/review-records" + query({ recording_id: recordingId ? requireId(recordingId) : undefined });
  return (await request<{ reviews: Review[] }>(path, { signal })).reviews;
}

export async function getReview(id: string, signal?: AbortSignal): Promise<ReviewBundle> {
  return request<ReviewBundle>(`/api/v1/review-records/${requireId(id)}`, { signal });
}
