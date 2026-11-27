/** The replication endpoint (`/bin/studio/replication.json`): agents, queue, status, references, activate/deactivate. */

/** One delivery (or fetch) an agent made: what, when, and whether it got there. */
export interface DeliveryRecord {
  id: string;
  action: string;
  path: string;
  at: string;
  error?: string;
}

export interface ReplicationAgent {
  id: string;
  title: string;
  description?: string;
  enabled: boolean;
  valid: boolean;
  transportUri?: string;
  transportUser?: string;
  retryDelay?: number;
  /** `studio` (content to a publish instance), `flush` (tells a cache what was published), `reverse` or `webhook`. */
  serializationType?: string;
  queue: number;
  blocked: boolean;
  lastAction?: string;
  lastPath?: string;
  lastAt?: string;
  lastSuccessAt?: string;
  lastError?: string;
  /** reverse agents: what the publish's outbox still held after the last fetch */
  outboxWaiting?: number | null;
  /** `preview`: the preview tier's agent (Publish to Preview only); `publish` otherwise */
  tier?: 'publish' | 'preview';
  /** where that tier's visitors see the content */
  publicUrl?: string | null;
}

/** The preview tier's status of an item (AEM as a Cloud Service's Publish to Preview). */
export interface PreviewStatusInfo {
  lastReplicated?: string;
  lastReplicatedBy?: string;
  lastReplicationAction?: string;
  activated?: boolean;
  published: boolean;
  pending: boolean;
  /** the item on the preview instance */
  url?: string;
}

export interface QueuedRequest {
  id: string;
  agent: string;
  action: string;
  path: string;
  deep?: boolean;
  user?: string;
  requested?: string;
  retries: number;
  state: string;
  error?: string;
  /** SCHEDULED requests: when they fire. */
  scheduled?: string;
}

export interface ReplicationStatusInfo {
  path: string;
  lastReplicated?: string;
  lastReplicatedBy?: string;
  lastReplicationAction?: string;
  activated?: boolean;
  /** Activated, delivered, and not modified since. */
  published: boolean;
  /** Still in a queue. */
  pending: boolean;
  /** A Publish Later / Unpublish Later request waiting for its time. */
  scheduled?: string;
  scheduledAction?: string;
  scheduledId?: string;
  /** only when there is a preview tier */
  preview?: PreviewStatusInfo;
}

/** Content a page uses that is not published yet (the Publish dialog lists it). */
export interface UnpublishedReference {
  path: string;
  type: 'asset' | 'experience-fragment' | 'template' | 'segment' | 'page';
  title: string;
  status: 'never' | 'modified' | 'deactivated';
}

/** A cache in front of the published site (the Live cache), as the publish instance behind it reports it. */
export interface LiveCacheInfo {
  /** The flush target that tells this cache about publications. */
  id: string;
  title: string;
  /** Where it answers, e.g. `http://127.0.0.1:4520`. */
  target?: string;
  /** Is its flush target enabled, so that it hears about publications? */
  enabled?: boolean;
  /** Did it answer? Otherwise `message` says why not. */
  ok: boolean;
  message?: string;
  /** The agent of the publish instance that reported it (absent in a publish's own console). */
  via?: string;
  viaTitle?: string;
  /** Answers kept right now: plain files the web server serves, answers kept with their headers, and what the last publications took out. */
  files?: { plain: number; held: number; stale: number };
  /** Since the cache started: answered from the cache by the service, fetched and kept, passed on unkept, served as they were while the publish was away, refused by the filter, not fetched. */
  hits?: number;
  stored?: number;
  passed?: number;
  stale?: number;
  denied?: number;
  errors?: number;
  invalidations?: number;
  lastInvalidation?: { action: string; path: string; removed: number; at: number } | null;
  /** The publish instances it fetches from, and whether one is left out for not answering. */
  publish?: { url: string; skipped: boolean }[];
  /** Served as they were while fetched again after a publication (grace period). */
  graced?: number;
  /** Answered from a site's redirect map. */
  redirected?: number;
  /** Protected pages served after the publish said yes, and refused. */
  checked?: number;
  refused?: number;
  /** The publish's vanity URLs it lets through. */
  vanityUrls?: { count: number; readAt: number } | null;
  /** The sites behind it (what a dispatcher calls farms). */
  sites?: LiveCacheSite[];
}

