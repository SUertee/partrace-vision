import { describe, expect, it } from "vitest";
import { SseParser, readSse } from "./sse";

describe("SseParser", () => {
  it("joins frames split across chunks and normalizes CRLF", () => {
    const parser = new SseParser();
    expect(parser.push("id: 1\r\nevent: run\r\nda")).toEqual([]);
    expect(parser.push("ta: {\"a\":1}\r\n\r\n")).toEqual([{ id: "1", event: "run", data: "{\"a\":1}" }]);
  });

  it("ignores keep-alive comments and joins multi-line data", () => {
    const parser = new SseParser();
    const frames = parser.push(": keep-alive\n\nevent: run\ndata: a\ndata: b\n\n");
    expect(frames).toEqual([{ id: undefined, event: "run", data: "a\nb" }]);
  });

  it("flushes a final frame without trailing blank line", async () => {
    const bytes = new TextEncoder().encode("event: run\ndata: done");
    const body = new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(bytes); controller.close(); } });
    const seen: string[] = [];
    await readSse(body, (frame) => seen.push(frame.data));
    expect(seen).toEqual(["done"]);
  });
});
