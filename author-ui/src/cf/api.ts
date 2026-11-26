/**
 * Client of the content fragment API served by the studio-cfm bundle (`/bin/studio/cf.json`).
 * The storage stays AEM's (dam:Asset + jcr:content/data/<variation>), so pages and Core Components keep working.
 */

export type FieldKind =
  | 'text'
  | 'longtext'
  | 'number'
  | 'boolean'
  | 'date'
  | 'enumeration'
  | 'tags'
  | 'content-reference'
  | 'fragment-reference'
  | 'json'
  | 'tab'
  | 'unknown';

export interface CfOption {
  value: string;
  label: string;
}

/** One element of a content fragment model, as the editor needs it. */
export interface CfField {
  /** Node name of the model item (stable across saves); absent for fields not saved yet. */
  id?: string;
  /** Translation projects translate the element (text elements). */
  translatable?: boolean;
  name: string;
  kind: FieldKind;
  metaType?: string;
  /** JCR value type of the model: string, string[], long, double, boolean, calendar/datetime, string/reference ... */
  valueType: string;
  label: string;
  description?: string;
  emptyText?: string;
  required: boolean;
  multiple: boolean;
  defaultValue?: unknown;
  maxLength?: number;
  step?: string;
  /** Default content type of long text: text/html, text/x-markdown, text/plain. */
  mimeType?: string;
  rootPath?: string;
  /** date, datetime or time. */
  dateType?: string;
  /** Resource type of the model widget (e.g. the multi-checkbox variant of enumerations). */
  displayAs?: string;
  allowedModels?: string[];
  options?: CfOption[];
  validation?: { regex?: string; message?: string; min?: number; max?: number; type?: string };
}

export interface CfModelSummary {
  path: string;
  title: string;
  description: string;
  conf: string | null;
  enabled: boolean;
}

export interface CfModel extends CfModelSummary {
  fields: CfField[];
}

/** A version of a fragment (a JCR version of its asset). */
export interface CfVersion {
  /** JCR version name, e.g. 1.0 */
  name: string;
  created: string;
  createdBy?: string | null;
  labels: string[];
  comment?: string | null;
  title?: string | null;
}

/** A configuration (`/conf/<name>`) models can live in. */
export interface CfConf {
  path: string;
  title: string;
  models: number;
}

/** A model write; a 422 answer (CfValidationError) names fields as `title` or `fields.<index>.<property>`. */
export interface ModelSaveRequest {
  path: string;
  title: string;
  description: string;
  fields: CfField[];
}

export interface CfSummary {
  path: string;
  name: string;
  title: string;
  description: string;
  model: string | null;
  modelTitle: string | null;
  modified: string | null;
}

export interface CfFolderRef {
  name: string;
  path: string;
  title: string;
  /** Whether it holds a subfolder or a fragment (older servers do not say). */
  hasChildren?: boolean;
}

export interface CfFolder {
  path: string;
  title: string;
  folders: CfFolderRef[];
  fragments: CfSummary[];
}

export interface CfSearchResult {
  total: number;
  truncated: boolean;
  fragments: CfSummary[];
}

export type CfValue = string | number | boolean | null | Array<string | number | boolean>;

export interface CfVariation {
  name: string;
  title: string;
  description?: string;
}

export interface CfFragment extends CfSummary {
  variations: CfVariation[];
  /** variation -> element -> value */
  values: Record<string, Record<string, CfValue>>;
  /** variation -> long text element -> content type */
  contentTypes: Record<string, Record<string, string>>;
  tags: string[];
  modifiedBy?: string | null;
}

export interface SaveRequest {
  path: string;
  variation: string;
  /** Only the elements to change; null removes a value. */
  values: Record<string, CfValue>;
  contentTypes?: Record<string, string>;
  title?: string;
  description?: string;
  tags?: string[];
}

export interface DamItem {
  name: string;
  path: string;
  title: string;
  kind: 'folder' | 'asset' | 'fragment';
}

export interface FieldError {
  field: string;
  message: string;
}

/** The server refused a save because of invalid values (HTTP 422). */
export class CfValidationError extends Error {
  constructor(readonly errors: FieldError[]) {
    super(`${errors.length} field${errors.length === 1 ? '' : 's'} need attention`);
  }
}

