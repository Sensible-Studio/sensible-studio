/** The Submissions API (`studio-forms`: /bin/studio/forms/submissions.json). */

export interface SubmissionField {
  name: string;
  type: string;
  label: string;
  required: boolean;
}

export interface SubmissionFile {
  field: string;
  name: string;
  path: string;
  type?: string;
  size?: number;
}

/** What an after-submit action did (flow / webhook / email). */
export interface SubmissionAction {
  name: string;
  status: 'done' | 'failed' | 'skipped';
  detail?: string | null;
  at?: string | null;
}

export type SubmissionCounts = Record<string, { total: number; unseen: number }>;

export interface Submission {
  path: string;
  id: string;
  form: string;
  submittedAt: string | null;
  seen: boolean;
  values: Record<string, string>;
  files?: SubmissionFile[];
  actions?: SubmissionAction[];
  locale?: string;
  userAgent?: string;
  origin?: string;
  referrer?: string;
}

export interface SubmissionList {
  form: string;
  title: string;
  fields: SubmissionField[];
  total: number;
  items: Submission[];
}

export interface SubmissionsClient {
  list(form: string, opts?: { q?: string; offset?: number; limit?: number }): Promise<SubmissionList>;
  get(path: string): Promise<Submission>;
  count(form: string): Promise<{ total: number; unseen: number }>;
  /** total / unseen per form, for every form under a folder (or the form itself). */
  counts(under: string): Promise<SubmissionCounts>;
  remove(paths: string[]): Promise<number>;
  /** Put the "new" mark back on submissions that were opened. */
  markNew(paths: string[]): Promise<number>;
  exportUrl(form: string, format: 'csv' | 'json'): string;
  /** Where an uploaded file can be opened or downloaded. */
  fileUrl(file: SubmissionFile, download?: boolean): string;
}

const API = '/bin/studio/forms/submissions.json';

export const submissionsUrl = (form: string) => `/forms/submissions.html${encodeURI(form)}`;

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { error?: string };
      if (body.error) message = body.error;
    } catch {
      /* not JSON */
    }
    throw new Error(message);
  }
  return (await res.json()) as T;
}

export class HttpSubmissionsClient implements SubmissionsClient {
  async list(form: string, opts: { q?: string; offset?: number; limit?: number } = {}): Promise<SubmissionList> {
    const p = new URLSearchParams({ form });
    if (opts.q) p.set('q', opts.q);
    if (opts.offset) p.set('offset', String(opts.offset));
    if (opts.limit) p.set('limit', String(opts.limit));
    return json(await fetch(`${API}?${p}`, { credentials: 'same-origin' }));
  }

  async get(path: string): Promise<Submission> {
    const p = new URLSearchParams({ op: 'get', path });
    return json(await fetch(`${API}?${p}`, { credentials: 'same-origin' }));
  }

  async count(form: string): Promise<{ total: number; unseen: number }> {
    const p = new URLSearchParams({ op: 'count', form });
    return json(await fetch(`${API}?${p}`, { credentials: 'same-origin' }));
  }

  async counts(under: string): Promise<SubmissionCounts> {
    const p = new URLSearchParams({ op: 'counts', under });
    return json(await fetch(`${API}?${p}`, { credentials: 'same-origin' }));
  }

  async remove(paths: string[]): Promise<number> {
    const body = new URLSearchParams();
    body.set('op', 'delete');
    for (const p of paths) body.append('path', p);
    const res = await json<{ deleted: number }>(
      await fetch(API, { method: 'POST', credentials: 'same-origin', headers: { 'X-Studio-CSRF': '1' }, body }),
    );
    return res.deleted;
  }

  async markNew(paths: string[]): Promise<number> {
    const body = new URLSearchParams();
    body.set('op', 'unseen');
    for (const p of paths) body.append('path', p);
    const res = await json<{ changed: number }>(
      await fetch(API, { method: 'POST', credentials: 'same-origin', headers: { 'X-Studio-CSRF': '1' }, body }),
    );
    return res.changed;
  }

  exportUrl(form: string, format: 'csv' | 'json'): string {
    const p = new URLSearchParams({ op: 'export', form, format });
    return `/bin/studio/forms/submissions.${format}?${p}`;
  }

  fileUrl(file: SubmissionFile, download = false): string {
    const p = new URLSearchParams({ op: 'file', path: file.path });
    if (download) p.set('download', 'true');
    return `${API}?${p}`;
  }
}
