// Minimal text/event-stream parser; fetch is used because EventSource cannot send Authorization.

export interface SseFrame {
  id?: string;
  event: string;
  data: string;
}

/** Incremental parser: feed decoded text chunks, receive complete frames. */
export class SseParser {
  private buffer = "";

  push(chunk: string): SseFrame[] {
    this.buffer += chunk.replace(/\r\n?/g, "\n");
    const frames: SseFrame[] = [];
    let index: number;
    while ((index = this.buffer.indexOf("\n\n")) >= 0) {
      const block = this.buffer.slice(0, index);
      this.buffer = this.buffer.slice(index + 2);
      const frame = parseBlock(block);
      if (frame) frames.push(frame);
    }
    return frames;
  }
}

function parseBlock(block: string): SseFrame | null {
  let id: string | undefined;
  let event = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue; // comments carry keep-alive only
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "id") id = value;
    else if (field === "event") event = value;
    else if (field === "data") data.push(value);
  }
  return data.length ? { id, event, data: data.join("\n") } : null;
}

/** Reads frames until the stream ends or the signal aborts. */
export async function readSse(body: ReadableStream<Uint8Array>, onFrame: (frame: SseFrame) => void, signal?: AbortSignal) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  const cancel = () => void reader.cancel().catch(() => undefined);
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      for (const frame of parser.push(decoder.decode(value, { stream: true }))) onFrame(frame);
    }
    for (const frame of parser.push(decoder.decode() + "\n\n")) onFrame(frame);
  } finally {
    signal?.removeEventListener("abort", cancel);
    reader.releaseLock();
  }
}