/** A failure of a service behind the API, with its code (e.g. a translation connector: MISSING_CREDENTIALS). */
export class CfServiceError extends Error {
  constructor(
    message: string,
    readonly code: string,
  ) {
    super(message);
  }
}

/** A language next to the fragment's language root. */
export interface CfLanguage {
  code: string;
  name: string;
  root: string;
}

/** The translation record of a language copy. */
export interface CfTranslationInfo {
  path: string;
  language?: string;
  sourcePath?: string | null;
  sourceLanguage?: string | null;
  targetLanguage?: string | null;
  /** TRANSLATED, APPROVED, ERROR_UPDATE */
  status: string | null;
  provider?: string | null;
  attribution?: string | null;
  translatedAt?: string | null;
  translatedBy?: string | null;
  approvedAt?: string | null;
  approvedBy?: string | null;
  failed?: string[];
  /** Source texts changed since the last translation (segment ids like master/description). */
  outdated?: string[];
  sourceMissing?: boolean;
}

/** `op=languages`: the language root of a path, the languages next to it and the fragment's copies. */
export interface CfLanguages {
  path: string;
  languageRoot: string | null;
  language?: string;
  languages?: CfLanguage[];
  copies?: CfTranslationInfo[];
  /** set when the path is itself a language copy */
  translation?: CfTranslationInfo;
}

export interface CfTranslationProvider {
  /** A connector name (studio-ai …) or a translation cloud configuration's path. */
  name: string;
  label: string;
  available: boolean;
  default: boolean;
  problem?: string;
  /** The connector's error code when it is not available, e.g. MISSING_CREDENTIALS. */
  code?: string;
  /** A translation cloud configuration (Tools → Cloud Services → Translation) and its folder. */
  cloudConfig?: boolean;
  conf?: string;
}

export interface CfTranslateRequest {
  paths: string[];
  languages: string[];
  provider?: string;
  includeReferences?: boolean;
  /** translate every text again, also unchanged ones */
  force?: boolean;
}

export interface CfTranslateResult {
  source: string;
  path: string;
  language: string;
  created: boolean;
  translated: number;
  unchanged: number;
  failed: string[];
  status: string;
  attribution?: string;
}

/** A page, experience fragment, fragment or asset linked with a fragment (`op=references`). */
export interface CfReference {
  path: string;
  title: string;
  /** page | experience-fragment | fragment | asset | other */
  type: string;
  model?: string;
  /** usedIn: the nodes and properties that name the fragment */
  via?: { node: string; property: string; resourceType: string | null }[];
  /** references: the fragment's elements that point to it */
  elements?: string[];
}

export interface CfReferences {
  path: string;
  usedIn: CfReference[];
  references: CfReference[];
  scanned?: number;
  /** the usedIn search stopped early (node or time budget) */
  truncated?: boolean;
}

export interface CfClient {
  models(): Promise<CfModelSummary[]>;
  model(path: string): Promise<CfModel>;
  folder(path: string): Promise<CfFolder>;
  search(root: string, query: string, model: string | null, offset?: number, limit?: number): Promise<CfSearchResult>;
  fragment(path: string): Promise<CfFragment>;
  save(request: SaveRequest): Promise<CfFragment>;
  create(parent: string, title: string, model: string, name?: string, description?: string): Promise<string>;
  remove(path: string): Promise<void>;
  createVariation(path: string, title: string, name?: string, description?: string): Promise<CfFragment>;
  deleteVariation(path: string, name: string): Promise<CfFragment>;
  /** Languages next to a fragment's (or folder's) language root, and the fragment's language copies. */
  languages(path: string): Promise<CfLanguages>;
  /** What can translate content at `path`: its site's cloud configurations first, then the connectors. */
  translationProviders(path?: string): Promise<CfTranslationProvider[]>;
  /** Creates or updates language copies (CfServiceError with the connector's code when it fails). */
  translate(request: CfTranslateRequest): Promise<CfTranslateResult[]>;
  approveTranslation(path: string): Promise<CfLanguages>;
  /** Configurations models can be created in. */
  confs(): Promise<CfConf[]>;
  createModel(conf: string, title: string, name?: string, description?: string): Promise<string>;
  saveModel(request: ModelSaveRequest): Promise<CfModel>;
  setModelStatus(path: string, enabled: boolean): Promise<CfModel>;
  /** Refused (CfValidationError) while fragments use the model, unless `force`. */
  deleteModel(path: string, force?: boolean): Promise<void>;
  /** Fragments that use a model (up to 200). */
  modelUsage(path: string): Promise<{ fragments: string[]; truncated: boolean }>;
  /** Versions of a fragment, newest first. */
  versions(path: string): Promise<CfVersion[]>;
  /** The fragment as it was in a version (with `version` describing it). */
  versionContent(path: string, version: string): Promise<CfFragment & { version: CfVersion }>;
  createVersion(path: string, label?: string, comment?: string): Promise<CfVersion>;
  /** Restores a version after saving the current state as a version (answered as `saved`). */
  restoreVersion(path: string, version: string): Promise<{ saved: CfVersion; fragment: CfFragment }>;
  /** Where the fragment is used, and what it uses. */
  references(path: string): Promise<CfReferences>;
  /** Folders, assets and fragments directly in a DAM folder (for the reference pickers). */
  listDam(path: string): Promise<DamItem[]>;
  thumbnailUrl(assetPath: string): string;
  currentUser(): Promise<string>;
}

