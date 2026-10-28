import { describe, expect, it } from "vitest";
import { ApiError } from "./api/client";
import { errorMessage, isAuthError, offsetLabel, rangeLabel, timeLabel } from "./format";

describe("format", () => {
  it("formats relative offsets", () => {
    expect(offsetLabel(0)).toBe("0:00");
    expect(offsetLabel(65_400)).toBe("1:05");
    expect(offsetLabel(3_725_000)).toBe("1:02:05");
    expect(rangeLabel(1000, 1000)).toBe("0:01");
  });

  it("keeps unknown time unknown", () => {
    expect(timeLabel(null)).toBe("时间未知");
    expect(timeLabel("not-a-date")).toBe("时间未知");
  });

  it("maps errors to friendly Chinese text without raw codes", () => {
    expect(errorMessage(new ApiError(401, "unauthorized"))).toContain("凭据");
    expect(errorMessage(new ApiError(404, "media_missing"))).toContain("关键帧");
    expect(errorMessage(new ApiError(409, "run_in_progress"))).toContain("仍在执行");
    expect(errorMessage(new ApiError(503, "http_error"))).toBe("服务暂时不可用，请稍后重试。");
    expect(errorMessage(new Error("boom"))).not.toContain("boom");
    expect(isAuthError(new ApiError(0, "not_configured"))).toBe(true);
  });
});
