/** Client of `/bin/studio/security.json` (studio-security bundle). */

export interface AuthorizableSummary {
  id: string;
  type: 'user' | 'group';
  principal: string;
  path: string;
  name: string;
  email?: string;
  description?: string;
  jobTitle?: string;
  disabled?: boolean;
  system?: boolean;
  admin?: boolean;
  protected?: boolean;
}

export const PROFILE_FIELDS = [
  'givenName',
  'familyName',
  'email',
  'title',
  'jobTitle',
  'gender',
  'phoneNumber',
  'aboutMe',
  'street',
  'city',
  'postalCode',
  'country',
] as const;

export type ProfileField = (typeof PROFILE_FIELDS)[number];
export type Profile = Partial<Record<ProfileField, string | null>>;

export interface AuthorizableDetail extends AuthorizableSummary {
  profile: Profile;
  memberOf: AuthorizableSummary[];
  members?: AuthorizableSummary[];
  disabledReason?: string;
  impersonators?: string[];
}

export interface Ace {
  /** null: repository-level entry */
  path: string | null;
  index: number;
  allow: boolean;
  privileges: string[];
  restrictions: Record<string, string | string[]>;
}

export interface PrivilegeInfo {
  name: string;
  aggregate: boolean;
  contains: string[];
}

export interface Effective {
  path: string;
  principal: string;
  privileges: string[];
  summary: Record<'read' | 'modify' | 'create' | 'delete' | 'readAcl' | 'editAcl', boolean>;
  principals: string[];
}

/** The columns of the permission tree: what an author thinks of, each a few JCR privileges on the server. */
export type PermColumn = 'read' | 'modify' | 'create' | 'delete' | 'readAcl' | 'editAcl' | 'replicate';

/** A node of the permission tree, for one user or group. */
export interface PermRow {
  path: string;
  name: string;
  title?: string;
  type: string;
  hasChildren: boolean;
  /** What the principal can do here, with its groups, everyone and the entries above. */
  can: Partial<Record<PermColumn, boolean>>;
  /** What its own entries on this node say. */
  set: Partial<Record<PermColumn, 'allow' | 'deny'>>;
  /** It also has entries with restrictions (globs) here, which the tree does not show or change. */
  restricted?: boolean;
}

export interface PermLevel {
  principal: string;
  columns: PermColumn[];
  node: PermRow;
  children: PermRow[];
  /** More children than were sent. */
  more: boolean;
}

export type PermValue = 'allow' | 'deny' | 'inherit';

const URL_ = '/bin/studio/security.json';

async function call<T>(method: 'GET' | 'POST', params: Array<[string, string]>): Promise<T> {
  const qs = new URLSearchParams(params);
  const res =
    method === 'GET'
      ? await fetch(`${URL_}?${qs}`, { credentials: 'same-origin' })
      : await fetch(URL_, {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
          body: qs,
        });
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error((body as { error?: string }).error || `HTTP ${res.status}`);
  return body;
}

const profileParams = (p: Profile): Array<[string, string]> =>
  Object.entries(p)
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => [`profile.${k}`, v ?? '']);

export const security = {
  users: (q = '', system = false) => call<AuthorizableSummary[]>('GET', [['op', 'users'], ['q', q], ['system', String(system)]]),
  groups: (q = '') => call<AuthorizableSummary[]>('GET', [['op', 'groups'], ['q', q]]),
  get: (id: string) => call<AuthorizableDetail>('GET', [['op', 'authorizable'], ['id', id]]),
  createUser: (id: string, password: string, profile: Profile) =>
    call<AuthorizableDetail>('POST', [['op', 'createUser'], ['id', id], ['password', password], ...profileParams(profile)]),
  createGroup: (id: string, profile: Profile) => call<AuthorizableDetail>('POST', [['op', 'createGroup'], ['id', id], ...profileParams(profile)]),
  update: (id: string, profile: Profile, opts: { disabled?: boolean; reason?: string; password?: string } = {}) => {
    const p: Array<[string, string]> = [['op', 'update'], ['id', id], ...profileParams(profile)];
    if (opts.disabled !== undefined) p.push(['disabled', String(opts.disabled)]);
    if (opts.reason) p.push(['reason', opts.reason]);
    if (opts.password) p.push(['password', opts.password]);
    return call<AuthorizableDetail>('POST', p);
  },
  remove: (id: string) => call<{ deleted: string }>('POST', [['op', 'delete'], ['id', id]]),
  setMemberOf: (id: string, groups: string[]) => call<AuthorizableDetail>('POST', [['op', 'memberOf'], ['id', id], ...groups.map((g): [string, string] => ['group', g])]),
  setMembers: (id: string, members: string[]) => call<AuthorizableDetail>('POST', [['op', 'members'], ['id', id], ...members.map((m): [string, string] => ['member', m])]),
  setImpersonators: (id: string, principals: string[]) =>
    call<AuthorizableDetail>('POST', [['op', 'impersonators'], ['id', id], ...principals.map((p): [string, string] => ['principal', p])]),
  aces: (principal: string) => call<Ace[]>('GET', [['op', 'aces'], ['principal', principal]]),
  saveAce: (principal: string, path: string | null, allow: boolean, privileges: string[], glob: string, index?: number) =>
    call<{ aces: Ace[] }>('POST', [
      ['op', 'saveAce'],
      ['principal', principal],
      ['path', path ?? ''],
      ['allow', String(allow)],
      ['glob', glob],
      ...(index !== undefined ? ([['index', String(index)]] as Array<[string, string]>) : []),
      ...privileges.map((p): [string, string] => ['privilege', p]),
    ]),
  removeAce: (principal: string, path: string | null, index: number) =>
    call<{ aces: Ace[] }>('POST', [['op', 'removeAce'], ['principal', principal], ['path', path ?? ''], ['index', String(index)]]),
  effective: (principal: string, path: string) => call<Effective>('GET', [['op', 'effective'], ['principal', principal], ['path', path]]),
  privileges: () => call<PrivilegeInfo[]>('GET', [['op', 'privileges']]),
  tree: (principal: string, path: string) => call<PermLevel>('GET', [['op', 'tree'], ['principal', principal], ['path', path]]),
  setPermissions: (principal: string, path: string, values: Partial<Record<PermColumn, PermValue>>) =>
    call<PermRow>('POST', [['op', 'setPermissions'], ['principal', principal], ['path', path], ...Object.entries(values).map(([k, v]): [string, string] => [k, v as string])]),
};

export const securityUrl = {
  users: () => '/security/users.html',
  groups: () => '/security/groups.html',
  permissions: (principal?: string) => `/security/permissions.html${principal ? `?principal=${encodeURIComponent(principal)}` : ''}`,
  user: (id?: string) => `/security/user.html${id ? `?id=${encodeURIComponent(id)}` : ''}`,
  group: (id?: string) => `/security/group.html${id ? `?id=${encodeURIComponent(id)}` : ''}`,
};
