import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { Film, Search } from "lucide-react";
import { getReview } from "../api/reviews";
import { cameraName, zoneName, zonesFor } from "../api/overview";
import { ErrorState, EvidenceImage, NotConfigured, Spinner, SubHeader, Tag } from "../components/ui";
import { DECISION_LABEL, eventTypeLabel, offsetLabel, rangeLabel, timeLabel } from "../format";
import { useLoad } from "../hooks";
import { useStation } from "../station";
import { DECISION_TONE, ReviewList, useReviews } from "./ProfilePage";

export function ReviewsPage() {
  const { configured } = useStation();
  const reviews = useReviews(configured);
  const [keyword, setKeyword] = useState("");
  return (
    <div className="page page--plain">
      <SubHeader title="审核记录" back="/profile" />
      <main className="page__content">
        <label className="search-box">
          <Search size={18} aria-hidden />
          <input type="search" value={keyword} onChange={(event) => setKeyword(event.target.value)}
            placeholder="搜索事件编号、备注或时间" aria-label="搜索审核记录" />
        </label>
        <section className="card">
          {!configured ? <NotConfigured detail="连接后可查看已保存的人工审核记录。" />
            : reviews.error ? <ErrorState error={reviews.error} onRetry={reviews.reload} title="审核记录读取失败" />
              : !reviews.data ? <Spinner label="正在读取审核记录…" />
                : <ReviewList reviews={reviews.data} keyword={keyword} />}
        </section>
      </main>
    </div>
  );
}

export function ReviewDetailPage() {
  const { reviewId = "" } = useParams();
  const navigate = useNavigate();
  const { configured, overview } = useStation();
  const detail = useLoad(configured ? (signal) => getReview(reviewId, signal) : null, [reviewId, configured]);
  const bundle = detail.data;
  const recording = overview.data?.recordings.find((item) => item.recording_id === bundle?.review.query.recording_id);
  const zones = zonesFor(overview.data, recording);

  return (
    <div className="page page--plain">
      <SubHeader title="审核详情" back="/reviews" />
      <main className="page__content">
        {!configured ? (
          <div className="card"><NotConfigured detail="连接后可查看审核详情。" /></div>
        ) : detail.error ? (
          <div className="card"><ErrorState error={detail.error} onRetry={detail.reload} title="审核详情读取失败" /></div>
        ) : !bundle ? (
          <div className="card"><Spinner label="正在读取审核详情…" /></div>
        ) : (
          <>
            <section className="card detail-head">
              <div className="tag-row">
                <Tag tone={DECISION_TONE[bundle.review.decision]}>{DECISION_LABEL[bundle.review.decision]}</Tag>
                <span className="muted">{timeLabel(bundle.review.created_at)}</span>
              </div>
              <h2>{eventTypeLabel(bundle.event.event_type)}</h2>
              <dl className="detail-list">
                <dt>事件</dt><dd className="id-line">{bundle.review.event_id}</dd>
                <dt>录像</dt><dd>{cameraName(overview.data, bundle.review.query.camera_id)} · {rangeLabel(bundle.review.query.start_offset_ms, bundle.review.query.end_offset_ms)}</dd>
                <dt>区域</dt><dd>{zoneName(zones, bundle.review.query.zone_id)}</dd>
                <dt>核查人</dt><dd>{bundle.review.reviewer_id}</dd>
                <dt>备注</dt><dd>{bundle.review.note || "无备注"}</dd>
                <dt>记录</dt><dd className="id-line">{bundle.review.review_id}</dd>
              </dl>
              <p className="sheet-note">人工决定与原始事件分开保存，不修改检测结果。</p>
            </section>
            <section className="card">
              <h3 className="card__title">引用关键帧</h3>
              {bundle.evidence.length === 0 ? <p className="muted">该记录未引用关键帧。</p> : (
                <div className="thumb-row thumb-row--wrap">
                  {bundle.evidence.map((item) => (
                    <button key={item.evidence_id} type="button" className="thumb"
                      onClick={() => navigate(`/events/${encodeURIComponent(item.event_id)}?evidence=${encodeURIComponent(item.evidence_id)}`)}
                      aria-label={`查看关键帧 ${offsetLabel(item.offset_ms)}`}>
                      <EvidenceImage evidenceId={item.evidence_id} mediaStatus={item.media_status} alt={`关键帧 ${offsetLabel(item.offset_ms)}`} />
                      <span className="thumb__time">{offsetLabel(item.offset_ms)}</span>
                    </button>
                  ))}
                </div>
              )}
              <button type="button" className="text-button" onClick={() => navigate(`/events/${encodeURIComponent(bundle.event.event_id)}`)}>
                <Film size={16} aria-hidden />查看事件过程与关键帧
              </button>
            </section>
          </>
        )}
      </main>
    </div>
  );
}
