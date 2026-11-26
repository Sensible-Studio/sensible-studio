/** Targeting and ContextHub (`/bin/studio/targeting.json`): audiences, personas, targeted components. */

/** A comparison of a ContextHub store property (`profile/age`) with a value or another property. */
export interface Comparison {
  type: 'comparison';
  property: string;
  operator: string;
  value?: string;
  otherProperty?: string;
  dataType?: 'string' | 'number' | 'boolean';
}

export interface Logic {
  type: 'and' | 'or';
  items: Expression[];
}

export type Expression = Logic | Comparison;

export interface Segment {
  path: string;
  name: string;
  title: string;
  description?: string;
  boost: number;
  expression: Expression | null;
  conf: string;
}

export interface Persona {
  id: string;
  path: string;
  title: string;
  description?: string;
  stores: Record<string, Record<string, unknown>>;
}

export interface Experience {
  name: string;
  path: string;
  title: string;
  segment?: string;
  type?: string;
  /** in an A/B test: its share of the visitors, in percent */
  share?: number;
}

/** How a targeted component chooses: by the visitor's audience, or an A/B test that splits the visitors. */
export type TargetMode = 'targeting' | 'abtest';

/** 100% split as evenly as whole numbers allow: 34, 33, 33. */
export function evenShares(names: string[]): Record<string, number> {
  const out: Record<string, number> = {};
  const base = names.length ? Math.floor(100 / names.length) : 0;
  let rest = names.length ? 100 - base * names.length : 0;
  for (const n of names) out[n] = base + (rest-- > 0 ? 1 : 0);
  return out;
}

/** Why these shares cannot be saved, or null: whole numbers 0–100 that add up to 100, two experiences taking part. */
export function sharesProblem(shares: Record<string, number>): string | null {
  const values = Object.values(shares);
  if (values.some((v) => !Number.isInteger(v) || v < 0 || v > 100)) return 'A share is a whole number from 0 to 100';
  const sum = values.reduce((a, b) => a + b, 0);
  if (sum !== 100) return `The shares add up to ${sum}%; they must add up to 100%`;
  if (values.filter((v) => v > 0).length < 2) return 'An A/B test needs at least two experiences with a share';
  return null;
}

export type GoalType = 'none' | 'click' | 'pageview' | 'custom';

/** AEM's "Goal & Settings": what counts as success for a targeted component. */
export interface Goal {
  type: GoalType;
  /** click: a CSS selector (empty: any click in the experience); pageview: a page path; custom: an event name. */
  value: string;
  activity?: string;
}

export interface DayStats {
  day: string;
  impressions: number;
  conversions: number;
}

export interface ExperienceStats {
  impressions: number;
  conversions: number;
  /** Conversions per 100 impressions. */
  rate: number;
  days: DayStats[];
}

export interface TargetInfo {
  path: string;
  targeted: boolean;
  experiences: Experience[];
  goal?: Goal;
  mode?: TargetMode;
  stats?: Record<string, ExperienceStats>;
}

/** One row of the activity report: a targeted component on a page with its counters. */
export interface Activity {
  path: string;
  page: string;
  pageTitle: string;
  type?: string;
  goal: Goal;
  mode?: TargetMode;
  experiences: Experience[];
  stats: Record<string, ExperienceStats>;
}

/** A page-level rule: visitors in the audience are sent to another page. */
export interface Redirect {
  segment: string;
  target: string;
}

export interface SegmentsResult {
  conf: string;
  segments: Segment[];
  operators?: string[];
}

export const OPERATORS: Array<{ id: string; label: string; unary?: boolean }> = [
  { id: 'equals', label: 'equals' },
  { id: 'not-equals', label: 'does not equal' },
  { id: 'contains', label: 'contains' },
  { id: 'not-contains', label: 'does not contain' },
  { id: 'starts-with', label: 'starts with' },
  { id: 'ends-with', label: 'ends with' },
  { id: 'greater-than', label: 'is greater than' },
  { id: 'greater-than-or-equal', label: 'is at least' },
  { id: 'less-than', label: 'is less than' },
  { id: 'less-than-or-equal', label: 'is at most' },
  { id: 'exists', label: 'exists', unary: true },
  { id: 'not-exists', label: 'does not exist', unary: true },
];

/** The store properties ContextHub knows out of the box (authors can type any other). */
export const STORE_PROPERTIES: Array<{ store: string; properties: string[] }> = [
  { store: 'profile', properties: ['givenName', 'familyName', 'gender', 'age', 'interests', 'loyalty', 'children', 'email'] },
  { store: 'geolocation', properties: ['country', 'city', 'latitude', 'longitude'] },
  { store: 'surferinfo', properties: ['device', 'os', 'browser', 'isMobile', 'isTablet', 'isDesktop', 'month', 'year', 'hour', 'weekday'] },
];

