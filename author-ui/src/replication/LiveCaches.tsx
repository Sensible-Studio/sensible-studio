import { ActionButton, Button, DialogContainer, ToastQueue } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { formatWhen } from '../msm/LiveCopyStatus';
import type { LiveCacheInfo, ReplicationClient } from './api';
import { SettingsCard, SettingsGrid } from '../shell/Settings';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { RedirectMapDialog, type RedirectMapIO } from './RedirectMap';
import { UrlTesterDialog } from './UrlTester';

interface Props {
  client: Pick<ReplicationClient, 'caches' | 'clearCache'> & Partial<Pick<ReplicationClient, 'explainUrl'>>;
  /** Changes when the console refreshes. */
  tick: number;
  /** For the tests: how a redirect map is read, saved and published. */
  redirectIO?: RedirectMapIO;
}

const count = (n: number, one: string, many: string) => `${n.toLocaleString()} ${n === 1 ? one : many}`;

/** What the cache did since it started, in words; only what happened. */
export function activity(c: LiveCacheInfo): string {
  const parts: string[] = [];
  if (c.stored) parts.push(`${count(c.stored, 'answer', 'answers')} fetched and kept`);
  if (c.hits) parts.push(`${c.hits.toLocaleString()} answered from the cache by the service`);
  if (c.passed) parts.push(`${c.passed.toLocaleString()} passed on without keeping`);
  if (c.stale) parts.push(`${c.stale.toLocaleString()} served as they were while the publish did not answer`);
  if (c.graced) parts.push(`${c.graced.toLocaleString()} served as they were while fetched again after a publication`);
  if (c.redirected) parts.push(`${c.redirected.toLocaleString()} redirected by a redirect map`);
  if (c.checked) parts.push(`${c.checked.toLocaleString()} protected pages served after the publish said yes`);
  if (c.refused) parts.push(`${c.refused.toLocaleString()} protected pages refused`);
  if (c.denied) parts.push(`${c.denied.toLocaleString()} refused by the filter`);
  if (c.errors) parts.push(`${c.errors.toLocaleString()} failed`);
  return parts.length ? parts.join(' · ') : 'nothing asked of it yet';
}

/**
 * One card per cache: several publish instances tell the same cache (each has its own flush
 * target), so it is reported once for each; the first report stands for it, the others' agents are
 * named beside it.
 */
export function oneEach(list: LiveCacheInfo[]): (LiveCacheInfo & { alsoVia?: string[] })[] {
  const out: (LiveCacheInfo & { alsoVia?: string[] })[] = [];
  for (const c of list) {
    const same = c.target ? out.find((o) => o.target === c.target && o.id === c.id) : undefined;
    if (same) {
      if (c.viaTitle) same.alsoVia = [...(same.alsoVia ?? []), c.viaTitle];
      // not told by one of them: say so
      if (!c.enabled) same.enabled = false;
    } else {
      out.push({ ...c });
    }
  }
  return out;
}

/**
 * The caches in front of the published site (the Live cache), in the Publishing console: what each
 * holds, what it was last told, the publish instances it fetches from, and emptying it. An author
 * gets them from the publish instances it delivers to; nothing is shown where there is none.
 */
