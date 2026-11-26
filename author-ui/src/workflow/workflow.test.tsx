import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { GraniteNode } from '../granite';
import { designOf, flatten, STEP_TYPES, stepKind, workflowRoute } from './api';
import { CUSTOM, FakeWorkflow, studio } from './testing/FakeWorkflow';
import { ModelEditor } from './ModelEditor';
import { modelName, ModelsConsole } from './ModelsConsole';
import { workflowRegistry } from './registry';

// jsdom has no CSS.escape, which Spectrum menus use
if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = { escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`) };
}


let wf: FakeWorkflow;
beforeEach(() => {
  wf = new FakeWorkflow();
  try {
    window.localStorage.clear();
  } catch {
    // ignore
  }
});
afterEach(cleanup);

const inProvider = (ui: React.ReactElement) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

describe('routes and helpers', () => {
  it('maps the console and the model editor URLs', () => {
    expect(workflowRoute('/workflow/models.html')).toEqual({ kind: 'models' });
    expect(workflowRoute('/editor.html/conf/global/settings/workflow/models/review.html')).toEqual({ kind: 'model', path: `${CUSTOM}/review` });
    expect(workflowRoute('/editor.html/libs/settings/workflow/models/dam/update_asset.html')).toEqual({ kind: 'model', path: '/libs/settings/workflow/models/dam/update_asset' });
    expect(workflowRoute('/editor.html/content/wknd/us/en.html')).toBeNull();
    expect(workflowRoute('/editor.html/conf/wknd/settings/dam/cfm/models/article')).toBeNull();
  });

  it('knows steps, designs and model names', () => {
    expect(stepKind('/libs/cq/workflow/components/model/dynamic_participant')).toBe('dynamicParticipant');
    expect(stepKind('my/step')).toBe('unknown');
    expect(designOf(`${CUSTOM}/review/jcr:content/flow/or/1/process`)).toBe(`${CUSTOM}/review`);
    expect(flatten(wf.design.steps).map((s) => s.title)).toEqual(['Approve', 'Decide', 'Mark approved', 'Again']);
    expect(modelName('Request for Révision 2!')).toBe('request-for-revision-2');
  });
});

describe('Flow Models console', () => {
  it('lists the models with their details, sync state and type', async () => {
    inProvider(<ModelsConsole client={wf} studio={studio} user="admin" />);
    const row = (await screen.findByText('Review')).closest('tr')!;
    expect(within(row).getByText('Ask an editor')).toBeTruthy();
    expect(within(row).getByText('v1.2')).toBeTruthy();
    expect(within(row).getByText('Not synced')).toBeTruthy();
    expect(within(row).getByText('Custom')).toBeTruthy();
    const publish = screen.getByText('Publish').closest('tr')!;
    expect(within(publish).getByText('Default')).toBeTruthy();
    expect(within(publish).getByLabelText('Synced')).toBeTruthy();
    expect(within(screen.getByText('Draft').closest('tr')!).getByText('Not synced', { selector: 'td' })).toBeTruthy();
  });

  it('offers Start Flow only for synced-once models and Delete only for custom ones', async () => {
    inProvider(<ModelsConsole client={wf} studio={studio} user="admin" />);
    fireEvent.click(await screen.findByLabelText('Select Draft'));
    expect(screen.getByRole('button', { name: 'Start Flow' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: 'Delete' })).toBeTruthy();
    fireEvent.click(screen.getByLabelText('Select Draft'));
    fireEvent.click(screen.getByLabelText('Select Publish'));
    expect(screen.getByRole('button', { name: 'Start Flow' }).hasAttribute('disabled')).toBe(false);
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });

  it('creates a model from the Add Flow Model dialog', async () => {
    inProvider(<ModelsConsole client={wf} studio={studio} user="admin" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Title/), { target: { value: 'Legal Review' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(wf.calls).toContain('create Legal Review'));
    expect(await screen.findByText('Legal Review')).toBeTruthy();
  });
});

describe('model editor', () => {
  it('shows the flow with its branches and what each step does', async () => {
    inProvider(<ModelEditor client={wf} studio={studio} path={`${CUSTOM}/review`} />);
    expect(await screen.findByText('Flow Start')).toBeTruthy();
    expect(screen.getByText('Flow End')).toBeTruthy();
    expect(screen.getByText('Assigned to editors')).toBeTruthy();
    expect(screen.getByLabelText('Branch 1')).toBeTruthy();
    expect(screen.getByLabelText('Branch 2')).toBeTruthy();
    await waitFor(() => expect(screen.getByText('Mark Approved')).toBeTruthy()); // the process label, once processes load
    expect(screen.getByText('Go to Approve')).toBeTruthy();
    expect(screen.getByText(/Changes not synced/)).toBeTruthy();
    // one "Drag steps here" at the end of the flow and of each branch
    expect(screen.getAllByText('Drag steps here')).toHaveLength(3);
  });

  it('adds, deletes and syncs', async () => {
    inProvider(<ModelEditor client={wf} studio={studio} path={`${CUSTOM}/review`} />);
    fireEvent.doubleClick((await screen.findByText('Process Step')).closest('li')!);
    await waitFor(() => expect(wf.calls).toContain(`addStep ${wf.flow} ${STEP_TYPES.process} Process Step`));

    fireEvent.click(screen.getByText('Approve'));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(wf.calls).toContain(`deleteStep ${wf.flow}/participant`));

    fireEvent.click(screen.getByRole('button', { name: 'Sync' }));
    await waitFor(() => expect(wf.calls).toContain(`sync ${CUSTOM}/review`));
  });

  it('inserts a step from the placeholder menu, without dragging', async () => {
    inProvider(<ModelEditor client={wf} studio={studio} path={`${CUSTOM}/review`} />);
    const branch2 = await screen.findByLabelText('Branch 2');
    fireEvent.click(within(branch2).getByRole('button', { name: /Drag steps here/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Participant Step' }));
    await waitFor(() => expect(wf.calls).toContain(`addStep ${wf.flow}/or/2 ${STEP_TYPES.participant} Participant Step`));
  });

  it('is read-only for default models', async () => {
    wf.design = { ...wf.design, type: 'default', editable: false };
    inProvider(<ModelEditor client={wf} studio={studio} path="/libs/settings/workflow/models/publish" />);
    expect(await screen.findByText(/This is a default model/)).toBeTruthy();
    expect(screen.queryByText('Drag steps here')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Sync' })).toBeNull();
  });
});

describe('step dialog datasources', () => {
  const field: GraniteNode = { name: 'f', properties: {}, children: [] };

  it('lists registered processes by label, stored as class names', async () => {
    const ds = workflowRegistry(wf).dataSource('studio/workflow/datasources/processes')!;
    expect(await ds(field, {})).toEqual([
      { value: 'com.example.Mark', text: 'Mark Approved' },
      { value: 'com.example.Noop', text: 'No Operation' },
    ]);
  });

  it('offers the other steps of the model as goto targets', async () => {
    const ds = workflowRegistry(wf).dataSource('studio/workflow/datasources/steps')!;
    const options = await ds(field, { contentPath: `${wf.flow}/or/2/goto` });
    expect(options.map((o) => o.text)).toEqual(['Approve', 'Decide', 'Mark approved']);
    expect(options[0].value).toBe(`${wf.flow}/participant`);
    expect(wf.calls).toContain(`model ${CUSTOM}/review`);
  });
});