export interface LiveCacheSite {
  name: string;
  hosts: string[];
  roots: string[];
  files?: { plain: number; held: number; stale: number } | null;
  /** Pages that said what they show, so that a published asset takes out only them. */
  tracked?: number;
  redirects?: { map: string; rules: number } | null;
  protected?: { globs: string[]; files: number | null } | null;
}

/** The URL tester: what a cache would do with a request from a visitor who is not signed in. */
export interface LiveCacheExplanation {
  /** In words, as the cache says it. */
  summary: string;
  url?: string;
  method?: string;
  host?: string | null;
  /** The site the host name belongs to. */
  site?: string | null;
  /** The path the cache goes by (a short URL read below `/content`). */
  path?: string;
  shortUrl?: boolean;
  allowed?: boolean;
  /** The status it answers with itself (a refusal or a redirect). */
  answer?: number;
  location?: string;
  decidedBy?: string;
  cacheable?: boolean;
  notCacheableBecause?: string | null;
  protected?: boolean;
  /** Where the answer is now: `plain` (the web server serves it), `held`, `stale`, or nothing. */
  inCache?: string | null;
  file?: string | null;
  sling?: { path: string; selectors: string[]; extension: string | null; suffix: string };
}

export interface ReplicationClient {
  /** The caches in front of the published site, and whether this user may empty them. */
  caches(): Promise<{ caches: LiveCacheInfo[]; canClear: boolean }>;
  /** The URL tester of one cache; `via` as the cache was reported. */
  explainUrl(cache: string, via: string | undefined, url: string, options?: { method?: string; host?: string }): Promise<{ ok: boolean; message?: string; explain?: LiveCacheExplanation }>;
  /** Empties one cache; `via` as the cache reported it. */
  clearCache(id: string, via?: string): Promise<{ ok: boolean; message: string; caches: LiveCacheInfo[] }>;
  agents(): Promise<ReplicationAgent[]>;
  queue(agent?: string): Promise<QueuedRequest[]>;
  status(paths: string[]): Promise<ReplicationStatusInfo[]>;
  /** what the page uses that the tier (publish, or `preview`) lacks */
  references(path: string, tier?: 'preview'): Promise<UnpublishedReference[]>;
  activate(paths: string[], options?: { deep?: boolean; agent?: string; at?: string; tier?: 'preview' }): Promise<ReplicationStatusInfo[]>;
  deactivate(paths: string[], options?: { deep?: boolean; agent?: string; at?: string; tier?: 'preview' }): Promise<ReplicationStatusInfo[]>;
  unschedule(id: string): Promise<QueuedRequest[]>;
  /** Takes one request out of a target's queue; answers what is still queued for that target. */
  removeJob(id: string, agent?: string): Promise<QueuedRequest[]>;
  test(agent: string): Promise<{ ok: boolean; message: string }>;
  saveAgent(agent: { id: string; title?: string; description?: string; enabled?: boolean; transportUri?: string; transportUser?: string; transportPassword?: string; retryDelay?: number; serializationType?: string; tier?: 'publish' | 'preview'; publicUrl?: string }): Promise<ReplicationAgent[]>;
  removeAgent(id: string): Promise<ReplicationAgent[]>;
  clearQueue(agent: string): Promise<ReplicationAgent[]>;
  retry(agent: string): Promise<ReplicationAgent[]>;
  /** A reverse agent: fetch its outbox now. */
  poll(agent: string): Promise<{ ok: boolean; message?: string; installed?: number; failed?: number; agents: ReplicationAgent[] }>;
  /** The agent's last deliveries, newest first. */
  history(agent: string, limit?: number): Promise<DeliveryRecord[]>;
}

const ENDPOINT = '/bin/studio/replication.json';

export class HttpReplicationClient implements ReplicationClient {
  constructor(private readonly base = '') {}

