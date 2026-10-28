import { useEffect, useId, useRef, type ReactNode } from "react";
import { NavLink, useNavigate } from "react-router";
import { ChevronLeft, CircleAlert, House, ImageOff, LoaderCircle, MessagesSquare, ScanSearch, Settings2, UserRound, X } from "lucide-react";
import { getEvidenceImage } from "../api/events";
import type { MediaStatus } from "../api/types";
import { errorMessage, isAuthError } from "../format";
import { useLoad, useObjectUrl } from "../hooks";

const TABS = [
  { to: "/", label: "首页", icon: House, end: true },
  { to: "/events", label: "事件", icon: ScanSearch, end: false },
  { to: "/assistant", label: "AI追溯", icon: MessagesSquare, end: false },
  { to: "/profile", label: "我的", icon: UserRound, end: false },
];

export function BottomNav() {
  return (
    <nav className="bottom-nav" aria-label="主导航">
      {TABS.map(({ to, label, icon: Icon, end }) => (
        <NavLink key={to} to={to} end={end} className={({ isActive }) => "bottom-nav__item" + (isActive ? " is-active" : "")}
          state={to === "/assistant" ? { fresh: true } : undefined}>
          <Icon size={24} strokeWidth={1.8} aria-hidden />
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}

export function IconButton({ label, onClick, children, className = "", disabled }: {
  label: string; onClick?: () => void; children: ReactNode; className?: string; disabled?: boolean;
}) {
  return (
    <button type="button" className={"icon-button " + className} aria-label={label} title={label} onClick={onClick} disabled={disabled}>
      {children}
    </button>
  );
}

/** Secondary page header with back navigation (messages, reviews, details). */
export function SubHeader({ title, back = -1, action }: { title: string; back?: string | -1; action?: ReactNode }) {
  const navigate = useNavigate();
  const goBack = () => {
    if (back === -1 && window.history.state?.idx > 0) navigate(-1);
    else navigate(back === -1 ? "/" : back);
  };
  return (
    <header className="sub-header">
      <IconButton label="返回" onClick={goBack}><ChevronLeft size={26} /></IconButton>
      <h1>{title}</h1>
      <div className="sub-header__action">{action}</div>
    </header>
  );
}

export function Spinner({ label = "正在加载…" }: { label?: string }) {
  return (
    <div className="state-view" role="status">
      <LoaderCircle className="spin" size={24} aria-hidden />
      <p>{label}</p>
    </div>
  );
}

export function EmptyState({ title, detail, action }: { title: string; detail?: string; action?: ReactNode }) {
  return (
    <div className="state-view">
      <p className="state-view__title">{title}</p>
      {detail && <p>{detail}</p>}
      {action}
    </div>
  );
}

/** Error with a friendly message, retry, and a settings link for credential problems. */
export function ErrorState({ error, onRetry, title = "暂时无法读取数据" }: { error: unknown; onRetry?: () => void; title?: string }) {
  const navigate = useNavigate();
  const auth = isAuthError(error);
  return (
    <div className="state-view state-view--error" role="alert">
      <CircleAlert size={22} aria-hidden />
      <p className="state-view__title">{auth ? "需要连接后端服务" : title}</p>
      <p>{errorMessage(error)}</p>
      <div className="state-view__actions">
        {auth ? (
          <button type="button" className="text-button" onClick={() => navigate("/settings")}><Settings2 size={16} />服务设置</button>
        ) : onRetry && <button type="button" className="text-button" onClick={onRetry}>重试</button>}
      </div>
    </div>
  );
}

export function NotConfigured({ detail }: { detail: string }) {
  const navigate = useNavigate();
  return (
    <EmptyState title="尚未连接后端服务" detail={detail}
      action={<button type="button" className="text-button" onClick={() => navigate("/settings")}><Settings2 size={16} />服务设置</button>} />
  );
}

export function Tag({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "blue" | "amber" | "green" | "red" | "gray" }) {
  return <span className={`tag tag--${tone}`}>{children}</span>;
}

/** Modal bottom sheet; closes on backdrop, Escape, or the close button. */
export function Sheet({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    document.body.classList.add("no-scroll");
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.classList.remove("no-scroll");
      previous?.focus?.();
    };
  }, [onClose]);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1} ref={panel}
        onClick={(event) => event.stopPropagation()}>
        <div className="sheet__header">
          <h2 id={titleId}>{title}</h2>
          <IconButton label="关闭" onClick={onClose}><X size={20} /></IconButton>
        </div>
        <div className="sheet__body">{children}</div>
        {footer && <div className="sheet__footer">{footer}</div>}
      </div>
    </div>
  );
}

/** Keyframe loaded through the authorized media endpoint. */
export function EvidenceImage({ evidenceId, mediaStatus, alt, className = "" }: {
  evidenceId: string; mediaStatus: MediaStatus; alt: string; className?: string;
}) {
  const available = mediaStatus === "available";
  const image = useLoad(available ? (signal) => getEvidenceImage(evidenceId, signal) : null, [evidenceId, available]);
  const url = useObjectUrl(image.data);
  return (
    <div className={"evidence-image " + className}>
      {!available ? (
        <div className="evidence-image__note"><ImageOff size={20} aria-hidden /><span>关键帧媒体缺失，引用仍保留</span></div>
      ) : image.error ? (
        <div className="evidence-image__note"><ImageOff size={20} aria-hidden /><span>{errorMessage(image.error)}</span></div>
      ) : url ? (
        <img src={url} alt={alt} />
      ) : (
        <div className="evidence-image__note"><LoaderCircle className="spin" size={20} aria-hidden /><span>正在读取关键帧…</span></div>
      )}
    </div>
  );
}