const API = '/bin/studio/cf.json';

export class HttpCfClient implements CfClient {
  constructor(private readonly base = '') {}

  models() {
    return this.get<CfModelSummary[]>({ op: 'models' });
  }

  model(path: string) {
    return this.get<CfModel>({ op: 'model', path });
  }

  folder(path: string) {
    return this.get<CfFolder>({ op: 'folder', path });
  }

  search(root: string, query: string, model: string | null, offset = 0, limit = 100) {
    const params: Record<string, string> = { op: 'search', path: root, q: query, offset: String(offset), limit: String(limit) };
    if (model) params.model = model;
    return this.get<CfSearchResult>(params);
  }

  fragment(path: string) {
    return this.get<CfFragment>({ op: 'fragment', path });
  }

  async save(request: SaveRequest) {
    const out = await this.post<{ fragment: CfFragment }>({ op: 'save', ...request });
    return out.fragment;
  }

  async create(parent: string, title: string, model: string, name?: string, description?: string) {
    const out = await this.post<{ path: string }>({ op: 'create', parent, title, model, name, description });
    return out.path;
  }

  async remove(path: string) {
    await this.post({ op: 'delete', path });
  }

  async createVariation(path: string, title: string, name?: string, description?: string) {
    const out = await this.post<{ fragment: CfFragment }>({ op: 'createVariation', path, title, name, description });
    return out.fragment;
  }

  async deleteVariation(path: string, name: string) {
    const out = await this.post<{ fragment: CfFragment }>({ op: 'deleteVariation', path, name });
    return out.fragment;
  }

  references(path: string) {
    return this.get<CfReferences>({ op: 'references', path });
  }

  confs() {
    return this.get<CfConf[]>({ op: 'confs' });
  }

  languages(path: string) {
    return this.get<CfLanguages>({ op: 'languages', path });
  }

  translationProviders(path?: string) {
    return this.get<CfTranslationProvider[]>(path ? { op: 'translationProviders', path } : { op: 'translationProviders' });
  }

  async translate(request: CfTranslateRequest) {
    const out = await this.post<{ results: CfTranslateResult[] }>({ op: 'translate', ...request });
    return out.results;
  }

  async approveTranslation(path: string) {
    const out = await this.post<{ languages: CfLanguages }>({ op: 'approveTranslation', path });
    return out.languages;
  }

  versions(path: string) {
    return this.get<CfVersion[]>({ op: 'versions', path });
  }

  versionContent(path: string, version: string) {
    return this.get<CfFragment & { version: CfVersion }>({ op: 'version', path, version });
  }

  async createVersion(path: string, label?: string, comment?: string) {
    const out = await this.post<{ version: CfVersion }>({ op: 'createVersion', path, label, comment });
    return out.version;
  }

  restoreVersion(path: string, version: string) {
    return this.post<{ saved: CfVersion; fragment: CfFragment }>({ op: 'restoreVersion', path, version });
  }

  async createModel(conf: string, title: string, name?: string, description?: string) {
    const out = await this.post<{ path: string }>({ op: 'createModel', conf, title, name, description });
    return out.path;
  }

  async saveModel(request: ModelSaveRequest) {
    const out = await this.post<{ model: CfModel }>({ op: 'saveModel', ...request });
    return out.model;
  }

