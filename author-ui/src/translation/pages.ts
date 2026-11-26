/**
 * Language copies of pages (`/bin/studio/pagetranslation`): a page's language, its copies in the other languages of
 * the site and their translation state; translating creates or updates copies.
 */

export interface PageTranslationRecord {
  sourcePath: string;
  sourceLanguage: string;
  targetLanguage: string;
  status: 'TRANSLATED' | 'APPROVED' | 'CHANGES' | 'ERROR_UPDATE';
  provider?: string;
  attribution?: string;
  translatedAt?: number;
  translatedBy?: string;
  approvedAt?: number;
  approvedBy?: string;
  /** A reviewer asked for changes (review workflow), with their comment. */
  changesRequestedBy?: string;
  changesRequestedAt?: number;
  reviewComment?: string;
  failed: string[];
  /** The source changed since the last translation. */
  outdated?: boolean;
  sourceMissing?: boolean;
}

export interface PageLanguageCopy {
  language: string;
  name: string;
  root: string;
  path: string;
  exists: boolean;
  liveCopy: boolean;
  title?: string;
  translation?: PageTranslationRecord;
}

export interface PageLanguages {
  path: string;
  title: string;
  liveCopy: boolean;
  /** null: the page is not below a language root. */
  languageRoot: string | null;
  language?: string;
  /** When the page is itself a translated copy. */
  translation?: PageTranslationRecord;
  copies?: PageLanguageCopy[];
  /** Languages the connectors translate well, code → name. */
  languages?: Record<string, string>;
}

export interface PageTranslateResult {
  source: string;
  path: string;
  language: string;
  status: 'TRANSLATED' | 'SKIPPED';
  created?: boolean;
  /** A page created above the selected one (its language root or a parent). */
  above?: boolean;
  /** An experience or content fragment the page refers to, translated with it. */
  reference?: boolean;
  translated?: number;
  unchanged?: number;
  kept?: number;
  /** Texts the translation memory answered (translated identically before). */
  fromMemory?: number;
  failed?: string[];
  attribution?: string;
  error?: string;
}

export interface TranslationProvider {
  name: string;
  label: string;
  available: boolean;
  default: boolean;
  problem?: string;
  code?: string;
  cloudConfig?: boolean;
  conf?: string;
}

