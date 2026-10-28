import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { ChevronLeft, ClockFading, CornerDownLeft, Film, LayoutGrid, LoaderCircle, MapPin, Sparkles } from "lucide-react";
import { cameraName, isRealRecording, zoneName, zonesFor } from "../api/overview";
import type { Query, Recording } from "../api/types";
import { EmptyState, IconButton, NotConfigured, Sheet, Spinner, Tag } from "../components/ui";
import { SOURCE_LABEL, errorMessage, offsetLabel, timeLabel } from "../format";
import { useStation } from "../station";
import { HistorySheet } from "./HistorySheet";
import { PlanEditor } from "./PlanEditor";
import { RunPanel } from "./RunCards";
import { useTraceSession, type Turn } from "./session";

const PROMPTS = ["这段录像里有哪些人物活动？", "这个区域有哪些可核查的事件？", "能给我看看相关关键帧吗？"];
type Overlay = "recording" | "range" | "prompts" | "zone" | "history" | null;

export default function AssistantPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const session = useTraceSession();
  const { configured, overview } = useStation();
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [question, setQuestion] = useState("");
  const [submitError, setSubmitError] = useState<unknown>();
  const [recordingId, setRecordingId] = useState<string | null>(null);
  const [zoneId, setZoneId] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);

  // Entering from the tab or home starts a fresh session unless a run is still in progress.
  useEffect(() => {
    if ((location.state as { fresh?: boolean } | null)?.fresh) {
      session.reset();
      navigate(location.pathname, { replace: true, state: null });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.state]);

  const recordings = overview.data?.recordings ?? [];
  const real = recordings.filter(isRealRecording);
  const recording = real.find((item) => item.recording_id === recordingId) ?? real[0];
  const zones = useMemo(() => zonesFor(overview.data, recording), [overview.data, recording]);
  const zone = zones.find((item) => item.zone_id === zoneId) ?? zones[0];
  const scope: Query | null = recording && zone ? {
    recording_id: recording.recording_id, camera_id: recording.camera_id, zone_id: zone.zone_id,
    start_offset_ms: 0, end_offset_ms: recording.duration_ms,
  } : null;

  const turnCount = session.turns.length;
  const lastStatus = session.turns.at(-1)?.planStatus;
  useEffect(() => { bottom.current?.scrollIntoView({ block: "end" }); }, [turnCount, lastStatus]);

  const submit = async (text = question) => {
    if (!scope || !text.trim() || session.submitting || session.hasRunning) return;
    setSubmitError(undefined);
    try {
      await session.submit(text, scope);
      setQuestion("");
    } catch (error) {
      setSubmitError(error);
    }
  };

  const blocked = !configured || !scope;
  return (
    <div className="page page--assistant">
      <header className="sub-header sub-header--assistant">
        <IconButton label="返回" onClick={() => (window.history.state?.idx > 0 ? navigate(-1) : navigate("/"))}><ChevronLeft size={26} /></IconButton>
        <h1>P.T.Vision</h1>
        <IconButton label="历史会话" onClick={() => setOverlay("history")} disabled={!configured}><ClockFading size={22} /></IconButton>
      </header>

      <main className="assistant__body">
        {session.turns.length === 0 ? (
          <section className="welcome">
            <h2>Hi 驿站追迹</h2>
            <p>选择真实录像，查询人物活动与相关证据</p>
            {!configured ? (
              <NotConfigured detail="连接后可选择真实录像并发起 AI 追溯。" />
            ) : overview.error ? (
              <p className="inline-error" role="alert">{errorMessage(overview.error)}</p>
            ) : !overview.data ? (
              <Spinner label="正在读取录像…" />
            ) : (
              <div className="prompt-list">
                {PROMPTS.map((prompt) => (
                  <button key={prompt} type="button" className="prompt" disabled={blocked || session.submitting} onClick={() => submit(prompt)}>
                    <Sparkles size={15} aria-hidden />{prompt}
                  </button>
                ))}
              </div>
            )}
          </section>
        ) : (
          <>
            {session.nextCursor && (
              <button type="button" className="text-button text-button--center" onClick={() => session.loadMoreMessages().catch(setSubmitError)}>
                加载更多消息
              </button>
            )}
            {session.turns.map((turn) => <TurnView key={turn.messageId} turn={turn} />)}
          </>
        )}
        <div ref={bottom} />
      </main>

      <footer className="composer">
        {configured && overview.data && (
          <div className="chip-row" aria-label="查询范围">
            <button type="button" className="chip" onClick={() => setOverlay("recording")}>
              <Film size={14} aria-hidden />{recording ? cameraName(overview.data, recording.camera_id) : "录像"}
            </button>
            <button type="button" className="chip" onClick={() => setOverlay("range")} disabled={!recording}>
              <ClockFading size={14} aria-hidden />录像时段
            </button>
            <button type="button" className="chip" onClick={() => setOverlay("zone")} disabled={!recording}>
              <MapPin size={14} aria-hidden />{zone ? zone.name?.trim() || zone.zone_id : "区域/货架"}
            </button>
            <button type="button" className="chip" onClick={() => setOverlay("prompts")}>
              <LayoutGrid size={14} aria-hidden />示例问题
            </button>
          </div>
        )}
        {submitError !== undefined && <p className="inline-error" role="alert">{errorMessage(submitError)}</p>}
        {configured && overview.data && !recording && <p className="inline-error">暂无可分析的真实录像；历史模拟分析仅供查看。</p>}
        {configured && recording && !zone && <p className="inline-error">该录像的镜头尚未配置区域，暂不能发起分析。</p>}
        <form className="composer__input" onSubmit={(event) => { event.preventDefault(); submit(); }}>
          <textarea rows={1} value={question} maxLength={2000} placeholder="这段录像里发生了什么？" aria-label="问题"
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); submit(); } }} />
          <button type="submit" className="composer__send" aria-label="发送" title="发送"
            disabled={blocked || !question.trim() || session.submitting || session.hasRunning}>
            {session.submitting ? <LoaderCircle className="spin" size={20} /> : <CornerDownLeft size={20} />}
          </button>
        </form>
        <p className="composer__note">上述内容由AI生成，仅供参考</p>
      </footer>

      {overlay === "history" && <HistorySheet onClose={() => setOverlay(null)} />}
      {overlay === "recording" && (
        <Sheet title="选择录像" onClose={() => setOverlay(null)}>
          {recordings.length === 0 ? <EmptyState title="暂无已发布录像" /> : (
            <div className="option-list">
              {recordings.map((item) => <RecordingOption key={item.recording_id} item={item} selected={item.recording_id === recording?.recording_id}
                label={cameraName(overview.data, item.camera_id)}
                onSelect={() => { setRecordingId(item.recording_id); setZoneId(null); setOverlay(null); }} />)}
            </div>
          )}
        </Sheet>
      )}
      {overlay === "range" && recording && (
        <Sheet title="录像时段" onClose={() => setOverlay(null)}>
          <p className="sheet-text">当前查询整段录像：0:00—{offsetLabel(recording.duration_ms)}</p>
          <p className="sheet-note">{recording.started_at ? `录像起点 ${timeLabel(recording.started_at)}，时间以片段内相对时间为准。` : "该录像真实时钟未校准，仅使用片段内相对时间。"}</p>
        </Sheet>
      )}
      {overlay === "zone" && (
        <Sheet title="区域/货架" onClose={() => setOverlay(null)}>
          {zones.length === 0 ? <EmptyState title="该镜头尚未配置区域" /> : (
            <div className="option-list">
              {zones.map((item) => (
                <button key={item.zone_id} type="button" className={"option" + (item.zone_id === zone?.zone_id ? " is-selected" : "")}
                  onClick={() => { setZoneId(item.zone_id); setOverlay(null); }}>{zoneName(zones, item.zone_id)}</button>
              ))}
            </div>
          )}
        </Sheet>
      )}
      {overlay === "prompts" && (
        <Sheet title="示例问题" onClose={() => setOverlay(null)}>
          <div className="option-list">
            {PROMPTS.map((prompt) => (
              <button key={prompt} type="button" className="option" onClick={() => { setQuestion(prompt); setOverlay(null); }}>{prompt}</button>
            ))}
          </div>
        </Sheet>
      )}
    </div>
  );
}