/** A ContextHub store of a configuration: one built in (profile, geolocation, surferinfo) or one of its own. */
export interface HubStore {
  name: string;
  title?: string;
  /** profile, geolocation, surferinfo (built in); generic (values it starts with); json (the answer of an address);
   *  other: a store of AEM's that Sensible Studio keeps but does not run (segmentation, emulators…) */
  type: 'profile' | 'geolocation' | 'surferinfo' | 'generic' | 'json' | 'other';
  /** AEM's store type, such as granite.profile */
  storeType?: string;
  /** added by Sensible Studio (Reset takes it away) */
  managed?: boolean;
  enabled: boolean;
  /** the values the store starts with */
  initial: Record<string, unknown>;
  /** json: the address read in the visitor's browser */
  url?: string;
  /** json: read again after this many minutes */
  refresh?: number;
  /** json: the part of the answer to keep (a dotted name) */
  select?: string;
}

/** A field of the ContextHub bar (AEM's UI module). */
export interface HubModule {
  title: string;
  store: string;
  property: string;
  type: 'text' | 'number' | 'select';
  options?: string[];
}

/** A module of one of AEM's UI modes. */
export interface HubModeItem {
  name: string;
  title: string;
  /** AEM's `moduleType`: granite.profile, contexthub.location, contexthub.device… */
  type: string;
  enabled?: boolean;
  storeMapping?: Record<string, string> | null;
  template?: string | null;
}

/** ContextHub's configuration as it applies to a page or a configuration (Tools › ContextHub). */
export interface ContextHubSettings {
  conf: string;
  /** where it was read from; null: the default */
  from: string | null;
  /** the configuration has its own (otherwise /conf/global's or the default applies) */
  custom: boolean;
  /** Sensible Studio made it (Reset removes it); otherwise it came from AEM (WKND's) and Reset undoes only the changes */
  madeHere?: boolean;
  /** AEM's own UI modes, kept as they are; the ContextHub bar in the page shows their modules */
  modes?: Array<{ name: string; title: string; modules: string[]; items?: HubModeItem[] }>;
  canEdit?: boolean;
  modified?: string | null;
  modifiedBy?: string | null;
  stores: HubStore[];
  modules: HubModule[];
  /** store/property names it knows: the bar's fields and the values stores start with */
  properties: string[];
}

export interface TargetingClient {
  segments(path: string): Promise<SegmentsResult>;
  personas(path: string): Promise<Persona[]>;
  target(path: string): Promise<TargetInfo>;
  saveSegment(s: { conf: string; name: string; title: string; description?: string; boost?: number; expression: Expression | null }): Promise<SegmentsResult & { segment: Segment }>;
  removeSegment(path: string): Promise<SegmentsResult>;
  targetComponent(path: string): Promise<TargetInfo>;
  untarget(path: string, keep?: string): Promise<TargetInfo>;
  addExperience(path: string, e: { name: string; title: string; segment?: string; from?: string }): Promise<TargetInfo>;
  updateExperience(path: string, e: { name: string; title?: string; segment?: string }): Promise<TargetInfo>;
  removeExperience(path: string, name: string): Promise<TargetInfo>;
  setGoal(path: string, goal: Goal): Promise<TargetInfo>;
  /** by audience, or an A/B test with these shares (experience name → percent, adding up to 100) */
  setSplit(path: string, mode: TargetMode, shares: Record<string, number>): Promise<TargetInfo>;
  resetStats(path: string): Promise<void>;
  activities(root: string): Promise<Activity[]>;
  redirects(pagePath: string): Promise<Redirect[]>;
  setRedirects(pagePath: string, redirects: Redirect[]): Promise<Redirect[]>;
  /** ContextHub's configuration for a page or a configuration */
  contexthub(path: string): Promise<ContextHubSettings>;
  saveContexthub(conf: string, settings: { stores: HubStore[]; modules: HubModule[] }): Promise<ContextHubSettings>;
  /** back to /conf/global's or the default */
  resetContexthub(conf: string): Promise<ContextHubSettings>;
}

const ENDPOINT = '/bin/studio/targeting.json';

export class HttpTargetingClient implements TargetingClient {
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

  segments(path: string) {
    return this.get<SegmentsResult>({ op: 'segments', path });
  }

  contexthub(path: string) {
    return this.get<ContextHubSettings>({ op: 'contexthub', path });
  }

  saveContexthub(conf: string, settings: { stores: HubStore[]; modules: HubModule[] }) {
    return this.post<ContextHubSettings>({ op: 'saveContexthub', conf, config: JSON.stringify(settings) });
  }

