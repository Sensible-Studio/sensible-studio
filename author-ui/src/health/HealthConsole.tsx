import { ActionButton, Button, Checkbox, Item, Menu, MenuTrigger, NumberField, Picker, ProgressBar, ProgressCircle, StatusLight, Switch, Text, ToastContainer, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useMemo, useRef, useState, type Key } from 'react';
import { relativeTime } from '../editor/shell/TopBar';
import { ProductBar } from '../shell/ProductBar';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { bytes, type Check, type HealthClient, type Run, type Settings, type Status } from './api';
import { ToolBar } from '../shell/ToolCrumbs';
import { SettingsCard, SettingsGrid } from '../shell/Settings';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: HealthClient;
  currentUser?: () => Promise<string | null>;
}

const LIGHT: Record<Check['status'], 'positive' | 'notice' | 'negative'> = { ok: 'positive', warn: 'notice', error: 'negative' };
const OVERALL: Record<Check['status'], string> = { ok: 'Everything is fine', warn: 'Worth a look', error: 'Needs attention' };

function when(iso?: string | null) {
  return iso ? relativeTime(new Date(iso)) : '';
}

function at(iso?: string | null) {
  return iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '';
}

function duration(ms?: number) {
  if (!ms) return '';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${Math.round(ms / 1000)} s`;
  return `${Math.round(ms / 60_000)} min`;
}

/**
 * Health (`/studio/health.html`): how this instance is right now — repository size and growth, disk,
 * memory, bundles, job queues, errors in the log — and the nightly maintenance (compaction of the
 * repository and purge of old versions) that runs by itself as on AEM as a Cloud Service, only visible:
 * what it recovered each night, a Run now, and its few settings.
 */
export function HealthConsole({ client, currentUser }: Props) {
  const tab = useTab();
  const [status, setStatus] = useState<Status | null>(null);
  const [runs, setRuns] = useState<Run[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [user, setUser] = useState<string | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [savingSettings, setSavingSettings] = useState(false);
  const running = status?.progress.running ?? false;
  const wasRunning = useRef(false);

  useScreenTitle(VOCAB.health, `${VOCAB.health} | ${VOCAB.product}`);
  useEffect(() => {
    if (!tab && currentUser) currentUser().then(setUser, () => setUser(null));
  }, [tab, currentUser]);

  const load = useCallback(() => {
    Promise.all([client.status(), client.runs()]).then(
      ([s, r]) => {
        setStatus(s);
        setRuns(r);
        setSettings((prev) => prev ?? s.settings);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  // while a run goes, follow it; when it ends, say what it recovered
  useEffect(() => {
    if (!running) {
      if (wasRunning.current && status?.lastRun) {
        const r = status.lastRun;
        if (status.progress.error) ToastQueue.negative(`Maintenance stopped: ${status.progress.error}`, { timeout: 8000 });
        else ToastQueue.positive(`Maintenance done in ${duration(r.millis)}: ${bytes(r.recovered)} recovered${r.versions ? `, ${r.versions.removed} versions purged` : ''}`, { timeout: 8000 });
      }
      wasRunning.current = false;
      return;
    }
    wasRunning.current = true;
    const t = window.setInterval(load, 3000);
    return () => window.clearInterval(t);
  }, [running, load, status]);

  const start = async (kind: 'all' | 'compact' | 'versions') => {
    try {
      const started = await client.run(kind);
      ToastQueue.info(started ? 'Maintenance started' : 'A run is already going', { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
    load();
  };

  const saveSettings = async () => {
    if (!settings) return;
    setSavingSettings(true);
    try {
      const s = await client.saveSettings(settings);
      setSettings(s);
      ToastQueue.positive('Settings saved', { timeout: 3000 });
      load();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
    setSavingSettings(false);
  };

  const dirty = useMemo(() => !!status && !!settings && JSON.stringify(settings) !== JSON.stringify(status.settings), [settings, status]);
  const repo = status?.checks.find((c) => c.id === 'repository');
  const samples = (repo?.details.samples as Record<string, number> | undefined) ?? {};

  return (
    <div className="studio-sites studio-health">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/studio/health.html"
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Check again</Tooltip>
          </TooltipTrigger>
        }
        primary={
          <MenuTrigger>
            <Button variant="accent" isDisabled={running}>
              <Text>Run maintenance now</Text>
              <ChevronDown size="S" />
            </Button>
            <Menu onAction={(k: Key) => void start(k as 'all' | 'compact' | 'versions')}>
              <Item key="all">Everything (compact and purge versions)</Item>
              <Item key="compact">Compact the repository only</Item>
              <Item key="versions">Purge old versions only</Item>
            </Menu>
          </MenuTrigger>
        }
      />

      <main className="studio-sites__content studio-health__body">
        {error ? (
          <ErrorState title="Could not read the instance's health">{error}</ErrorState>
        ) : !status ? (
          <LoadingState />
        ) : (
          <>
            <div className={`studio-health__overall is-${status.overall}`}>
              <StatusLight variant={LIGHT[status.overall]}>{OVERALL[status.overall]}</StatusLight>
              <span className="studio-health__muted">checked {when(status.now)}</span>
              {running && (
                <ProgressBar
                  size="S"
                  width="size-3000"
                  isIndeterminate
                  label={status.progress.phase === 'compact' ? 'Compacting the repository…' : status.progress.phase === 'versions' ? 'Purging old versions…' : 'Measuring…'}
                  UNSAFE_className="studio-health__running"
                />
              )}
            </div>

            <SettingsGrid>
              {status.checks.map((c) => (
                <SettingsCard key={c.id} title={c.title} status={<StatusLight variant={LIGHT[c.status]}>{c.status}</StatusLight>} className={`studio-health__card is-${c.status}`}>
                  <p className="studio-health__summary">{c.summary}</p>
                  <CardDetails check={c} samples={c.id === 'repository' ? samples : undefined} />
                </SettingsCard>
              ))}
            </SettingsGrid>

            <div className="studio-health__columns">
              <section className="studio-health__panel" aria-label="Maintenance history">
                <h2>Maintenance history</h2>
                {!runs ? (
                  <ProgressCircle size="S" isIndeterminate aria-label="Loading" />
                ) : runs.length === 0 ? (
                  <p className="studio-health__muted">No run yet. The first one is {status.nextRun ? at(status.nextRun) : 'when maintenance is switched on'}; or run it now.</p>
                ) : (
                  <div className="studio-listview studio-health__runs">
                    <table className="studio-table">
                      <thead>
                        <tr>
                          <th>When</th>
                          <th>What</th>
                          <th>Recovered</th>
                          <th>Versions</th>
                          <th>Took</th>
                        </tr>
                      </thead>
                      <tbody>
                        {runs.map((r) => (
                          <tr key={r.id ?? r.started}>
                            <td title={at(r.started)}>
                              {at(r.started)}
                              <span className="studio-health__muted"> · {r.by === 'scheduled' ? 'nightly' : r.by}</span>
                            </td>
                            <td>{r.kind === 'all' ? 'Compact + versions' : r.kind === 'compact' ? 'Compact' : 'Versions'}</td>
                            <td>
                              <strong>{bytes(r.recovered)}</strong>
                              <span className="studio-health__muted">
                                {' '}
                                ({bytes(r.sizeBefore)} → {bytes(r.sizeAfter)})
                              </span>
                              {r.compaction && r.compaction !== 'unavailable' && r.compaction.message && (r.compaction.status !== 4 || r.compaction.skipped) && (
                                <span className={`studio-health__note ${r.compaction.skipped ? 'studio-health__warn' : 'studio-health__muted'}`}>
                                  {r.compaction.skipped ? 'Compaction skipped: ' : ''}
                                  {r.compaction.message}
                                </span>
                              )}
                            </td>
                            <td>{r.versions ? `${r.versions.removed} of ${r.versions.versions} removed` : '—'}</td>
                            <td>{duration(r.millis)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>

              <section className="studio-health__panel studio-health__settings" aria-label="Maintenance settings">
                <h2>Nightly maintenance</h2>
                {settings && (
                  <>
                    <Switch isSelected={settings.enabled} onChange={(on) => setSettings({ ...settings, enabled: on })}>
                      Run every night
                    </Switch>
                    <Picker label="At" selectedKey={String(settings.hour)} onSelectionChange={(k) => setSettings({ ...settings, hour: Number(k) })} isDisabled={!settings.enabled} width="size-1600">
                      {Array.from({ length: 24 }, (_, h) => (
                        <Item key={String(h)}>{`${String(h).padStart(2, '0')}:00`}</Item>
                      ))}
                    </Picker>
                    <Checkbox isSelected={settings.compact} onChange={(on) => setSettings({ ...settings, compact: on })} isDisabled={!status.compactionAvailable}>
                      Compact the repository (remove old revisions)
                    </Checkbox>
                    {!status.compactionAvailable && <p className="studio-health__muted">Compaction is not available on this repository.</p>}
                    <Checkbox isSelected={settings.purgeVersions} onChange={(on) => setSettings({ ...settings, purgeVersions: on })}>
                      Purge old versions of pages, content fragments and media
                    </Checkbox>
                    <div className="studio-health__fields">
                      <NumberField label="Keep the newest" value={settings.keepVersions} minValue={1} maxValue={100} onChange={(v) => setSettings({ ...settings, keepVersions: v })} isDisabled={!settings.purgeVersions} width="size-1600" />
                      <NumberField label="and everything from the last … days" value={settings.keepDays} minValue={0} maxValue={3650} onChange={(v) => setSettings({ ...settings, keepDays: v })} isDisabled={!settings.purgeVersions} width="size-2400" />
                    </div>
                    <p className="studio-health__muted">
                      Labelled versions are always kept. The space a compaction frees shows up on the run after it (the repository keeps one generation
                      back). {status.nextRun ? `Next run ${at(status.nextRun)}.` : ''}
                    </p>
                    <div className="studio-health__actions">
                      <Button variant="secondary" onPress={() => setSettings(status.settings)} isDisabled={!dirty || savingSettings}>
                        Cancel
                      </Button>
                      <Button variant="accent" onPress={() => void saveSettings()} isDisabled={!dirty} isPending={savingSettings}>
                        Save
                      </Button>
                    </div>
                  </>
                )}
              </section>
            </div>
          </>
        )}
      </main>
      <ToastContainer placement="bottom" />
    </div>
  );
}

/** What a card shows under its summary: the numbers behind the check, and for the repository its growth. */
function CardDetails({ check, samples }: { check: Check; samples?: Record<string, number> }) {
  const d = check.details;
  switch (check.id) {
    case 'repository':
      return <Sparkline samples={samples ?? {}} />;
    case 'disk':
      return <Bar value={Number(d.total) - Number(d.free)} max={Number(d.total)} label={`${bytes(Number(d.total) - Number(d.free))} used`} />;
    case 'memory':
      return <Bar value={Number(d.used)} max={Number(d.max)} label={`${d.percent}%`} />;
    case 'bundles': {
      const notActive = (d.notActive as string[]) ?? [];
      return notActive.length ? (
        <ul className="studio-health__list">
          {notActive.map((b) => (
            <li key={b}>{b}</li>
          ))}
        </ul>
      ) : null;
    }
    case 'jobs':
      return (
        <dl className="studio-health__dl">
          <div>
            <dt>Waiting</dt>
            <dd>{String(d.queued ?? 0)}</dd>
          </div>
          <div>
            <dt>Running</dt>
            <dd>{String(d.active ?? 0)}</dd>
          </div>
          <div>
            <dt>Failed</dt>
            <dd>{String(Number(d.failed ?? 0) + Number(d.cancelled ?? 0))}</dd>
          </div>
          <div>
            <dt>Done</dt>
            <dd>{String(d.finished ?? 0)}</dd>
          </div>
        </dl>
      );
    case 'log': {
      const last = (d.last as string[]) ?? [];
      return last.length ? (
        <ul className="studio-health__log">
          {last.slice(-4).map((l, i) => (
            <li key={i} title={l}>
              {l.replace(/^\S+ \S+ \*ERROR\* (\[[^\]]*\] )?/, '')}
            </li>
          ))}
        </ul>
      ) : null;
    }
    case 'maintenance':
      return d.next ? <p className="studio-health__muted">Next run {at(String(d.next))}</p> : null;
    default:
      return null;
  }
}

function Bar({ value, max, label }: { value: number; max: number; label: string }) {
  const pct = max ? Math.min(100, (100 * value) / max) : 0;
  return (
    <div className="studio-health__bar" role="img" aria-label={label}>
      <div className="studio-health__bar-fill" style={{ width: `${pct}%` }} />
      <span>{label}</span>
    </div>
  );
}

/** The repository's size over the sampled days, as a small line. */
function Sparkline({ samples }: { samples: Record<string, number> }) {
  const points = Object.entries(samples).sort(([a], [b]) => a.localeCompare(b));
  if (points.length < 2) return <p className="studio-health__muted">Growth shows once a few days are sampled.</p>;
  const w = 220;
  const h = 48;
  const values = points.map(([, v]) => v);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const path = points.map(([, v], i) => `${i === 0 ? 'M' : 'L'}${((i / (points.length - 1)) * (w - 4) + 2).toFixed(1)},${(h - 4 - ((v - min) / span) * (h - 8)).toFixed(1)}`).join(' ');
  return (
    <div className="studio-health__spark">
      <svg viewBox={`0 0 ${w} ${h}`} width={w} height={h} role="img" aria-label={`From ${bytes(values[0])} on ${points[0][0]} to ${bytes(values[values.length - 1])} on ${points[points.length - 1][0]}`}>
        <path d={path} fill="none" stroke="currentColor" strokeWidth="1.6" />
      </svg>
      <span className="studio-health__muted">
        {points[0][0]} → {points[points.length - 1][0]}
      </span>
    </div>
  );
}
