import type {
  BlueprintInfo,
  BlueprintStructure,
  CreateLiveCopy,
  CreateSite,
  InheritanceOp,
  LiveCopyConfigChange,
  MsmStatus,
  Overview,
  PageRevision,
  RolloutConfigInfo,
  RolloutResult,
} from './types';

/** What the author UI needs from Sensible Studio's MSM endpoint. */
export interface MsmClient {
  status(path: string): Promise<MsmStatus>;
  overview(blueprintPage: string): Promise<Overview>;
  configs(): Promise<RolloutConfigInfo[]>;
  /** Rolls a blueprint page out to (some of) its live copies. */
  rollout(path: string, options: { deep: boolean; targets?: string[] }): Promise<RolloutResult>;
  /** Synchronises a live copy page or component from its blueprint (`reset`: re-enable cancelled inheritance first). */
  sync(path: string, options: { deep?: boolean; reset?: boolean }): Promise<RolloutResult>;
  /** Cancel / re-enable a component, suspend / resume a page, detach. Returns the new status. */
  inheritance(path: string, op: InheritanceOp, options?: { deep?: boolean }): Promise<MsmStatus | undefined>;
  /** Cancels or re-enables inheritance of properties (or child nodes) of `path`. */
  propertyInheritance(path: string, names: string[], cancel: boolean): Promise<MsmStatus | undefined>;
  createLiveCopy(request: CreateLiveCopy): Promise<{ path: string; result: RolloutResult }>;
  /** Saved versions of a page, newest first. */
  revisions(page: string): Promise<PageRevision[]>;
  /** Restores a version (the current state is saved as a version first). */
  restore(page: string, revision: string): Promise<PageRevision[]>;
  blueprints(): Promise<BlueprintInfo[]>;
  blueprint(path: string): Promise<BlueprintStructure>;
  /** The languages and chapters of a site that has no blueprint configuration yet. */
  siteStructure(sitePath: string): Promise<BlueprintStructure>;
  createSite(request: CreateSite): Promise<{ path: string; result: RolloutResult }>;
  createBlueprint(request: { name: string; title: string; description?: string; sitePath: string }): Promise<{ path: string }>;
  /** Changes a blueprint configuration's title, description and source (Blueprint Properties). */
  updateBlueprint(path: string, request: { title: string; description?: string; sitePath: string }): Promise<BlueprintInfo>;
  /** Removes a blueprint configuration; the sites made from it stay. */
  deleteBlueprint(path: string): Promise<void>;
  /** Saves the Live Copy tab of a page's properties. */
  liveCopyConfig(page: string, change: LiveCopyConfigChange): Promise<MsmStatus | undefined>;
  /** Saves the rollout configurations a blueprint page gives its live copies (empty: none of its own). */
  blueprintConfig(page: string, rolloutConfigs: string[]): Promise<MsmStatus | undefined>;
}

export class MsmError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const FORM = 'application/x-www-form-urlencoded;charset=UTF-8';
const ENDPOINT = '/bin/studio/msm.json';

/** {@link MsmClient} over HTTP (same origin; the dev server proxies to Sensible Studio). */
export class HttpMsmClient implements MsmClient {
  constructor(private readonly base = '') {}

  status(path: string) {
    return this.get<MsmStatus>({ op: 'status', path }, `Sync status of ${path}`);
  }

  overview(blueprintPage: string) {
    return this.get<Overview>({ op: 'overview', path: blueprintPage }, `Linked copies of ${blueprintPage}`);
  }

  async configs() {
    return (await this.get<{ configs: RolloutConfigInfo[] }>({ op: 'configs' }, 'Sync changes configurations')).configs;
  }

  async rollout(path: string, options: { deep: boolean; targets?: string[] }) {
    const body = new URLSearchParams({ op: 'rollout', path, deep: String(options.deep) });
    for (const t of options.targets ?? []) body.append('target', t);
    return (await this.post<{ result: RolloutResult }>(body)).result;
  }

  async sync(path: string, options: { deep?: boolean; reset?: boolean }) {
    const body = new URLSearchParams({ op: 'sync', path, deep: String(!!options.deep), reset: String(!!options.reset) });
    return (await this.post<{ result: RolloutResult }>(body)).result;
  }

  async inheritance(path: string, op: InheritanceOp, options: { deep?: boolean } = {}) {
    const body = new URLSearchParams({ op, path });
    if (options.deep !== undefined) body.set('deep', String(options.deep));
    return (await this.post<{ status?: MsmStatus }>(body)).status;
  }

  async propertyInheritance(path: string, names: string[], cancel: boolean) {
    const body = new URLSearchParams({ op: cancel ? 'cancelProperty' : 'reenableProperty', path });
    for (const n of names) body.append('name', n);
    return (await this.post<{ status?: MsmStatus }>(body)).status;
  }

  createLiveCopy(r: CreateLiveCopy) {
    const body = new URLSearchParams({ op: 'create', source: r.source, parent: r.parent, name: r.name, deep: String(r.deep) });
    if (r.title) body.set('title', r.title);
    for (const c of r.rolloutConfigs) body.append('rolloutConfig', c);
    for (const e of r.exclude ?? []) body.append('exclude', e);
    return this.post<{ path: string; result: RolloutResult }>(body);
  }

  async revisions(page: string) {
    return (await this.get<{ revisions: PageRevision[] }>({ op: 'revisions', path: page }, `Versions of ${page}`)).revisions;
  }

