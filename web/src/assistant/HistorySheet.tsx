import { useState } from "react";
import { LoaderCircle, Plus } from "lucide-react";
import { listConversations } from "../api/agent";
import type { Conversation } from "../api/types";
import { EmptyState, ErrorState, Sheet, Spinner } from "../components/ui";
import { errorMessage, timeLabel } from "../format";
import { useLoad } from "../hooks";
import { useTraceSession } from "./session";

/** Paged conversation history; opening one replaces the current session. */
export function HistorySheet({ onClose }: { onClose: () => void }) {
  const session = useTraceSession();
  const first = useLoad((signal) => listConversations(null, signal), []);
  const [more, setMore] = useState<Conversation[]>([]);
  const [cursor, setCursor] = useState<string | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const items = [...(first.data?.items ?? []), ...more];
  const next = cursor === undefined ? first.data?.next_cursor : cursor;

  const loadMore = async () => {
    if (!next) return;
    setBusy(true);
    try {
      const page = await listConversations(next);
      setMore((value) => [...value, ...page.items]);
      setCursor(page.next_cursor);
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };

  const open = async (conversationId: string) => {
    setBusy(true);
    setError(undefined);
    try {
      await session.openConversation(conversationId);
      onClose();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  };

  const startNew = () => {
    if (session.reset()) onClose();
    else setError("running");
  };

  return (
    <Sheet title="历史会话" onClose={onClose}
      footer={<button type="button" className="primary-button" onClick={startNew}><Plus size={16} aria-hidden />新建会话</button>}>
      {error !== undefined && (
        <p className="inline-error" role="alert">{error === "running" ? "当前分析仍在执行，请先停止或等待完成。" : errorMessage(error)}</p>
      )}
      {first.error ? (
        <ErrorState error={first.error} onRetry={first.reload} title="历史会话读取失败" />
      ) : !first.data ? (
        <Spinner label="正在读取历史会话…" />
      ) : items.length === 0 ? (
        <EmptyState title="暂无历史会话" />
      ) : (
        <div className="option-list">
          {items.map((item) => (
            <button key={item.conversation_id} type="button" disabled={busy}
              className={"option option--stacked" + (item.conversation_id === session.conversationId ? " is-selected" : "")}
              onClick={() => open(item.conversation_id)}>
              <span>{timeLabel(item.created_at)}</span>
              <span className="id-line">{item.conversation_id}</span>
            </button>
          ))}
          {next && (
            <button type="button" className="text-button" onClick={loadMore} disabled={busy}>
              {busy && <LoaderCircle className="spin" size={16} aria-hidden />}加载更多
            </button>
          )}
        </div>
      )}
    </Sheet>
  );
}