function RecordingOption({ item, label, selected, onSelect }: { item: Recording; label: string; selected: boolean; onSelect: () => void }) {
  const real = isRealRecording(item);
  return (
    <button type="button" className={"option option--stacked" + (selected ? " is-selected" : "")} disabled={!real} onClick={onSelect}>
      <span>{label} · {offsetLabel(item.duration_ms)}</span>
      <span className="tag-row">
        <Tag tone={real ? "blue" : "amber"}>{SOURCE_LABEL[item.source_mode]}</Tag>
        {!real && <span className="muted">历史模拟分析仅供查看</span>}
        <span className="muted">入库 {timeLabel(item.created_at)}</span>
      </span>
    </button>
  );
}

function TurnView({ turn }: { turn: Turn }) {
  const session = useTraceSession();
  const [stopping, setStopping] = useState(false);
  const stop = async () => {
    setStopping(true);
    await session.stop(turn.messageId);
    setStopping(false);
  };

  return (
    <div className="turn">
      {turn.question && <p className="bubble bubble--user">{turn.question}</p>}
      {turn.planStatus === "pending" && <div className="bubble bubble--assistant"><LoaderCircle className="spin" size={16} aria-hidden />正在生成分析计划…</div>}
      {turn.plan && (turn.planStatus === "draft" || turn.planStatus === "starting") && (
        <PlanEditor plan={turn.plan} busy={turn.planStatus === "starting"} onConfirm={(plan) => session.confirm(turn.messageId, plan)}
          onCancel={() => session.cancelPlan(turn.messageId)} onRegenerate={() => session.regeneratePlan(turn.messageId)} />
      )}
      {turn.planStatus === "failed" && !turn.error && <p className="bubble bubble--assistant muted">已取消本次分析计划。</p>}
      {turn.planStatus === "failed" && turn.scope && turn.error !== undefined && (
        <button type="button" className="text-button" onClick={() => session.regeneratePlan(turn.messageId)}>重新生成计划</button>
      )}
      {turn.planStatus === "history" && !turn.run && (
        <div className="bubble bubble--assistant">
          {turn.answer && <p className="run-card__text">{turn.answer}</p>}
          {turn.runId && (
            <button type="button" className="text-button" onClick={() => session.loadRun(turn.messageId)} disabled={turn.loadingRun}>
              {turn.loadingRun && <LoaderCircle className="spin" size={16} aria-hidden />}查看相关片段与核查过程
            </button>
          )}
        </div>
      )}
      {turn.run && (
        <RunPanel run={turn.run} live={turn.live} steps={turn.plan?.steps ?? turn.run.plan.steps} stopping={stopping}
          onStop={stop} onRetry={() => session.retry(turn.messageId)} />
      )}
      {turn.error !== undefined && <p className="inline-error" role="alert">{errorMessage(turn.error)}</p>}
    </div>
  );
}
