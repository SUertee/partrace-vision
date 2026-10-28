import { useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { ChevronRight, Search, Settings2, Smartphone } from "lucide-react";
import { listReviews } from "../api/reviews";
import type { Review } from "../api/types";
import { BottomNav, EmptyState, ErrorState, IconButton, NotConfigured, Sheet, Spinner, Tag } from "../components/ui";
import { DECISION_LABEL, timeLabel } from "../format";
import { useLoad } from "../hooks";
import { useStation } from "../station";

export const DECISION_TONE = { confirmed: "green", dismissed: "gray", uncertain: "amber" } as const;

/** Matches event id, note, or the formatted save time. */
export function matchReview(review: Review, keyword: string): boolean {
  const key = keyword.trim().toLowerCase();
  if (!key) return true;
  return [review.event_id, review.note ?? "", timeLabel(review.created_at), review.created_at]
    .some((value) => value.toLowerCase().includes(key));
}

export function ReviewList({ reviews, keyword }: { reviews: Review[]; keyword: string }) {
  const navigate = useNavigate();
  const items = reviews.filter((review) => matchReview(review, keyword))
    .sort((a, b) => b.created_at.localeCompare(a.created_at));
  if (items.length === 0) return <EmptyState title={keyword.trim() ? "没有匹配的审核记录" : "暂无审核记录"} />;
  return (
    <ul className="review-list">
      {items.map((review) => (
        <li key={review.review_id}>
          <button type="button" className="review-item" onClick={() => navigate(`/reviews/${encodeURIComponent(review.review_id)}`)}>
            <span className="review-item__body">
              <span className="review-item__head">
                <Tag tone={DECISION_TONE[review.decision]}>{DECISION_LABEL[review.decision]}</Tag>
                <span className="muted">{timeLabel(review.created_at)}</span>
              </span>
              <span className="id-line">事件 {review.event_id}</span>
              {review.note && <span className="review-item__note">{review.note}</span>}
            </span>
            <ChevronRight size={18} aria-hidden />
          </button>
        </li>
      ))}
    </ul>
  );
}

export function useReviews(configured: boolean) {
  return useLoad(configured ? (signal) => listReviews(signal) : null, [configured]);
}

export default function ProfilePage() {
  const navigate = useNavigate();
  const { configured, user } = useStation();
  const reviews = useReviews(configured);
  const [keyword, setKeyword] = useState("");
  const [settings, setSettings] = useState(false);
  const name = useMemo(() => user.data?.display_name?.trim() || user.data?.user_id, [user.data]);

  return (
    <div className="page page--gradient">
      <header className="profile-header">
        <img src="/brand-mark.png" alt="" className="profile-header__mark" />
        <div className="profile-header__text">
          <h1>驿站追迹</h1>
          <p>{!configured ? "尚未连接后端服务" : user.error ? "用户信息读取失败" : name ?? "正在读取用户…"}</p>
        </div>
        <IconButton label="设置" className="on-gradient" onClick={() => setSettings(true)}><Settings2 size={24} /></IconButton>
      </header>
      <main className="page__content">
        <label className="search-box">
          <Search size={18} aria-hidden />
          <input type="search" value={keyword} onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索事件编号、备注或时间" aria-label="搜索审核记录" />
        </label>
        <h2 className="list-title">全部</h2>
        <section className="card">
          {!configured ? <NotConfigured detail="连接后可查看已保存的人工审核记录。" />
            : reviews.error ? <ErrorState error={reviews.error} onRetry={reviews.reload} title="审核记录读取失败" />
              : !reviews.data ? <Spinner label="正在读取审核记录…" />
                : <ReviewList reviews={reviews.data} keyword={keyword} />}
        </section>
      </main>
      <BottomNav />
      {settings && (
        <Sheet title="设置" onClose={() => setSettings(false)}>
          <div className="option-list">
            <button type="button" className="option" onClick={() => navigate("/messages")}>消息中心</button>
            <button type="button" className="option" onClick={() => navigate("/reviews")}>审核记录</button>
            <button type="button" className="option" onClick={() => navigate("/settings")}>AI 服务设置</button>
            <div className="option option--disabled" aria-disabled="true">
              <span>摄像头账号与设备设置</span>
              <Tag tone="gray"><Smartphone size={12} aria-hidden />仅 App</Tag>
            </div>
          </div>
        </Sheet>
      )}
    </div>
  );
}