  async setModelStatus(path: string, enabled: boolean) {
    const out = await this.post<{ model: CfModel }>({ op: 'setModelStatus', path, enabled });
    return out.model;
  }

  async deleteModel(path: string, force = false) {
    await this.post({ op: 'deleteModel', path, force });
  }

  modelUsage(path: string) {
    return this.get<{ fragments: string[]; truncated: boolean }>({ op: 'modelUsage', path });
  }

  async listDam(path: string): Promise<DamItem[]> {
    const res = await fetch(`${this.base}${encodePath(path)}.2.json`, { credentials: 'same-origin' });
    if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
    return damItems(path, (await res.json()) as Record<string, unknown>);
  }

  thumbnailUrl(assetPath: string) {
    return `${this.base}${encodePath(assetPath)}/jcr:content/renditions/cq5dam.thumbnail.319.319.png`;
  }

  async currentUser() {
    const res = await fetch(`${this.base}/system/sling/info.sessionInfo.json`, { credentials: 'same-origin' });
    if (!res.ok) return 'anonymous';
    const info = (await res.json()) as { userID?: string };
    return info.userID ?? 'anonymous';
  }

  // ------------------------------------------------------------------------------------------- transport

  private async get<T>(params: Record<string, string>): Promise<T> {
    const res = await fetch(`${this.base}${API}?${new URLSearchParams(params)}`, { credentials: 'same-origin' });
    return this.read<T>(res);
  }

  private async post<T = unknown>(body: Record<string, unknown>): Promise<T> {
    const res = await fetch(`${this.base}${API}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    });
    return this.read<T>(res);
  }

  private async read<T>(res: Response): Promise<T> {
    const text = await res.text();
    let json: unknown = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      /* not JSON: reported below */
    }
    if (res.status === 422 && json && typeof json === 'object' && 'errors' in json) {
      throw new CfValidationError((json as { errors: FieldError[] }).errors);
    }
    if (!res.ok) {
      const message = json && typeof json === 'object' && 'error' in json ? String((json as { error: unknown }).error) : `HTTP ${res.status}`;
      if (json && typeof json === 'object' && 'code' in json) throw new CfServiceError(message, String((json as { code: unknown }).code));
      throw new Error(message);
    }
    return json as T;
  }
}

/** Turns a Sling `.2.json` listing of a DAM folder into picker items. */
export function damItems(path: string, json: Record<string, unknown>): DamItem[] {
  const base = path.replace(/\/$/, '');
  const out: DamItem[] = [];
  for (const [name, value] of Object.entries(json)) {
    if (!value || typeof value !== 'object' || Array.isArray(value) || name.includes(':')) continue;
    const node = value as Record<string, unknown>;
    const type = String(node['jcr:primaryType'] ?? '');
    const content = (node['jcr:content'] ?? {}) as Record<string, unknown>;
    let kind: DamItem['kind'] | null = null;
    if (type === 'dam:Asset') kind = content.contentFragment === true ? 'fragment' : 'asset';
    else if (type.endsWith('Folder') || type === 'nt:folder') kind = 'folder';
    if (!kind) continue;
    out.push({ name, path: `${base}/${name}`, title: String(content['jcr:title'] ?? node['jcr:title'] ?? name), kind });
  }
  const order = { folder: 0, fragment: 1, asset: 2 };
  return out.sort((a, b) => order[a.kind] - order[b.kind] || a.title.localeCompare(b.title));
}

export function encodePath(path: string): string {
  return path
    .split('/')
    .map((s) => encodeURIComponent(s).replace(/%3A/gi, ':'))
    .join('/');
}

export const nameOf = (path: string) => path.slice(path.lastIndexOf('/') + 1);
export const parentOf = (path: string) => path.slice(0, path.lastIndexOf('/')) || '/';

/** Console and editor URLs, AEM style. */
export const cfConsoleUrl = (folder: string) => `/cf.html${folder}`;
/** The models console: all configurations, or the models of one. */
export const cfModelsUrl = (conf?: string | null) => `/cf.html${conf ?? '/conf'}`;
/** The model editor. */
export const cfModelEditorUrl = (model: string) => `/editor.html${encodePath(model)}`;
export const cfEditorUrl = (fragment: string) => `/editor.html${fragment}`;
