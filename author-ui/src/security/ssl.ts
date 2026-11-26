/** Client of `/bin/studio/ssl.json`: HTTPS for this instance (Tools › Security › SSL Configuration). */
import type { TrustedCertificate } from './truststore';

export type SslChangeState = 'applying' | 'done' | 'rolledBack' | 'failed';

export interface SslChange {
  state: SslChangeState;
  message: string;
  what: string;
  at: number;
}

export interface SslStatus {
  httpPort: number;
  enabled: boolean;
  port: number | null;
  hostname: string;
  url: string | null;
  /** The key store's certificates, the server's own first. */
  certificate: Omit<TrustedCertificate, 'alias'>[];
  keystoreError?: string;
  /** HTTPS answers on the port. */
  listening: boolean;
  /** SHA-256 of the certificate it shows. */
  serves?: string | null;
  suggestedPort: number;
  last: SslChange | null;
  canEdit?: boolean;
}

/** What the wizard sends. */
export interface SslRequest {
  selfSigned: boolean;
  key?: File | string;
  certificate?: File | string;
  hostname: string;
  port: number;
}

export interface SslCheck {
  certificate: Omit<TrustedCertificate, 'alias'>[];
  warnings: string[];
  hostname: string;
  port: number;
  url: string;
}

export interface SslClient {
  status(): Promise<SslStatus>;
  check(r: SslRequest): Promise<SslCheck>;
  apply(r: SslRequest): Promise<SslStatus>;
  disable(): Promise<SslStatus>;
  enable(): Promise<SslStatus>;
}

const ENDPOINT = '/bin/studio/ssl.json';

async function json<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

function form(op: string, r?: SslRequest): FormData {
  const body = new FormData();
  body.set('op', op);
  if (!r) return body;
  body.set('hostname', r.hostname.trim());
  body.set('port', String(r.port));
  if (r.selfSigned) {
    body.set('selfSigned', 'true');
    return body;
  }
  if (r.key instanceof File) body.set('key', r.key);
  else if (r.key) body.set('keyText', r.key);
  if (r.certificate instanceof File) body.set('certificate', r.certificate);
  else if (r.certificate) body.set('certificateText', r.certificate);
  return body;
}

const post = async <T,>(op: string, r?: SslRequest) =>
  json<T>(await fetch(ENDPOINT, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' }, body: form(op, r) }));

export const httpSsl: SslClient = {
  async status() {
    return json(await fetch(`${ENDPOINT}?op=status`, { credentials: 'same-origin', headers: { Accept: 'application/json' }, cache: 'no-store' }));
  },
  check: (r) => post('check', r),
  apply: (r) => post('apply', r),
  disable: () => post('disable'),
  enable: () => post('enable'),
};

/** A port as the field gives it: a whole number from 1 to 65535, else null. */
export function portOf(text: string): number | null {
  const t = text.trim();
  if (!/^\d{1,5}$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 && n <= 65535 ? n : null;
}

/** How the change that started (the `last` an apply answered with) ended, or null while it is being made. */
export function finished(s: SslStatus | null, started: SslChange | null): SslChange | null {
  const l = s?.last;
  if (!l || l.state === 'applying') return null;
  return !started || l.at > started.at || l.state !== started.state ? l : null;
}
