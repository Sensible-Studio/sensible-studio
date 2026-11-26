/** Projects (`/bin/studio/projects.json`): a team, a due date, the content it is about, its tasks. */
export type Role = 'owner' | 'editor' | 'observer';
export type Priority = 'low' | 'medium' | 'high';

export interface Member {
  user: string;
  role: Role;
}

export interface ProjectSummary {
  path: string;
  name: string;
  title: string;
  description?: string | null;
  /** yyyy-MM-dd */
  due?: string | null;
  active: boolean;
  owner?: string | null;
  created?: string | null;
  members: Member[];
  myRole?: Role | null;
  open: number;
  done: number;
  overdue: number;
}

export interface ProjectLink {
  path: string;
  title: string;
  exists: boolean;
  kind: 'page' | 'media';
}

export interface Task {
  id: string;
  title: string;
  description?: string | null;
  assignee?: string | null;
  due?: string | null;
  priority: Priority;
  status: 'open' | 'done';
  created?: string | null;
  createdBy?: string | null;
  doneAt?: string | null;
  doneBy?: string | null;
  overdue: boolean;
}

export interface Project extends Omit<ProjectSummary, never> {
  links: ProjectLink[];
  tasks: Task[];
}

export interface MyTask extends Task {
  project: string;
  projectTitle: string;
}

export type Fields = Record<string, string | undefined>;

export interface ProjectsClient {
  list(): Promise<{ projects: ProjectSummary[]; canCreate: boolean }>;
  get(path: string): Promise<Project>;
  myTasks(): Promise<MyTask[]>;
  create(fields: Fields): Promise<Project>;
  /** save | member | removeMember | link | unlink | task | removeTask */
  change(path: string, op: string, fields?: Fields): Promise<Project>;
  remove(path: string): Promise<void>;
}

export const PROJECTS_URL = '/studio/projects.html';
export const projectUrl = (name: string) => `${PROJECTS_URL}/${encodeURIComponent(name)}`;
export const pathOfName = (name: string) => `/content/projects/${name}`;

export class HttpProjectsClient implements ProjectsClient {
  constructor(
    private readonly base = '/bin/studio/projects.json',
    private readonly fetcher: typeof fetch = (...a) => fetch(...a),
  ) {}

  private async read<T>(r: Response): Promise<T> {
    const body = (await r.json().catch(() => ({}))) as T & { error?: string };
    if (!r.ok) throw new Error(body.error ?? `HTTP ${r.status}`);
    return body;
  }

  private async get_<T>(query: Record<string, string>): Promise<T> {
    return this.read<T>(await this.fetcher(`${this.base}?${new URLSearchParams(query).toString()}`, { credentials: 'same-origin' }));
  }

  private async post<T>(fields: Fields): Promise<T> {
    const body = new URLSearchParams();
    for (const [k, v] of Object.entries(fields)) if (v !== undefined) body.append(k, v);
    return this.read<T>(await this.fetcher(this.base, { method: 'POST', credentials: 'same-origin', headers: { 'X-Studio-CSRF': '1' }, body }));
  }

  list() {
    return this.get_<{ projects: ProjectSummary[]; canCreate: boolean }>({ op: 'list' });
  }

  get(path: string) {
    return this.get_<Project>({ op: 'get', path });
  }

  async myTasks() {
    return (await this.get_<{ tasks: MyTask[] }>({ op: 'mytasks' })).tasks;
  }

  create(fields: Fields) {
    return this.post<Project>({ ...fields, op: 'create' });
  }

  change(path: string, op: string, fields: Fields = {}) {
    return this.post<Project>({ ...fields, op, path });
  }

  async remove(path: string) {
    await this.post<unknown>({ op: 'delete', path });
  }
}

/** "in 3 days", "today", "2 days late", for a yyyy-MM-dd date. */
export function dueText(due: string | null | undefined, today = new Date()): string {
  if (!due) return '';
  const d = new Date(`${due}T00:00:00`);
  const t = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const days = Math.round((d.getTime() - t.getTime()) / 86400000);
  if (days === 0) return 'due today';
  if (days === 1) return 'due tomorrow';
  if (days > 1) return `due in ${days} days`;
  return days === -1 ? '1 day late' : `${-days} days late`;
}
