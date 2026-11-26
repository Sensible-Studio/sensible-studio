import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GraniteForm, useGraniteForm, type ContentNode, type GraniteNode } from '../granite';
import { InboxBell } from './InboxBell';
import { ModelEditor } from './ModelEditor';
import { workflowRegistry } from './registry';
import { StartWorkflowDialog } from './StartWorkflowDialog';
import { CUSTOM, FakeWorkflow, studio } from './testing/FakeWorkflow';
import { PurgeDialog } from './PurgeDialog';
import { stageLabel } from './api';
import { TopBar } from '../editor/shell/TopBar';

// jsdom has no CSS.escape, which Spectrum menus use
if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = { escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`) };
}

let wf: FakeWorkflow;
beforeEach(() => {
  wf = new FakeWorkflow();
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const inProvider = (ui: React.ReactElement) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

const CORAL = 'granite/ui/components/coral/foundation';
const n = (name: string, properties: GraniteNode['properties'], children: GraniteNode[] = []): GraniteNode => ({ name, properties, children });

/** The shape of the OR split dialog of studio-workflow-content: a Branches select and one tab per branch. */
const OR_DIALOG = n('cq:dialog', { 'sling:resourceType': 'cq/gui/components/authoring/dialog', extraClientlibs: ['studio.workflow.split'] }, [
  n('content', { 'sling:resourceType': `${CORAL}/container` }, [
    n('items', {}, [
      n('tabs', { 'sling:resourceType': `${CORAL}/tabs` }, [
        n('items', {}, [
          n('split', { 'sling:resourceType': `${CORAL}/container`, 'jcr:title': 'OR Split' }, [
            n('items', {}, [
              n('branches', { 'sling:resourceType': `${CORAL}/form/select`, fieldLabel: 'Branches', name: './branches' }, [
                n('items', {}, ['2', '3', '4', '5'].map((v) => n(`o${v}`, { text: v, value: v }))),
              ]),
            ]),
          ]),
          ...[1, 2, 3, 4, 5].map((i) =>
            n(`branch${i}`, { 'sling:resourceType': `${CORAL}/container`, 'jcr:title': `Branch ${i}` }, [
              n('items', {}, [n('rule', { 'sling:resourceType': `${CORAL}/form/textfield`, fieldLabel: 'Rule', name: `./metaData/rule${i}` })]),
            ]),
          ),
        ]),
      ]),
    ]),
  ]),
]);

function OrDialog({ content }: { content: ContentNode }) {
  const registry = workflowRegistry(wf);
  const form = useGraniteForm(OR_DIALOG, content, registry, {});
  return <GraniteForm form={form} registry={registry} env={{}} />;
}

describe('OR split dialog', () => {
  it('shows one Branch tab per branch', async () => {
    inProvider(<OrDialog content={{ branches: '3' }} />);
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['OR Split', 'Branch 1', 'Branch 2', 'Branch 3']);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '5' } });
    await waitFor(() => expect(screen.getAllByRole('tab')).toHaveLength(6));
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '2' } });
    await waitFor(() => expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['OR Split', 'Branch 1', 'Branch 2']));
  });
});

describe('Start Flow from a console', () => {
  it('starts the chosen model once per selected item', async () => {
    const onClose = vi.fn();
    inProvider(<StartWorkflowDialog client={wf} payloads={['/content/site/a', '/content/site/b']} onClose={onClose} />);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('2 items: one flow each')).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: 'Start' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(within(dialog).getByRole('button', { name: /Flow Model/ }));
    const options = await screen.findAllByRole('option');
    // only models that were synced at least once
    expect(options.map((o) => o.textContent)).toEqual(['Review', 'Publish']);
    fireEvent.click(options[0]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(wf.calls).toContain('start /var/workflow/models/review /content/site/a');
    expect(wf.calls).toContain('start /var/workflow/models/review /content/site/b');
  });
});

describe('Inbox bell', () => {
  it('shows how many steps wait', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{}, {}, {}]), { status: 200 })));
    inProvider(<InboxBell />);
    expect(await screen.findByText('3')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Inbox: 3 steps waiting' })).toBeTruthy();
  });

  it('shows no count when nothing waits or the inbox cannot be read', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 403 })));
    inProvider(<InboxBell />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Inbox' })).toBeTruthy());
  });
});

describe('model properties', () => {
  it('saves title, description and the transient flag', async () => {
    inProvider(<ModelEditor client={wf} studio={studio} path={`${CUSTOM}/review`} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Model Properties' }));
    const dialog = await screen.findByRole('form');
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Transient Flow' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(wf.calls).toContain(`post ${CUSTOM}/review/jcr:content jcr:title=Review&jcr:description=Ask an editor&transient=true&cq:workflowStages@Delete=`),
    );
    expect(wf.calls).toContain(`touch ${CUSTOM}/review/jcr:content`);
  });
});

describe('stages', () => {
  it('are edited in the model properties and saved as cq:workflowStages', async () => {
    wf.design = { ...wf.design, stages: ['Draft', 'Review'] };
    inProvider(<ModelEditor client={wf} studio={studio} path={`${CUSTOM}/review`} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Model Properties' }));
    const dialog = await screen.findByRole('form');
    expect((within(dialog).getByLabelText('Stage 2') as HTMLInputElement).value).toBe('Review');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add Stage' }));
    fireEvent.change(within(dialog).getByLabelText('Stage 3'), { target: { value: 'Publish' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move stage 3 up' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove stage 1' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(wf.calls.find((c) => c.startsWith(`post ${CUSTOM}/review/jcr:content`))).toContain('cq:workflowStages@TypeHint=String[]&cq:workflowStages=Publish&cq:workflowStages=Review'));
  });

  it('are shown with their position', () => {
    expect(stageLabel('Review', ['Draft', 'Review', 'Publish'])).toBe('Review (2 of 3)');
    expect(stageLabel('Other', ['Draft'])).toBe('Other');
    expect(stageLabel(null, ['Draft'])).toBeNull();
  });

  it('offer the model stages in step dialogs', async () => {
    wf.design = { ...wf.design, stages: ['Draft', 'Review'] };
    const ds = workflowRegistry(wf).dataSource('studio/workflow/datasources/stages')!;
    expect(await ds({ name: 'f', properties: {}, children: [] }, { contentPath: `${wf.flow}/participant` })).toEqual([
      { value: 'Draft', text: 'Draft' },
      { value: 'Review', text: 'Review' },
    ]);
  });
});

describe('purge settings', () => {
  it('saves the purge age, or keeps ended flows', async () => {
    const onClose = vi.fn();
    inProvider(<PurgeDialog client={wf} onClose={onClose} onPurged={() => undefined} />);
    expect(await screen.findByText('4')).toBeTruthy(); // ended workflows
    const age = screen.getByRole('textbox', { name: /Remove completed and terminated flows after/ });
    fireEvent.change(age, { target: { value: '7' } });
    fireEvent.blur(age);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(wf.calls).toContain('setPurge 7'));
    expect(onClose).toHaveBeenCalled();
  });

  it('keeps everything when asked, and purges on demand', async () => {
    const onPurged = vi.fn();
    inProvider(<PurgeDialog client={wf} onClose={() => undefined} onPurged={onPurged} />);
    fireEvent.click(await screen.findByRole('switch', { name: 'Keep ended flows' }));
    expect(screen.queryByRole('textbox', { name: /Remove completed/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Purge Now' }));
    await waitFor(() => expect(onPurged).toHaveBeenCalledWith(3));
    expect(wf.calls).toContain('purgeNow 30');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(wf.calls).toContain('setPurge 0'));
  });

  it('is read-only for non-administrators', async () => {
    wf.maintenanceInfo = { ...wf.maintenanceInfo, admin: false };
    inProvider(<PurgeDialog client={wf} onClose={() => undefined} onPurged={() => undefined} />);
    expect(await screen.findByText('Only administrators can change these settings.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(true);
  });
});

describe('page editor', () => {
  it('offers Start Flow in the page information menu', async () => {
    const onPageAction = vi.fn();
    inProvider(
      <TopBar
        page={null}
        pagePath="/content/site/en"
        mode="edit"
        onMode={() => undefined}
        sidePanelOpen={false}
        onToggleSidePanel={() => undefined}
        emulatorOpen={false}
        onToggleEmulator={() => undefined}
        onPageAction={onPageAction}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Page menu' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Start Flow' }));
    expect(onPageAction).toHaveBeenCalledWith('workflow');
  });
});
