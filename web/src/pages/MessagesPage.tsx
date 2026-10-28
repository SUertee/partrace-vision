import { useState } from "react";
import { Bell, BellOff, Inbox, Settings2, Smartphone } from "lucide-react";
import { EmptyState, IconButton, Sheet, SubHeader, Tag } from "../components/ui";

type Permission = NotificationPermission | "unsupported";
const readPermission = (): Permission => (typeof Notification === "undefined" ? "unsupported" : Notification.permission);
const PERMISSION_LABEL: Record<Permission, string> = {
  granted: "已允许", denied: "已拒绝，请在浏览器设置中修改", default: "未设置", unsupported: "当前浏览器不支持",
};

export default function MessagesPage() {
  const [settings, setSettings] = useState(false);
  const [permission, setPermission] = useState<Permission>(readPermission);
  const ask = async () => {
    if (permission !== "default") return;
    try {
      setPermission(await Notification.requestPermission());
    } catch {
      setPermission(readPermission());
    }
  };

  return (
    <div className="page page--grey">
      <SubHeader title="消息中心" action={<IconButton label="消息设置" onClick={() => setSettings(true)}><Settings2 size={22} /></IconButton>} />
      <main className="page__content">
        <h2 className="list-title"><Inbox size={18} aria-hidden />报警消息</h2>
        <section className="card">
          <EmptyState title="暂无信息" detail="报警消息数据源尚未接入网页端。" />
        </section>
      </main>
      {settings && (
        <Sheet title="消息设置" onClose={() => setSettings(false)}>
          <div className="option-list">
            <button type="button" className="option" onClick={ask} disabled={permission !== "default"}>
              <span className="option__label">{permission === "granted" ? <Bell size={18} aria-hidden /> : <BellOff size={18} aria-hidden />}浏览器通知</span>
              <span className="muted">{PERMISSION_LABEL[permission]}</span>
            </button>
            <div className="option option--disabled" aria-disabled="true">
              <span>人物录像自动解析</span>
              <Tag tone="gray"><Smartphone size={12} aria-hidden />仅 App</Tag>
            </div>
          </div>
        </Sheet>
      )}
    </div>
  );
}
