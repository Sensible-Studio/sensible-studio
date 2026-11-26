/**
 * The Jobs console (`/bin/studio/jobs.json`, studio-health): Sensible Studio's work in the background as Sling jobs,
 * and the queues that run it (AEM's Tools › General › Jobs).
 */
export type JobState = 'waiting' | 'retrying' | 'running' | 'failed' | 'done' | 'stopped';
export type JobFilter = 'all' | 'waiting' | 'running' | 'failed' | 'done';

export interface StudioJob {
  id: string;
  topic: string;
  /** what it does, in words: Media processing, Publishing · publish… */
  kind: string;
  /** the page, file or flow it works on */
  item: string | null;
  action?: string | null;
  user?: string | null;
  state: JobState;
  queue: string | null;
  created: string | null;
  started?: string | null;
  finished?: string | null;
  tries: number;
  maxTries: number;
  message?: string | null;
}

export interface JobCounts {
  waiting: number;
  running: number;
  failed: number;
}

export interface LiveQueue {
  name: string;
  waiting: number;
  running: number;
  failed: number;
  done: number;
  suspended: boolean;
  state?: string;
}

export interface QueueConfig {
  pid: string;
  name: string;
  topics: string[];
  type: string;
  maxParallel: number;
  /** -1: always try again */
  retries: number;
  /** milliseconds */
  retryDelay: number;
  priority: string;
  queues: LiveQueue[];
  waiting: number;
  running: number;
  failed: number;
}

export interface JobsClient {
  list(state: JobFilter, limit?: number): Promise<{ jobs: StudioJob[]; counts: JobCounts; admin: boolean }>;
  queues(): Promise<{ queues: QueueConfig[]; admin: boolean }>;
  retry(id: string): Promise<void>;
  remove(id: string): Promise<void>;
  suspend(queue: string, on: boolean): Promise<void>;
  configure(pid: string, values: { maxParallel?: number; retries?: number; retryDelay?: number }): Promise<void>;
}

const API = '/bin/studio/jobs.json';

async function call<T>(method: 'GET' | 'POST', params: Record<string, string>): Promise<T> {
  const qs = new URLSearchParams(params);
  const res =
    method === 'GET'
      ? await fetch(`${API}?${qs}`, { credentials: 'same-origin' })
      : await fetch(API, {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'X-Studio-CSRF': '1', 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
          body: qs,
        });
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

export class HttpJobsClient implements JobsClient {
  list = (state: JobFilter, limit = 100) => call<{ jobs: StudioJob[]; counts: JobCounts; admin: boolean }>('GET', { op: 'list', state, limit: String(limit) });
  queues = () => call<{ queues: QueueConfig[]; admin: boolean }>('GET', { op: 'queues' });
  retry = async (id: string) => {
    await call('POST', { op: 'retry', id });
  };
  remove = async (id: string) => {
    await call('POST', { op: 'remove', id });
  };
  suspend = async (queue: string, on: boolean) => {
    await call('POST', { op: on ? 'suspend' : 'resume', queue });
  };
  configure = async (pid: string, values: { maxParallel?: number; retries?: number; retryDelay?: number }) => {
    const p: Record<string, string> = { op: 'configure', pid };
    for (const [k, v] of Object.entries(values)) if (v !== undefined) p[k] = String(v);
    await call('POST', p);
  };
}

/** Where the item of a job opens: a Media file's page, a page in the editor, a flow's instance. */
export function itemUrl(item: string | null): string | null {
  if (!item) return null;
  if (item.startsWith('/content/dam/')) return `/assetdetails.html${encodeURI(item)}`;
  if (item.startsWith('/content/')) return `/editor.html${encodeURI(item)}.html`;
  if (item.startsWith('/var/workflow/instances/')) return `/workflow/instances.html`;
  return null;
}

/** 30000 → "30 s", 90000 → "1 min 30 s" */
export function delayLabel(ms: number): string {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  return s % 60 ? `${m} min ${s % 60} s` : `${m} min`;
}
