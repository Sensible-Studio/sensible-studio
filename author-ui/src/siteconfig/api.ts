/**
 * Site configurations (Sling context-aware configurations), over `/bin/studio/siteconfig.json`: what the
 * bundles of the instance define, what a site sets for itself and what it inherits.
 */
export type PropertyType = 'string' | 'boolean' | 'integer' | 'number';
export type Scalar = string | number | boolean;
export type Value = Scalar | Scalar[] | null;

export interface ConfigSummary {
  name: string;
  label: string;
  description?: string | null;
  collection: boolean;
  /** a bundle defines it (it has a form); otherwise it is only stored content */
  defined: boolean;
  items: number;
  resourcePath?: string | null;
  /** this context has values of its own */
  set: boolean;
  inherited: boolean;
}

export interface ConfigList {
  path: string;
  /** the nearest ancestor that names a configuration (sling:configRef); null when none does */
  contextPath: string | null;
  contextPaths: string[];
  configs: ConfigSummary[];
}

export interface ConfigProperty {
  name: string;
  label: string;
  description?: string | null;
  type: PropertyType;
  multiple: boolean;
  nested: boolean;
  default: Value;
  /** set in the configuration resource that applies */
  value: Value;
  effectiveValue: Value;
  isDefault: boolean;
  inherited: boolean;
  overridden: boolean;
  sourcePath?: string | null;
}

export interface ConfigItem {
  name: string;
  resourcePath?: string | null;
  inherited: boolean;
  properties: ConfigProperty[];
}

export interface ConfigDetail {
  name: string;
  label: string;
  description?: string | null;
  path: string;
  contextPath: string | null;
  collection: boolean;
  resourcePath?: string | null;
  inherited?: boolean;
  overridden?: boolean;
  /** a singleton's properties; for a collection, those of a new item */
  properties: ConfigProperty[];
  items?: ConfigItem[];
}

export type Values = Record<string, Value>;

export interface SiteConfigClient {
  list(path: string): Promise<ConfigList>;
  get(path: string, name: string): Promise<ConfigDetail>;
  save(path: string, name: string, values: Values): Promise<ConfigDetail>;
  saveItems(path: string, name: string, items: Array<{ name: string; values: Values }>): Promise<ConfigDetail>;
  remove(path: string, name: string): Promise<ConfigDetail>;
}

/** The form body of a save: `p.<property>` once per value; nothing for a value not set. */
export function encodeValues(body: URLSearchParams, prefix: string, values: Values) {
  for (const [k, v] of Object.entries(values)) {
    if (v === null || v === undefined) continue;
    for (const one of Array.isArray(v) ? v : [v]) {
      if (one === '' || one === null || one === undefined) continue;
      body.append(prefix + k, String(one));
    }
  }
}

export class HttpSiteConfigClient implements SiteConfigClient {
  constructor(
    private readonly base = '/bin/studio/siteconfig.json',
    private readonly fetcher: typeof fetch = (...a) => fetch(...a),
  ) {}

  private async ask<T>(init: RequestInit | undefined, query: URLSearchParams): Promise<T> {
    const r = init ? await this.fetcher(this.base, { ...init, credentials: 'same-origin' }) : await this.fetcher(`${this.base}?${query.toString()}`, { credentials: 'same-origin' });
    const body = (await r.json().catch(() => ({}))) as T & { error?: string };
    if (!r.ok) throw new Error(body.error ?? `HTTP ${r.status}`);
    return body;
  }

  private post<T>(body: URLSearchParams): Promise<T> {
    return this.ask<T>({ method: 'POST', headers: { 'X-Studio-CSRF': '1' }, body }, body);
  }

  list(path: string) {
    return this.ask<ConfigList>(undefined, new URLSearchParams({ op: 'list', path }));
  }

  get(path: string, name: string) {
    return this.ask<ConfigDetail>(undefined, new URLSearchParams({ op: 'get', path, name }));
  }

  save(path: string, name: string, values: Values) {
    const body = new URLSearchParams({ op: 'save', path, name });
    encodeValues(body, 'p.', values);
    return this.post<ConfigDetail>(body);
  }

  saveItems(path: string, name: string, items: Array<{ name: string; values: Values }>) {
    const body = new URLSearchParams({ op: 'save', path, name, items: String(items.length) });
    items.forEach((item, k) => {
      body.append(`i.${k}.name`, item.name);
      encodeValues(body, `i.${k}.p.`, item.values);
    });
    return this.post<ConfigDetail>(body);
  }

  remove(path: string, name: string) {
    return this.post<ConfigDetail>(new URLSearchParams({ op: 'delete', path, name }));
  }
}
