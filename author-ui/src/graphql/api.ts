/**
 * GraphQL over the records (content fragments), as AEM: endpoints per configuration
 * (`/content/cq:graphql/<conf>/endpoint`, plus the global one) and persisted queries
 * (`/conf/<conf>/settings/graphql/persistentQueries/<name>`). The server side is `studio-cfm`'s headless package.
 */

export interface GqlEndpoint {
  /** The configuration's name (`wknd-shared`); null for the global endpoint. */
  conf: string | null;
  title: string;
  /** Where queries are POSTed: `/content/cq:graphql/<conf>/endpoint.json`. */
  url: string;
}

export interface GqlConf {
  /** `/conf/wknd-shared` */
  path: string;
  title: string;
  models: number;
}

export interface PersistedQuery {
  conf: string;
  name: string;
  /** `/conf/<conf>/settings/graphql/persistentQueries/<name>` */
  path: string;
  query: string;
  lastModified?: string;
}

export interface GqlResult {
  status: number;
  ms: number;
  body: unknown;
}

/** A field of the Query type, from introspection: what the schema rail lists. */
export interface GqlField {
  name: string;
  description?: string | null;
  args: Array<{ name: string; type: string }>;
  type: string;
}

export interface GqlSchema {
  queries: GqlField[];
  /** Object types by name with their fields (for the fields of a result). */
  types: Record<string, GqlField[]>;
}

export interface GraphQLClient {
  endpoints(): Promise<GqlEndpoint[]>;
  confs(): Promise<GqlConf[]>;
  createEndpoint(conf: string, title: string): Promise<void>;
  deleteEndpoint(endpoint: GqlEndpoint): Promise<void>;
  run(endpoint: GqlEndpoint, query: string, variables?: Record<string, unknown>, operationName?: string): Promise<GqlResult>;
  schema(endpoint: GqlEndpoint): Promise<GqlSchema>;
  persisted(conf?: string): Promise<PersistedQuery[]>;
  /** Stores a query; `replace` when one of that name exists. */
  persist(conf: string, name: string, query: string, replace: boolean): Promise<void>;
  deletePersisted(query: PersistedQuery): Promise<void>;
}

const nameOfConf = (path: string) => path.replace(/^\/conf\//, '');

/** The URL a persisted query is run at (GET; variables as `;name=value`). */
export const executeUrl = (q: { conf: string; name: string }) => `/graphql/execute.json/${q.conf}/${q.name}`;

// `fields` once only: graphql-java refuses an introspection that asks for `__Type.fields` twice (bad faith)
const INTROSPECTION = `{ __schema { queryType { name }
  types { name kind fields { name description args { name type { ...T } } type { ...T } } } } }
fragment T on __Type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }`;

interface IntroType {
  kind: string;
  name: string | null;
  ofType?: IntroType | null;
}

/** `[Adventure!]!` from introspection's nested type. */
export function typeName(t: IntroType | null | undefined): string {
  if (!t) return '';
  if (t.kind === 'NON_NULL') return `${typeName(t.ofType)}!`;
  if (t.kind === 'LIST') return `[${typeName(t.ofType)}]`;
  return t.name ?? '';
}

interface IntroField {
  name: string;
  description?: string | null;
  args?: Array<{ name: string; type: IntroType }>;
  type: IntroType;
}

const field = (f: IntroField): GqlField => ({
  name: f.name,
  description: f.description,
  args: (f.args ?? []).map((a) => ({ name: a.name, type: typeName(a.type) })),
  type: typeName(f.type),
});

export class HttpGraphQLClient implements GraphQLClient {
  constructor(private readonly base = '') {}

  private async json<T>(url: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`${this.base}${url}`, { credentials: 'same-origin', ...init });
    const text = await res.text();
    let body: unknown = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      /* not JSON */
    }
    if (!res.ok) {
      const msg = (body as { error?: string; errors?: Array<{ message: string }> } | null)?.error ?? (body as { errors?: Array<{ message: string }> } | null)?.errors?.[0]?.message;
      throw new Error(msg ?? `HTTP ${res.status}`);
    }
    return body as T;
  }

  private post(path: string, fields: Record<string, string>) {
    const body = new URLSearchParams({ ...fields, _charset_: 'utf-8' });
    return fetch(`${this.base}${path.split('/').map(encodeURIComponent).join('/').replace(/%3A/g, ':')}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
      body,
    }).then((r) => {
      if (!r.ok) throw new Error(`Saving ${path} failed: HTTP ${r.status}`);
    });
  }

  endpoints() {
    return this.json<GqlEndpoint[]>('/bin/studio/cf.json?op=endpoints');
  }

  confs() {
    return this.json<GqlConf[]>('/bin/studio/cf.json?op=confs');
  }

  async createEndpoint(conf: string, title: string) {
    const folder = `/content/cq:graphql/${conf}`;
    const exists = await fetch(`${this.base}${folder}.json`, { credentials: 'same-origin' }).then((r) => r.ok);
    if (!exists) await this.post(folder, { 'jcr:primaryType': 'sling:Folder' });
    await this.post(`${folder}/endpoint`, {
      'jcr:primaryType': 'nt:unstructured',
      'sling:resourceType': 'graphql/sites/components/endpoint',
      'jcr:title': title,
    });
  }

  async deleteEndpoint(endpoint: GqlEndpoint) {
    if (!endpoint.conf) throw new Error('The global endpoint cannot be removed');
    const node = endpoint.url.replace(/\.json$/, '');
    await this.post(node, { ':operation': 'delete' });
    // the configuration's folder goes too when the endpoint was all it held
    const folder = node.slice(0, node.lastIndexOf('/'));
    const rest = await fetch(`${this.base}${folder}.1.json`, { credentials: 'same-origin', cache: 'no-store' }).then((r) => (r.ok ? (r.json() as Promise<Record<string, unknown>>) : null));
    if (rest && !Object.values(rest).some((v) => v && typeof v === 'object')) await this.post(folder, { ':operation': 'delete' });
  }

  async run(endpoint: GqlEndpoint, query: string, variables?: Record<string, unknown>, operationName?: string): Promise<GqlResult> {
    const started = performance.now();
    const res = await fetch(`${this.base}${endpoint.url}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ query, variables: variables ?? {}, ...(operationName ? { operationName } : {}) }),
    });
    const text = await res.text();
    let body: unknown = text;
    try {
      body = JSON.parse(text);
    } catch {
      /* the text as it is */
    }
    return { status: res.status, ms: Math.round(performance.now() - started), body };
  }

  async schema(endpoint: GqlEndpoint): Promise<GqlSchema> {
    const r = await this.run(endpoint, INTROSPECTION);
    const body = r.body as { data?: { __schema: { queryType: { name: string }; types: Array<{ name: string; kind: string; fields?: IntroField[] | null }> } }; errors?: Array<{ message: string }> } | null;
    const data = body?.data;
    if (!data) throw new Error(body?.errors?.[0]?.message ?? `The endpoint did not describe its schema (HTTP ${r.status})`);
    const types: Record<string, GqlField[]> = {};
    for (const t of data.__schema.types) if (t.kind === 'OBJECT' && t.fields && !t.name.startsWith('__')) types[t.name] = t.fields.map(field);
    const queries = types[data.__schema.queryType.name] ?? [];
    delete types[data.__schema.queryType.name];
    return { queries, types };
  }

  async persisted(conf?: string) {
    const list = await this.json<Array<{ path: { shortForm: string; fullForm: string }; query: string; lastModified?: string }>>(`/graphql/list.json${conf ? `/${conf}` : ''}`);
    return list.map((q) => {
      const [, c, name] = q.path.shortForm.split('/');
      return { conf: c, name, path: q.path.fullForm, query: q.query, lastModified: q.lastModified };
    });
  }

  async persist(conf: string, name: string, query: string, replace: boolean) {
    const res = await fetch(`${this.base}/graphql/persist.json/${conf}/${encodeURIComponent(name)}`, {
      method: replace ? 'POST' : 'PUT',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query }),
    });
    if (!res.ok) {
      const body = (await res.json().catch(() => null)) as { error?: string; errors?: Array<{ message: string }> } | null;
      throw new Error(body?.error ?? body?.errors?.[0]?.message ?? `HTTP ${res.status}`);
    }
  }

  async deletePersisted(query: PersistedQuery) {
    await this.post(query.path, { ':operation': 'delete' });
  }
}

