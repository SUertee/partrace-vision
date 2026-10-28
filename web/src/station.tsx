import { createContext, useContext, type ReactNode } from "react";
import { loadStationOverview, type StationOverview } from "./api/overview";
import { getUser } from "./api/station";
import type { User } from "./api/types";
import { useLoad, useServiceConfig, type LoadState } from "./hooks";

interface StationContextValue {
  configured: boolean;
  overview: LoadState<StationOverview>;
  user: LoadState<User>;
}

const StationContext = createContext<StationContextValue | null>(null);

/** Shares one recordings/config read across tabs; reloads when the credential changes. */
export function StationProvider({ children }: { children: ReactNode }) {
  const config = useServiceConfig();
  const bearer = config?.bearer;
  const overview = useLoad(bearer ? (signal) => loadStationOverview(signal) : null, [bearer]);
  const user = useLoad(bearer ? (signal) => getUser(signal) : null, [bearer]);
  return <StationContext.Provider value={{ configured: !!bearer, overview, user }}>{children}</StationContext.Provider>;
}

export function useStation(): StationContextValue {
  const value = useContext(StationContext);
  if (!value) throw new Error("StationProvider missing");
  return value;
}
