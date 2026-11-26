/** Health and maintenance (`/bin/studio/health.json`): how the instance is, and the nightly housekeeping. */

export type CheckStatus = 'ok' | 'warn' | 'error';

export interface Check {
  id: 'maintenance' | 'repository' | 'disk' | 'memory' | 'bundles' | 'jobs' | 'log' | string;
  title: string;
  status: CheckStatus;
  summary: string;
  details: Record<string, unknown>;
}

export interface Settings {
  enabled: boolean;
  /** The night window's hour, local time. */
  hour: number;
  compact: boolean;
  purgeVersions: boolean;
  keepVersions: number;
  keepDays: number;
}

export interface Progress {
  running: boolean;
  kind?: 'all' | 'compact' | 'versions';
  phase?: 'size' | 'compact' | 'versions';
  by?: string;
  started?: string;
  finished?: string;
  error?: string;
}

export interface Run {
  id?: string;
  kind: string;
  by: string;
  started: string;
  finished: string;
  millis: number;
  sizeBefore: number;
  sizeAfter: number;
  recovered: number;
  compaction?: 'unavailable' | { status: number; message?: string; millis: number; /** Oak's estimation found too little garbage and did nothing */ skipped?: boolean };
  versions?: { nodes: number; versions: number; removed: number; errors?: string[] };
}

export interface Status {
  overall: CheckStatus;
  checks: Check[];
  settings: Settings;
  progress: Progress;
  lastRun: Run | null;
  nextRun: string | null;
  compactionAvailable: boolean;
  now: string;
}

export interface HealthClient {
  status(): Promise<Status>;
  runs(): Promise<Run[]>;
  progress(): Promise<Progress>;
  /** Start maintenance now; false when a run is already going. */
  run(kind: 'all' | 'compact' | 'versions'): Promise<boolean>;
  saveSettings(values: Partial<Settings>): Promise<Settings>;
}

const ENDPOINT = '/bin/studio/health.json';

export class HttpHealthClient implements HealthClient {
  constructor(private readonly base = '') {}

  private async get<T>(params: Record<string, string>): Promise<T> {
    const res = await fetch(`${this.base}${ENDPOINT}?${new URLSearchParams(params)}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
    return json;
  }

  private async post<T>(params: Record<string, string>): Promise<{ status: number; json: T }> {
    const res = await fetch(`${this.base}${ENDPOINT}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'X-Studio-CSRF': '1' },
      body: new URLSearchParams(params),
    });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok && res.status !== 409) throw new Error(json.error || `HTTP ${res.status}`);
    return { status: res.status, json };
  }

  status() {
    return this.get<Status>({ op: 'status' });
  }

  async runs() {
    return (await this.get<{ runs: Run[] }>({ op: 'runs' })).runs;
  }

  progress() {
    return this.get<Progress>({ op: 'progress' });
  }

  async run(kind: 'all' | 'compact' | 'versions') {
    return (await this.post<{ started: boolean }>({ op: 'run', kind })).json.started;
  }

  async saveSettings(values: Partial<Settings>) {
    const params: Record<string, string> = { op: 'settings' };
    for (const [k, v] of Object.entries(values)) if (v !== undefined) params[k] = String(v);
    return (await this.post<Settings>(params)).json;
  }
}

export function bytes(n: number | undefined | null): string {
  if (n === undefined || n === null) return '';
  const abs = Math.abs(n);
  if (abs < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = abs;
  let u = -1;
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024;
    u++;
  }
  return `${n < 0 ? '−' : ''}${v >= 100 ? v.toFixed(0) : v.toFixed(1)} ${units[u]}`;
}