export { nameOfConf };

/** A light formatter: one field per line, two spaces a level; comments and strings are kept as they are. */
export function prettify(query: string): string {
  let out = '';
  let depth = 0;
  let paren = 0;
  let line = '';
  const flush = () => {
    const t = line.trim();
    if (t) out += `${'  '.repeat(Math.max(0, depth))}${t}\n`;
    line = '';
  };
  for (let i = 0; i < query.length; i++) {
    const c = query[i];
    if (c === '#') {
      const end = query.indexOf('\n', i);
      line += query.slice(i, end < 0 ? undefined : end);
      flush();
      i = end < 0 ? query.length : end;
      continue;
    }
    if (c === '"') {
      const triple = query.startsWith('"""', i);
      const close = triple ? query.indexOf('"""', i + 3) + 3 : (() => {
        let j = i + 1;
        while (j < query.length && query[j] !== '"') j += query[j] === '\\' ? 2 : 1;
        return j + 1;
      })();
      line += query.slice(i, close > i ? close : undefined);
      i = (close > i ? close : query.length) - 1;
      continue;
    }
    if (c === '(' || c === ')') {
      paren += c === '(' ? 1 : -1;
      line = c === ')' ? `${line.replace(/[\s,]+$/, '')})` : /\b(query|mutation|subscription)$/.test(line.trimEnd()) ? `${line.trimEnd()} (` : `${line.trimEnd()}(`;
      continue;
    }
    if (paren > 0 && (c === '\n' || c === ',' || /\s/.test(c))) {
      // arguments stay on the field's line: a, b
      if (c === ',' || c === '\n') {
        if (!/[(,]\s*$/.test(line)) line = `${line.trimEnd()}, `;
      } else if (line && !line.endsWith(' ') && !line.endsWith('(')) line += ' ';
      continue;
    }
    if (c === '{') {
      line += ' {';
      flush();
      depth++;
    } else if (c === '}') {
      flush();
      depth--;
      line = '}';
      flush();
    } else if (c === '\n' || c === ',') {
      flush();
    } else if (/\s/.test(c)) {
      if (line && !line.endsWith(' ')) line += ' ';
    } else line += c;
  }
  flush();
  return out.replace(/\s+\{/g, ' {').trimEnd() + '\n';
}