export class PageTranslationError extends Error {
  constructor(
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

export interface PageTranslateRequest {
  path: string;
  languages: string[];
  provider?: string;
  force?: boolean;
  /** All source pages of the batch (links between them point to their copies). */
  batch?: string[];
  /** Also translate the experience and content fragments the page uses. */
  includeReferences?: boolean;
  /** Referenced sources earlier pages of the batch translated already. */
  skipReferences?: string[];
}

export type ReviewState = 'review' | 'changes' | 'outdated' | 'failed' | 'approved';

export interface QueueItem extends PageTranslationRecord {
  kind: 'page' | 'experience-fragment' | 'content-fragment' | 'asset';
  path: string;
  title: string;
  state: ReviewState;
}

export interface ReviewSegment {
  id: string;
  label: string;
  html: boolean;
  source: string;
  translation: string | null;
  failed: boolean;
  /** The source text changed since it was translated. */
  changed: boolean;
}

export interface PageReview {
  path: string;
  title: string;
  sourceTitle?: string;
  translation: PageTranslationRecord;
  canEdit: boolean;
  segments: ReviewSegment[];
  retranslated?: number;
  attribution?: string;
}

/** 422 of a review save: what is wrong, by segment id. */
export class ReviewValidationError extends Error {
  constructor(readonly errors: Record<string, string>) {
    super(`${Object.keys(errors).length} text(s) cannot be saved`);
  }
}

export interface BulkApproval {
  approved: string[];
  skipped: { path: string; reason: string }[];
  failed: { path: string; error: string }[];
}

export interface PageTranslationClient {
  languages(path: string): Promise<PageLanguages>;
  /** The pages a translation covers, parents first. */
  pages(path: string, deep: boolean): Promise<string[]>;
  translate(request: PageTranslateRequest): Promise<PageTranslateResult[]>;
  approve(path: string): Promise<PageLanguages>;
  /** Approves many copies at once; unfinished ones (untranslated texts, outdated) and approved ones are skipped. */
  approveAll(paths: string[]): Promise<BulkApproval>;
  providers(path: string): Promise<TranslationProvider[]>;
  queue(filter?: { language?: string; under?: string; status?: ReviewState; kind?: QueueItem['kind'] }): Promise<QueueItem[]>;
  review(path: string): Promise<PageReview>;
  saveReview(path: string, translations: Record<string, string>): Promise<PageReview>;
  retranslate(path: string, ids: string[], provider?: string): Promise<PageReview>;
}

const API = '/bin/studio/pagetranslation';

export class HttpPageTranslationClient implements PageTranslationClient {
  constructor(private readonly base = '') {}

  async languages(path: string) {
    return this.get<PageLanguages>(`${API}.json?${new URLSearchParams({ op: 'languages', path })}`);
  }

  async pages(path: string, deep: boolean) {
    return (await this.get<{ pages: string[] }>(`${API}.json?${new URLSearchParams({ op: 'pages', path, deep: String(deep) })}`)).pages;
  }

  async translate(request: PageTranslateRequest) {
    return (await this.post<{ results: PageTranslateResult[] }>({ op: 'translate', ...request })).results;
  }

  async approve(path: string) {
    return this.post<PageLanguages>({ op: 'approve', path });
  }

  async approveAll(paths: string[]) {
    return this.post<BulkApproval>({ op: 'approveAll', paths });
  }

  async queue(filter: { language?: string; under?: string; status?: ReviewState; kind?: QueueItem['kind'] } = {}) {
    const params = new URLSearchParams({ op: 'queue' });
    for (const [k, v] of Object.entries(filter)) if (v) params.set(k, v);
    return (await this.get<{ items: QueueItem[] }>(`${API}.json?${params}`)).items;
  }

  async review(path: string) {
    return this.get<PageReview>(`${API}.json?${new URLSearchParams({ op: 'review', path })}`);
  }

  async saveReview(path: string, translations: Record<string, string>) {
    return this.post<PageReview>({ op: 'saveReview', path, translations });
  }

  async retranslate(path: string, ids: string[], provider?: string) {
    return this.post<PageReview>({ op: 'retranslate', path, ids, provider });
  }

  async providers(path: string) {
    return this.get<TranslationProvider[]>(`/bin/studio/translation.json?${new URLSearchParams({ op: 'providers', path })}`);
  }

  private async get<T>(url: string): Promise<T> {
    return read<T>(await fetch(`${this.base}${url}`, { credentials: 'same-origin' }));
  }

  private async post<T>(body: Record<string, unknown>): Promise<T> {
    return read<T>(
      await fetch(`${this.base}${API}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      }),
    );
  }
}

async function read<T>(res: Response): Promise<T> {
  const text = await res.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* not JSON */
  }
  if (res.status === 422 && json && typeof json === 'object' && 'errors' in json) {
    throw new ReviewValidationError((json as { errors: Record<string, string> }).errors);
  }
  if (!res.ok) {
    const o = (json ?? {}) as { error?: unknown; code?: unknown };
    throw new PageTranslationError(o.error ? String(o.error) : `HTTP ${res.status}`, o.code ? String(o.code) : undefined);
  }
  return json as T;
}

/** The state of a copy, in words and as a status light variant. */
export function copyState(c: PageLanguageCopy): { text: string; variant: 'positive' | 'notice' | 'neutral' | 'info' | 'negative' } {
  if (c.liveCopy) return { text: 'Linked copy (synced from its source)', variant: 'info' };
  if (!c.exists) return { text: 'Not created', variant: 'neutral' };
  const t = c.translation;
  if (!t) return { text: 'Not translated by Sensible Studio', variant: 'neutral' };
  if (t.outdated) return { text: 'Outdated: the source changed', variant: 'notice' };
  if (t.failed.length) return { text: `${t.failed.length} text(s) not translated`, variant: 'negative' };
  return t.status === 'APPROVED' ? { text: 'Approved', variant: 'positive' } : { text: 'Translated, to review', variant: 'notice' };
}

/** The default review workflow (studio-translation-content). */
export const REVIEW_MODEL = '/var/workflow/models/translation_review';

/** Starting and following "Translation Review" workflows (`/bin/studio/workflow.json`). */
export interface ReviewWorkflowClient {
  /** Starts one review per copy; answers the instance ids. */
  requestReview(paths: string[], comment?: string): Promise<string[]>;
  /** Copies with a running review, by payload path. */
  running(): Promise<Record<string, { id: string; assignee?: string }>>;
  /**
   * Ends the running reviews of copies approved on the review screen, so nothing is left waiting in an Inbox:
   * the review step is completed through its Approve route when it is in the author's Inbox, otherwise the flow
   * is ended. Answers how many reviews were closed.
   */
  close(paths: string[]): Promise<number>;
}

export class HttpReviewWorkflowClient implements ReviewWorkflowClient {
  constructor(private readonly base = '') {}

  async requestReview(paths: string[], comment?: string) {
    // listing the models syncs the default ones (the runtime model of Translation Review)
    await fetch(`${this.base}/bin/studio/workflow.json?op=models`, { credentials: 'same-origin' });
    const ids: string[] = [];
    for (const payload of paths) {
      const body = new URLSearchParams({ op: 'start', model: REVIEW_MODEL, payload, payloadType: 'JCR_PATH', title: `Review the translation of ${payload}` });
      if (comment) body.set('comment', comment);
      body.set('_charset_', 'utf-8');
      const res = await fetch(`${this.base}/bin/studio/workflow.json`, { method: 'POST', credentials: 'same-origin', body });
      const json = (await res.json().catch(() => ({}))) as { id?: string; error?: string };
      if (!res.ok) throw new PageTranslationError(json.error ?? `HTTP ${res.status}`);
      if (json.id) ids.push(json.id);
    }
    window.dispatchEvent(new Event('studio:inbox-changed'));
    return ids;
  }

  async running() {
    const res = await fetch(`${this.base}/bin/studio/workflow.json?${new URLSearchParams({ op: 'instances', status: 'RUNNING', model: REVIEW_MODEL })}`, {
      credentials: 'same-origin',
    });
    if (!res.ok) return {};
    const json = (await res.json()) as unknown;
    const list = (Array.isArray(json) ? json : ((json as { instances?: unknown[] }).instances ?? [])) as {
      id: string;
      payload?: string;
      current?: { assignee?: string }[];
    }[];
    const out: Record<string, { id: string; assignee?: string }> = {};
    for (const i of list) if (i.payload) out[i.payload] = { id: i.id, assignee: i.current?.[0]?.assignee };
    return out;
  }

  async close(paths: string[]) {
    const running = await this.running();
    const open = paths.filter((p) => running[p]?.id);
    if (!open.length) return 0;
    const res = await fetch(`${this.base}/bin/studio/workflow.json?op=inbox`, { credentials: 'same-origin' });
    const inbox = (res.ok ? await res.json().catch(() => []) : []) as {
      id: string;
      workflow?: { id?: string };
      routes?: { id: string; name: string }[];
    }[];
    const post = async (entries: Record<string, string>) => {
      const body = new URLSearchParams({ ...entries, _charset_: 'utf-8' });
      const r = await fetch(`${this.base}/bin/studio/workflow.json`, { method: 'POST', credentials: 'same-origin', body });
      return r.ok;
    };
    let closed = 0;
    for (const path of open) {
      const id = running[path].id;
      const item = (Array.isArray(inbox) ? inbox : []).find((e) => e.workflow?.id === id);
      const route = item?.routes?.find((r) => /^approve/i.test(r.name));
      // the review step is ours: decide it as approved, so the flow's history tells what happened
      const done = item && route ? await post({ op: 'complete', item: item.id, route: route.id, comment: 'Approved on the review screen' }) : false;
      if (done || (await post({ op: 'terminate', id }))) closed++;
    }
    if (closed) window.dispatchEvent(new Event('studio:inbox-changed'));
    return closed;
  }
}