  private async get<T>(params: Record<string, string | string[]>): Promise<T> {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (Array.isArray(v)) v.forEach((x) => qs.append(k, x));
      else qs.set(k, v);
    }
    const res = await fetch(`${this.base}${ENDPOINT}?${qs}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
    return json;
  }

  private async post<T>(params: Record<string, string | string[] | undefined>): Promise<T> {
    const body = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined) continue;
      if (Array.isArray(v)) v.forEach((x) => body.append(k, x));
      else body.set(k, v);
    }
    const res = await fetch(`${this.base}${ENDPOINT}`, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' }, body });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
    return json;
  }

  async caches() {
    return this.get<{ caches: LiveCacheInfo[]; canClear: boolean }>({ op: 'cache' });
  }

  async explainUrl(cache: string, via: string | undefined, url: string, options: { method?: string; host?: string } = {}) {
    const params: Record<string, string> = { op: 'explainUrl', cache, url };
    if (via) params.via = via;
    if (options.method) params.method = options.method;
    if (options.host) params.host = options.host;
    return this.get<{ ok: boolean; message?: string; explain?: LiveCacheExplanation }>(params);
  }

  async clearCache(id: string, via?: string) {
    return this.post<{ ok: boolean; message: string; caches: LiveCacheInfo[] }>({ op: 'clearCache', cache: id, via });
  }

  async agents() {
    return (await this.get<{ agents: ReplicationAgent[] }>({ op: 'agents' })).agents;
  }

  async queue(agent?: string) {
    return (await this.get<{ jobs: QueuedRequest[] }>({ op: 'queue', ...(agent ? { agent } : {}) })).jobs;
  }

  async status(paths: string[]) {
    return (await this.get<{ status: ReplicationStatusInfo[] }>({ op: 'status', path: paths })).status;
  }

  async references(path: string, tier?: 'preview') {
    return (await this.get<{ references: UnpublishedReference[] }>({ op: 'references', path, ...(tier ? { tier } : {}) })).references;
  }

  async activate(paths: string[], options: { deep?: boolean; agent?: string; at?: string; tier?: 'preview' } = {}) {
    return (await this.post<{ status: ReplicationStatusInfo[] }>({ op: 'activate', path: paths, deep: options.deep ? 'true' : undefined, agent: options.agent, at: options.at, tier: options.tier }))
      .status;
  }

  async deactivate(paths: string[], options: { deep?: boolean; agent?: string; at?: string; tier?: 'preview' } = {}) {
    return (await this.post<{ status: ReplicationStatusInfo[] }>({ op: 'deactivate', path: paths, deep: options.deep ? 'true' : undefined, agent: options.agent, at: options.at, tier: options.tier }))
      .status;
  }

  async unschedule(id: string) {
    return (await this.post<{ jobs: QueuedRequest[] }>({ op: 'unschedule', id })).jobs;
  }

  async removeJob(id: string, agent?: string) {
    return (await this.post<{ jobs: QueuedRequest[] }>({ op: 'removeJob', id, agent })).jobs;
  }

  async test(agent: string) {
    return this.post<{ ok: boolean; message: string }>({ op: 'test', agent });
  }

  async saveAgent(a: { id: string; title?: string; description?: string; enabled?: boolean; transportUri?: string; transportUser?: string; transportPassword?: string; retryDelay?: number; serializationType?: string; tier?: 'publish' | 'preview'; publicUrl?: string }) {
    return (
      await this.post<{ agents: ReplicationAgent[] }>({
        op: 'agent',
        id: a.id,
        title: a.title,
        description: a.description,
        enabled: a.enabled === undefined ? undefined : String(a.enabled),
        transportUri: a.transportUri,
        transportUser: a.transportUser,
        transportPassword: a.transportPassword,
        retryDelay: a.retryDelay === undefined ? undefined : String(a.retryDelay),
        serializationType: a.serializationType,
        tier: a.tier,
        publicUrl: a.publicUrl,
      })
    ).agents;
  }

  async removeAgent(id: string) {
    return (await this.post<{ agents: ReplicationAgent[] }>({ op: 'removeAgent', id })).agents;
  }

  async clearQueue(agent: string) {
    return (await this.post<{ agents: ReplicationAgent[] }>({ op: 'clear', agent })).agents;
  }

  async history(agent: string, limit = 100) {
    return (await this.get<{ history: DeliveryRecord[] }>({ op: 'history', agent, limit: String(limit) })).history;
  }

  async poll(agent: string) {
    return this.post<{ ok: boolean; message?: string; installed?: number; failed?: number; agents: ReplicationAgent[] }>({ op: 'poll', agent });
  }

  async retry(agent: string) {
    return (await this.post<{ agents: ReplicationAgent[] }>({ op: 'retry', agent })).agents;
  }
}
