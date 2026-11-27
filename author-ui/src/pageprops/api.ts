/** Client of `/bin/studio/pagepermissions.json` (the Permissions tab of the page properties). */

export const PERMISSION_FLAGS = ['read', 'modify', 'delete', 'replicate', 'create'] as const;
export type PermissionFlag = (typeof PERMISSION_FLAGS)[number];

export const PERMISSION_LABELS: Record<PermissionFlag, string> = {
  read: 'Browse page',
  modify: 'Edit content',
  delete: 'Delete page',
  replicate: 'Publish/unpublish page',
  create: 'Create sub-pages',
};

export interface PermissionRow extends Record<PermissionFlag, boolean> {
  principal: string;
  title?: string;
  group: boolean;
  /** the principal also has entries on the page that are none of the five permissions */
  other?: boolean;
}

export interface PagePermissions {
  acl: PermissionRow[];
  cug: { supported: boolean; principals: PermissionRow[] };
  /** what the signed-in user may do on the page */
  own?: Record<PermissionFlag, boolean> & { principal: string };
  /** the user may not read who was granted what: only `own` says something */
  restricted?: boolean;
}

export interface PrincipalChoice {
  id: string;
  title: string;
  group: boolean;
}

export interface PagePermissionsClient {
  load(page: string): Promise<PagePermissions>;
  effective(page: string): Promise<PermissionRow[]>;
  save(page: string, principal: string, flags: Partial<Record<PermissionFlag, boolean>>): Promise<PagePermissions>;
  remove(page: string, principal: string): Promise<PagePermissions>;
  setCug(page: string, principals: string[]): Promise<PagePermissions>;
  /** users and groups whose ID or name matches */
  findPrincipals(query: string): Promise<PrincipalChoice[]>;
}

const API = '/bin/studio/pagepermissions.json';

async function call<T>(method: 'GET' | 'POST', params: Array<[string, string]>): Promise<T> {
  const qs = new URLSearchParams(params);
  const res =
    method === 'GET'
      ? await fetch(`${API}?${qs}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      : await fetch(API, {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', Accept: 'application/json' },
          body: qs,
        });
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error((body as { error?: string }).error || `HTTP ${res.status}`);
  return body;
}

interface AuthorizableSummary {
  id: string;
  type: 'user' | 'group';
  principal: string;
  name: string;
}

export class HttpPagePermissionsClient implements PagePermissionsClient {
  load(page: string) {
    return call<PagePermissions>('GET', [['path', page]]);
  }

  async effective(page: string) {
    return (await call<{ effective: PermissionRow[] }>('GET', [['op', 'effective'], ['path', page]])).effective;
  }

  save(page: string, principal: string, flags: Partial<Record<PermissionFlag, boolean>>) {
    return call<PagePermissions>('POST', [
      ['op', 'save'],
      ['path', page],
      ['principal', principal],
      ...PERMISSION_FLAGS.map((f): [string, string] => [f, String(!!flags[f])]),
    ]);
  }

  remove(page: string, principal: string) {
    return call<PagePermissions>('POST', [['op', 'remove'], ['path', page], ['principal', principal]]);
  }

  setCug(page: string, principals: string[]) {
    return call<PagePermissions>('POST', [['op', 'cug'], ['path', page], ...principals.map((p): [string, string] => ['principal', p])]);
  }

  async findPrincipals(query: string) {
    const get = (op: string) =>
      fetch(`/bin/studio/security.json?${new URLSearchParams({ op, q: query })}`, { credentials: 'same-origin' }).then((r) =>
        r.ok ? (r.json() as Promise<AuthorizableSummary[]>) : [],
      );
    const [users, groups] = await Promise.all([get('users'), get('groups')]);
    return [...groups, ...users].slice(0, 30).map((a) => ({ id: a.principal || a.id, title: a.name || a.id, group: a.type === 'group' }));
  }
}

/** Where the page properties of a page are, as in AEM. */
export function propertiesUrl(page: string): string {
  return `/mnt/overlay/wcm/core/content/sites/properties.html?item=${encodeURIComponent(page)}`;
}

/** A template's initial content: its page properties are the initial properties of pages made from it. */
const TEMPLATE_INITIAL = /^\/conf\/.+\/settings\/wcm\/templates\/[^/]+\/initial\/?$/;

/** The page of `/mnt/overlay/wcm/core/content/sites/properties.html?item=<page>` (or a template's initial content), or null on other URLs. */
export function propertiesRoute(location: { pathname: string; search: string }): string | null {
  if (!/^\/mnt\/overlay\/wcm\/core\/content\/sites\/properties\.html$/.test(location.pathname)) return null;
  const item = new URLSearchParams(location.search).get('item');
  return item && (item.startsWith('/content/') || TEMPLATE_INITIAL.test(item)) ? item.replace(/\/$/, '') : null;
}
