/** The link checker (`/bin/studio/linkchecker.json`): the links in content and whether they still lead somewhere. */

export type LinkStatus = 'valid' | 'invalid' | 'pending';
export type LinkKind = 'external' | 'internal';

export interface Link {
  url: string;
  kind: LinkKind;
  status: LinkStatus;
  /** The HTTP status of the last check (external links). */
  code?: number;
  error?: string;
  /** Where the link ends up after redirects, when that is somewhere else. */
  finalUrl?: string;
  lastChecked?: string;
  firstSeen?: string;
  /** How many times the link is written in content. */
  referrerCount: number;
  /** The first pages / files that use it (`op=list`). */
  owners?: string[];
}

export interface Referrer {
  /** The page, block, record or file the link is written in. */
  owner: string;
  ownerTitle: string;
  ownerKind: 'page' | 'block' | 'record' | 'media' | 'other';
  node: string;
  /** The component inside the page (`root/container/text`), when the link is in one. */
  component?: string;
  property?: string;
}

export interface LinkDetails extends Link {
  referrers: Referrer[];
}

export interface Progress {
  running: boolean;
  phase?: 'scan' | 'check';
  by?: string;
  done: number;
  total: number;
  started?: string;
  finished?: string;
  error?: string;
  scan?: { links: number; external: number; internal: number; added: number; removed: number };
}

export interface LinkList {
  links: Link[];
  total: number;
  counts: { all: number; invalid: number; pending: number; valid: number };
  progress: Progress;
  checkPeriodHours: number;
}

export interface ListQuery {
  status?: LinkStatus | 'all';
  kind?: LinkKind | 'all';
  q?: string;
  offset?: number;
  limit?: number;
}

export interface LinkCheckerClient {
  list(query: ListQuery): Promise<LinkList>;
  info(url: string): Promise<LinkDetails>;
  /** Scan content for links, then check the ones that are due. False when a run is already going. */
  scan(): Promise<boolean>;
  /** Check these links now. */
  check(urls: string[]): Promise<boolean>;
  /** Check every link now. */
  checkAll(): Promise<boolean>;
  /** Forget these links (a scan finds them again while content uses them). */
  remove(urls: string[]): Promise<number>;
}

const ENDPOINT = '/bin/studio/linkchecker.json';

export class HttpLinkCheckerClient implements LinkCheckerClient {
  constructor(private readonly base = '') {}

  private async get<T>(params: Record<string, string>): Promise<T> {
    const res = await fetch(`${this.base}${ENDPOINT}?${new URLSearchParams(params)}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
    return json;
  }

  /** A run answers 202 when it started and 409 when one was already going: both are fine. */
  private async post<T>(op: string, urls: string[] = []): Promise<{ status: number; json: T }> {
    const body = new URLSearchParams({ op });
    for (const u of urls) body.append('url', u);
    const res = await fetch(`${this.base}${ENDPOINT}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'X-Studio-CSRF': '1' },
      body,
    });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok && res.status !== 409) throw new Error(json.error || `HTTP ${res.status}`);
    return { status: res.status, json };
  }

  list(query: ListQuery) {
    const params: Record<string, string> = { op: 'list' };
    for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== '') params[k] = String(v);
    return this.get<LinkList>(params);
  }

  info(url: string) {
    return this.get<LinkDetails>({ op: 'info', url });
  }

  async scan() {
    return (await this.post<{ started: boolean }>('scan')).json.started;
  }

  async check(urls: string[]) {
    return (await this.post<{ started: boolean }>('check', urls)).json.started;
  }

  async checkAll() {
    return (await this.post<{ started: boolean }>('checkAll')).json.started;
  }

  async remove(urls: string[]) {
    return (await this.post<{ removed: number }>('delete', urls)).json.removed;
  }
}

/** Where to open the thing a link is written in. */
export function ownerUrl(r: Pick<Referrer, 'owner' | 'ownerKind'>): string {
  switch (r.ownerKind) {
    case 'page':
    case 'block':
      return `/editor.html${r.owner}.html`;
    case 'record':
      return `/editor.html${r.owner}`;
    case 'media':
      return `/assetdetails.html${r.owner}`;
    default:
      return `/bin/browser.html${r.owner}`;
  }
}
