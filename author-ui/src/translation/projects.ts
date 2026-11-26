import { PageTranslationError, type ReviewState } from './pages';

/** Translation projects (AEM-like): what to translate, into which languages, one job per language. */

export type JobStatus = 'DRAFT' | 'IN_PROGRESS' | 'READY_FOR_REVIEW' | 'APPROVED' | 'COMPLETE' | 'CANCELLED';
export type ItemKind = 'page' | 'experience-fragment' | 'content-fragment' | 'asset';
export type ItemStatus = 'DRAFT' | 'TRANSLATED' | 'FAILED' | 'SKIPPED';

export interface ProjectItem {
  id: string;
  path: string;
  kind: ItemKind;
  title?: string | null;
  exists?: boolean;
}

export interface JobItem {
  id: string;
  status: ItemStatus;
  copyPath?: string;
  error?: string;
  translated?: number;
  unchanged?: number;
  failedTexts?: number;
  at?: number;
}

export interface JobCopy {
  path: string;
  title?: string | null;
  kind?: ItemKind | null;
  state?: ReviewState | null;
}

export interface TranslationJob {
  language: string;
  status: JobStatus;
  total: number;
  done: number;
  failedItems: number;
  copyCount: number;
  started?: number;
  startedBy?: string;
  finished?: number;
  approvedAt?: number;
  approvedBy?: string;
  completedAt?: number;
  error?: string;
  /** Worked on the server: queued, running, or stopping after the current item. */
  background?: 'QUEUED' | 'RUNNING' | 'STOPPING';
  backgroundBy?: string;
  backgroundStarted?: number;
  textsTranslated?: number;
  characters?: number;
  fromMemory?: number;
  textsImported?: number;
  items?: JobItem[];
  copies?: JobCopy[];
  review?: Partial<Record<ReviewState, number>>;
}

export interface TranslationProject {
  name: string;
  title: string;
  description?: string;
  sourceLanguage: string;
  languages: string[];
  provider?: string;
  deep: boolean;
  includeReferences: boolean;
  created?: number;
  createdBy?: string;
  modified?: number;
  dueDate?: number;
  /** Past its due date with jobs still open. */
  overdue?: boolean;
  /** The Inbox reminder (a workflow) while the project is open and has a due date. */
  dueReminder?: string;
  itemCount: number;
  items?: ProjectItem[];
  jobs: TranslationJob[];
}

export interface NewProject {
  title: string;
  description?: string;
  paths: string[];
  deep: boolean;
  includeReferences: boolean;
  languages: string[];
  provider?: string;
  dueDate?: number;
}

export interface XliffImport {
  files: number;
  saved: number;
  unchanged: number;
  errors: { path: string; id?: string; label?: string; message: string }[];
  project: TranslationProject;
}

export interface ItemOutcome extends JobItem {
  project: TranslationProject;
}

export interface TranslationProjectClient {
  list(): Promise<TranslationProject[]>;
  get(name: string): Promise<TranslationProject>;
  create(project: NewProject): Promise<TranslationProject>;
  addItems(name: string, paths: string[], deep: boolean): Promise<TranslationProject>;
  removeItem(name: string, item: string): Promise<TranslationProject>;
  addLanguage(name: string, language: string): Promise<TranslationProject>;
  /** A due date (millis) or null; the owner's Inbox reminder follows. */
  setDueDate(name: string, dueDate: number | null): Promise<TranslationProject>;
  translateItem(name: string, language: string, item: string, force?: boolean): Promise<ItemOutcome>;
  setStatus(name: string, language: string, status: 'APPROVED' | 'COMPLETE' | 'CANCELLED' | 'DRAFT'): Promise<TranslationProject>;
  remove(name: string): Promise<void>;
  runInBackground(name: string, language: string): Promise<TranslationProject>;
  stopBackground(name: string, language: string): Promise<TranslationProject>;
  /** Where the job's XLIFF downloads from (1.2 unless 2.0 is asked for). */
  xliffUrl(name: string, language: string, version?: '1.2' | '2.0'): string;
  importXliff(name: string, language: string, file: Blob): Promise<XliffImport>;
}

const API = '/bin/studio/translationprojects';

export class HttpTranslationProjectClient implements TranslationProjectClient {
  constructor(private readonly base = '') {}

  async list() {
    return (await this.fetchJson<{ projects: TranslationProject[] }>(`${API}.json?op=list`)).projects;
  }

  async get(name: string) {
    return this.fetchJson<TranslationProject>(`${API}.json?${new URLSearchParams({ op: 'get', name })}`);
  }

