/** Launches (`/bin/studio/launches.json`): future versions of pages, edited apart and promoted back. */

export interface Launch {
  path: string;
  name: string;
  title: string;
  description?: string;
  /** The source page the launch was made from. */
  source: string;
  /** Every page the launch was made from (several pages selected); `source` is the first. */
  sources?: string[];
  /** Where that page lives inside the launch (`<launch>/content<source>`). */
  sourceInLaunch: string;
  liveDate?: string;
  liveCopy: boolean;
  deep: boolean;
  created?: string;
  createdBy?: string;
  promoted?: string;
  promotedBy?: string;
  /** AEM's "Production ready": at the live date it is promoted and its pages published by themselves */
  productionReady?: boolean;
  /** why going live by itself failed; it is not tried again until production ready is set again */
  autoError?: string | null;
  /** `op=list` only. */
  pages?: number;
  modified?: number;
  /** `op=info` on a source page: where this page is inside the launch. */
  pageInLaunch?: string;
}

export interface LaunchPage {
  path: string;
  sourcePath: string | null;
  title: string;
  /** Changed since the launch was created. */
  modified: boolean;
  sourceExists: boolean;
  lastModified?: string;
}

export interface LaunchInfo {
  /** The launch the path belongs to; null for a normal page. */
  launch: Launch | null;
  /** The source page of the path inside the launch. */
  sourcePath?: string | null;
  pages?: LaunchPage[];
  /** For a normal page: the launches (not yet promoted) that contain it. */
  launchesOfPage: Launch[];
}

export interface Promotion {
  promoted: string[];
  created: string[];
  skipped: string[];
}

/** What Go live did; with `publish`, the pages sent to publish or why they were not. */
export interface PromoteResult {
  result: Promotion;
  deleted: boolean;
  published?: string[];
  publishError?: string;
}

export interface LaunchesClient {
  list(): Promise<Launch[]>;
  info(path: string): Promise<LaunchInfo>;
  create(r: { source: string; title: string; description?: string; liveDate?: string; deep: boolean; liveCopy: boolean; productionReady?: boolean }): Promise<{ launch: Launch; liveCopyParent?: string; liveCopyName?: string }>;
  /** One more source page in a launch. */
  addSource(path: string, r: { source: string; deep: boolean; liveCopy: boolean }): Promise<{ launch: Launch; liveCopyParent?: string; liveCopyName?: string }>;
  update(path: string, r: { title?: string; description?: string; liveDate?: string; productionReady?: boolean }): Promise<Launch>;
  promote(path: string, r: { scope: 'all' | 'page' | 'pageAndSubpages'; page?: string; modifiedOnly: boolean; delete: boolean; publish?: boolean }): Promise<PromoteResult>;
  remove(path: string): Promise<void>;
}

const ENDPOINT = '/bin/studio/launches.json';

export class HttpLaunchesClient implements LaunchesClient {
  constructor(private readonly base = '') {}

  private async get<T>(params: Record<string, string>): Promise<T> {
    const res = await fetch(`${this.base}${ENDPOINT}?${new URLSearchParams(params)}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
    return json;
  }

  private async post<T>(params: Record<string, string | undefined>): Promise<T> {
    const body = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) if (v !== undefined) body.set(k, v);
    const res = await fetch(`${this.base}${ENDPOINT}`, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' }, body });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
    return json;
  }

  async list() {
    return (await this.get<{ launches: Launch[] }>({ op: 'list' })).launches;
  }

  info(path: string) {
    return this.get<LaunchInfo>({ op: 'info', path });
  }

  create(r: { source: string; title: string; description?: string; liveDate?: string; deep: boolean; liveCopy: boolean; productionReady?: boolean }) {
    return this.post<{ launch: Launch; liveCopyParent?: string; liveCopyName?: string }>({
      op: 'create',
      source: r.source,
      title: r.title,
      description: r.description,
      liveDate: r.liveDate,
      deep: String(r.deep),
      liveCopy: String(r.liveCopy),
      productionReady: r.productionReady ? 'true' : undefined,
    });
  }

  addSource(path: string, r: { source: string; deep: boolean; liveCopy: boolean }) {
    return this.post<{ launch: Launch; liveCopyParent?: string; liveCopyName?: string }>({ op: 'addSource', path, source: r.source, deep: String(r.deep), liveCopy: String(r.liveCopy) });
  }

  async update(path: string, r: { title?: string; description?: string; liveDate?: string; productionReady?: boolean }) {
    return (await this.post<{ launch: Launch }>({ op: 'update', path, title: r.title, description: r.description, liveDate: r.liveDate, productionReady: r.productionReady === undefined ? undefined : String(r.productionReady) })).launch;
  }

  promote(path: string, r: { scope: 'all' | 'page' | 'pageAndSubpages'; page?: string; modifiedOnly: boolean; delete: boolean; publish?: boolean }) {
    return this.post<PromoteResult>({ op: 'promote', path, scope: r.scope, page: r.page, modifiedOnly: String(r.modifiedOnly), delete: String(r.delete), publish: r.publish ? 'true' : undefined });
  }

  async remove(path: string) {
    await this.post<{ deleted: string }>({ op: 'delete', path });
  }
}

/** The editor URL of a page. */
export function editorUrl(pagePath: string): string {
  return `/editor.html${pagePath}.html`;
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}
