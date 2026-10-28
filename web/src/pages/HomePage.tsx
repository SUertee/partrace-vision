import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { Bell, BellOff, ChevronDown, ChevronRight, Menu, MessageSquareText, MessagesSquare, Play, RefreshCw, Smartphone } from "lucide-react";
import type { CameraSummary } from "../api/overview";
import { BottomNav, EmptyState, ErrorState, IconButton, NotConfigured, Sheet, Spinner, Tag } from "../components/ui";
import { SOURCE_LABEL, offsetLabel, timeLabel } from "../format";
import { useLocalPreference } from "../hooks";
import { useStation } from "../station";

const STATIONS = ["校园驿站", "安防赛场"] as const;
type Overlay = "sites" | "mute" | "menu" | "status" | "preview" | null;
const FOREVER = Number.MAX_SAFE_INTEGER;

export default function HomePage() {
  const navigate = useNavigate();
  const { configured, overview } = useStation();
  const [station, setStation] = useLocalPreference<string>("partrace.web.station", STATIONS[0]);
  const [mutes, setMutes] = useLocalPreference<Record<string, number>>("partrace.web.mutes", {});
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [now, setNow] = useState(() => Date.now());
  const mutedUntil = mutes[station] ?? 0;
  const muted = mutedUntil > now;

  // Timed mutes expire on their own, like the Android per-station preference.
  useEffect(() => {
    if (!mutedUntil || mutedUntil === FOREVER) return;
    const timer = setTimeout(() => setNow(Date.now()), Math.max(0, Math.min(mutedUntil - Date.now(), 2 ** 31 - 1)));
    return () => clearTimeout(timer);
  }, [mutedUntil, now]);

  const setMute = (until: number) => {
    setMutes({ ...mutes, [station]: until });
    setNow(Date.now());
    setOverlay(null);
  };
  const cameras = overview.data?.cameras ?? [];
  const close = () => setOverlay(null);

  return (
    <div className="page page--gradient">
      <header className="home-header">
        <IconButton label={muted ? `恢复${station}的通知` : `关闭${station}的通知`} className="bell-button"
          onClick={() => (muted ? setMute(0) : setOverlay("mute"))}>
          {muted ? <BellOff size={24} color="#CC812E" /> : <Bell size={24} color="#50A7FA" />}
        </IconButton>
        <button type="button" className="station-switch" onClick={() => setOverlay("sites")} aria-haspopup="dialog">
          <span>{station}</span>
          <ChevronDown size={22} aria-hidden />
        </button>
        <IconButton label="消息中心" className="on-gradient" onClick={() => navigate("/messages")}><MessageSquareText size={25} /></IconButton>
      </header>
      {muted && <p className="mute-note">{mutedUntil === FOREVER ? "通知已静音，直到重新打开" : `通知静音至 ${timeLabel(new Date(mutedUntil).toISOString())}`}</p>}

      <div className="section-title">
        <h2>所有设备</h2>
        <IconButton label="设备列表菜单" className="on-gradient" onClick={() => setOverlay("menu")}><Menu size={25} /></IconButton>
      </div>

      <main className="page__content">
        <button type="button" className="row-card" onClick={() => setOverlay("preview")}>
          <span className="row-card__title">多画面预览</span>
          <span className="play-dot" aria-hidden><Play size={14} fill="currentColor" /></span>
        </button>

        <button type="button" className="ai-entry" onClick={() => navigate("/assistant", { state: { fresh: true } })}>
          <MessagesSquare size={22} aria-hidden />
          <span className="ai-entry__text"><strong>AI 追溯</strong><span>选择真实录像，查询人物活动与相关证据</span></span>
          <ChevronRight size={20} aria-hidden />
        </button>

        {!configured ? (
          <div className="card"><NotConfigured detail="连接后可查看后端已发布录像对应的镜头。" /></div>
        ) : overview.error ? (
          <div className="card"><ErrorState error={overview.error} onRetry={overview.reload} title="暂时无法获取摄像头数据" /></div>
        ) : !overview.data ? (
          <div className="card"><Spinner label="正在加载镜头…" /></div>
        ) : cameras.length === 0 ? (
          <div className="card"><EmptyState title="暂无已发布录像" detail="录像解析并发布后，镜头会显示在这里。" /></div>
        ) : (
          <>
            <div className="summary-strip" aria-label="驿站概况">
              <div><strong>{cameras.length}</strong><span>镜头</span></div>
              <div><strong>{overview.data.recordings.length}</strong><span>已发布录像</span></div>
              <div><strong>{cameras.reduce((sum, camera) => sum + camera.zones.length, 0)}</strong><span>配置区域</span></div>
            </div>
            {cameras.map((camera) => <CameraCard key={camera.cameraId} camera={camera} />)}
          </>
        )}
      </main>
      <BottomNav />

      {overlay === "sites" && (
        <Sheet title="选择站点" onClose={close}>
          <div className="option-list">
            {STATIONS.map((name) => (
              <button key={name} type="button" className={"option" + (name === station ? " is-selected" : "")}
                onClick={() => { setStation(name); close(); }}>{name}</button>
            ))}
          </div>
          <p className="sheet-note">站点仅用于本机界面分组，不筛选后端镜头。</p>
        </Sheet>
      )}
      {overlay === "mute" && (
        <Sheet title={`关闭${station}的通知`} onClose={close}>
          <div className="option-list">
            <button type="button" className="option" onClick={() => setMute(Date.now() + 30 * 60 * 1000)}>30分钟</button>
            <button type="button" className="option" onClick={() => setMute(Date.now() + 60 * 60 * 1000)}>1小时</button>
            <button type="button" className="option" onClick={() => setMute(FOREVER)}>直到我重新打开它</button>
            <button type="button" className="option option--muted" onClick={close}>取消</button>
          </div>
          <p className="sheet-note">仅保存本机偏好，不修改摄像头报警配置。</p>
        </Sheet>
      )}
      {overlay === "menu" && (
        <Sheet title="设备列表" onClose={close}>
          <div className="option-list">
            <button type="button" className="option" onClick={() => setOverlay("status")}>设备连接状态</button>
            <button type="button" className="option" onClick={() => { overview.reload(); close(); }}>刷新设备</button>
            <button type="button" className="option" onClick={() => setOverlay("preview")}>查看多画面</button>
            <button type="button" className="option" onClick={() => navigate("/messages")}>消息中心</button>
            <button type="button" className="option" onClick={() => navigate("/reviews")}>审核记录</button>
            <AppOnlyOption label="人物录像自动解析" />
            <AppOnlyOption label="原工程 SDK 调试" />
          </div>
        </Sheet>
      )}
      {overlay === "status" && (
        <Sheet title="设备连接状态" onClose={close}
          footer={<button type="button" className="primary-button" onClick={() => { overview.reload(); close(); }}><RefreshCw size={16} />刷新</button>}>
          <p className="sheet-text">
            {!configured ? "尚未连接后端服务" : overview.loading ? "正在刷新镜头列表" : overview.error ? "镜头列表读取失败"
              : `已读取 ${cameras.length} 个镜头的已发布录像。`}
          </p>
          <p className="sheet-note">摄像头在线状态来自官方 SDK，仅 Android App 可读取；网页端显示为未知。</p>
        </Sheet>
      )}
      {overlay === "preview" && (
        <Sheet title="多画面预览" onClose={close}>
          <p className="sheet-note">实时画面需官方 SDK，仅 Android App 可用。这里展示后端已知镜头，点击查看事件。</p>
          {cameras.length === 0 ? (
            <EmptyState title={configured ? "暂无可预览的镜头" : "尚未连接后端服务"} />
          ) : (
            <div className="preview-grid">
              {cameras.map((camera) => (
                <button key={camera.cameraId} type="button" className="preview-tile"
                  onClick={() => navigate(`/events?recording=${encodeURIComponent(camera.latest.recording_id)}`)}>
                  <span className="preview-tile__label">{camera.name}</span>
                  <span className="preview-tile__meta">{camera.recordings.length} 段录像</span>
                </button>
              ))}
            </div>
          )}
        </Sheet>
      )}
    </div>
  );
}