  async create(project: NewProject) {
    if (project.dueDate) await this.syncWorkflowModels();
    return this.post<TranslationProject>({ op: 'create', ...project });
  }

  async setDueDate(name: string, dueDate: number | null) {
    if (dueDate) await this.syncWorkflowModels();
    return this.post<TranslationProject>({ op: 'setDueDate', name, dueDate });
  }

  /** Listing the workflow models syncs the default ones (the due-date reminder's runtime model). */
  private async syncWorkflowModels() {
    await fetch(`${this.base}/bin/studio/workflow.json?op=models`, { credentials: 'same-origin' }).catch(() => undefined);
  }

  async addItems(name: string, paths: string[], deep: boolean) {
    return this.post<TranslationProject>({ op: 'addItems', name, paths, deep });
  }

  async removeItem(name: string, item: string) {
    return this.post<TranslationProject>({ op: 'removeItem', name, item });
  }

  async addLanguage(name: string, language: string) {
    return this.post<TranslationProject>({ op: 'addLanguage', name, language });
  }

  async translateItem(name: string, language: string, item: string, force = false) {
    return this.post<ItemOutcome>({ op: 'translateItem', name, language, item, force });
  }

  async setStatus(name: string, language: string, status: 'APPROVED' | 'COMPLETE' | 'CANCELLED' | 'DRAFT') {
    return this.post<TranslationProject>({ op: 'setStatus', name, language, status });
  }

  async remove(name: string) {
    await this.post<unknown>({ op: 'delete', name });
  }

  async runInBackground(name: string, language: string) {
    return this.post<TranslationProject>({ op: 'runInBackground', name, language });
  }

  async stopBackground(name: string, language: string) {
    return this.post<TranslationProject>({ op: 'stopBackground', name, language });
  }

  xliffUrl(name: string, language: string, version: '1.2' | '2.0' = '1.2') {
    return `${this.base}${API}.json?${new URLSearchParams({ op: 'xliff', name, language, version })}`;
  }

  async importXliff(name: string, language: string, file: Blob) {
    return read<XliffImport>(
      await fetch(`${this.base}${API}?${new URLSearchParams({ op: 'importXliff', name, language })}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/xml', Accept: 'application/json' },
        body: file,
      }),
    );
  }

  private async fetchJson<T>(url: string): Promise<T> {
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
  if (!res.ok) {
    const o = (json ?? {}) as { error?: unknown; code?: unknown };
    throw new PageTranslationError(o.error ? String(o.error) : `HTTP ${res.status}`, o.code ? String(o.code) : undefined);
  }
  return json as T;
}

export const JOB_STATUS: Record<JobStatus, { text: string; variant: 'neutral' | 'notice' | 'info' | 'positive' | 'negative' }> = {
  DRAFT: { text: 'Draft', variant: 'neutral' },
  IN_PROGRESS: { text: 'In progress', variant: 'notice' },
  READY_FOR_REVIEW: { text: 'Ready for review', variant: 'info' },
  APPROVED: { text: 'Approved', variant: 'positive' },
  COMPLETE: { text: 'Complete', variant: 'positive' },
  CANCELLED: { text: 'Cancelled', variant: 'negative' },
};

/** Items still to translate in a job, in project order. */
export function pendingItems(project: TranslationProject, job: TranslationJob): string[] {
  const status = new Map((job.items ?? []).map((i) => [i.id, i.status]));
  return (project.items ?? []).map((i) => i.id).filter((id) => (status.get(id) ?? 'DRAFT') === 'DRAFT');
}

/** Lines of paths (one per line, blanks and duplicates dropped). */
export function parsePaths(text: string): string[] {
  return [...new Set(text.split(/[\n,]+/).map((s) => s.trim().replace(/\.html$/, '')).filter(Boolean))];
}

/** "152 texts translated · 8,431 characters · 12 from the memory · 3 imported" (what was done). */
export function jobStats(job: TranslationJob): string {
  const n = (v?: number) => (v ?? 0).toLocaleString('en-US');
  const parts: string[] = [];
  if (job.textsTranslated) parts.push(`${n(job.textsTranslated)} text${job.textsTranslated === 1 ? '' : 's'} translated`);
  if (job.characters) parts.push(`${n(job.characters)} characters`);
  if (job.fromMemory) parts.push(`${n(job.fromMemory)} from the memory`);
  if (job.textsImported) parts.push(`${n(job.textsImported)} imported from XLIFF`);
  return parts.join(' · ');
}
