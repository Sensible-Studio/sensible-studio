import './jobs.scss';
import { ActionButton, Button, NumberField, Text, ToastContainer, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useState } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { ProductBar } from '../shell/ProductBar';
import { Segmented } from '../shell/Segmented';
import { Link, useScreenTitle, useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { delayLabel, itemUrl, type JobCounts, type JobFilter, type JobsClient, type QueueConfig, type StudioJob } from './api';

export const JOBS_URL = '/mnt/overlay/granite/async/content/asyncjobs.html';

const FILTERS: Array<{ key: JobFilter; label: string; count?: keyof JobCounts }> = [
  { key: 'all', label: 'All' },
  { key: 'waiting', label: 'Waiting', count: 'waiting' },
  { key: 'running', label: 'Running', count: 'running' },
  { key: 'failed', label: 'Failed', count: 'failed' },
  { key: 'done', label: 'Done' },
];

const STATE_LABEL: Record<StudioJob['state'], string> = {
  waiting: 'Waiting',
  retrying: 'Trying again',
  running: 'Running',
  failed: 'Failed',
  done: 'Done',
  stopped: 'Stopped',
};

const when = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : '');
const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * AEM's Jobs (Tools › General › Jobs): the work Sensible Studio does in the background — media processing, auto tags,
 * smart crops, publishing, rollouts, flows, translations — waiting, running, failed or done, with Retry and Remove;
 * and the Queues that run it: how many at once, how often a failed job is tried again and after how long.
 */