function AppOnlyOption({ label }: { label: string }) {
  return (
    <div className="option option--disabled" aria-disabled="true">
      <span>{label}</span>
      <Tag tone="gray"><Smartphone size={12} aria-hidden />仅 App</Tag>
    </div>
  );
}

function CameraCard({ camera }: { camera: CameraSummary }) {
  const navigate = useNavigate();
  const open = () => navigate(`/events?recording=${encodeURIComponent(camera.latest.recording_id)}`);
  return (
    <article className="card camera-card">
      <div className="camera-card__head">
        <h3>{camera.name}</h3>
        <span className="status-pill" title="在线状态需官方 SDK，仅 App 可读取">状态未知</span>
      </div>
      <button type="button" className="camera-card__preview" onClick={open} aria-label={`查看${camera.name}的事件`}>
        <span className="camera-card__overlay">
          <span className="camera-card__id">{camera.cameraId}</span>
          <span className="camera-card__live"><Smartphone size={14} aria-hidden />实时画面仅 App 可用</span>
        </span>
        <span className="camera-card__stats">
          <span>{camera.recordings.length} 段录像</span>
          <span>{camera.zones.length ? `${camera.zones.length} 个区域` : "区域未读取"}</span>
          <span>最近 {offsetLabel(camera.latest.duration_ms)}</span>
        </span>
      </button>
      <div className="camera-card__foot">
        <Tag tone={camera.latest.source_mode === "mock" ? "amber" : "blue"}>{SOURCE_LABEL[camera.latest.source_mode]}</Tag>
        <span className="muted">入库 {timeLabel(camera.latest.created_at)}</span>
      </div>
    </article>
  );
}