export function LiveCaches({ client, tick, redirectIO }: Props) {
  const [caches, setCaches] = useState<LiveCacheInfo[]>([]);
  const [canClear, setCanClear] = useState(false);
  const [confirm, setConfirm] = useState<LiveCacheInfo | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ map: string; site: string } | null>(null);
  const [testing, setTesting] = useState<LiveCacheInfo | null>(null);

  useEffect(() => {
    let live = true;
    client.caches().then(
      (r) => {
        if (!live) return;
        setCaches(oneEach(r.caches ?? []));
        setCanClear(!!r.canClear);
      },
      // an instance older than this, or none to ask: no caches to show
      () => live && setCaches([]),
    );
    return () => {
      live = false;
    };
  }, [client, tick]);

  const clear = async (c: LiveCacheInfo) => {
    setBusy(c.id);
    try {
      const r = await client.clearCache(c.id, c.via);
      if (r.caches) setCaches(oneEach(r.caches));
      if (r.ok) ToastQueue.positive(`${c.title}: emptied (${r.message})`, { timeout: 4000 });
      else ToastQueue.negative(`${c.title}: ${r.message}`, { timeout: 8000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    } finally {
      setBusy(null);
    }
  };

  if (caches.length === 0) return null;
  return (
    <section className="studio-replication__caches" aria-label="Cache">
      <h3>Cache in front of the published site</h3>
      <SettingsGrid>
      {caches.map((c) => {
        const kept = c.files ? c.files.plain + c.files.held : null;
        const away = (c.publish ?? []).filter((p) => p.skipped).length;
        return (
          <SettingsCard
            key={`${c.via ?? ''}/${c.id}`}
            title={c.title}
            description={`${c.id}${c.viaTitle ? ` · behind ${[c.viaTitle, ...((c as { alsoVia?: string[] }).alsoVia ?? [])].join(', ')}` : ''}`}
            status={!c.ok ? 'Not answering' : !c.enabled ? 'Disabled' : away ? 'Degraded' : 'Running'}
            className="studio-replication__agent studio-replication__cache"
            actions={
              c.ok && (canClear || client.explainUrl) ? (
                <>
                  {client.explainUrl && (
                    <Button variant="secondary" onPress={() => setTesting(c)}>
                      Test a URL
                    </Button>
                  )}
                  {canClear && (
                    <Button variant="negative" isPending={busy === c.id} onPress={() => setConfirm(c)}>
                      Clear cache
                    </Button>
                  )}
                </>
              ) : undefined
            }
          >
            <dl>
              <dt>Address</dt>
              <dd>
                <code>{c.target ?? '—'}</code>
              </dd>
              <dt>State</dt>
              <dd>
                {!c.ok ? (
                  <span className="studio-replication__error">Not answering: {c.message ?? 'no answer'}</span>
                ) : !c.enabled ? (
                  'Running, but its target is disabled: it is not told what is published'
                ) : (
                  'Running, and told about every publication'
                )}
              </dd>
              {c.ok && kept !== null && (
                <>
                  <dt>Holds</dt>
                  <dd>
                    {count(kept, 'page or file', 'pages and files')}
                    {c.files && c.files.stale > 0 && <span className="studio-replication__muted"> · {c.files.stale.toLocaleString()} kept from before the last publications, for when the publish does not answer</span>}
                  </dd>
                </>
              )}
              {c.ok && (
                <>
                  <dt>Since it started</dt>
                  <dd>
                    {activity(c)}
                    <span className="studio-replication__muted"> · what the web server serves from its files is not counted</span>
                  </dd>
                  <dt>Last told</dt>
                  <dd>
                    {c.lastInvalidation ? (
                      <>
                        {c.lastInvalidation.action === 'Clear' ? 'Emptied' : c.lastInvalidation.action} {c.lastInvalidation.action !== 'Clear' && <code>{c.lastInvalidation.path}</code>}{' '}
                        {formatWhen(new Date(c.lastInvalidation.at).toISOString())}
                        <span className="studio-replication__muted"> · {count(c.lastInvalidation.removed, 'file', 'files')} taken out</span>
                      </>
                    ) : (
                      'nothing yet'
                    )}
                  </dd>
                  {c.sites && c.sites.length > 0 && (
                    <>
                      <dt>Sites</dt>
                      <dd>
                        <ul className="studio-replication__sites">
                          {c.sites.map((s) => (
                            <li key={s.name}>
                              <strong>{s.name}</strong> <span className="studio-replication__muted">{s.hosts.join(', ')}</span>
                              <br />
                              <span className="studio-replication__muted">
                                {s.roots.join(', ')}
                                {s.files ? ` · ${count(s.files.plain + s.files.held, 'file', 'files')}` : ''}
                                {s.tracked ? ` · ${s.tracked.toLocaleString()} saying what they show` : ''}
                                {s.redirects ? ` · ${count(s.redirects.rules, 'redirect', 'redirects')} from ${s.redirects.map}` : ''}
                                {s.protected ? ` · protected ${s.protected.globs.join(', ')}${s.protected.files ? ` (${s.protected.files.toLocaleString()} kept)` : ''}` : ''}
                              </span>
                              {s.redirects && canClear && (
                                <ActionButton isQuiet onPress={() => setEditing({ map: s.redirects!.map, site: s.name })} UNSAFE_className="studio-replication__siteaction">
                                  Edit redirects
                                </ActionButton>
                              )}
                            </li>
                          ))}
                        </ul>
                      </dd>
                    </>
                  )}
                  {c.vanityUrls && (
                    <>
                      <dt>Vanity URLs</dt>
                      <dd>
                        {count(c.vanityUrls.count, 'vanity URL', 'vanity URLs')} let through
                        {c.vanityUrls.readAt > 0 && <span className="studio-replication__muted"> · read {formatWhen(new Date(c.vanityUrls.readAt).toISOString())}</span>}
                      </dd>
                    </>
                  )}
                  {c.publish && c.publish.length > 0 && (
                    <>
                      <dt>Fetches from</dt>
                      <dd>
                        <ul>
                          {c.publish.map((p) => (
                            <li key={p.url}>
                              <code>{p.url}</code>
                              {p.skipped && <span className="studio-replication__error"> · not answering, left out for now</span>}
                            </li>
                          ))}
                        </ul>
                      </dd>
                    </>
                  )}
                </>
              )}
            </dl>
          </SettingsCard>
        );
      })}
      </SettingsGrid>
      <DialogContainer onDismiss={() => setTesting(null)}>
        {testing && client.explainUrl && <UrlTesterDialog cache={testing} explain={client.explainUrl.bind(client)} onClose={() => setTesting(null)} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && <RedirectMapDialog map={editing.map} site={editing.site} onClose={() => setEditing(null)} io={redirectIO} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setConfirm(null)}>
        {confirm && (
          <StudioConfirmDialog
            title={`Clear ${confirm.title}?`}
            variant="destructive"
            primaryActionLabel="Clear cache"
            cancelLabel="Cancel"
            onPrimaryAction={() => void clear(confirm)}
          >
            Everything it holds is removed, the pages kept for when the publish does not answer included. Each page is rendered by the publish
            again the next time someone asks for it. Use it after new code was deployed; publishing a page does not need it.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </section>
  );
}
