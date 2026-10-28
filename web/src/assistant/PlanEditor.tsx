import { useEffect, useState } from "react";
import { Check, RefreshCw, X } from "lucide-react";
import type { Plan } from "../api/types";
import { rangeLabel } from "../format";

/** Editable draft: the query text and one step per line, confirmed before any run starts. */
export function PlanEditor({ plan, busy, onConfirm, onCancel, onRegenerate }: {
  plan: Plan; busy: boolean; onConfirm: (plan: Plan) => void; onCancel: () => void; onRegenerate: () => void;
}) {
  const [text, setText] = useState(plan.query_text);
  const [steps, setSteps] = useState(plan.steps.join("\n"));
  useEffect(() => {
    setText(plan.query_text);
    setSteps(plan.steps.join("\n"));
  }, [plan]);
  const stepList = steps.split("\n").map((step) => step.trim()).filter(Boolean);
  const valid = text.trim().length > 0 && stepList.length > 0;

  return (
    <section className="plan-editor" aria-label="分析计划">
      <div className="plan-editor__head">
        <h3>分析计划</h3>
        <span className="muted">{rangeLabel(plan.query.start_offset_ms, plan.query.end_offset_ms)} · {plan.query.zone_id}</span>
      </div>
      <label className="field">
        <span className="field__label">检索内容</span>
        <textarea rows={2} value={text} maxLength={2000} onChange={(event) => setText(event.target.value)} />
      </label>
      <label className="field">
        <span className="field__label">执行步骤</span>
        <textarea rows={Math.min(6, Math.max(3, stepList.length + 1))} value={steps} onChange={(event) => setSteps(event.target.value)} />
      </label>
      <div className="plan-editor__actions">
        <button type="button" className="primary-button" disabled={!valid || busy}
          onClick={() => onConfirm({ query: plan.query, query_text: text.trim(), steps: stepList })}>
          <Check size={16} aria-hidden />确认并开始分析
        </button>
        <button type="button" className="icon-button icon-button--outline" aria-label="重新生成计划" title="重新生成计划" onClick={onRegenerate} disabled={busy}>
          <RefreshCw size={18} />
        </button>
        <button type="button" className="icon-button icon-button--outline" aria-label="取消计划" title="取消计划" onClick={onCancel} disabled={busy}>
          <X size={18} />
        </button>
      </div>
    </section>
  );
}
