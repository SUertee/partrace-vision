import { getConfig, listRecordings } from "./station";
import type { Camera, Recording, StationConfig, Zone } from "./types";

export interface CameraSummary {
  cameraId: string;
  name: string;
  zones: Zone[];
  recordings: Recording[];
  latest: Recording;
  configAvailable: boolean;
}

export interface StationOverview {
  recordings: Recording[];
  cameras: CameraSummary[];
  configs: Map<string, StationConfig>;
}

/** Recordings grouped by camera, named from their pinned configuration. */
export async function loadStationOverview(signal?: AbortSignal): Promise<StationOverview> {
  const recordings = (await listRecordings(signal)).filter((item) => item.publication_status === "published");
  const versions = [...new Set(recordings.map((item) => item.config_version))];
  const configs = new Map<string, StationConfig>();
  // A version the credential cannot read leaves its cameras unnamed rather than failing the page.
  await Promise.all(versions.map(async (version) => {
    try {
      configs.set(version, await getConfig(version, signal));
    } catch (error) {
      if (signal?.aborted) throw error;
    }
  }));
  const groups = new Map<string, Recording[]>();
  for (const recording of recordings) groups.set(recording.camera_id, [...(groups.get(recording.camera_id) ?? []), recording]);
  const cameras = [...groups.entries()].map(([cameraId, items]) => {
    const camera = findCamera(configs.get(items[0].config_version), cameraId);
    return {
      cameraId,
      name: camera?.name?.trim() || cameraId,
      zones: camera?.zones ?? [],
      recordings: items,
      latest: items[0],
      configAvailable: !!camera,
    };
  });
  return { recordings, cameras, configs };
}

export function findCamera(config: StationConfig | undefined, cameraId: string): Camera | undefined {
  return config?.cameras.find((camera) => camera.camera_id === cameraId);
}

export function zonesFor(overview: StationOverview | undefined, recording: Recording | undefined): Zone[] {
  if (!overview || !recording) return [];
  const zones = findCamera(overview.configs.get(recording.config_version), recording.camera_id)?.zones ?? [];
  return [...new Map(zones.map((zone) => [zone.zone_id, zone])).values()].sort((a, b) => a.zone_id.localeCompare(b.zone_id));
}

export function zoneName(zones: Zone[], zoneId: string): string {
  const name = zones.find((zone) => zone.zone_id === zoneId)?.name?.trim();
  return name ? `${name}（${zoneId}）` : zoneId;
}

export function cameraName(overview: StationOverview | undefined, cameraId: string): string {
  return overview?.cameras.find((camera) => camera.cameraId === cameraId)?.name ?? cameraId;
}

/** Real sources only; mock recordings stay viewable but cannot start a new analysis. */
export const isRealRecording = (recording: Recording) => recording.source_mode === "sdk" || recording.source_mode === "prerecorded";
