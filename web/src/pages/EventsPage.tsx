import { useMemo } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { ChevronRight, Film, RefreshCw, Smartphone } from "lucide-react";
import { listEvents } from "../api/events";
import { cameraName, zoneName, zonesFor } from "../api/overview";
import type { EventList, Recording, Zone } from "../api/types";
import { BottomNav, EmptyState, ErrorState, IconButton, NotConfigured, Spinner, Tag } from "../components/ui";
import { DETECTION_LABEL, OBSERVATION_LABEL, SOURCE_LABEL, eventTypeLabel, rangeLabel, timeLabel } from "../format";
import { useLoad } from "../hooks";
import { useStation } from "../station";

interface ZoneResult {
  zone: Zone;
  result: EventList;
}

export default function EventsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const { configured, overview } = useStation();
  const recordings = overview.data?.recordings ?? [];
  const selectedId = params.get("recording");
  const recording = recordings.find((item) => item.recording_id === selectedId) ?? recordings[0];
  const zones = useMemo(() => zonesFor(overview.data, recording), [overview.data, recording]);
  const typeFilter = params.get("type") ?? "all";

  const timeline = useLoad<ZoneResult[]>(recording && zones.length ? async (signal) => Promise.all(zones.map(async (zone) => ({
    zone,
    result: await listEvents({
      recording_id: recording.recording_id, camera_id: recording.camera_id, zone_id: zone.zone_id,
      start_offset_ms: 0, end_offset_ms: recording.duration_ms,
    }, signal),
  }))) : null, [recording?.recording_id, zones.map((zone) => zone.zone_id).join(",")]);

  const select = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value === null) next.delete(key); else next.set(key, value);
    setParams(next, { replace: true });
  };

  const events = (timeline.data ?? []).flatMap(({ result }) => result.events)
    .filter((event) => typeFilter === "all" || event.event_type === typeFilter)
    .sort((a, b) => a.start_offset_ms - b.start_offset_ms || a.event_id.localeCompare(b.event_id));
  const types = [...new Set((timeline.data ?? []).flatMap(({ result }) => result.events.map((event) => event.event_type)))];

  return (
    <div className="page page--gradient">
      <header className="tab-header">
        <h1>事件</h1>
        <IconButton label="刷新" className="on-gradient" onClick={() => { overview.reload(); timeline.reload(); }}><RefreshCw size={22} /></IconButton>
      </header>
      <main className="page__content">
        <div className="notice"><Smartphone size={16} aria-hidden /><span>摄像头 SDK 检测记录与回放仅 Android App 可用；这里显示后端已发布的区域事件。</span></div>
        {!configured ? (
          <div className="card"><NotConfigured detail="连接后可按录像和区域查看事件时间线。" /></div>
        ) : overview.error ? (
          <div className="card"><ErrorState error={overview.error} onRetry={overview.reload} title="事件加载失败" /></div>
        ) : !overview.data ? (
          <div className="card"><Spinner label="正在读取录像…" /></div>
        ) : !recording ? (
          <div className="card"><EmptyState title="暂无已发布录像" detail="录像解析并发布后即可查看事件。" /></div>
        ) : (
          <>
            <RecordingPicker recordings={recordings} selected={recording} onSelect={(id) => select("recording", id)}
              cameraLabel={(id) => cameraName(overview.data, id)} />
            {types.length > 0 && (
              <div className="chip-row" role="tablist" aria-label="事件类型">
                {["all", ...types].map((type) => (
                  <button key={type} type="button" role="tab" aria-selected={typeFilter === type}
                    className={"chip" + (typeFilter === type ? " is-active" : "")}
                    onClick={() => select("type", type === "all" ? null : type)}>
                    {type === "all" ? "全部" : eventTypeLabel(type)}
                  </button>
                ))}
              </div>
            )}
            {zones.length === 0 ? (
              <div className="card"><EmptyState title="该镜头尚未配置区域" detail="暂无区域事件；配置区域后可查询。" /></div>
            ) : timeline.error ? (
              <div className="card"><ErrorState error={timeline.error} onRetry={timeline.reload} title="事件加载失败" /></div>
            ) : !timeline.data ? (
              <div className="card"><Spinner label="正在读取事件时间线…" /></div>
            ) : (
              <>
                <div className="zone-coverage">
                  {timeline.data.map(({ zone, result }) => (
                    <div key={zone.zone_id} className="zone-coverage__item">
                      <span>{zoneName(zones, zone.zone_id)}</span>
                      <Tag tone={result.query_status === "matched" ? "blue" : "gray"}>
                        {result.query_status === "matched" ? `${result.events.length} 条事件`
                          : result.query_status === "no_detections" ? "覆盖内零事件" : "超出录像范围"}
                      </Tag>
                    </div>
                  ))}
                </div>
                {events.length === 0 ? (
                  <div className="card"><EmptyState title="没有符合条件的事件" detail="所选录像与区域在覆盖范围内没有检测到事件。" /></div>
                ) : (
                  <ol className="timeline" aria-label="事件时间线">
                    {events.map((event) => (
                      <li key={event.event_id}>
                        <button type="button" className="timeline__item"
                          onClick={() => navigate(`/events/${encodeURIComponent(event.event_id)}?recording=${encodeURIComponent(recording.recording_id)}`)}>
                          <span className="timeline__time">{rangeLabel(event.start_offset_ms, event.end_offset_ms)}</span>
                          <span className="timeline__body">
                            <span className="timeline__title">{eventTypeLabel(event.event_type)}</span>
                            <span className="timeline__meta">{zoneName(zones, event.zone_id)} · {event.evidence_ids.length} 张关键帧</span>
                            {event.summary && <span className="timeline__summary">{event.summary}</span>}
                            <span className="tag-row">
                              <Tag tone={event.observation_status === "candidate" ? "amber" : "green"}>{OBSERVATION_LABEL[event.observation_status]}</Tag>
                              <Tag tone={event.detection_source === "mock" ? "amber" : "neutral"}>{DETECTION_LABEL[event.detection_source]}</Tag>
                            </span>
                          </span>
                          <ChevronRight size={18} className="timeline__chevron" aria-hidden />
                        </button>
                      </li>
                    ))}
                  </ol>
                )}
              </>
            )}
          </>
        )}
      </main>
      <BottomNav />
    </div>
  );
}

export function RecordingPicker({ recordings, selected, onSelect, cameraLabel }: {
  recordings: Recording[]; selected: Recording; onSelect: (id: string) => void; cameraLabel: (id: string) => string;
}) {
  return (
    <section className="card recording-picker">
      <label className="field">
        <span className="field__label"><Film size={15} aria-hidden />录像</span>
        <select value={selected.recording_id} onChange={(event) => onSelect(event.target.value)}>
          {recordings.map((item) => (
            <option key={item.recording_id} value={item.recording_id}>
              {cameraLabel(item.camera_id)} · {timeLabel(item.created_at)} · {SOURCE_LABEL[item.source_mode]}
            </option>
          ))}
        </select>
      </label>
      <div className="tag-row">
        <Tag tone={selected.source_mode === "mock" ? "amber" : "blue"}>{SOURCE_LABEL[selected.source_mode]}</Tag>
        <Tag>时长 {rangeLabel(0, selected.duration_ms)}</Tag>
        <Tag tone={selected.started_at ? "neutral" : "gray"}>{selected.started_at ? `起点 ${timeLabel(selected.started_at)}` : "真实时钟未校准"}</Tag>
      </div>
    </section>
  );
}
