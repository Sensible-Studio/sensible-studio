import { Button, Text, TextArea, ToastQueue } from '@adobe/react-spectrum';
import { useEffect, useMemo, useState } from 'react';
import { StudioDialog } from '../shell/StudioDialog';

/** One rule of a redirect map, as the Live cache reads it. */
export interface RedirectRule {
  source: string;
  target: string;
  status: number;
}

export interface BadLine {
  line: number;
  text: string;
  why: string;
}

const STATUSES = [301, 302, 303, 307, 308];

/**
 * A redirect map as the Live cache reads it (`parse_redirect_map` in live-service): one rule a line,
 * `<source> <target> [301|302|303|307|308]`, `#` for a comment; the first rule for a source wins.
 */
export function parseRedirectMap(text: string): { rules: RedirectRule[]; bad: BadLine[] } {
  const rules: RedirectRule[] = [];
  const bad: BadLine[] = [];
  const seen = new Set<string>();
  text.split(/\r?\n/).forEach((raw, i) => {
    const line = raw.trim();
    if (!line || line.startsWith('#')) return;
    const fields = line.split(/\s+/);
    const [source, target, code] = fields;
    const why =
      fields.length > 3
        ? 'more than a source, a target and a status'
        : !source.startsWith('/') || source.includes(';') || source.includes('..') || /[\u0000-\u001f\\]/.test(source)
          ? 'the source is not a path'
          : !target
            ? 'no target'
            : !((target.startsWith('/') && !target.startsWith('//')) || /^https?:\/\//.test(target)) || /[|\s]/.test(target) || /[^\x21-\x7e]/.test(target)
              ? 'the target is not a path or an http(s) URL'
              : code !== undefined && !STATUSES.includes(Number(code))
                ? `the status is not ${STATUSES.join(', ')}`
                : null;
    if (why) {
      bad.push({ line: i + 1, text: line, why });
      return;
    }
    if (seen.has(source)) return;
    seen.add(source);
    rules.push({ source, target, status: code === undefined ? 301 : Number(code) });
  });
  return { rules, bad };
}

interface Props {
  /** The map's path in Media (`/content/dam/<site>/redirectmap.txt`). */
  map: string;
  site: string;
  onClose(): void;
  /** For the tests: how the map is read, saved and published. */
  io?: RedirectMapIO;
}

export interface RedirectMapIO {
  read(map: string): Promise<string | null>;
  save(map: string, text: string): Promise<void>;
  publish(map: string): Promise<void>;
}

const CSRF = { 'X-Studio-CSRF': '1' };

/** The map read from and written to Media on the author, published through the usual queue. */
export const httpRedirectMapIO: RedirectMapIO = {
  async read(map) {
    const res = await fetch(map, { credentials: 'same-origin', cache: 'no-store' });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`${res.status} reading ${map}`);
    return res.text();
  },
  async save(map, text) {
    const slash = map.lastIndexOf('/');
    const body = new FormData();
    body.set('folder', map.slice(0, slash));
    body.set('conflict', 'replace');
    body.set('file', new File([text], map.slice(slash + 1), { type: 'text/plain' }));
    const res = await fetch('/bin/studio/dam/upload', { method: 'POST', credentials: 'same-origin', headers: CSRF, body });
    if (!res.ok) throw new Error(`${res.status} saving ${map}`);
  },
  async publish(map) {
    const body = new URLSearchParams({ op: 'activate', path: map });
    const res = await fetch('/bin/studio/replication.json', { method: 'POST', credentials: 'same-origin', body });
    if (!res.ok) throw new Error(`${res.status} publishing ${map}`);
  },
};

/**
 * Edit a site's redirect map, AEM's Redirect Map Manager in its simplest form: the rules as text,
 * checked line by line as the cache will read them, saved to Media and published.
 */
export function RedirectMapDialog({ map, site, onClose, io = httpRedirectMapIO }: Props) {
  const [text, setText] = useState<string | null>(null);
  const [original, setOriginal] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    io.read(map).then(
      (t) => {
        const value = t ?? `# Redirects of ${site}: <source> <target> [301|302|303|307|308]\n`;
        setText(value);
        setOriginal(t ?? '');
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [io, map, site]);

  const parsed = useMemo(() => parseRedirectMap(text ?? ''), [text]);

  const save = async (andPublish: boolean) => {
    if (text === null) return;
    setBusy(true);
    try {
      await io.save(map, text);
      if (andPublish) await io.publish(map);
      ToastQueue.positive(andPublish ? `${parsed.rules.length} redirects saved and published` : `${parsed.rules.length} redirects saved`, { timeout: 4000 });
      onClose();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
      setBusy(false);
    }
  };

  return (
    <StudioDialog
      title={`Redirects of ${site}`}
      subject={map}
      size="L"
      onCancel={onClose}
      busy={busy}
      primaryLabel="Save and publish"
      onPrimary={() => void save(true)}
      primaryDisabled={text === null || parsed.bad.length > 0}
      extras={
        <Button variant="secondary" onPress={() => void save(false)} isDisabled={busy || text === null || parsed.bad.length > 0 || text === original}>
          Save
        </Button>
      }
    >
      {error ? (
        <Text UNSAFE_className="studio-replication__error">{error}</Text>
      ) : text === null ? (
        <Text>Loading…</Text>
      ) : (
        <div className="studio-redirectmap">
          <TextArea
            aria-label="Redirect rules"
            value={text}
            onChange={setText}
            width="100%"
            height="size-3600"
            UNSAFE_className="studio-redirectmap__text"
          />
          <p className="studio-replication__muted">
            {parsed.rules.length} {parsed.rules.length === 1 ? 'redirect' : 'redirects'}. One a line: the path visitors ask for, where they go (a path or
            an http(s) URL), and 301 (the default), 302, 303, 307 or 308. The web server answers them once published, before any page or cached file.
          </p>
          {parsed.bad.length > 0 && (
            <ul className="studio-redirectmap__bad" aria-label="Lines the cache would leave out">
              {parsed.bad.map((b) => (
                <li key={b.line} className="studio-replication__error">
                  Line {b.line}: {b.why} (<code>{b.text}</code>)
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </StudioDialog>
  );
}
