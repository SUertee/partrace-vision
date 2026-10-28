import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { ChevronLeft, ChevronRight, Smartphone } from "lucide-react";
import { getEvent, listEvents } from "../api/events";
import { cameraName, zoneName, zonesFor } from "../api/overview";
import { getRecording } from "../api/station";
import type { EventBundle, Recording, StationEvent } from "../api/types";
import { EmptyState, ErrorState, EvidenceImage, IconButton, NotConfigured, Spinner, SubHeader, Tag } from "../components/ui";
import { DETECTION_LABEL, OBSERVATION_LABEL, SOURCE_LABEL, eventTypeLabel, offsetLabel, rangeLabel } from "../format";
import { useLoad } from "../hooks";
import { useStation } from "../station";

interface Detail {
  bundle: EventBundle;
  recording: Recording;
  siblings: StationEvent[];
}

export default function EventDetailPage() {
  const { eventId = "" } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { configured, overview } = useStation();
  const focus = params.get("evidence");

  const detail = useLoad<Detail>(configured ? async (signal) => {
    const bundle = await getEvent(eventId, signal);
    const recording = await getRecording(bundle.event.recording_id, signal);
    const expected = params.get("recording");
    if (expected && expected !== recording.recording_id) throw new Error("recording mismatch");
    // Sibling events share the recording and zone, matching the Android timeline scope.
    const list = await listEvents({
      recording_id: recording.recording_id, camera_id: recording.camera_id, zone_id: bundle.event.zone_id,
      start_offset_ms: 0, end_offset_ms: recording.duration_ms,
    }, signal);
    return { bundle, recording, siblings: list.events };
  } : null, [eventId, configured]);

  const evidence = useMemo(() => [...(detail.data?.bundle.evidence ?? [])]
    .sort((a, b) => a.offset_ms - b.offset_ms || a.evidence_id.localeCompare(b.evidence_id)), [detail.data]);
  const [index, setIndex] = useState(0);
  useEffect(() => {
    const found = focus ? evidence.findIndex((item) => item.evidence_id === focus) : -1;
    setIndex(found >= 0 ? found : 0);
  }, [evidence, focus]);

  const zones = zonesFor(overview.data, detail.data?.recording);
  const event = detail.data?.bundle.event;
  const frame = evidence[index];

  return (
    <div className="page page--plain">
      <SubHeader title="事件详情" back="/events" />
      <main className="page__content">
        {!configured ? (
          <div className="card"><NotConfigured detail="连接后可查看事件过程与关键帧。" /></div>
        ) : detail.error ? (
          <div className="card"><ErrorState error={detail.error} onRetry={detail.reload} title="事件详情读取失败" /></div>
        ) : !detail.data || !event ? (
          <div className="card"><Spinner label="正在读取事件过程…" /></div>
        ) : (
          <>
            <section className="card detail-head">
              <h2>{eventTypeLabel(event.event_type)}</h2>
              <p className="muted">
                {cameraName(overview.data, detail.data.recording.camera_id)} · {zoneName(zones, event.zone_id)} · {rangeLabel(event.start_offset_ms, event.end_offset_ms)}
              </p>
              {event.summary && <p className="detail-head__summary">{event.summary}</p>}
              <div className="tag-row">
                <Tag tone={event.observation_status === "candidate" ? "amber" : "green"}>{OBSERVATION_LABEL[event.observation_status]}</Tag>
                <Tag tone={event.detection_source === "mock" ? "amber" : "neutral"}>{DETECTION_LABEL[event.detection_source]}</Tag>
                <Tag tone={detail.data.recording.source_mode === "mock" ? "amber" : "blue"}>{SOURCE_LABEL[detail.data.recording.source_mode]}</Tag>
                {event.shelf_level != null && <Tag>货架第 {event.shelf_level} 层</Tag>}
              </div>
              <p className="id-line">事件 {event.event_id}{event.track_key ? ` · 轨迹 ${event.track_key}` : ""}</p>
            </section>

            <section className="card keyframes" aria-label="关键帧">
              <div className="keyframes__head">
                <h3>关键帧</h3>
                {frame && <span className="muted">{index + 1} / {evidence.length} · {offsetLabel(frame.offset_ms)}</span>}
              </div>
              {frame ? (
                <>
                  <EvidenceImage key={frame.evidence_id} evidenceId={frame.evidence_id} mediaStatus={frame.media_status}
                    alt={`关键帧 ${offsetLabel(frame.offset_ms)}`} />
                  <div className="keyframes__nav">
                    <IconButton label="上一帧" onClick={() => setIndex(index - 1)} disabled={index === 0}><ChevronLeft size={20} /></IconButton>
                    <span className="id-line">证据 {frame.evidence_id}</span>
                    <IconButton label="下一帧" onClick={() => setIndex(index + 1)} disabled={index >= evidence.length - 1}><ChevronRight size={20} /></IconButton>
                  </div>
                </>
              ) : (
                <EmptyState title="该事件暂无可查看的关键帧" />
              )}
              <p className="sheet-note"><Smartphone size={13} aria-hidden /> 摄像头原片回放需官方 SDK，仅 App 可用；缩略图不证明连续轨迹。</p>
            </section>

            <section className="card">
              <h3 className="card__title">事件时间线</h3>
              <ol className="mini-timeline">
                {detail.data.siblings.map((item) => (
                  <li key={item.event_id}>
                    <button type="button" className={"mini-timeline__item" + (item.event_id === event.event_id ? " is-active" : "")}
                      aria-current={item.event_id === event.event_id}
                      onClick={() => navigate(`/events/${encodeURIComponent(item.event_id)}?recording=${encodeURIComponent(item.recording_id)}`, { replace: true })}>
                      <span className="mini-timeline__time">{rangeLabel(item.start_offset_ms, item.end_offset_ms)}</span>
                      <span>{eventTypeLabel(item.event_type)}</span>
                      <span className="muted">{OBSERVATION_LABEL[item.observation_status]}</span>
                    </button>
                  </li>
                ))}
              </ol>
            </section>
          </>
        )}
      </main>
    </div>
  );
}
