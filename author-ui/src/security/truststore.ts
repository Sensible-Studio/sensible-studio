/** Client of `/bin/studio/truststore.json`: the certificates the instance trusts for its own HTTPS connections. */

export interface TrustedCertificate {
  alias: string;
  subject?: string;
  issuer?: string;
  /** DNS names and IP addresses it is for. */
  names?: string[];
  notBefore?: number;
  notAfter?: number;
  expired?: boolean;
  /** May sign other certificates (an authority). */
  authority?: boolean;
  selfSigned?: boolean;
  sha256?: string;
  /** Only in a check's chain: the certificate as PEM, to add it. */
  pem?: string;
  error?: string;
}

export interface TrustCheck {
  trusted: boolean;
  message: string;
  chain: TrustedCertificate[];
}

export interface TrustStoreClient {
  list(): Promise<{ certificates: TrustedCertificate[]; canEdit: boolean }>;
  check(url: string): Promise<TrustCheck>;
  /** A PEM text or a file (PEM or DER). */
  add(alias: string, certificate: string | File): Promise<{ added: string[]; certificates: TrustedCertificate[] }>;
  remove(alias: string): Promise<{ certificates: TrustedCertificate[] }>;
}

const ENDPOINT = '/bin/studio/truststore.json';

async function json<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

export const httpTrustStore: TrustStoreClient = {
  async list() {
    return json(await fetch(`${ENDPOINT}?op=list`, { credentials: 'same-origin', headers: { Accept: 'application/json' } }));
  },
  async check(url) {
    return json(await fetch(`${ENDPOINT}?op=check&url=${encodeURIComponent(url)}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } }));
  },
  async add(alias, certificate) {
    const body = new FormData();
    body.set('op', 'add');
    if (alias.trim()) body.set('alias', alias.trim());
    if (typeof certificate === 'string') body.set('certificate', certificate);
    else body.set('file', certificate);
    return json(await fetch(ENDPOINT, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' }, body }));
  },
  async remove(alias) {
    const body = new URLSearchParams({ op: 'remove', alias });
    return json(await fetch(ENDPOINT, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' }, body }));
  },
};

/** "CN=Test Root CA,O=Acme" → "Test Root CA". */
export function commonName(dn: string | undefined): string {
  if (!dn) return '';
  const cn = dn.split(/,(?=\s*[A-Za-z]+=)/).map((p) => p.trim()).find((p) => p.toUpperCase().startsWith('CN='));
  return cn ? cn.slice(3) : dn;
}

/** When a certificate stops being valid, in words. */
export function validity(c: TrustedCertificate, now = Date.now()): string {
  if (!c.notAfter) return '';
  if (c.expired || c.notAfter < now) return `expired ${new Date(c.notAfter).toLocaleDateString()}`;
  const days = Math.floor((c.notAfter - now) / 86_400_000);
  if (days < 1) return 'expires today';
  if (days <= 60) return `expires in ${days} day${days === 1 ? '' : 's'}`;
  return `valid until ${new Date(c.notAfter).toLocaleDateString()}`;
}
