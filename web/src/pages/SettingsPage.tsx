import { useState, type FormEvent } from "react";
import { Check, KeyRound, LoaderCircle, LogOut, Server } from "lucide-react";
import { getServiceConfig, saveServiceConfig } from "../api/client";
import { getUser } from "../api/station";
import { SubHeader } from "../components/ui";
import { errorMessage } from "../format";
import { useServiceConfig } from "../hooks";

type Test = { state: "idle" } | { state: "testing" } | { state: "ok"; name: string } | { state: "error"; error: unknown };

export default function SettingsPage() {
  const config = useServiceConfig();
  const [bearer, setBearer] = useState(() => getServiceConfig()?.bearer ?? "");
  const [remember, setRemember] = useState(() => getServiceConfig()?.remember ?? false);
  const [test, setTest] = useState<Test>({ state: "idle" });

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!bearer.trim()) return;
    saveServiceConfig({ bearer, remember });
    setTest({ state: "testing" });
    try {
      const user = await getUser();
      setTest({ state: "ok", name: user.display_name?.trim() || user.user_id });
    } catch (error) {
      setTest({ state: "error", error });
    }
  };

  const clear = () => {
    saveServiceConfig(null);
    setBearer("");
    setRemember(false);
    setTest({ state: "idle" });
  };

  return (
    <div className="page page--plain">
      <SubHeader title="AI 服务设置" back="/profile" />
      <main className="page__content">
        <form className="card settings-form" onSubmit={save}>
          <div className="field">
            <span className="field__label"><Server size={15} aria-hidden />服务地址</span>
            <p className="settings-form__value">当前站点 /api/v1</p>
          </div>
          <label className="field">
            <span className="field__label"><KeyRound size={15} aria-hidden />访问凭据</span>
            <input type="password" autoComplete="off" spellCheck={false} value={bearer} placeholder="Bearer 凭据"
              onChange={(event) => setBearer(event.target.value)} />
          </label>
          <label className="checkbox">
            <input type="checkbox" checked={remember} onChange={(event) => setRemember(event.target.checked)} />
            <span>在此设备上记住</span>
          </label>
          <div className="plan-editor__actions">
            <button type="submit" className="primary-button" disabled={!bearer.trim() || test.state === "testing"}>
              {test.state === "testing" ? <LoaderCircle className="spin" size={16} aria-hidden /> : <Check size={16} aria-hidden />}保存并测试
            </button>
            {config && (
              <button type="button" className="text-button text-button--danger" onClick={clear}><LogOut size={16} aria-hidden />清除凭据</button>
            )}
          </div>
          {test.state === "ok" && <p className="inline-ok" role="status">连接成功：{test.name}</p>}
          {test.state === "error" && <p className="inline-error" role="alert">{errorMessage(test.error)}</p>}
          <p className="sheet-note">凭据默认仅保存在当前浏览器会话中；共享设备上请勿勾选记住。</p>
        </form>
      </main>
    </div>
  );
}
