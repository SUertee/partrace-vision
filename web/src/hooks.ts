import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { getServiceConfig, subscribeServiceConfig, type ServiceConfig } from "./api/client";
import { isAbort } from "./format";

export function useServiceConfig(): ServiceConfig | null {
  return useSyncExternalStore(subscribeServiceConfig, getServiceConfig, getServiceConfig);
}

export interface LoadState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
}

/** Runs an abortable loader when deps change; stale results are dropped. */
export function useLoad<T>(loader: ((signal: AbortSignal) => Promise<T>) | null, deps: unknown[]): LoadState<T> {
  const [state, setState] = useState<{ data?: T; error?: unknown; loading: boolean }>({ loading: !!loader });
  const [tick, setTick] = useState(0);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  useEffect(() => {
    const run = loaderRef.current;
    if (!run) {
      setState({ loading: false });
      return;
    }
    const controller = new AbortController();
    setState((previous) => ({ data: previous.data, loading: true }));
    run(controller.signal).then(
      (data) => !controller.signal.aborted && setState({ data, loading: false }),
      (error) => !controller.signal.aborted && !isAbort(error) && setState({ error, loading: false }),
    );
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, !!loader]);

  const reload = useCallback(() => setTick((value) => value + 1), []);
  return { data: state.data, error: state.error, loading: state.loading, reload };
}

/** Blob URL for an authorized image, revoked when the source changes or unmounts. */
export function useObjectUrl(blob: Blob | undefined): string | undefined {
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    if (!blob) {
      setUrl(undefined);
      return;
    }
    const next = URL.createObjectURL(blob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [blob]);
  return url;
}

export function useLocalPreference<T>(key: string, initial: T): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw === null ? initial : (JSON.parse(raw) as T);
    } catch {
      return initial;
    }
  });
  const update = useCallback((next: T) => {
    setValue(next);
    try {
      window.localStorage.setItem(key, JSON.stringify(next));
    } catch {
      // Preference stays in memory when storage is blocked.
    }
  }, [key]);
  return [value, update];
}