export function JobsConsole({ client, currentUser }: { client: JobsClient; currentUser?(): Promise<string> }) {
  const tab = useTab();
  const [user, setUser] = useState<string | null>(null);
  const [view, setView] = useState<'jobs' | 'queues'>('jobs');
  useScreenTitle('Jobs', 'Jobs | Sensible Studio');
  useEffect(() => {
    if (!tab && currentUser) currentUser().then(setUser, () => setUser(null));
  }, [tab, currentUser]);
  const [tick, setTick] = useState(0);

  return (
    <div className="studio-sites studio-jobs">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url={JOBS_URL}
        title="Jobs"
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={() => setTick((t) => t + 1)}>
              <Refresh />
            </ActionButton>
            <Tooltip>Look again</Tooltip>
          </TooltipTrigger>
        }
      >
        <Segmented
          label="Jobs or queues"
          items={[
            { key: 'jobs', label: 'Jobs' },
            { key: 'queues', label: 'Queues' },
          ]}
          value={view}
          onChange={(k) => setView(k as 'jobs' | 'queues')}
        />
      </ToolBar>
      <main className="studio-sites__content studio-jobs__content">
        {view === 'jobs' ? <JobsList key={tick} client={client} /> : <QueuesList key={tick} client={client} />}
      </main>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function JobsList({ client }: { client: JobsClient }) {
  const [filter, setFilter] = useState<JobFilter>('all');
  const [jobs, setJobs] = useState<StudioJob[] | null>(null);
  const [counts, setCounts] = useState<JobCounts | null>(null);
  const [admin, setAdmin] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    client.list(filter).then(
      (r) => {
        setJobs(r.jobs);
        setCounts(r.counts);
        setAdmin(r.admin);
        setError(null);
      },
      (e: unknown) => setError(message(e)),
    );
  }, [client, filter]);
  useEffect(() => {
    setJobs(null);
    load();
    // a busy queue changes by the second: look again now and then
    const timer = window.setInterval(load, 10_000);
    return () => window.clearInterval(timer);
  }, [load]);

  const act = async (j: StudioJob, what: 'retry' | 'remove') => {
    setBusy(j.id);
    try {
      if (what === 'retry') await client.retry(j.id);
      else await client.remove(j.id);
      ToastQueue.positive(what === 'retry' ? `${j.kind}: tried again` : `${j.kind}: removed`, { timeout: 3000 });
      load();
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <div className="studio-jobs__filters">
        <Segmented
          label="Which jobs"
          items={FILTERS.map((f) => ({ key: f.key, label: f.count && counts ? `${f.label} (${counts[f.count]})` : f.label }))}
          value={filter}
          onChange={(k) => setFilter(k as JobFilter)}
        />
      </div>
      {error ? (
        <ErrorState title="Could not read the jobs">{error}</ErrorState>
      ) : !jobs ? (
        <LoadingState />
      ) : jobs.length === 0 ? (
        <EmptyState title={filter === 'failed' ? 'Nothing failed' : 'No jobs'}>
          {filter === 'all' ? 'Nothing runs in the background right now, and nothing ran lately.' : 'None in this state.'}
        </EmptyState>
      ) : (
        <div className="studio-listview">
          <table className="studio-table" aria-label="Jobs">
            <thead>
              <tr>
                <th>Job</th>
                <th>On</th>
                <th>State</th>
                <th>Created</th>
                <th>Finished</th>
                <th>Tries</th>
                {admin && <th aria-label="Actions" />}
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => {
                const url = itemUrl(j.item);
                return (
                  <tr key={j.id}>
                    <td>
                      <strong>{j.kind}</strong>
                      {(j.action || j.user) && <div className="studio-jobs__sub">{[j.action, j.user && `by ${j.user}`].filter(Boolean).join(' · ')}</div>}
                    </td>
                    <td className="studio-jobs__item">{url ? <Link to={url}>{j.item}</Link> : (j.item ?? '')}</td>
                    <td>
                      <span className={`studio-jobs__state is-${j.state}`}>{STATE_LABEL[j.state] ?? j.state}</span>
                      {j.message && <div className="studio-jobs__message">{j.message}</div>}
                    </td>
                    <td className="studio-listview__muted">{when(j.created)}</td>
                    <td className="studio-listview__muted">{when(j.finished)}</td>
                    <td className="studio-listview__muted">{j.maxTries < 0 ? j.tries : `${j.tries} of ${j.maxTries}`}</td>
                    {admin && (
                      <td className="studio-jobs__actions">
                        {(j.state === 'failed' || j.state === 'stopped') && (
                          <Button variant="secondary" style="outline" isDisabled={busy === j.id} onPress={() => void act(j, 'retry')}>
                            Retry
                          </Button>
                        )}
                        <Button variant="secondary" style="outline" isDisabled={busy === j.id} onPress={() => void act(j, 'remove')}>
                          {j.state === 'running' || j.state === 'waiting' || j.state === 'retrying' ? 'Stop' : 'Remove'}
                        </Button>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

/** A queue's settings while they are changed. */
interface Draft {
  maxParallel: number;
  retries: number;
  /** seconds */
  delay: number;
}

const draftOf = (q: QueueConfig): Draft => ({ maxParallel: q.maxParallel, retries: q.retries, delay: Math.round(q.retryDelay / 1000) });

function QueuesList({ client }: { client: JobsClient }) {
  const [queues, setQueues] = useState<QueueConfig[] | null>(null);
  const [admin, setAdmin] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    client.queues().then(
      (r) => {
        setQueues(r.queues);
        setAdmin(r.admin);
        setDrafts(Object.fromEntries(r.queues.map((q) => [q.pid, draftOf(q)])));
        setError(null);
      },
      (e: unknown) => setError(message(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  const save = async (q: QueueConfig) => {
    const d = drafts[q.pid];
    setBusy(q.pid);
    try {
      await client.configure(q.pid, { maxParallel: d.maxParallel, retries: d.retries, retryDelay: d.delay * 1000 });
      ToastQueue.positive(`${q.name}: saved. The queue runs this way now.`, { timeout: 4000 });
      load();
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  const suspend = async (name: string, on: boolean) => {
    setBusy(name);
    try {
      await client.suspend(name, on);
      load();
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  if (error) return <ErrorState title="Could not read the queues">{error}</ErrorState>;
  if (!queues) return <LoadingState />;
  return (
    <div className="studio-jobs__queues">
      <p className="studio-listview__muted">
        Each queue runs some kinds of jobs. A failed job waits the delay, then is tried again, as many times as it says
        {admin ? '; changes apply at once.' : '. Only administrators change them.'}
      </p>
      <ul>
        {queues.map((q) => {
          const d = drafts[q.pid] ?? draftOf(q);
          const ordered = q.type === 'ORDERED';
          const dirty = JSON.stringify(d) !== JSON.stringify(draftOf(q));
          return (
            <li key={q.pid} className="studio-jobs__queue" aria-label={q.name}>
              <header>
                <h2>{q.name.replace('{0}', '(one per agent)')}</h2>
                <span className="studio-listview__muted">
                  {q.topics.join(', ')} · {ordered ? 'one after the other, in order' : 'in any order'}
                </span>
                <span className="studio-jobs__now">
                  {q.waiting} waiting · {q.running} running{q.failed ? ` · ${q.failed} failed` : ''}
                </span>
              </header>
              <div className="studio-jobs__fields">
                <NumberField
                  label="At once"
                  value={d.maxParallel}
                  minValue={1}
                  maxValue={ordered ? 1 : 32}
                  isDisabled={!admin || ordered}
                  description={ordered ? 'In order: one at a time' : undefined}
                  onChange={(v) => setDrafts((x) => ({ ...x, [q.pid]: { ...d, maxParallel: Number.isNaN(v) ? d.maxParallel : v } }))}
                  width="size-1600"
                />
                <NumberField
                  label="Tries again"
                  value={d.retries}
                  minValue={-1}
                  maxValue={10000}
                  isDisabled={!admin}
                  description={d.retries < 0 ? 'Until it works' : `${d.retries} time${d.retries === 1 ? '' : 's'}`}
                  onChange={(v) => setDrafts((x) => ({ ...x, [q.pid]: { ...d, retries: Number.isNaN(v) ? d.retries : v } }))}
                  width="size-1600"
                />
                <NumberField
                  label="After (seconds)"
                  value={d.delay}
                  minValue={0}
                  maxValue={86400}
                  isDisabled={!admin}
                  description={delayLabel(d.delay * 1000)}
                  onChange={(v) => setDrafts((x) => ({ ...x, [q.pid]: { ...d, delay: Number.isNaN(v) ? d.delay : v } }))}
                  width="size-1600"
                />
                {admin && (
                  <div className="studio-jobs__save">
                    <Button variant="secondary" style="outline" isDisabled={!dirty || busy === q.pid} onPress={() => setDrafts((x) => ({ ...x, [q.pid]: draftOf(q) }))}>
                      Cancel
                    </Button>
                    <Button variant="accent" isDisabled={!dirty || busy === q.pid} onPress={() => void save(q)}>
                      Save
                    </Button>
                  </div>
                )}
              </div>
              {q.queues.length > 0 && (
                <table className="studio-table studio-jobs__live" aria-label={`Running queues of ${q.name}`}>
                  <tbody>
                    {q.queues.map((l) => (
                      <tr key={l.name}>
                        <td>{l.name}</td>
                        <td className="studio-listview__muted">
                          {l.waiting} waiting · {l.running} running · {l.failed} failed · {l.done} done
                        </td>
                        <td>{l.suspended && <span className="studio-jobs__state is-stopped">Paused</span>}</td>
                        <td className="studio-jobs__actions">
                          {admin && (
                            <Button variant="secondary" style="outline" isDisabled={busy === l.name} onPress={() => void suspend(l.name, !l.suspended)}>
                              <Text>{l.suspended ? 'Resume' : 'Pause'}</Text>
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
