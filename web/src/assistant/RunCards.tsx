import { useNavigate } from "react-router";
import { ArrowRight, CircleStop, Film, LoaderCircle, RotateCcw, TriangleAlert } from "lucide-react";
import type { Card, EvidenceItem, Run, RunPush } from "../api/types";
import { EvidenceImage, Tag } from "../components/ui";
import { DETECTION_LABEL, OBSERVATION_LABEL, RUN_STATUS_LABEL, TOOL_LABEL, errorMessage, eventTypeLabel, offsetLabel, rangeLabel } from "../format";

const GAP_LABEL = { unindexed: "部分事件尚未建立检索索引", no_matches: "覆盖范围内没有匹配事件", media_missing: "部分关键帧媒体缺失" } as const;

const eventLink = (eventId: string, evidenceId?: string) =>
  `/events/${encodeURIComponent(eventId)}` + (evidenceId ? `?evidence=${encodeURIComponent(evidenceId)}` : "");

/** Live progress while running, structured cards once the run is terminal. */
export function RunPanel({ run, live, steps, onStop, onRetry, stopping }: {
  run: Run; live?: RunPush; steps: string[]; onStop: () => void; onRetry: () => void; stopping?: boolean;
}) {
  const status = live?.status ?? run.status;
  const cards = (live?.cards ?? run.cards)?.cards ?? [];
  const error = live?.error ?? run.error;

  if (status === "running") {
    const progress = run.progress ?? [];
    return (
      <section className="run-panel" aria-live="polite">
        <div className="run-panel__head">
          <LoaderCircle className="spin" size={18} aria-hidden />
          <strong>{RUN_STATUS_LABEL.running}</strong>
          <button type="button" className="text-button text-button--danger" onClick={onStop} disabled={stopping}>
            <CircleStop size={16} aria-hidden />停止
          </button>
        </div>
        <ol className="run-steps">
          {progress.length > 0
            ? progress.map((call) => (
              <li key={call.call_id} className={`run-steps__item is-${call.status}`}>
                {TOOL_LABEL[call.tool] ?? "执行检索"}
              </li>
            ))
            : steps.map((step, index) => <li key={index} className="run-steps__item">{step}</li>)}
        </ol>
      </section>
    );
  }

  return (
    <section className="run-panel" aria-live="polite">
      <div className="run-panel__head">
        <Tag tone={status === "completed" ? "green" : status === "stopped" ? "gray" : "red"}>{RUN_STATUS_LABEL[status]}</Tag>
        <span className="id-line">{run.run_id}</span>
      </div>
      {cards.length === 0 && status === "completed" && run.summary && <p className="run-card__text">{run.summary}</p>}
      {cards.length === 0 && status === "failed" && (
        <ResultCard card={{ kind: "run_status", title: "分析未完成", status: "failed", detail: error ? errorMessage(error) : "" }} onRetry={onRetry} />
      )}
      {cards.map((card, index) => <ResultCard key={index} card={card} onRetry={onRetry} />)}
    </section>
  );
}

function ResultCard({ card, onRetry }: { card: Card; onRetry: () => void }) {
  const navigate = useNavigate();
  switch (card.kind) {
    case "run_summary":
      return (
        <article className="run-card">
          <h3>{card.title}</h3>
          <p className="run-card__text">{card.summary}</p>
          {card.references.length > 0 && (
            <div className="chip-row chip-row--wrap" aria-label="引用证据">
              {card.references.map((ref) => <ReferenceChip key={ref.evidence_id} item={ref} />)}
            </div>
          )}
        </article>
      );
    case "event_evidence":
      return (
        <article className="run-card">
          <div className="run-card__title-row">
            <h3>{eventTypeLabel(card.event_type)}</h3>
            <span className="muted">{rangeLabel(card.start_offset_ms, card.end_offset_ms)}</span>
          </div>
          <div className="tag-row">
            <Tag tone={card.observation_status === "candidate" ? "amber" : "green"}>{OBSERVATION_LABEL[card.observation_status]}</Tag>
            <Tag tone={card.detection_source === "mock" ? "amber" : "neutral"}>{DETECTION_LABEL[card.detection_source]}</Tag>
          </div>
          {card.evidence.length > 0 && (
            <div className="thumb-row">
              {card.evidence.slice(0, 3).map((item) => (
                <button key={item.evidence_id} type="button" className="thumb" onClick={() => navigate(eventLink(card.event_id, item.evidence_id))}
                  aria-label={`查看关键帧 ${offsetLabel(item.offset_ms)}`}>
                  <EvidenceImage evidenceId={item.evidence_id} mediaStatus={item.media_status} alt={`关键帧 ${offsetLabel(item.offset_ms)}`} />
                  <span className="thumb__time">{offsetLabel(item.offset_ms)}</span>
                </button>
              ))}
            </div>
          )}
          <p className="id-line">事件 {card.event_id}</p>
          <button type="button" className="text-button" onClick={() => navigate(eventLink(card.event_id))}>
            <Film size={16} aria-hidden />查看事件过程与关键帧<ArrowRight size={16} aria-hidden />
          </button>
        </article>
      );
    case "coverage_gap":
      return (
        <article className="run-card run-card--gap">
          <h3><TriangleAlert size={16} aria-hidden />{GAP_LABEL[card.gap] ?? card.title}</h3>
          {card.detail && <p className="run-card__text">{card.detail}</p>}
          {card.eligible_event_count != null && (
            <p className="muted">已索引 {card.indexed_event_count ?? "未知"} / 可检索 {card.eligible_event_count} 条事件</p>
          )}
        </article>
      );
    case "run_status":
      return (
        <article className={"run-card " + (card.status === "failed" ? "run-card--failed" : "")}>
          <h3>{card.status === "failed" ? "分析未完成" : "分析已停止"}</h3>
          {card.detail && <p className="run-card__text">{card.detail}</p>}
          {card.status === "failed" && (
            <button type="button" className="text-button" onClick={onRetry}><RotateCcw size={16} aria-hidden />重新分析</button>
          )}
        </article>
      );
    default:
      return null;
  }
}

function ReferenceChip({ item }: { item: EvidenceItem }) {
  const navigate = useNavigate();
  return (
    <button type="button" className="chip chip--ref" onClick={() => navigate(eventLink(item.event_id, item.evidence_id))}
      title={`证据 ${item.evidence_id}`}>
      {offsetLabel(item.offset_ms)} · {item.media_status === "available" ? "关键帧" : "媒体缺失"}
    </button>
  );
}
