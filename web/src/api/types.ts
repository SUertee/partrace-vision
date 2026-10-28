// Shapes mirror docs/api/openapi.json (0.3-mvp-draft); only fields the UI reads.

export type SourceMode = "sdk" | "prerecorded" | "mock";
export type MediaStatus = "available" | "missing";
export type ObservationStatus = "candidate" | "observed";
export type DetectionSource = "automatic" | "manual" | "mock";
export type Decision = "confirmed" | "dismissed" | "uncertain";
export type RunStatus = "running" | "completed" | "stopped" | "failed";

export interface User {
  user_id: string;
  display_name?: string | null;
  role?: string | null;
  status?: string | null;
}

export interface Recording {
  recording_id: string;
  camera_id: string;
  config_version: string;
  source_mode: SourceMode;
  started_at: string | null;
  duration_ms: number;
  media_status: MediaStatus;
  created_at: string;
  publication_status: "pending" | "published";
}

export interface Zone {
  zone_id: string;
  name?: string | null;
}

export interface Camera {
  camera_id: string;
  name?: string | null;
  zones: Zone[];
}

export interface StationConfig {
  config_version: string;
  cameras: Camera[];
}

export interface Query {
  recording_id: string;
  camera_id: string;
  zone_id: string;
  start_offset_ms: number;
  end_offset_ms: number;
}

export interface StationEvent {
  event_id: string;
  recording_id: string;
  zone_id: string;
  track_key?: string | null;
  person_id?: string | null;
  event_type: string;
  shelf_level?: number | null;
  observation_status: ObservationStatus;
  start_offset_ms: number;
  end_offset_ms: number;
  detection_source: DetectionSource;
  summary?: string | null;
  evidence_ids: string[];
}

export interface Evidence {
  evidence_id: string;
  event_id: string;
  offset_ms: number;
  media_status: MediaStatus;
  media_url: string | null;
}

export type QueryStatus = "matched" | "no_detections" | "outside_recording";

export interface EventList {
  query: Query;
  query_status: QueryStatus;
  events: StationEvent[];
}

export interface EventBundle {
  event: StationEvent;
  evidence: Evidence[];
}

export interface Review {
  review_id: string;
  event_id: string;
  query: Query;
  decision: Decision;
  note: string | null;
  reviewer_id: string;
  created_at: string;
}

export interface ReviewBundle {
  review: Review;
  event: StationEvent;
  evidence: Evidence[];
}

export interface Conversation {
  conversation_id: string;
  created_at: string;
}

export interface Message {
  message_id: string;
  conversation_id: string;
  role: "user" | "assistant";
  content: string;
  run_id: string | null;
  created_at: string;
}

export interface Page<T> {
  items: T[];
  next_cursor: string | null;
}

export interface Plan {
  query: Query;
  query_text: string;
  steps: string[];
}

export interface EvidenceItem {
  event_id: string;
  evidence_id: string;
  offset_ms: number;
  media_status: MediaStatus;
}

export interface RunSummaryCard {
  kind: "run_summary";
  title: string;
  summary: string;
  references: EvidenceItem[];
}

export interface EventEvidenceCard {
  kind: "event_evidence";
  title: string;
  event_id: string;
  event_type: string;
  observation_status: ObservationStatus;
  detection_source: DetectionSource;
  start_offset_ms: number;
  end_offset_ms: number;
  evidence: EvidenceItem[];
}

export interface CoverageGapCard {
  kind: "coverage_gap";
  title: string;
  gap: "unindexed" | "no_matches" | "media_missing";
  detail: string;
  eligible_event_count?: number | null;
  indexed_event_count?: number | null;
}

export interface RunStatusCard {
  kind: "run_status";
  title: string;
  status: "stopped" | "failed";
  detail: string;
}

export type Card = RunSummaryCard | EventEvidenceCard | CoverageGapCard | RunStatusCard;

export interface CardBundle {
  version: 1;
  run_id: string;
  query: Query;
  cards: Card[];
}

export type ToolName =
  | "load_skill"
  | "search_events"
  | "semantic_search_events"
  | "get_event"
  | "get_evidence"
  | "read_recording";

export interface ToolCall {
  call_id: string;
  sequence: number;
  tool: ToolName;
  status: "running" | "completed" | "failed" | "unavailable" | "cancelled";
  started_at: string;
  finished_at: string | null;
}

export interface RunError {
  code: string;
  message: string;
}

export interface Run {
  run_id: string;
  conversation_id: string;
  input_message_id: string;
  status: RunStatus;
  plan: Plan;
  summary: string | null;
  references: EvidenceItem[];
  cards: CardBundle | null;
  error: RunError | null;
  created_at: string;
  finished_at: string | null;
  // Optional: rendered only when the backend actually reports tool calls.
  progress?: ToolCall[];
}

/** Payload of one `event: run` SSE frame. */
export interface RunPush {
  run_id: string;
  status: RunStatus;
  cards: CardBundle | null;
  error: RunError | null;
  finished_at: string | null;
}
