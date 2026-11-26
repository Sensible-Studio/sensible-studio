/** Client of `/bin/studio/oauth.json`: the OAuth clients of this instance and the tokens they were given. */

export type OAuthScope = 'read' | 'write' | 'offline_access';
export const OAUTH_SCOPES: Array<{ key: OAuthScope; label: string; description: string }> = [
  { key: 'read', label: 'Read', description: 'GET, HEAD: what its user may read' },
  { key: 'write', label: 'Write', description: 'any request: what its user may change' },
  { key: 'offline_access', label: 'Offline access', description: 'a refresh token, so that it goes on without the user (30 days at a time)' },
];

export interface OAuthClient {
  id: string;
  name: string;
  redirectUris: string[];
  scopes: OAuthScope[];
  /** Client credentials: the user its tokens act as, without anybody allowing it each time. */
  runAs: string;
  created?: number;
  createdBy?: string;
  /** Only in the answer that made it, or a new secret's. */
  secret?: string;
}

export interface OAuthToken {
  id: string;
  kind: 'access' | 'refresh';
  client: string;
  clientName: string;
  user: string;
  scopes: OAuthScope[];
  created: number;
  expires: number;
  lastUsed?: number;
}

export interface OAuthClientInput {
  name: string;
  redirectUris: string;
  scopes: OAuthScope[];
  runAs: string;
}

export interface OAuthAdminClient {
  clients(): Promise<{ admin: boolean; clients: OAuthClient[] }>;
  tokens(): Promise<{ admin: boolean; user: string; tokens: OAuthToken[] }>;
  create(input: OAuthClientInput): Promise<{ client: OAuthClient; clients: OAuthClient[] }>;
  update(id: string, input: OAuthClientInput): Promise<{ clients: OAuthClient[] }>;
  newSecret(id: string): Promise<{ secret: string }>;
  remove(id: string): Promise<{ clients: OAuthClient[] }>;
  revoke(tokenId: string): Promise<{ tokens: OAuthToken[] }>;
}

const ENDPOINT = '/bin/studio/oauth.json';

async function json<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

function form(op: string, input?: OAuthClientInput, extra: Record<string, string> = {}): URLSearchParams {
  const body = new URLSearchParams({ op, ...extra });
  if (input) {
    body.set('name', input.name);
    body.set('redirectUris', input.redirectUris);
    body.set('runAs', input.runAs);
    input.scopes.forEach((s) => body.append('scope', s));
  }
  return body;
}

const post = async <T>(body: URLSearchParams) =>
  json<T>(await fetch(ENDPOINT, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' }, body }));
const get = async <T>(op: string) => json<T>(await fetch(`${ENDPOINT}?op=${op}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } }));

export const httpOAuth: OAuthAdminClient = {
  clients: () => get('clients'),
  tokens: () => get('tokens'),
  create: (input) => post(form('createClient', input)),
  update: (id, input) => post(form('updateClient', input, { id })),
  newSecret: (id) => post(form('secret', undefined, { id })),
  remove: (id) => post(form('deleteClient', undefined, { id })),
  revoke: (tokenId) => post(form('revoke', undefined, { id: tokenId })),
};

/** How long until, or since, in words. */
export function relative(at: number, now = Date.now()): string {
  const d = at - now;
  const abs = Math.abs(d);
  const unit = abs < 3_600_000 ? [60_000, 'minute'] : abs < 86_400_000 ? [3_600_000, 'hour'] : [86_400_000, 'day'];
  const n = Math.max(1, Math.round(abs / (unit[0] as number)));
  const words = `${n} ${unit[1]}${n === 1 ? '' : 's'}`;
  return d >= 0 ? `in ${words}` : `${words} ago`;
}
