import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { payloadUrl, workflowRoute, type InboxEntry, type InstanceSummary, type WorkItemInfo } from './api';
import { InboxConsole } from './InboxConsole';
import { InstancesConsole } from './InstancesConsole';
import { LaunchersConsole } from './LaunchersConsole';
import { FakeWorkflow } from './testing/FakeWorkflow';

// jsdom has no CSS.escape, which Spectrum menus use
if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = { escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`) };
}

const DAY = '/var/workflow/instances/server0/2026-10-17';

function item(instance: string, n: number, title: string, status: WorkItemInfo['status'], extra: Partial<WorkItemInfo> = {}): WorkItemInfo {
  return { id: `${instance}/workItems/item_${n}`, name: `item_${n}`, nodeId: `node${n}`, title, status, startTime: '2026-10-17T08:00:00Z', ...extra };
}

function instance(n: number, status: string, extra: Partial<InstanceSummary> = {}): InstanceSummary {
  const id = `${DAY}/review_${n}`;
  return {
    id,
    name: `review_${n}`,
    modelId: '/var/workflow/models/review',
    modelTitle: 'Review',
    modelVersion: '1.1',
    status,
    initiator: 'admin',
    startTime: '2026-10-17T08:00:00Z',
    payload: `/content/site/en/page${n}/jcr:content`,
    current: [],
    failed: false,
    history: [],
    ...extra,
  };
}

let wf: FakeWorkflow;
beforeEach(() => {
  wf = new FakeWorkflow();
  window.history.replaceState(null, '', '/workflow/instances.html');
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const inProvider = (ui: React.ReactElement) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

describe('helpers', () => {
  it('routes every flow console and opens payloads where they are edited', () => {
    expect(workflowRoute('/workflow/instances.html')).toEqual({ kind: 'instances' });
    expect(workflowRoute('/workflow/launchers.html')).toEqual({ kind: 'launchers' });
    expect(workflowRoute('/workflow/inbox.html')).toEqual({ kind: 'inbox' });
    expect(workflowRoute('/workflow/other.html')).toBeNull();
    expect(payloadUrl('/content/wknd/en/jcr:content')).toBe('/editor.html/content/wknd/en.html');
    expect(payloadUrl('/content/dam/wknd/a.jpg')).toBe('/assetdetails.html/content/dam/wknd/a.jpg');
    expect(payloadUrl('/var/x')).toBe('/bin/browser.html/var/x');
  });
});

describe('Flow Instances', () => {
  beforeEach(() => {
    const running = instance(1, 'RUNNING', { title: 'Check the page', comment: 'urgent' });
    running.current = [item(running.id, 1, 'Approve', 'ACTIVE', { assignee: 'editors' })];
    running.history = [item(running.id, 1, 'Approve', 'ACTIVE', { assignee: 'editors', delegatedFrom: 'admin' })];
    const failed = instance(2, 'RUNNING', { failed: true });
    failed.current = [item(failed.id, 1, 'Publish', 'FAILED', { failure: 'No process step x is registered' })];
    const suspended = instance(3, 'SUSPENDED');
    const done = instance(4, 'COMPLETED', { endTime: '2026-10-17T09:00:00Z' });
    wf.instanceList = [running, failed, suspended, done];
  });

  it('lists running and suspended flows with their current step', async () => {
    inProvider(<InstancesConsole client={wf} user="admin" />);
    const row = (await screen.findByText('Check the page')).closest('tr')!;
    expect(within(row).getByText('urgent')).toBeTruthy();
    expect(within(row).getByText('Running')).toBeTruthy();
    expect(within(row).getByText('Approve · editors')).toBeTruthy();
    expect(within(row).getByText('/content/site/en/page1/jcr:content').getAttribute('href')).toBe('/editor.html/content/site/en/page1.html');
    expect(within(screen.getByText('No process step x is registered').closest('tr')!).getByText('Failed')).toBeTruthy();
    expect(screen.getByText('Suspended')).toBeTruthy();
    expect(screen.queryByText('Completed')).toBeNull();
    expect(wf.calls).toContain('instances RUNNING,SUSPENDED');
  });

  it('suspends, retries and terminates the selection', async () => {
    inProvider(<InstancesConsole client={wf} user="admin" />);
    fireEvent.click(await screen.findByLabelText('Select Check the page'));
    fireEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    await waitFor(() => expect(wf.calls).toContain(`suspend ${DAY}/review_1`));

    fireEvent.click(screen.getAllByLabelText('Select Review')[0]); // review_2: failed
    fireEvent.click(screen.getByLabelText('Select Check the page'));
    fireEvent.click(screen.getByRole('button', { name: 'Retry Step' }));
    await waitFor(() => expect(wf.calls).toContain(`retry ${DAY}/review_2/workItems/item_1`));

    fireEvent.click(screen.getByRole('button', { name: 'Terminate' }));
    fireEvent.click(within((await screen.findAllByRole('alertdialog')).at(-1)!).getByRole('button', { name: 'Terminate' }));
    await waitFor(() => expect(wf.calls).toContain(`terminate ${DAY}/review_2`));
  });

  it('shows failures and the archive, where ended flows can be deleted', async () => {
    inProvider(<InstancesConsole client={wf} user="admin" />);
    fireEvent.click(await screen.findByRole('radio', { name: 'Failures' }));
    await waitFor(() => expect(wf.calls).toContain('instances RUNNING,SUSPENDED failed'));
    expect(await screen.findByText('No process step x is registered')).toBeTruthy();
    expect(screen.queryByText('Check the page')).toBeNull();
    expect(window.location.search).toBe('?view=failures');

    fireEvent.click(screen.getByRole('radio', { name: 'Archive' }));
    const row = (await screen.findByText('Completed')).closest('tr')!;
    fireEvent.click(within(row).getByRole('checkbox'));
    expect(screen.queryByRole('button', { name: 'Terminate' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    fireEvent.click(within((await screen.findAllByRole('alertdialog')).at(-1)!).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(wf.calls).toContain(`deleteInstance ${DAY}/review_4`));
  });

  it('opens the history of a flow', async () => {
    inProvider(<InstancesConsole client={wf} user="admin" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Check the page' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('Approve')).toBeTruthy();
    expect(within(dialog).getByText('(from admin)')).toBeTruthy();
    expect(within(dialog).getByText('Review')).toBeTruthy();
  });
});

describe('Inbox', () => {
  beforeEach(() => {
    const running = instance(1, 'RUNNING', { title: 'Check the page' });
    const entry: InboxEntry = {
      ...item(running.id, 1, 'Review content', 'ACTIVE', { assignee: 'administrators' }),
      workflow: running,
      routes: [
        { id: 'node4', name: 'Approve', default: true },
        { id: 'node5', name: 'Request changes', default: false },
      ],
    };
    wf.inboxList = [entry];
  });

  it('completes a step along the chosen route', async () => {
    inProvider(<InboxConsole client={wf} user="admin" />);
    const row = (await screen.findByRole('button', { name: 'Review content' })).closest('tr')!;
    expect(within(row).getByText('Check the page')).toBeTruthy();
    expect(within(row).getByText('administrators')).toBeTruthy();
    fireEvent.click(within(row).getByRole('button', { name: 'Review content' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Approve', { selector: 'span' })).toBeTruthy(); // the default route is preselected
    fireEvent.change(within(dialog).getByLabelText('Comment'), { target: { value: 'looks good' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Complete' }));
    await waitFor(() => expect(wf.calls).toContain(`complete ${DAY}/review_1/workItems/item_1 node4 looks good`));
  });

  it('offers Delegate and the payload for the selected step', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => new Response(JSON.stringify(String(url).includes('op=groups') ? [{ id: 'editors', name: 'Editors', type: 'group' }] : []), { status: 200 })),
    );
    inProvider(<InboxConsole client={wf} user="admin" />);
    fireEvent.click(await screen.findByLabelText('Select Review content'));
    expect(screen.getByRole('button', { name: 'Open content' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Delegate' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('administrators')).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: 'Delegate' }).hasAttribute('disabled')).toBe(true);
  });

  it('completes several steps at once along a next step they share', async () => {
    const second = { ...structuredClone(wf.inboxList[0]), id: `${DAY}/review_2/workItems/item_1`, title: 'Review content 2' };
    second.routes = [
      { id: 'node9', name: 'Request changes', default: false },
      { id: 'node8', name: 'Approve', default: true },
    ];
    wf.inboxList = [wf.inboxList[0], second];
    inProvider(<InboxConsole client={wf} user="admin" />);
    fireEvent.click(await screen.findByLabelText('Select all'));
    const bar = screen.getByRole('toolbar', { name: 'Actions for the selection' });
    expect(bar.textContent).toContain('2 selected');
    fireEvent.click(within(bar).getByRole('button', { name: 'Complete (2)' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Approve', { selector: 'span' })).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText(/Comment/), { target: { value: 'ok' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Complete 2' }));
    // each along its own route id for "Approve"
    await waitFor(() => expect(wf.calls).toContain(`complete ${DAY}/review_2/workItems/item_1 node8 ok`));
    expect(wf.calls).toContain(`complete ${DAY}/review_1/workItems/item_1 node4 ok`);
  });

  it('says when nothing waits', async () => {
    wf.inboxList = [];
    inProvider(<InboxConsole client={wf} user="admin" />);
    expect(await screen.findByText('Nothing waits for you.')).toBeTruthy();
  });
});

describe('Flow Triggers', () => {
  beforeEach(() => {
    wf.launcherList = [
      {
        path: '/conf/global/settings/workflow/launcher/config/pages',
        name: 'pages',
        type: 'custom',
        eventType: 16,
        glob: '/content/site(/.*)/jcr:content',
        nodetype: 'cq:PageContent',
        conditions: ['status==ready'],
        excludeList: ['jcr:lastModified'],
        workflow: '/var/workflow/models/review',
        description: 'Review edited pages',
        enabled: true,
        runModes: 'author',
        valid: true,
      },
    ];
  });

  it('lists triggers with the model title and toggles them', async () => {
    inProvider(<LaunchersConsole client={wf} user="admin" />);
    const row = (await screen.findByText('/content/site(/.*)/jcr:content')).closest('tr')!;
    expect(within(row).getByText('Modified')).toBeTruthy();
    expect(within(row).getByText('status==ready')).toBeTruthy();
    await waitFor(() => expect(within(row).getByText('Review')).toBeTruthy());
    fireEvent.click(within(row).getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(wf.calls).toContain('enableLauncher /conf/global/settings/workflow/launcher/config/pages false'));
  });

  it('creates a trigger, validating the path first', async () => {
    inProvider(<LaunchersConsole client={wf} user="admin" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Create trigger' }));
    const form = await screen.findByRole('form', { name: /Flow trigger/ });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    expect(within(form).getByText('The path is required')).toBeTruthy();
    expect(wf.calls.some((c) => c.startsWith('saveLauncher'))).toBe(false);

    fireEvent.change(within(form).getByLabelText(/^Path/), { target: { value: '/content/site(/.*)' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add' }));
    fireEvent.change(within(form).getByLabelText('Condition 1'), { target: { value: 'status==ready' } });
    fireEvent.change(within(form).getByLabelText(/^Exclude List/), { target: { value: 'jcr:lastModified\n\nevent-user-data:changedByWorkflowProcess' } });
    // the model picker lists synced models; pick one
    fireEvent.click(within(form).getByRole('button', { name: /Flow Model/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Review' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(wf.calls.find((c) => c.startsWith('saveLauncher new'))).toBeTruthy());
    const saved = JSON.parse(wf.calls.find((c) => c.startsWith('saveLauncher new'))!.slice('saveLauncher new '.length));
    expect(saved).toMatchObject({
      glob: '/content/site(/.*)',
      eventType: 16,
      nodetype: 'cq:PageContent',
      conditions: ['status==ready'],
      excludeList: ['jcr:lastModified', 'event-user-data:changedByWorkflowProcess'],
      workflow: '/var/workflow/models/review',
      enabled: true,
      runModes: 'author',
    });
  });

  it('deletes custom triggers after confirmation', async () => {
    inProvider(<LaunchersConsole client={wf} user="admin" />);
    fireEvent.click(await screen.findByLabelText('Select /content/site(/.*)/jcr:content'));
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    fireEvent.click(within((await screen.findAllByRole('alertdialog')).at(-1)!).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(wf.calls).toContain('deleteLauncher /conf/global/settings/workflow/launcher/config/pages'));
  });
});
