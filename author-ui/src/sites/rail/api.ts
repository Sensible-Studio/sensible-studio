import type { SiteItem } from '../../editor/api/StudioClient';

/** The rails of the Sites console, in the order (and with the shortcuts) of the rail selector. */
/** The left rails: the map (Content Tree) and the search (Filter). Timeline, References and Site live in the inspector at the right. */
export const RAILS = [
  { id: 'content', label: 'Content Only', shortcut: '`' },
  { id: 'tree', label: 'Content Tree', shortcut: 'alt+1' },
  { id: 'filter', label: 'Filter', shortcut: 'alt+2' },
] as const;

export type RailId = (typeof RAILS)[number]['id'];

export type TimelineKind = 'activity' | 'version' | 'workflow' | 'comment' | 'annotation' | 'launch';

export interface TimelineEvent {
  kind: TimelineKind;
  id: string;
  title: string;
  text?: string;
  user?: string;
  /** ISO-8601 */
  time: string;
  /** version events: the version name (1.0, 1.1 …) */
  version?: string;
  /** workflow start events: the instance and its status */
  instance?: string;
  status?: string;
  /** comment events: written by the current user */
  mine?: boolean;
  /** launch events: the launch, the page's copy in it, its live date (ISO-8601), whether it was promoted */
  launch?: string;
  pageInLaunch?: string;
  liveDate?: string;
  promoted?: boolean;
  /** annotation events: the component the note is on (below the page's jcr:content) and the marker's colour */
  component?: string;
  color?: string;
}

/** A launch that holds a copy of a page (studio-launches). */
export interface PageLaunch {
  path: string;
  title: string;
  /** the page's copy in the launch */
  pageInLaunch: string;
  liveDate?: string;
  created?: string;
  createdBy?: string;
  /** set once the launch was promoted */
  promoted?: string;
}

export interface Timeline {
  events: TimelineEvent[];
  lockOwner?: string;
}

export interface SiteSummary {
  path: string;
  title: string;
  isPage: boolean;
  conf?: string;
  languages: Array<{ path: string; title: string; language: string }>;
  pages: number;
  liveCopyPages: number;
  lockedPages: number;
  lastModified?: { path: string; title: string; time: string };
  templates: Array<{ path: string; title: string; status?: string }>;
}

export interface FilterCriteria {
  /** search below this path */
  path: string;
  text: string;
  template: string | null;
  /** tag IDs (namespace:path) */
  tags: string[];
  /** 0: any time */
  modifiedDays: number;
  liveCopy: '' | 'livecopy' | 'notlivecopy';
  locked: boolean;
  /** options filters of the search form: a page property has one of the values; properties are ANDed */
  meta?: Record<string, string[]>;
  /** text filters of the search form: a page property contains the text, any case */
  metaText?: Record<string, string>;
}

/** The filters the Filter rail can show: six built in, then filters on a page property. */
export type PageFacetType = 'path' | 'template' | 'tags' | 'modified' | 'livecopy' | 'locked' | 'options' | 'text';

/** One filter of the Filter rail (Search Forms; SiteSearchForms on the server). */
export interface PageFacet {
  type: PageFacetType;
  label: string;
  description?: string;
  /** options and text: the property of the page (jcr:content), such as pageTitle */
  property?: string;
  /** tags: the tag the picker starts at, such as wknd: */
  root?: string;
  /** options: the values to tick */
  options?: Array<{ value: string; label: string }>;
}

export interface PageSearchForm {
  facets: PageFacet[];
  /** false: the default form (nothing saved) */
  custom: boolean;
  modified?: string;
  modifiedBy?: string;
  /** the user may change the form (admin) */
  canEdit?: boolean;
}

export interface PropertyValues {
  property: string;
  /** the most used first */
  values: Array<{ value: string; count: number }>;
  more: boolean;
  /** not every page was read (a large site) */
  truncated: boolean;
}

export const BUILT_IN_PAGE_FACETS: PageFacetType[] = ['path', 'template', 'tags', 'modified', 'livecopy', 'locked'];

