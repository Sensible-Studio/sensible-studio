import { ComboBox, Flex, Item, Picker, Text, TextField } from '@adobe/react-spectrum';
import { useMemo, useState } from 'react';
import type { LiveCacheExplanation, LiveCacheInfo, ReplicationClient } from './api';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  cache: LiveCacheInfo;
  explain: ReplicationClient['explainUrl'];
  onClose(): void;
}

/** One word for what happens to the request, and how it should look. */
export function verdict(e: LiveCacheExplanation): { label: string; tone: 'refused' | 'redirect' | 'served' | 'kept' | 'passed' } {
  if (e.answer && e.answer >= 300 && e.answer < 400) return { label: `Redirected (${e.answer})`, tone: 'redirect' };
  if (e.allowed === false) return { label: `Refused (${e.answer ?? 404})`, tone: 'refused' };
  if (e.inCache === 'plain' && e.cacheable) return { label: 'Served by the web server', tone: 'served' };
  if (e.cacheable && e.inCache === 'stale') return { label: 'Fetched again when asked', tone: 'kept' };
  if (e.cacheable) return { label: e.inCache ? 'Answered from the cache' : 'Fetched and kept', tone: 'kept' };
  return { label: 'Always asked of the publish', tone: 'passed' };
}

/** How Sling reads the path: the resource, its selectors, extension and suffix. */
export function slingParts(e: LiveCacheExplanation): string | null {
  if (!e.sling) return null;
  const parts = [e.sling.path];
  if (e.sling.selectors.length) parts.push(`selectors ${e.sling.selectors.join('.')}`);
  if (e.sling.extension) parts.push(`extension ${e.sling.extension}`);
  if (e.sling.suffix) parts.push(`suffix ${e.sling.suffix}`);
  return parts.join(' · ');
}

const METHODS = ['GET', 'HEAD', 'POST'];

/**
 * The URL tester of one cache: what it would do with a request from a visitor who is not signed
 * in (the filter, whether it keeps the answer, where the answer is now, a redirect), asked of the
 * cache itself through the publish instance that reported it.
 */
export function UrlTesterDialog({ cache, explain, onClose }: Props) {
  const hosts = useMemo(() => {
    const all = (cache.sites ?? []).flatMap((s) => s.hosts.filter((h) => !h.includes('*')));
    return [...new Set(all)].map((h) => ({ id: h }));
  }, [cache.sites]);
  const [url, setUrl] = useState('');
  const [host, setHost] = useState(hosts[0]?.id ?? '');
  const [method, setMethod] = useState('GET');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<LiveCacheExplanation | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    const asked = url.trim();
    if (!asked) return;
    setBusy(true);
    setError(null);
    try {
      const r = await explain(cache.id, cache.via, asked.startsWith('/') ? asked : `/${asked}`, { method, host: host.trim() || undefined });
      if (r.ok && r.explain) setResult(r.explain);
      else {
        setResult(null);
        setError(r.message ?? 'The cache did not answer');
      }
    } catch (e) {
      setResult(null);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const v = result ? verdict(result) : null;
  return (
    <StudioDialog
      title="Test a URL"
      subject={cache.title}
      size="L"
      onCancel={onClose}
      cancelLabel="Close"
      busy={busy}
      primaryLabel="Test"
      onPrimary={() => void run()}
      primaryDisabled={!url.trim()}
    >
      <div className="studio-urltester">
        <Flex gap="size-150" wrap alignItems="end">
          <TextField
            label="URL"
            value={url}
            onChange={setUrl}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void run();
              else e.continuePropagation();
            }}
            description="e.g. /content/my-site/us/en.html?x=1"
            width="size-4600"
            autoFocus
          />
          {hosts.length > 0 && (
            <ComboBox label="Host" allowsCustomValue inputValue={host} onInputChange={setHost} defaultItems={hosts} width="size-2400">
              {(h) => <Item key={h.id}>{h.id}</Item>}
            </ComboBox>
          )}
          <Picker label="Method" selectedKey={method} onSelectionChange={(k) => setMethod(String(k))} width="size-1200">
            {METHODS.map((m) => (
              <Item key={m}>{m}</Item>
            ))}
          </Picker>
        </Flex>
        <p className="studio-replication__muted">
          As a visitor who is not signed in. The path the cache sees: short URLs such as <code>/us/en.html</code> are the web server&apos;s rewrite rules and
          reach it as their full path, while the site&apos;s redirect map and vanity URLs are answered as typed.
        </p>
        {error && <Text UNSAFE_className="studio-replication__error">{error}</Text>}
        {result && v && (
          <section className="studio-urltester__result" aria-label="What the cache would do">
            <p className={`studio-urltester__verdict studio-urltester__verdict--${v.tone}`}>
              <strong>{v.label}</strong>
            </p>
            <p>{result.summary}</p>
            <dl>
              {result.site && (
                <>
                  <dt>Site</dt>
                  <dd>{result.site}</dd>
                </>
              )}
              {result.path && (
                <>
                  <dt>Path</dt>
                  <dd>
                    <code>{result.path}</code>
                    {result.shortUrl && <span className="studio-replication__muted"> · a short URL, read below /content</span>}
                  </dd>
                </>
              )}
              {result.location && (
                <>
                  <dt>Goes to</dt>
                  <dd>
                    <code>{result.location}</code>
                  </dd>
                </>
              )}
              {slingParts(result) && (
                <>
                  <dt>Read by Sling as</dt>
                  <dd>{slingParts(result)}</dd>
                </>
              )}
              {result.decidedBy && (
                <>
                  <dt>Decided by</dt>
                  <dd>{result.decidedBy}</dd>
                </>
              )}
              {result.allowed && !result.location && (
                <>
                  <dt>Kept</dt>
                  <dd>{result.cacheable ? (result.protected ? 'Yes, apart, shown only to whom the publish lets in' : 'Yes') : `No: ${result.notCacheableBecause ?? 'not kept'}`}</dd>
                </>
              )}
              {result.allowed && !result.location && result.cacheable && (
                <>
                  <dt>Now</dt>
                  <dd>
                    {result.inCache === 'plain'
                      ? 'a file the web server serves'
                      : result.inCache === 'held'
                        ? 'kept with its headers, answered by the cache'
                        : result.inCache === 'stale'
                          ? 'only a copy from before the last publications'
                          : 'not in the cache'}
                    {result.file && result.cacheable && <span className="studio-replication__muted"> · {result.file}</span>}
                  </dd>
                </>
              )}
            </dl>
          </section>
        )}
      </div>
    </StudioDialog>
  );
}