  resetContexthub(conf: string) {
    return this.post<ContextHubSettings>({ op: 'resetContexthub', conf });
  }

  async personas(path: string) {
    return (await this.get<{ personas: Persona[] }>({ op: 'personas', path })).personas;
  }

  target(path: string) {
    return this.get<TargetInfo>({ op: 'target', path });
  }

  saveSegment(s: { conf: string; name: string; title: string; description?: string; boost?: number; expression: Expression | null }) {
    return this.post<SegmentsResult & { segment: Segment }>({
      op: 'saveSegment',
      conf: s.conf,
      name: s.name,
      title: s.title,
      description: s.description ?? '',
      boost: s.boost === undefined ? undefined : String(s.boost),
      expression: s.expression ? JSON.stringify(s.expression) : 'null',
    });
  }

  removeSegment(path: string) {
    return this.post<SegmentsResult>({ op: 'removeSegment', path });
  }

  targetComponent(path: string) {
    return this.post<TargetInfo>({ op: 'target', path });
  }

  untarget(path: string, keep?: string) {
    return this.post<TargetInfo>({ op: 'untarget', path, keep });
  }

  addExperience(path: string, e: { name: string; title: string; segment?: string; from?: string }) {
    return this.post<TargetInfo>({ op: 'addExperience', path, name: e.name, title: e.title, segment: e.segment, from: e.from });
  }

  updateExperience(path: string, e: { name: string; title?: string; segment?: string }) {
    return this.post<TargetInfo>({ op: 'updateExperience', path, name: e.name, title: e.title, segment: e.segment });
  }

  removeExperience(path: string, name: string) {
    return this.post<TargetInfo>({ op: 'removeExperience', path, name });
  }

  setGoal(path: string, goal: Goal) {
    return this.post<TargetInfo>({ op: 'setGoal', path, goalType: goal.type, goalValue: goal.value, activity: goal.activity ?? '' });
  }

  setSplit(path: string, mode: TargetMode, shares: Record<string, number>) {
    return this.post<TargetInfo>({ op: 'setSplit', path, mode, shares: JSON.stringify(shares) });
  }

  async resetStats(path: string) {
    await this.post<unknown>({ op: 'resetStats', path });
  }

  async activities(root: string) {
    return (await this.get<{ activities: Activity[] }>({ op: 'activities', path: root })).activities;
  }

  async redirects(pagePath: string) {
    return (await this.get<{ redirects: Redirect[] }>({ op: 'redirects', path: pagePath })).redirects ?? [];
  }

  async setRedirects(pagePath: string, redirects: Redirect[]) {
    return (await this.post<{ redirects: Redirect[] }>({ op: 'setRedirects', path: pagePath, redirects: JSON.stringify(redirects) })).redirects ?? [];
  }
}

export const GOAL_TYPES: Array<{ id: GoalType; label: string; hint: string }> = [
  { id: 'none', label: 'No goal', hint: 'Only impressions are counted.' },
  { id: 'click', label: 'Clicked', hint: 'A click in the shown experience; a CSS selector narrows it to one element (e.g. a.cmp-button).' },
  { id: 'pageview', label: 'Viewed page', hint: 'The visitor reaches this page later in the same visit.' },
  { id: 'custom', label: 'Custom event', hint: 'The site calls StudioContextHub.convert("<name>").' },
];

/** The totals of a target over its experiences. */
export function totals(stats: Record<string, ExperienceStats> | undefined): ExperienceStats {
  const t: ExperienceStats = { impressions: 0, conversions: 0, rate: 0, days: [] };
  for (const s of Object.values(stats ?? {})) {
    t.impressions += s.impressions;
    t.conversions += s.conversions;
  }
  t.rate = t.impressions ? Math.round((10000 * t.conversions) / t.impressions) / 100 : 0;
  return t;
}

/** One line for an expression, for lists: `profile/age is greater than 30 and …`. */
export function describe(e: Expression | null | undefined): string {
  if (!e) return 'Everyone (no conditions: never matches on its own)';
  if (e.type === 'comparison') {
    const op = OPERATORS.find((o) => o.id === e.operator);
    return `${e.property} ${op?.label ?? e.operator}${op?.unary ? '' : ` ${e.otherProperty ?? e.value ?? ''}`}`.trim();
  }
  if (!e.items.length) return 'No conditions';
  const inner = e.items.map((i) => (i.type === 'comparison' ? describe(i) : `(${describe(i)})`));
  return inner.join(e.type === 'and' ? ' and ' : ' or ');
}

