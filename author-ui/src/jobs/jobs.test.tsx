import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { JobsConsole } from './JobsConsole';
import { delayLabel, itemUrl, type JobFilter, type JobsClient, type QueueConfig, type StudioJob } from './api';
import { placeOfUrl, urlOf } from '../shell/places';

const job = (id: string, state: StudioJob['state'], kind = 'Media processing', item = '/content/dam/wknd/surf.jpg'): StudioJob => ({
  id,
  topic: 'studio/dam/process',
  kind,
  item,
  state,
  queue: 'Sensible Studio DAM processing',
  created: '2026-11-24T10:00:00Z',
  finished: state === 'failed' ? '2026-11-24T10:05:00Z' : null,
  tries: state === 'failed' ? 60 : 0,
  maxTries: 60,
  message: state === 'failed' ? 'ffmpeg not found' : null,
});

function fakeJobs(admin = true) {
  const calls: string[] = [];
  let jobs: StudioJob[] = [job('1', 'running'), job('2', 'failed', 'Auto tags'), job('3', 'waiting', 'Publishing · publish', '/content/wknd/en')];
  let queues: QueueConfig[] = [
    {
      pid: 'org.apache.sling.event.jobs.QueueConfiguration~studio-dam',
      name: 'Sensible Studio DAM processing',
      topics: ['studio/dam/process'],
      type: 'UNORDERED',
      maxParallel: 2,
      retries: 60,
      retryDelay: 30000,
      priority: 'NORM',
      queues: [{ name: 'Sensible Studio DAM processing', waiting: 1, running: 1, failed: 1, done: 9, suspended: false }],
      waiting: 1,
      running: 1,
      failed: 1,
    },
    {
      pid: 'org.apache.sling.event.jobs.QueueConfiguration~studio-replication',
      name: 'Sensible Studio replication {0}',
      topics: ['studio/replication/*'],
      type: 'ORDERED',
      maxParallel: 1,
      retries: -1,
      retryDelay: 60000,
      priority: 'NORM',
      queues: [],
      waiting: 0,
      running: 0,
      failed: 0,
    },
  ];
  const client: JobsClient & { calls: string[] } = {
    calls,
    async list(state: JobFilter) {
      calls.push(`list ${state}`);
      const pick = state === 'all' ? jobs : jobs.filter((j) => j.state === state);
      return { jobs: pick, counts: { waiting: 1, running: 1, failed: 1 }, admin };
    },
    async queues() {
      return { queues, admin };
    },
    async retry(id) {
      calls.push(`retry ${id}`);
      jobs = jobs.map((j) => (j.id === id ? { ...j, state: 'waiting' } : j));
    },
    async remove(id) {
      calls.push(`remove ${id}`);
      jobs = jobs.filter((j) => j.id !== id);
    },
    async suspend(name, on) {
      calls.push(`${on ? 'suspend' : 'resume'} ${name}`);
      queues = queues.map((q) => ({ ...q, queues: q.queues.map((l) => (l.name === name ? { ...l, suspended: on } : l)) }));
    },
    async configure(pid, values) {
      calls.push(`configure ${pid.split('~')[1]} ${JSON.stringify(values)}`);
      queues = queues.map((q) => (q.pid === pid ? { ...q, maxParallel: values.maxParallel ?? q.maxParallel, retries: values.retries ?? q.retries, retryDelay: values.retryDelay ?? q.retryDelay } : q));
    },
  };
  return client;
}

const show = (client: JobsClient) =>
  render(
    <Provider theme={defaultTheme}>
      <JobsConsole client={client} />
    </Provider>,
  );

describe('Jobs', () => {
  afterEach(cleanup);
  it("has AEM's address", () => {
    expect(placeOfUrl('/mnt/overlay/granite/async/content/asyncjobs.html')).toEqual({ kind: 'jobs' });
    expect(urlOf({ kind: 'jobs' })).toBe('/mnt/overlay/granite/async/content/asyncjobs.html');
    expect(itemUrl('/content/dam/a.jpg')).toBe('/assetdetails.html/content/dam/a.jpg');
    expect(itemUrl('/content/wknd/en')).toBe('/editor.html/content/wknd/en.html');
    expect(delayLabel(90_000)).toBe('1 min 30 s');
  });

  it('lists the jobs in words, filters them and retries a failed one', async () => {
    const client = fakeJobs();
    show(client);
    const table = await screen.findByRole('table', { name: 'Jobs' });
    expect(within(table).getByText('Publishing · publish')).toBeTruthy();
    expect(within(table).getByText('ffmpeg not found')).toBeTruthy();
    expect(within(table).getByRole('link', { name: '/content/wknd/en' })).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Failed (1)' })).toBeTruthy();
    fireEvent.click(within(table).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(client.calls).toContain('retry 2'));
    fireEvent.click(screen.getByRole('radio', { name: 'Failed (1)' }));
    await waitFor(() => expect(client.calls).toContain('list failed'));
  });

  it('shows the queues and changes how one runs', async () => {
    const client = fakeJobs();
    show(client);
    fireEvent.click(screen.getByRole('radio', { name: 'Queues' }));
    const dam = await screen.findByRole('listitem', { name: 'Sensible Studio DAM processing' });
    const atOnce = within(dam).getByRole('textbox', { name: 'At once' });
    fireEvent.change(atOnce, { target: { value: '4' } });
    fireEvent.blur(atOnce);
    fireEvent.click(within(dam).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(client.calls).toContain('configure studio-dam {"maxParallel":4,"retries":60,"retryDelay":30000}'));
    // an ordered queue runs one at a time
    const rep = screen.getByRole('listitem', { name: 'Sensible Studio replication {0}' });
    expect(within(rep).getByRole('textbox', { name: 'At once' })).toHaveProperty('disabled', true);
    expect(within(rep).getByText('Until it works')).toBeTruthy();
    fireEvent.click(within(dam).getByRole('button', { name: 'Pause' }));
    await waitFor(() => expect(client.calls).toContain('suspend Sensible Studio DAM processing'));
  });

  it('is read only for others than administrators', async () => {
    show(fakeJobs(false));
    const table = await screen.findByRole('table', { name: 'Jobs' });
    expect(within(table).queryByRole('button', { name: 'Retry' })).toBeNull();
    fireEvent.click(screen.getByRole('radio', { name: 'Queues' }));
    expect(await screen.findByText(/Only administrators change them/)).toBeTruthy();
  });
});