export const PAGE_FACET_NAMES: Record<PageFacetType, string> = {
  path: 'Path',
  template: 'Template',
  tags: 'Tags',
  modified: 'Modified',
  livecopy: 'Linked copy',
  locked: 'Locked pages only',
  options: '',
  text: '',
};

/** What the rail shows without a form of its own (the server's default too). */
export const DEFAULT_PAGE_FACETS: PageFacet[] = BUILT_IN_PAGE_FACETS.map((type) => ({ type, label: PAGE_FACET_NAMES[type] }));

/** A search of the Filter rail kept under a name, for the signed-in user. */
export interface SavedSearch {
  name: string;
  criteria: FilterCriteria;
  modified?: string;
}

export interface SearchResults {
  total: number;
  truncated: boolean;
  items: Array<SiteItem & { tags?: string[] }>;
}

export interface PageVersion {
  id: string;
  name: string;
  label?: string;
  comment?: string;
  createdBy?: string;
  /** the page title in that version */
  title?: string;
  /** ISO-8601 */
  created: string;
}

/** A page inside the selected one that has versions: Restore Version lists these. */
export interface VersionedPage {
  path: string;
  name: string;
  title: string;
  /** deleted since: it is created again from its version */
  deleted: boolean;
  deletedBy?: string;
  deletedAt?: string;
  /** deleted outside the console: when Sensible Studio noticed it (who deleted it and when is not known) */
  foundAt?: string;
  /** newest first */
  versions: PageVersion[];
}

/** One page of the tree as Restore Tree would make it. */
export interface TreeAtNode {
  path: string;
  name: string;
  title: string;
  page: boolean;
  exists: boolean;
  /** the latest version at the date, if any */
  version?: PageVersion;
  /** a deleted page without a version then: it comes back like this when pages below it do */
  fallback?: PageVersion;
  children: TreeAtNode[];
}

export interface RestoreResult {
  path: string;
  status: 'restored' | 'recreated' | 'failed';
  error?: string;
}

export interface RestoreTreeResult {
  restored: number;
  recreated: number;
  removed: number;
  failed: Array<{ path: string; error: string }>;
}

/** One difference between two states of a page's content (Compare to Current). */
export interface DiffChange {
  /** the component, relative to jcr:content ("" for the page itself) */
  node: string;
  kind: 'added' | 'removed' | 'changed' | 'propertyAdded' | 'propertyRemoved';
  resourceType?: string;
  /** for property changes */
  property?: string;
  before?: string;
  after?: string;
  /** for a component added or removed: its properties */
  properties?: Record<string, string>;
}

export interface PageDiff {
  from: string;
  to: string;
  changes: DiffChange[];
}

export interface SiteRailClient {
  timeline(path: string): Promise<Timeline>;
  comment(path: string, message: string): Promise<void>;
  deleteComment(id: string): Promise<void>;
  saveVersion(path: string, label: string, comment: string): Promise<{ id: string; version: string }>;
  site(path: string): Promise<SiteSummary>;
  templates(path: string): Promise<Array<{ path: string; title: string }>>;
  search(criteria: FilterCriteria): Promise<SearchResults>;
  versionsInside(path: string): Promise<VersionedPage[]>;
  restoreVersions(choices: Array<[page: string, version: string]>): Promise<RestoreResult[]>;
  treeAt(path: string, date: string): Promise<TreeAtNode>;
  restoreTree(path: string, date: string, preserve: boolean): Promise<RestoreTreeResult>;
  /** reverts one page to a version (the current state is saved as a version first) */
  restore(path: string, version: string): Promise<void>;
  /** deletes pages or folders, with a version of every page inside first (so Restore Version can bring them back) */
  deletePages(paths: string[]): Promise<void>;
  /** what differs from one state to another: 'current' or a version id */
  diff(path: string, from: string, to: string): Promise<PageDiff>;
  /** the signed-in user's saved searches, by name */
  savedSearches(): Promise<SavedSearch[]>;
  /** saves the criteria under a name (a search of the same name is replaced) */
  saveSearch(name: string, criteria: FilterCriteria): Promise<SavedSearch>;
  deleteSearch(name: string): Promise<void>;
  /** the launches that hold a copy of the page (none when launches are not installed) */
  launchesOf(path: string): Promise<PageLaunch[]>;
  /** the filters the Filter rail shows (Search Forms) */
  searchForm(): Promise<PageSearchForm>;
  /** admin: the filters, in order */
  saveSearchForm(facets: PageFacet[]): Promise<PageSearchForm>;
  /** admin: back to the default filters */
  resetSearchForm(): Promise<PageSearchForm>;
  /** the values a page property has below a path, the most used first */
  propertyValues(path: string, property: string): Promise<PropertyValues>;
}

