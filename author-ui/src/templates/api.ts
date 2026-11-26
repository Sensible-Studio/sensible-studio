/** A configuration under /conf, as the Templates console lists them. */
export interface TemplateConf {
  path: string;
  name: string;
  title: string;
  /** how many editable templates it has */
  templates: number;
}

export type TemplateStatus = 'draft' | 'enabled' | 'disabled';

/** An editable template in the console. */
export interface TemplateSummary {
  path: string;
  name: string;
  title: string;
  description?: string | null;
  status: TemplateStatus | string;
  templateType?: string | null;
  templateTypeTitle?: string | null;
  /** `thumbnail.png`, with a cache key */
  thumbnail?: string | null;
  lastModified?: string | null;
  lastModifiedBy?: string | null;
  created?: string | null;
}

/** A template type: what a new template starts as. */
export interface TemplateType {
  path: string;
  name: string;
  title: string;
  description?: string | null;
  thumbnail?: string | null;
  ranking: number;
  /** `/conf/<c>`, `/conf/global`, `/apps` or `/libs` */
  source: string;
}

/** A content policy a template maps (published with it). */
export interface TemplatePolicy {
  path: string;
  title: string;
  /** the component's resource type */
  component: string;
}

export interface TemplateUsage {
  count: number;
  pages: string[];
  complete: boolean;
}

export interface TemplatesClient {
  confs(): Promise<TemplateConf[]>;
  templates(conf: string): Promise<TemplateSummary[]>;
  types(conf: string): Promise<TemplateType[]>;
  usage(template: string): Promise<TemplateUsage>;
  policies(template: string): Promise<TemplatePolicy[]>;
  create(conf: string, type: string, title: string, description?: string): Promise<TemplateSummary>;
  copy(template: string): Promise<TemplateSummary>;
  remove(template: string): Promise<void>;
  setStatus(template: string, status: TemplateStatus): Promise<void>;
  saveProperties(template: string, title: string, description: string): Promise<TemplateSummary>;
  uploadThumbnail(template: string, file: File): Promise<TemplateSummary>;
  removeThumbnail(template: string): Promise<TemplateSummary>;
  /** one template (from its configuration's list) */
  template(path: string): Promise<TemplateSummary>;
  currentUser(): Promise<string>;
}

const API = '/bin/studio/templateconsole.json';
const EDITOR_API = '/bin/studio/templates.json';

async function json<T>(res: Response): Promise<T> {
  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  if (!res.ok) {
    const message = body && typeof body === 'object' && 'error' in body ? String((body as { error: unknown }).error) : `${res.status} ${res.statusText}`;
    throw new Error(message);
  }
  return body as T;
}

function form(params: Record<string, string | undefined>): URLSearchParams {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined) qs.set(k, v);
  return qs;
}

const get = <T>(params: Record<string, string>) =>
  fetch(`${API}?${form(params)}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } }).then((r) => json<T>(r));

const post = <T>(url: string, body: URLSearchParams | FormData) =>
  fetch(url, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' }, body }).then((r) => json<T>(r));

export class HttpTemplatesClient implements TemplatesClient {
  confs() {
    return get<{ confs: TemplateConf[] }>({ op: 'confs' }).then((r) => r.confs);
  }
  templates(conf: string) {
    return get<{ templates: TemplateSummary[] }>({ op: 'templates', conf }).then((r) => r.templates);
  }
  types(conf: string) {
    return get<{ types: TemplateType[] }>({ op: 'types', conf }).then((r) => r.types);
  }
  usage(template: string) {
    return get<TemplateUsage>({ op: 'usage', path: template });
  }
  policies(template: string) {
    return get<{ policies: TemplatePolicy[] }>({ op: 'policies', path: template }).then((r) => r.policies);
  }
  create(conf: string, type: string, title: string, description?: string) {
    return post<TemplateSummary>(API, form({ op: 'create', conf, type, title, description }));
  }
  copy(template: string) {
    return post<TemplateSummary>(API, form({ op: 'copy', path: template }));
  }
  async remove(template: string) {
    await post(API, form({ op: 'delete', path: template }));
  }
  async setStatus(template: string, status: TemplateStatus) {
    await post(EDITOR_API, form({ op: 'status', path: template, status }));
  }
  saveProperties(template: string, title: string, description: string) {
    return post<TemplateSummary>(API, form({ op: 'properties', path: template, title, description }));
  }
  uploadThumbnail(template: string, file: File) {
    const data = new FormData();
    data.set('op', 'thumbnail');
    data.set('path', template);
    data.set('file', file, file.name);
    return post<TemplateSummary>(API, data);
  }
  removeThumbnail(template: string) {
    return post<TemplateSummary>(API, form({ op: 'thumbnail', path: template, delete: 'true' }));
  }
  async template(path: string) {
    const conf = confOf(path);
    if (!conf) throw new Error(`${path} is not an editable template`);
    const found = (await this.templates(conf)).find((t) => t.path === path);
    if (!found) throw new Error(`No template at ${path}`);
    return found;
  }
  async currentUser() {
    const r = await fetch('/system/sling/info.sessionInfo.json', { credentials: 'same-origin' });
    return r.ok ? String(((await r.json()) as { userID?: string }).userID ?? '') : '';
  }
}

// ------------------------------------------------------------------------------------------------ URLs

export const CONSOLE = '/libs/wcm/core/content/sites/templates.html';
export const WIZARD = '/libs/wcm/core/content/sites/createtemplatewizard.html';
export const PROPERTIES = '/mnt/overlay/wcm/core/content/sites/templates/properties.html';

/** The configuration of a template path (`/conf/wknd` for `/conf/wknd/settings/wcm/templates/x`). */
export function confOf(templatePath: string): string | null {
  const m = templatePath.match(/^(\/conf\/.+?)\/settings\/wcm\/templates\/[^/]+\/?$/);
  return m ? m[1] : null;
}

export const consoleUrl = (conf?: string | null) => `${CONSOLE}${conf ?? '/conf'}`;
export const wizardUrl = (conf: string) => `${WIZARD}${conf}`;
export const templatePropertiesUrl = (template: string) => `${PROPERTIES}?item=${encodeURIComponent(template)}`;
export const templateEditorUrl = (template: string) => `/editor.html${template}/structure.html`;

export type TemplatesRoute =
  | { screen: 'console'; conf: string | null }
  | { screen: 'wizard'; conf: string }
  | { screen: 'properties'; template: string };

/** The Templates screens at AEM's URLs, or null on other URLs. */
export function templatesRoute(location: { pathname: string; search: string }): TemplatesRoute | null {
  const path = decodeURI(location.pathname);
  let m = path.match(/^\/libs\/wcm\/core\/content\/sites\/templates\.html(\/conf(?:\/[^/]+)*)?\/?$/);
  if (m) return { screen: 'console', conf: m[1] && m[1] !== '/conf' ? m[1] : null };
  m = path.match(/^\/libs\/wcm\/core\/content\/sites\/createtemplatewizard\.html(\/conf\/[^/].*?)\/?$/);
  if (m) return { screen: 'wizard', conf: m[1] };
  if (/^\/(?:mnt\/overlay|libs)\/wcm\/core\/content\/sites\/templates\/properties\.html$/.test(path)) {
    const item = new URLSearchParams(location.search).get('item');
    if (item && confOf(item)) return { screen: 'properties', template: item.replace(/\/$/, '') };
  }
  return null;
}
