import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ReviewValidationError, type PageReview, type PageTranslationClient, type QueueItem, type ReviewWorkflowClient } from './pages';
import { PageReviewView, TranslationReview } from './TranslationReview';

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  window.HTMLElement.prototype.scrollIntoView ??= () => {};
  (globalThis as { CSS?: { escape?: (s: string) => string } }).CSS ??= {};
  globalThis.CSS.escape ??= (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`);
});
afterEach(cleanup);

const COPY = '/content/wknd/language-masters/ro/magazine/guide';
const rec = { sourcePath: '/content/wknd/language-masters/en/magazine/guide', sourceLanguage: 'en', targetLanguage: 'ro', status: 'TRANSLATED' as const, failed: [] };

class MemoryReview implements PageTranslationClient {
  calls: { op: string; arg: unknown }[] = [];
  items: QueueItem[] = [
    { ...rec, kind: 'page', path: COPY, title: 'Ghidul', state: 'review', translatedAt: 2 },
    { ...rec, kind: 'asset', path: '/content/dam/wknd-shared/ro/beach.jpg', title: 'Plajă', state: 'review', translatedAt: 1 },
    { ...rec, kind: 'content-fragment', path: '/content/dam/wknd-shared/ro/guide', title: 'Ghidul CF', state: 'failed', failed: ['main'], translatedAt: 1 },
    { ...rec, targetLanguage: 'fr', kind: 'page', path: '/content/wknd/language-masters/fr/x', title: 'X', state: 'approved', status: 'APPROVED', translatedAt: 0 },
  ];
  current: PageReview = {
    path: COPY,
    title: 'Ghidul',
    sourceTitle: 'The Guide',
    translation: { ...rec },
    canEdit: true,
    segments: [
      { id: '@jcr:title', label: 'page jcr:title', html: false, source: 'The Guide', translation: 'Ghidul', failed: false, changed: false },
      { id: 'root/text@text', label: 'text text', html: true, source: '<p>Ride <b>on</b></p>', translation: null, failed: true, changed: false },
      { id: 'root/title@jcr:title', label: 'title jcr:title', html: false, source: 'Share this story', translation: 'Distribuie', failed: false, changed: true },
    ],
  };
  async languages(): Promise<never> {
    throw new Error('not used');
  }
  async pages() {
    return [];
  }
  async translate() {
    return [];
  }
  async providers() {
    return [];
  }
  async queue() {
    return structuredClone(this.items);
  }
  async review(path: string) {
    this.calls.push({ op: 'review', arg: path });
    return structuredClone(this.current);
  }
  async saveReview(path: string, translations: Record<string, string>) {
    this.calls.push({ op: 'saveReview', arg: { path, translations } });
    if (translations['root/text@text'] && !translations['root/text@text'].includes('<b>')) {
      throw new ReviewValidationError({ 'root/text@text': 'The tags changed: <b> is missing' });
    }
    for (const s of this.current.segments) if (translations[s.id] !== undefined) Object.assign(s, { translation: translations[s.id], failed: false });
    return structuredClone(this.current);
  }
  async retranslate(path: string, ids: string[]) {
    this.calls.push({ op: 'retranslate', arg: { path, ids } });
    for (const s of this.current.segments) if (ids.includes(s.id)) Object.assign(s, { translation: `[ro] ${s.source}`, failed: false, changed: false });
    return { ...structuredClone(this.current), retranslated: ids.length };
  }
  async approveAll(paths: string[]) {
    this.calls.push({ op: 'approveAll', arg: paths });
    // the failed one is left out, like on the server
    const approved = paths.filter((p) => this.items.find((i) => i.path === p)?.state !== 'failed');
    for (const i of this.items) if (approved.includes(i.path)) Object.assign(i, { state: 'approved', status: 'APPROVED' });
    return {
      approved,
      skipped: paths.filter((p) => !approved.includes(p)).map((path) => ({ path, reason: 'Some texts are not translated' })),
      failed: [],
    };
  }
  async approve(path: string) {
    this.calls.push({ op: 'approve', arg: path });
    this.current.translation.status = 'APPROVED';
    return { path, title: '', liveCopy: false, languageRoot: null };
  }
}

class FakeWorkflow implements ReviewWorkflowClient {
  started: string[] = [];
  async requestReview(paths: string[]) {
    this.started.push(...paths);
    return paths.map((_, i) => `/var/workflow/instances/x_${i}`);
  }
  async running() {
    return Object.fromEntries(this.started.map((p) => [p, { id: 'x', assignee: 'translation-reviewers' }]));
  }
  closed: string[] = [];
  async close(paths: string[]) {
    const open = paths.filter((p) => this.started.includes(p));
    this.closed.push(...open);
    this.started = this.started.filter((p) => !open.includes(p));
    return open.length;
  }
}

const wrap = (ui: React.ReactElement) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

describe('translation review', () => {
  it('lists what is to review and filters by state', async () => {
    const client = new MemoryReview();
    const workflow = new FakeWorkflow();
    wrap(<TranslationReview client={client} initialPath={null} workflow={workflow} />);
    const grid = await screen.findByRole('grid', { name: 'Translations' });
    await waitFor(() => expect(grid.textContent).toContain('Ghidul'));
    // the default filter: to review
    expect(grid.textContent).not.toContain('Ghidul CF');
    expect(grid.textContent).toContain('en → ro');
    expect(grid.textContent).toContain('To review');
    // assets' metadata copies are reviewed here too
    expect(grid.textContent).toContain('Plajă');
    expect(grid.textContent).toContain('Asset');

    // ask a reviewer: the workflow starts for the selected copy, which then shows as in review
    act(() => {
      fireEvent.click(within(grid).getAllByRole('checkbox')[1]);
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Request review' }));
    });
    expect(workflow.started).toEqual([COPY]);
    await waitFor(() => expect(grid.textContent).toContain('In review · translation-reviewers'));
  });

  it('shows what a reviewer asked to change', async () => {
    const client = new MemoryReview();
    Object.assign(client.current.translation, { status: 'CHANGES', changesRequestedBy: 'maria', reviewComment: 'The title is too literal' });
    wrap(<PageReviewView client={client} workflow={new FakeWorkflow()} path={COPY} onBack={() => {}} />);
    await screen.findByText('Changes requested by maria');
    expect(screen.getByText(/The title is too literal/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Request review' })).toBeTruthy();
  });

  it('corrects texts (the markup is checked), translates chosen ones again and approves', async () => {
    const client = new MemoryReview();
    wrap(<PageReviewView client={client} workflow={new FakeWorkflow()} path={COPY} onBack={() => {}} />);
    const texts = await screen.findByRole('list', { name: 'Texts' });
    expect(texts.textContent).toContain('Not translated');
    expect(texts.textContent).toContain('Source changed');

    // a correction that drops a tag: refused with the reason under the text
    act(() => {
      fireEvent.change(screen.getByRole('textbox', { name: 'Translation of text text' }), { target: { value: '<p>Mergi mai departe</p>' } });
      fireEvent.change(screen.getByRole('textbox', { name: 'Translation of page jcr:title' }), { target: { value: 'Ghidul suprem' } });
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Save \(2\)/ }));
    });
    await screen.findByText(/<b> is missing/);
    act(() => {
      fireEvent.change(screen.getByRole('textbox', { name: 'Translation of text text' }), { target: { value: '<p>Mergi <b>mai departe</b></p>' } });
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Save \(2\)/ }));
    });
    await waitFor(() => expect(screen.getByRole('button', { name: /^Save$/ })).toBeTruthy());
    expect((screen.getByRole('textbox', { name: 'Translation of page jcr:title' }) as HTMLTextAreaElement).value).toBe('Ghidul suprem');

    // translate the changed text again
    act(() => {
      fireEvent.click(screen.getByRole('checkbox', { name: 'Select title jcr:title' }));
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Translate again \(1\)/ }));
    });
    expect(client.calls).toContainEqual({ op: 'retranslate', arg: { path: COPY, ids: ['root/title@jcr:title'] } });
    await waitFor(() =>
      expect((screen.getByRole('textbox', { name: 'Translation of title jcr:title' }) as HTMLTextAreaElement).value).toBe('[ro] Share this story'),
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    });
    await waitFor(() => expect(client.calls.some((c) => c.op === 'approve')).toBe(true));
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Translation' })).getByText('Approved')).toBeTruthy());

    // approved as it stands: nothing is left to approve, so the leading action says so and rests
    const done = await screen.findByRole('button', { name: 'Approved' });
    expect((done as HTMLButtonElement).disabled).toBe(true);
    // a correction after the approval is something to approve again
    act(() => {
      fireEvent.change(screen.getByRole('textbox', { name: 'Translation of page jcr:title' }), { target: { value: 'Ghidul suprem, revizuit' } });
    });
    expect((screen.getByRole('button', { name: 'Approve' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('selects everything shown and approves it at once; unfinished ones are left out', async () => {
    const client = new MemoryReview();
    wrap(<TranslationReview client={client} initialPath={null} />);
    const grid = await screen.findByRole('grid', { name: 'Translations' });
    await waitFor(() => expect(grid.textContent).toContain('Ghidul'));
    // what the filter shows (to review): the page and the asset
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: /Select all/ }));
    });
    const bar = screen.getByRole('toolbar', { name: 'Selection' });
    expect(bar.textContent).toContain('2 selected');
    await act(async () => {
      fireEvent.click(within(bar).getByRole('button', { name: 'Approve (2)' }));
    });
    const confirm = await screen.findByRole('dialog');
    expect(confirm.textContent).toContain('Approve 2 translations?');
    await act(async () => {
      fireEvent.click(within(confirm).getByRole('button', { name: 'Approve' }));
    });
    expect(client.calls).toContainEqual({ op: 'approveAll', arg: ['/content/wknd/language-masters/ro/magazine/guide', '/content/dam/wknd-shared/ro/beach.jpg'] });
    await waitFor(() => expect(screen.queryByRole('toolbar', { name: 'Selection' })).toBeNull());
  });

  it('approving on the screen closes the review that was asked for', async () => {
    const client = new MemoryReview();
    client.current.segments = client.current.segments.map((x) => ({ ...x, failed: false, changed: false }));
    const workflow = new FakeWorkflow();
    workflow.started = [COPY];
    wrap(<PageReviewView client={client} workflow={workflow} path={COPY} onBack={() => {}} />);
    await screen.findByRole('button', { name: /^In review/ });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    });
    await waitFor(() => expect(workflow.closed).toEqual([COPY]));
    await screen.findByRole('button', { name: 'Request review' });
  });
});