/** Preview Version: the page rendered as it was in a version. */
export const versionPreviewUrl = (path: string, version: string) =>
  `/bin/studio/versionpreview.html?${new URLSearchParams({ path, version })}`;

/** The page as it is now, without the editor. */
export const currentPreviewUrl = (path: string) => `${path}.html?wcmmode=disabled`;

const API = '/bin/studio/siterail.json';
const FORM = 'application/x-www-form-urlencoded;charset=UTF-8';

async function call<T>(method: 'GET' | 'POST', params: Array<[string, string | undefined]>): Promise<T> {
  const qs = new URLSearchParams(params.filter((p): p is [string, string] => p[1] !== undefined && p[1] !== ''));
  const res =
    method === 'GET'
      ? await fetch(`${API}?${qs}`, { credentials: 'same-origin' })
      : await fetch(API, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': FORM }, body: qs });
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

/** `/bin/studio/siterail.json` of the studio-siterail bundle. */
export class HttpSiteRailClient implements SiteRailClient {
  timeline = (path: string) => call<Timeline>('GET', [['op', 'timeline'], ['path', path]]);

  comment = async (path: string, message: string) => {
    await call('POST', [['op', 'comment'], ['path', path], ['message', message]]);
  };

  deleteComment = async (id: string) => {
    await call('POST', [['op', 'deleteComment'], ['id', id]]);
  };

  saveVersion = (path: string, label: string, comment: string) =>
    call<{ id: string; version: string }>('POST', [['op', 'saveVersion'], ['path', path], ['label', label], ['comment', comment]]);

  site = (path: string) => call<SiteSummary>('GET', [['op', 'site'], ['path', path]]);

  templates = async (path: string) =>
    (await call<{ templates: Array<{ path: string; title: string }> }>('GET', [['op', 'templates'], ['path', path]])).templates;

  search = (c: FilterCriteria) =>
    call<SearchResults>('GET', [
      ['op', 'search'],
      ['path', c.path],
      ['q', c.text.trim()],
      ['template', c.template ?? undefined],
      ...c.tags.map((t): [string, string] => ['tag', t]),
      ['modified', c.modifiedDays ? String(c.modifiedDays) : undefined],
      ['livecopy', c.liveCopy || undefined],
      ['locked', c.locked ? 'true' : undefined],
      ...Object.entries(c.meta ?? {}).flatMap(([p, vs]) => vs.map((v): [string, string] => ['meta', `${p}=${v}`])),
      ...Object.entries(c.metaText ?? {})
        .filter(([, t]) => t.trim())
        .map(([p, t]): [string, string] => ['metaText', `${p}=${t.trim()}`]),
    ]);

  searchForm = () => call<PageSearchForm>('GET', [['op', 'searchForm']]);

  saveSearchForm = (facets: PageFacet[]) => call<PageSearchForm>('POST', [['op', 'saveSearchForm'], ['facets', JSON.stringify(facets)]]);

  resetSearchForm = () => call<PageSearchForm>('POST', [['op', 'resetSearchForm']]);

  propertyValues = (path: string, property: string) =>
    call<PropertyValues>('GET', [['op', 'propertyValues'], ['path', path], ['property', property]]);

  versionsInside = async (path: string) => (await call<{ pages: VersionedPage[] }>('GET', [['op', 'versionsInside'], ['path', path]])).pages;

  restoreVersions = async (choices: Array<[string, string]>) =>
    (await call<{ results: RestoreResult[] }>('POST', [['op', 'restoreVersions'], ...choices.map(([p, v]): [string, string] => ['restore', `${p}|${v}`])])).results;

  treeAt = (path: string, date: string) => call<TreeAtNode>('GET', [['op', 'treeAt'], ['path', path], ['date', date]]);

  restoreTree = (path: string, date: string, preserve: boolean) =>
    call<RestoreTreeResult>('POST', [['op', 'restoreTree'], ['path', path], ['date', date], ['preserve', String(preserve)]]);

  restore = async (path: string, version: string) => {
    await call('POST', [['op', 'restore'], ['path', path], ['version', version]]);
  };

  deletePages = async (paths: string[]) => {
    await call('POST', [['op', 'delete'], ...paths.map((p): [string, string] => ['path', p])]);
  };

  diff = (path: string, from: string, to: string) => call<PageDiff>('GET', [['op', 'diffVersion'], ['path', path], ['from', from], ['to', to]]);

  savedSearches = async () =>
    (await call<{ searches: Array<{ name: string; criteria: string; modified?: string }> }>('GET', [['op', 'searches']])).searches.map(
      (s): SavedSearch => ({ name: s.name, criteria: parseCriteria(s.criteria), modified: s.modified }),
    );

  saveSearch = async (name: string, criteria: FilterCriteria) => {
    const s = await call<{ name: string; criteria: string; modified?: string }>('POST', [
      ['op', 'saveSearch'],
      ['name', name],
      ['criteria', JSON.stringify(criteria)],
    ]);
    return { name: s.name, criteria: parseCriteria(s.criteria), modified: s.modified };
  };

  deleteSearch = async (name: string) => {
    await call('POST', [['op', 'deleteSearch'], ['name', name]]);
  };

  /** studio-launches' own endpoint: the launches of a page */
  launchesOf = async (path: string): Promise<PageLaunch[]> => {
    const res = await fetch(`/bin/studio/launches.json?${new URLSearchParams({ op: 'info', path })}`, { credentials: 'same-origin' });
    if (res.status === 404) return []; // launches not installed
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = (await res.json()) as { launchesOfPage?: Array<Record<string, unknown>> };
    return (body.launchesOfPage ?? [])
      .filter((l) => typeof l.path === 'string' && typeof l.pageInLaunch === 'string')
      .map((l) => ({
        path: String(l.path),
        title: typeof l.title === 'string' && l.title ? l.title : String(l.path).split('/').pop() ?? '',
        pageInLaunch: String(l.pageInLaunch),
        liveDate: typeof l.liveDate === 'string' ? l.liveDate : undefined,
        created: typeof l.created === 'string' ? l.created : undefined,
        createdBy: typeof l.createdBy === 'string' ? l.createdBy : undefined,
        promoted: typeof l.promoted === 'string' ? l.promoted : undefined,
      }));
  };
}

/** Saved criteria as the rail uses them: whatever was stored, with the filters it does not have left empty. */
export function parseCriteria(json: string): FilterCriteria {
  let raw: Record<string, unknown> = {};
  try {
    const v: unknown = JSON.parse(json);
    if (v && typeof v === 'object') raw = v as Record<string, unknown>;
  } catch {
    // an unreadable search is an empty one
  }
  const str = (k: string) => (typeof raw[k] === 'string' ? (raw[k] as string) : '');
  const live = str('liveCopy');
  return {
    path: str('path').startsWith('/') ? str('path') : '/content',
    text: str('text'),
    template: str('template') || null,
    tags: Array.isArray(raw.tags) ? raw.tags.filter((t): t is string => typeof t === 'string') : [],
    modifiedDays: typeof raw.modifiedDays === 'number' && raw.modifiedDays > 0 ? raw.modifiedDays : 0,
    liveCopy: live === 'livecopy' || live === 'notlivecopy' ? live : '',
    locked: raw.locked === true,
    ...propertyFilters(raw),
  };
}

/** The filters on page properties of saved criteria, only when there are some. */
function propertyFilters(raw: Record<string, unknown>): Pick<FilterCriteria, 'meta' | 'metaText'> {
  const out: Pick<FilterCriteria, 'meta' | 'metaText'> = {};
  if (raw.meta && typeof raw.meta === 'object') {
    const meta: Record<string, string[]> = {};
    for (const [k, v] of Object.entries(raw.meta as Record<string, unknown>)) {
      const vs = Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
      if (vs.length) meta[k] = vs;
    }
    if (Object.keys(meta).length) out.meta = meta;
  }
  if (raw.metaText && typeof raw.metaText === 'object') {
    const metaText: Record<string, string> = {};
    for (const [k, v] of Object.entries(raw.metaText as Record<string, unknown>)) if (typeof v === 'string' && v.trim()) metaText[k] = v;
    if (Object.keys(metaText).length) out.metaText = metaText;
  }
  return out;
}

/** The property filters as a stable text (empty ones left out), for comparing criteria. */
function propertyKey(c: FilterCriteria): string {
  const meta = Object.entries(c.meta ?? {})
    .filter(([, vs]) => vs.length)
    .map(([k, vs]) => `${k}=${[...vs].sort().join('|')}`)
    .sort();
  const text = Object.entries(c.metaText ?? {})
    .filter(([, t]) => t.trim())
    .map(([k, t]) => `${k}~${t.trim()}`)
    .sort();
  return [...meta, ...text].join('&');
}

/** Whether two criteria filter the same way (the saved search shown as chosen). */
export function sameCriteria(a: FilterCriteria, b: FilterCriteria): boolean {
  const norm = (c: FilterCriteria) =>
    JSON.stringify([c.path, c.text.trim(), c.template ?? '', [...c.tags].sort(), c.modifiedDays, c.liveCopy, c.locked, propertyKey(c)]);
  return norm(a) === norm(b);
}

/** Rich text as plain text, for comparing what a reader sees. */
export function plainText(value: string | undefined): string {
  if (!value) return '';
  if (!/[<&]/.test(value)) return value;
  const doc = new DOMParser().parseFromString(value, 'text/html');
  return (doc.body.textContent ?? '').replace(/\s+/g, ' ').trim();
}

export interface DiffPart {
  type: 'same' | 'added' | 'removed';
  text: string;
}

/**
 * What changed inside a text, for showing it: sentences that stayed the same anchor the comparison, and only the
 * stretches between them are compared word by word — so a paragraph with one sentence rewritten shows the rest as it
 * was, and a long text with a small change still gets its words marked.
 */
export function diffWords(before: string, after: string): DiffPart[] {
  if (before === after) return before ? [{ type: 'same', text: before }] : [];
  const a = sentences(before);
  const b = sentences(after);
  if (a.length < 2 && b.length < 2) return wordDiff(before, after);
  const key = (x: string) => x.trim();
  const n = a.length;
  const m = b.length;
  if (n * m > 250_000) return wordDiff(before, after);
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = key(a[i]) === key(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffPart[] = [];
  const push = (p: DiffPart) => {
    const last = out[out.length - 1];
    if (last && last.type === p.type) last.text += p.text;
    else if (p.text) out.push({ ...p });
  };
  let ra = '';
  let rb = '';
  const flush = () => {
    if (ra || rb) for (const p of wordDiff(ra, rb)) push(p);
    ra = '';
    rb = '';
  };
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (key(a[i]) === key(b[j])) {
      flush();
      push({ type: 'same', text: b[j] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      ra += a[i++];
    } else {
      rb += b[j++];
    }
  }
  while (i < n) ra += a[i++];
  while (j < m) rb += b[j++];
  flush();
  return out;
}

/** A text's sentences, each with the space after it (so they put back together exactly). */
export function sentences(text: string): string[] {
  // a sentence ends with . ! or ? before a space or the end ("1.2" and "e-mail.com" do not end one)
  return (text.match(/[\s\S]*?(?:[.!?]+(?:\s+|$)|$)/g) ?? []).filter(Boolean);
}

/** A word diff (longest common subsequence). */
function wordDiff(before: string, after: string): DiffPart[] {
  // words keep their trailing space, so the parts read naturally when put back together
  const a = before.match(/\s*\S+\s*/g) ?? [];
  const b = after.match(/\s*\S+\s*/g) ?? [];
  const whole = (): DiffPart[] =>
    [
      { type: 'removed' as const, text: before },
      { type: 'added' as const, text: after },
    ].filter((p) => p.text);
  if (before === after) return before ? [{ type: 'same', text: before }] : [];
  // long texts: compare as a whole rather than spend quadratic time
  if (a.length * b.length > 250_000) return whole();
  const key = (w: string) => w.trim();
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = key(a[i]) === key(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  // a rewrite with a few common words reads better as "this text became that text"
  if (lcs[0][0] * 2.5 < Math.max(a.length, b.length)) return whole();
  const out: DiffPart[] = [];
  const push = (type: DiffPart['type'], text: string) => {
    const last = out[out.length - 1];
    if (last && last.type === type) last.text += text;
    else out.push({ type, text });
  };
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (key(a[i]) === key(b[j])) {
      push('same', b[j]);
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      push('removed', a[i++]);
    } else {
      push('added', b[j++]);
    }
  }
  while (i < a.length) push('removed', a[i++]);
  while (j < b.length) push('added', b[j++]);
  return out;
}

/** "1.2 · Before release" */
export function versionLabel(v: PageVersion): string {
  return v.label ? `${v.name} · ${v.label}` : v.name;
}

/** `/content/cq:tags/wknd/activity/surfing` → `wknd:activity/surfing` (a namespace → `wknd:`). */
export function tagIdOf(path: string): string {
  const rest = path.replace(/^\/content\/cq:tags\/?/, '');
  const slash = rest.indexOf('/');
  return slash < 0 ? `${rest}:` : `${rest.slice(0, slash)}:${rest.slice(slash + 1)}`;
}

/** "just now", "5 minutes ago", "yesterday", "12 days ago", then the date. */
export function ago(iso: string, now = Date.now()): string {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const s = Math.round((now - t) / 1000);
  if (s < 60) return 'just now';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} minute${m === 1 ? '' : 's'} ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} hour${h === 1 ? '' : 's'} ago`;
  const d = Math.round(h / 24);
  if (d === 1) return 'yesterday';
  if (d < 30) return `${d} days ago`;
  return new Date(t).toLocaleDateString(undefined, { dateStyle: 'medium' });
}

/** The CSV report of the filter results. */
export function resultsCsv(items: SearchResults['items']): string {
  const cell = (v: unknown) => {
    const s = v == null ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const rows: unknown[][] = [['Title', 'Path', 'Template', 'Modified', 'Modified By', 'Tags', 'Locked By', 'Linked copy']];
  for (const i of items) {
    rows.push([i.title, i.path, i.templateTitle ?? i.template ?? '', i.lastModified ?? '', i.lastModifiedBy ?? '', (i.tags ?? []).join(' '), i.lockOwner ?? '', i.liveCopy ? 'yes' : '']);
  }
  return `${rows.map((r) => r.map(cell).join(',')).join('\n')}\n`;
}

export function emptyCriteria(path: string): FilterCriteria {
  return { path, text: '', template: null, tags: [], modifiedDays: 0, liveCopy: '', locked: false };
}

/** Whether any filter is set (only then the results replace the console's view). */
export function isFiltering(c: FilterCriteria): boolean {
  return !!(c.text.trim() || c.template || c.tags.length || c.modifiedDays || c.liveCopy || c.locked || propertyKey(c));
}

const RAIL_KEY = 'studio.sites.rail';

export function readRail(): RailId {
  try {
    const v = window.localStorage.getItem(RAIL_KEY);
    return v && RAILS.some((r) => r.id === v) ? (v as RailId) : 'content';
  } catch {
    return 'content';
  }
}

export function storeRail(rail: RailId) {
  try {
    window.localStorage.setItem(RAIL_KEY, rail);
  } catch {
    /* per-viewer convenience only */
  }
}

/** The path and its ancestors below /content, outermost first (what the content tree expands). */
export function ancestorsOf(path: string, root = '/content'): string[] {
  if (!path.startsWith(`${root}/`)) return [];
  const out: string[] = [];
  let p = root;
  for (const seg of path.slice(root.length + 1).split('/')) {
    p = `${p}/${seg}`;
    out.push(p);
  }
  return out;
}