  async restore(page: string, revision: string) {
    return (await this.post<{ revisions: PageRevision[] }>(new URLSearchParams({ op: 'restore', path: page, revision }))).revisions;
  }

  async blueprints() {
    return (await this.get<{ blueprints: BlueprintInfo[] }>({ op: 'blueprints' }, 'Blueprints')).blueprints;
  }

  blueprint(path: string) {
    return this.get<BlueprintStructure>({ op: 'blueprint', path }, `Source ${path}`);
  }

  siteStructure(sitePath: string) {
    return this.get<BlueprintStructure>({ op: 'blueprint', sitePath }, `Site ${sitePath}`);
  }

  createSite(r: CreateSite) {
    const body = new URLSearchParams({ op: 'createSite', parent: r.parent, title: r.title, liveCopy: String(r.liveCopy) });
    if (r.blueprint) body.set('blueprint', r.blueprint);
    if (r.newBlueprint) {
      body.set('newBlueprintTitle', r.newBlueprint.title);
      body.set('newBlueprintSite', r.newBlueprint.sitePath);
    }
    if (r.name) body.set('name', r.name);
    if (r.owner) body.set('owner', r.owner);
    for (const l of r.languages) body.append('language', l);
    for (const c of r.chapters) body.append('chapter', c);
    for (const c of r.rolloutConfigs) body.append('rolloutConfig', c);
    return this.post<{ path: string; result: RolloutResult }>(body);
  }

  createBlueprint(r: { name: string; title: string; description?: string; sitePath: string }) {
    const body = new URLSearchParams({ op: 'createBlueprint', name: r.name, title: r.title, sitePath: r.sitePath });
    if (r.description) body.set('description', r.description);
    return this.post<{ path: string }>(body);
  }

  async updateBlueprint(path: string, r: { title: string; description?: string; sitePath: string }) {
    const body = new URLSearchParams({ op: 'updateBlueprint', blueprint: path, title: r.title, sitePath: r.sitePath, description: r.description ?? '' });
    return (await this.post<{ blueprint: BlueprintInfo }>(body)).blueprint;
  }

  async deleteBlueprint(path: string) {
    await this.post(new URLSearchParams({ op: 'deleteBlueprint', blueprint: path }));
  }

  async liveCopyConfig(page: string, change: LiveCopyConfigChange) {
    const body = new URLSearchParams({ op: 'liveCopyConfig', path: page, inherit: String(change.rolloutConfigs === null) });
    for (const c of change.rolloutConfigs ?? []) body.append('rolloutConfig', c);
    if (change.deep !== undefined) body.set('deep', String(change.deep));
    if (change.excludedPaths !== undefined) {
      body.set('writeExcluded', 'true');
      for (const e of change.excludedPaths) body.append('excludedPath', e);
    }
    return (await this.post<{ status?: MsmStatus }>(body)).status;
  }

  async blueprintConfig(page: string, rolloutConfigs: string[]) {
    const body = new URLSearchParams({ op: 'blueprintConfig', path: page });
    for (const c of rolloutConfigs) body.append('rolloutConfig', c);
    return (await this.post<{ status?: MsmStatus }>(body)).status;
  }

  private async get<T>(params: Record<string, string>, what: string): Promise<T> {
    const res = await fetch(`${this.base}${ENDPOINT}?${new URLSearchParams(params)}`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) throw new MsmError(res.status, `${what}: HTTP ${res.status}`);
    return (await res.json()) as T;
  }

  private async post<T>(body: URLSearchParams): Promise<T> {
    const res = await fetch(`${this.base}${ENDPOINT}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': FORM, Accept: 'application/json' },
      body,
    });
    const json = (await res.json().catch(() => ({}))) as T & { error?: string };
    if (!res.ok) throw new MsmError(res.status, json.error || `HTTP ${res.status}`);
    return json;
  }
}

/** One line summing up a rollout, for a toast. */
export function describeResult(r: RolloutResult): string {
  const parts: string[] = [];
  const n = (k: number, one: string, many = `${one}s`) => `${k} ${k === 1 ? one : many}`;
  if (r.updated.length) parts.push(`${n(r.updated.length, 'page')} updated`);
  if (r.created.length) parts.push(`${n(r.created.length, 'page')} created`);
  if (r.deleted.length) parts.push(`${n(r.deleted.length, 'page')} deleted`);
  if (r.skipped.length) parts.push(`${n(r.skipped.length, 'page')} skipped (suspended or excluded)`);
  if (r.conflicts.length) parts.push(`${n(r.conflicts.length, 'conflict')} with local pages`);
  if (r.renamed.length) parts.push(`${n(r.renamed.length, 'local page')} renamed`);
  if (r.versioned?.length) parts.push(`${n(r.versioned.length, 'version')} saved`);
  if (r.unknownActions.length) parts.push(`unknown actions skipped: ${r.unknownActions.join(', ')}`);
  return parts.length ? parts.join(', ') : 'Nothing to sync';
}

/** Human label of a relationship state. */
export function stateLabel(s: string): string {
  switch (s) {
    case 'synced':
      return 'Inherited';
    case 'cancelled':
      return 'Inheritance cancelled';
    case 'suspended':
      return 'Suspended';
    case 'sourceMissing':
      return 'Source page missing';
    case 'missing':
      return 'Not synced yet';
    case 'local':
      return 'Local (not from the source)';
    case 'excluded':
      return 'Excluded';
    default:
      return s;
  }
}