/** A segment page name from a title: `Summer visitors` → `summer-visitors`. */
export function segmentName(title: string): string {
  return title
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
}

/** The ContextHub stores as the page's client script keeps them (same origin: the editor reads the same localStorage). */
export const CONTEXTHUB_KEY = 'studio.contexthub';

export interface Stores {
  _persona?: string | null;
  [store: string]: Record<string, unknown> | string | null | undefined;
}

export function readStores(): Stores {
  try {
    return (JSON.parse(window.localStorage.getItem(CONTEXTHUB_KEY) || '{}') as Stores) ?? {};
  } catch {
    return {};
  }
}

export function writeStores(stores: Stores): void {
  try {
    window.localStorage.setItem(CONTEXTHUB_KEY, JSON.stringify(stores));
  } catch {
    // a private window: the frame keeps what it has
  }
}

/** The devices of AEM's Device Emulator module, as the page's ContextHub script has them. */
export interface HubDevice {
  id: string;
  title: string;
  width?: number;
  height?: number;
  platform?: string;
  platformVersion?: string;
  device?: 'mobile' | 'tablet' | 'desktop';
}

export const HUB_DEVICES: HubDevice[] = [
  { id: 'native', title: 'This browser' },
  { id: 'iphone', title: 'iPhone', width: 390, height: 844, platform: 'iOS', platformVersion: '17', device: 'mobile' },
  { id: 'android', title: 'Android phone', width: 412, height: 915, platform: 'Android', platformVersion: '14', device: 'mobile' },
  { id: 'ipad', title: 'iPad', width: 820, height: 1180, platform: 'iOS', platformVersion: '17', device: 'tablet' },
  { id: 'desktop', title: 'Desktop', width: 1920, height: 1080, platform: 'Windows', platformVersion: '11', device: 'desktop' },
];

/** The device the stores emulate: its id, orientation and size; `native` when none. */
export function emulatedDevice(stores: Stores): { id: string; orientation: 'portrait' | 'landscape'; width?: number; height?: number } {
  const e = stores.emulators as { currentDevice?: { id?: string; orientation?: string; width?: number; height?: number } } | null | undefined;
  const d = e && typeof e === 'object' ? e.currentDevice : undefined;
  if (!d?.id) return { id: 'native', orientation: 'portrait' };
  return { id: d.id, orientation: d.orientation === 'landscape' ? 'landscape' : 'portrait', width: d.width, height: d.height };
}

/**
 * The stores with a device emulated, as the page's script does it (`StudioContextHub.emulate`): the
 * `emulators` store AEM's modules read and what audiences see of the device in `surferinfo`. `native`
 * removes both, so the page reads the real browser again.
 */
export function emulateDevice(stores: Stores, id: string, orientation: 'portrait' | 'landscape'): Stores {
  const d = HUB_DEVICES.find((x) => x.id === id);
  const next: Stores = { ...stores };
  const info = { ...((stores.surferinfo && typeof stores.surferinfo === 'object' ? stores.surferinfo : {}) as Record<string, unknown>) };
  if (!d || d.id === 'native' || !d.width || !d.height) {
    delete next.emulators;
    for (const k of ['device', 'os', 'isMobile', 'isTablet', 'isDesktop']) delete info[k];
    next.surferinfo = info;
    return next;
  }
  const land = d.device === 'desktop' || orientation === 'landscape';
  next.emulators = {
    currentDevice: {
      id: d.id,
      title: d.title,
      width: land ? Math.max(d.width, d.height) : d.width,
      height: land ? Math.min(d.width, d.height) : d.height,
      platform: d.platform,
      platformVersion: d.platformVersion,
      orientation: land ? 'landscape' : 'portrait',
    },
  };
  next.surferinfo = { ...info, device: d.device, os: d.platform, isMobile: d.device === 'mobile', isTablet: d.device === 'tablet', isDesktop: d.device === 'desktop' };
  return next;
}

/** The tags of AEM's tagcloud store (`{tags: {name: count}}` or a list), the most seen first, ten at most. */
export function tagNames(store: unknown): string[] {
  const tags = store && typeof store === 'object' ? ((store as { tags?: unknown }).tags ?? store) : null;
  if (!tags || typeof tags !== 'object') return [];
  const names = Array.isArray(tags)
    ? tags.map((t) => (t && typeof t === 'object' ? ((t as { name?: unknown; id?: unknown }).name ?? (t as { id?: unknown }).id) : t))
    : Object.keys(tags).sort((a, b) => (parseFloat(String((tags as Record<string, unknown>)[b])) || 0) - (parseFloat(String((tags as Record<string, unknown>)[a])) || 0));
  return names.filter(Boolean).map(String).slice(0, 10);
}
