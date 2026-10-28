// The single HTTP entry point. Pages and hooks never call fetch directly.

export const API_VERSION = "0.3-mvp-draft";
const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$/;
const CODE_PATTERN = /^[a-z][a-z0-9_]{0,99}$/;
const STORAGE_KEY = "partrace.web.service";

export interface ServiceConfig {
  bearer: string;
  remember: boolean;
}

/** Failure with an HTTP status (0 when no response) and a stable error code. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;

  constructor(status: number, code: string, requestId?: string) {
    super(code);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

function readStore(storage: Storage | undefined): ServiceConfig | null {
  try {
    const raw = storage?.getItem(STORAGE_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<ServiceConfig>;
    return typeof value.bearer === "string" && value.bearer ? { bearer: value.bearer, remember: !!value.remember } : null;
  } catch {
    return null;
  }
}

function browserStorage(kind: "local" | "session"): Storage | undefined {
  try {
    return typeof window === "undefined" ? undefined : kind === "local" ? window.localStorage : window.sessionStorage;
  } catch {
    return undefined;
  }
}

let current: ServiceConfig | null = readStore(browserStorage("session")) ?? readStore(browserStorage("local"));
const listeners = new Set<() => void>();

export function getServiceConfig(): ServiceConfig | null {
  return current;
}

export function subscribeServiceConfig(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Session storage by default; local storage only when the user opts in. */
export function saveServiceConfig(value: ServiceConfig | null) {
  const local = browserStorage("local");
  const session = browserStorage("session");
  local?.removeItem(STORAGE_KEY);
  session?.removeItem(STORAGE_KEY);
  const bearer = value?.bearer.trim() ?? "";
  current = bearer ? { bearer, remember: !!value?.remember } : null;
  if (current) (current.remember ? local : session)?.setItem(STORAGE_KEY, JSON.stringify(current));
  listeners.forEach((listener) => listener());
}

export function requireId(value: string): string {
  if (!ID_PATTERN.test(value)) throw new ApiError(0, "invalid_request");
  return value;
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== null) search.set(key, String(value));
  const text = search.toString();
  return text ? `?${text}` : "";
}

export interface RequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  signal?: AbortSignal;
  accept?: string;
  headers?: Record<string, string>;
}

/** Raw authorized request; returns the response only when it is 2xx. */
export async function send(path: string, options: RequestOptions = {}): Promise<Response> {
  if (!path.startsWith("/api/v1/") || path.includes("//") || path.includes("#") || path.includes("\\"))
    throw new ApiError(0, "invalid_request");
  const config = current;
  if (!config) throw new ApiError(0, "not_configured");
  const headers: Record<string, string> = {
    Authorization: `Bearer ${config.bearer}`,
    Accept: options.accept ?? "application/json",
    ...options.headers,
  };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? (options.body === undefined ? "GET" : "POST"),
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      redirect: "error",
      credentials: "omit",
      cache: "no-store",
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0, "network_unavailable");
  }
  if (!response.ok) {
    let code = "http_error";
    let requestId: string | undefined;
    try {
      const body = (await response.json()) as { error?: { code?: unknown; request_id?: unknown } };
      if (typeof body.error?.code === "string" && CODE_PATTERN.test(body.error.code)) code = body.error.code;
      if (typeof body.error?.request_id === "string") requestId = body.error.request_id;
    } catch {
      // Non-JSON error bodies (proxy pages) keep the generic code.
    }
    throw new ApiError(response.status, code, requestId);
  }
  return response;
}

/** JSON request that checks the shared api_version envelope. */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);
  let value: unknown;
  try {
    value = await response.json();
  } catch {
    throw new ApiError(response.status, "invalid_response");
  }
  if (!value || typeof value !== "object" || (value as { api_version?: unknown }).api_version !== API_VERSION)
    throw new ApiError(response.status, "invalid_response");
  return value as T;
}

export function newId(prefix: "conv" | "msg" | "run" | "rev"): string {
  const hex = crypto.randomUUID().replace(/-/g, "");
  return `${prefix}_${hex}`;
}
