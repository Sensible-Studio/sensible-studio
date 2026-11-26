import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { PageTranslationError, type PageTranslationClient, type ReviewWorkflowClient } from './pages';
import { parsePaths, pendingItems, type NewProject, type TranslationProject, type TranslationProjectClient } from './projects';
import { ProjectList, ProjectView } from './TranslationProjects';
import { languageOf, SitesTranslationProjectDialog } from './SitesTranslationProject';

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

const EN = '/content/wknd/language-masters/en';

class MemoryProjects implements TranslationProjectClient {
  calls: { op: string; arg: unknown }[] = [];
  failOn: string | null = null;
  projects: TranslationProject[] = [
    {
      name: 'spring',
      title: 'Spring campaign',
      description: 'New adventures',
      sourceLanguage: 'en',
      languages: ['ro', 'de'],
      deep: true,
      includeReferences: true,
      itemCount: 2,
      created: 1,
      createdBy: 'admin',
      items: [
        { id: 'item_0', path: `${EN}/adventures`, kind: 'page', title: 'Adventures', exists: true },
        { id: 'item_1', path: `${EN}/adventures/bali`, kind: 'page', title: 'Bali', exists: true },
      ],
      jobs: [
        { language: 'ro', status: 'DRAFT', total: 2, done: 0, failedItems: 0, copyCount: 0, items: [], copies: [], review: {} },
        { language: 'de', status: 'COMPLETE', total: 2, done: 2, failedItems: 0, copyCount: 2, items: [], copies: [], review: { approved: 2 } },
      ],
    },
  ];

  private find(name: string) {
    const p = this.projects.find((x) => x.name === name);
    if (!p) throw new Error(`No project ${name}`);
    return p;
  }

  async list() {
    return structuredClone(this.projects);
  }
  async get(name: string) {
    return structuredClone(this.find(name));
  }
  async create(np: NewProject) {
    this.calls.push({ op: 'create', arg: np });
    const p: TranslationProject = {
      name: 'new-one',
      title: np.title,
      sourceLanguage: 'en',
      languages: np.languages,
      deep: np.deep,
      includeReferences: np.includeReferences,
      itemCount: np.paths.length,
      items: np.paths.map((path, i) => ({ id: `item_${i}`, path, kind: 'page' })),
      jobs: np.languages.map((language) => ({ language, status: 'DRAFT', total: np.paths.length, done: 0, failedItems: 0, copyCount: 0 })),
    };
    this.projects.push(p);
    return structuredClone(p);
  }
  async addItems(name: string, paths: string[], deep: boolean) {
    this.calls.push({ op: 'addItems', arg: { name, paths, deep } });
    return structuredClone(this.find(name));
  }
  async removeItem(): Promise<TranslationProject> {
    throw new Error('not used');
  }
  async addLanguage(): Promise<TranslationProject> {
    throw new Error('not used');
  }
  async translateItem(name: string, language: string, item: string) {
    this.calls.push({ op: 'translateItem', arg: { name, language, item } });
    if (this.failOn === item) throw new PageTranslationError('No API key', 'MISSING_CREDENTIALS');
    const p = this.find(name);
    const job = p.jobs.find((j) => j.language === language)!;
    job.items = [...(job.items ?? []).filter((i) => i.id !== item), { id: item, status: 'TRANSLATED', translated: 5, unchanged: 0 }];
    const path = p.items!.find((i) => i.id === item)!.path.replace('/en/', `/${language}/`);
    job.copies = [...(job.copies ?? []), { path, title: `[${language}]`, kind: 'page', state: 'review' }];
    job.copyCount = job.copies.length;
    job.done = job.items.length;
    job.status = job.done === job.total ? 'READY_FOR_REVIEW' : 'IN_PROGRESS';
    job.review = { review: job.copies.length };
    return { id: item, status: 'TRANSLATED' as const, translated: 5, project: structuredClone(p) };
  }
  async setStatus(name: string, language: string, status: 'APPROVED' | 'COMPLETE' | 'CANCELLED' | 'DRAFT') {
    this.calls.push({ op: 'setStatus', arg: { language, status } });
    const job = this.find(name).jobs.find((j) => j.language === language)!;
    job.status = status;
    if (status === 'APPROVED') job.review = { approved: job.copyCount };
    return structuredClone(this.find(name));
  }
  async remove() {}
  async runInBackground(name: string, language: string) {
    this.calls.push({ op: 'runInBackground', arg: language });
    const job = this.find(name).jobs.find((j) => j.language === language)!;
    Object.assign(job, { background: 'RUNNING', backgroundBy: 'admin' });
    return structuredClone(this.find(name));
  }
  async setDueDate(name: string, dueDate: number | null) {
    this.calls.push({ op: 'setDueDate', arg: dueDate });
    const p = this.find(name);
    if (dueDate === null) delete p.dueDate;
    else p.dueDate = dueDate;
    p.overdue = dueDate !== null && dueDate < Date.now();
    return structuredClone(p);
  }
  xliffUrl(name: string, language: string, version = '1.2') {
    return `/xliff/${name}/${language}/${version}`;
  }
  async importXliff(name: string, language: string, file: Blob) {
    this.calls.push({ op: 'importXliff', arg: { language, size: file.size } });
    return {
      files: 2,
      saved: 7,
      unchanged: 30,
      errors: [{ path: '/content/wknd/language-masters/ro/adventures', id: 'root/text@text', label: 'Text · Text', message: 'The tags changed: <b> is missing' }],
      project: structuredClone(this.find(name)),
    };
  }
  async stopBackground(name: string, language: string) {
    this.calls.push({ op: 'stopBackground', arg: language });
    const job = this.find(name).jobs.find((j) => j.language === language)!;
    delete job.background;
    return structuredClone(this.find(name));
  }
}

const pages = { providers: async () => [] } as unknown as PageTranslationClient;
class FakeWorkflow implements ReviewWorkflowClient {
  requested: string[][] = [];
  async requestReview(paths: string[]) {
    this.requested.push(paths);
    return paths.map((_, i) => `wf_${i}`);
  }
  async running() {
    return {};
  }
  async close() {
    return 0;
  }
}

const wrap = (ui: React.ReactElement) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

describe('translation projects', () => {
  it('helpers: paths and what is left to do', () => {
    expect(parsePaths(` ${EN}/a.html\n\n${EN}/b, ${EN}/a `)).toEqual([`${EN}/a`, `${EN}/b`]);
    const p = new MemoryProjects().projects[0];
    expect(pendingItems(p, { ...p.jobs[0], items: [{ id: 'item_0', status: 'TRANSLATED' }] })).toEqual(['item_1']);
  });

  it('lists projects with their jobs and creates one', async () => {
    const client = new MemoryProjects();
    const opened: string[] = [];
    wrap(<ProjectList client={client} pages={pages} onOpen={(n) => opened.push(n)} />);
    const grid = await screen.findByRole('grid', { name: 'Translation projects' });
    await waitFor(() => expect(grid.textContent).toContain('Spring campaign'));
    expect(grid.textContent).toContain('en → ro, de');
    expect(grid.textContent).toContain('ro: Draft');
    expect(grid.textContent).toContain('de: Complete');

    fireEvent.click(screen.getByRole('button', { name: /Create project/ }));
    const dialog = await screen.findByRole('dialog');
    act(() => {
      fireEvent.change(within(dialog).getByRole('textbox', { name: /Title/ }), { target: { value: 'Autumn' } });
      fireEvent.change(within(dialog).getByRole('textbox', { name: /What to translate/ }), { target: { value: `${EN}/magazine` } });
      fireEvent.change(within(dialog).getByRole('textbox', { name: /Target languages/ }), { target: { value: 'ro, fr' } });
    });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    });
    await waitFor(() => expect(opened).toEqual(['new-one']));
    expect(client.calls[0]).toEqual({
      op: 'create',
      arg: expect.objectContaining({ title: 'Autumn', paths: [`${EN}/magazine`], languages: ['ro', 'fr'], deep: true, includeReferences: true }),
    });
  });

  it('runs a job item by item, requests review, approves; a connector failure stops it', async () => {
    const client = new MemoryProjects();
    const workflow = new FakeWorkflow();
    client.failOn = 'item_1';
    wrap(<ProjectView client={client} workflow={workflow} name="spring" onBack={() => {}} />);
    const ro = await screen.findByRole('article', { name: 'Romanian (ro) job' });
    await act(async () => {
      fireEvent.click(within(ro).getByRole('button', { name: 'Start' }));
    });
    // stopped at the second item: in progress, resumable
    await waitFor(() => expect(within(screen.getByRole('article', { name: 'Romanian (ro) job' })).getByRole('button', { name: /Resume \(1 to do\)/ })).toBeTruthy());
    expect(client.calls.filter((c) => c.op === 'translateItem')).toHaveLength(2);

    client.failOn = null;
    await act(async () => {
      fireEvent.click(within(screen.getByRole('article', { name: 'Romanian (ro) job' })).getByRole('button', { name: /Resume/ }));
    });
    const job = () => screen.getByRole('article', { name: 'Romanian (ro) job' });
    await waitFor(() => expect(job().textContent).toContain('Ready for review'));
    expect(job().textContent).toContain('2 language versions');

    await act(async () => {
      fireEvent.click(within(job()).getByRole('button', { name: 'Request review' }));
    });
    expect(workflow.requested).toEqual([[`/content/wknd/language-masters/ro/adventures`, `/content/wknd/language-masters/ro/adventures/bali`]]);

    await act(async () => {
      fireEvent.click(within(job()).getByRole('button', { name: 'Approve' }));
    });
    await waitFor(() => expect(within(job()).getByRole('button', { name: 'Complete' })).toBeTruthy());
    expect(client.calls).toContainEqual({ op: 'setStatus', arg: { language: 'ro', status: 'APPROVED' } });
    expect(job().textContent).toContain('2 approved');

    // the completed German job can be updated
    const de = screen.getByRole('article', { name: 'German (de) job' });
    await act(async () => {
      fireEvent.click(within(de).getByRole('button', { name: 'Update' }));
    });
    expect(client.calls).toContainEqual({ op: 'setStatus', arg: { language: 'de', status: 'DRAFT' } });
  });

  it('Pages: the selected pages go into a new project or join an open one in their language', async () => {
    expect(languageOf(`${EN}/adventures/bali`)).toBe('en');
    expect(languageOf('/content/wknd/us')).toBeNull();
    const client = new MemoryProjects();
    client.projects.push({ ...structuredClone(client.projects[0]), name: 'french', title: 'From French', sourceLanguage: 'fr' });
    client.projects.push({ ...structuredClone(client.projects[0]), name: 'closed', title: 'Closed', jobs: [{ ...client.projects[0].jobs[1] }] });
    const closed: string[] = [];
    const selected = [`${EN}/magazine/a`, `${EN}/magazine/b`];
    wrap(<SitesTranslationProjectDialog client={client} pages={pages} paths={selected} onClose={() => closed.push('x')} />);
    // a new project, the pages filled in, without the pages below
    let dialog = await screen.findByRole('dialog');
    expect((within(dialog).getByRole('textbox', { name: /What to translate/ }) as HTMLTextAreaElement).value).toBe(selected.join('\n'));
    expect((within(dialog).getByRole('checkbox', { name: 'Include the pages below' }) as HTMLInputElement).checked).toBe(false);

    // or an existing one: only open projects from the same language
    fireEvent.click(within(dialog).getByText('Add to an existing project instead'));
    dialog = await screen.findByRole('dialog', { name: 'Add to translation project' });
    await waitFor(() => expect(within(dialog).getByRole('button', { name: /Spring campaign/ })).toBeTruthy());
    expect(dialog.textContent).not.toContain('From French');
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    });
    expect(client.calls).toContainEqual({ op: 'addItems', arg: { name: 'spring', paths: selected, deep: false } });
    // the outcome, with the way to the project (the Assets console has no toasts)
    dialog = await screen.findByRole('dialog', { name: 'Added to the translation project' });
    expect(dialog.textContent).toContain('2 items went into “Spring campaign”');
    expect(within(dialog).getByRole('button', { name: 'Open project' })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(closed).toEqual(['x']));
  });

  it('a job can run on the server and be stopped there', async () => {
    const client = new MemoryProjects();
    wrap(<ProjectView client={client} workflow={new FakeWorkflow()} name="spring" onBack={() => {}} />);
    const job = () => screen.getByRole('article', { name: 'Romanian (ro) job' });
    await screen.findByRole('article', { name: 'Romanian (ro) job' });
    await act(async () => {
      fireEvent.click(within(job()).getByRole('button', { name: 'Run on the server' }));
    });
    await waitFor(() => expect(job().textContent).toContain('Translating on the server for admin'));
    expect(within(job()).queryByRole('button', { name: 'Start' })).toBeNull();
    await act(async () => {
      fireEvent.click(within(job()).getByRole('button', { name: 'Stop' }));
    });
    expect(client.calls.map((c) => c.op)).toEqual(['runInBackground', 'stopBackground']);
    await waitFor(() => expect(within(job()).getByRole('button', { name: 'Start' })).toBeTruthy());
  });

  it('shows what a job did and exchanges XLIFF with translators', async () => {
    const client = new MemoryProjects();
    Object.assign(client.projects[0].jobs[1], { status: 'READY_FOR_REVIEW', textsTranslated: 1520, characters: 84310, fromMemory: 12 });
    wrap(<ProjectView client={client} workflow={new FakeWorkflow()} name="spring" onBack={() => {}} />);
    const de = await screen.findByRole('article', { name: 'German (de) job' });
    expect(de.textContent).toContain('1,520 texts translated · 84,310 characters · 12 from the memory');
    // the version is chosen when exporting
    act(() => {
      fireEvent.keyDown(within(de).getByRole('button', { name: 'Export XLIFF' }), { key: 'ArrowDown' });
    });
    const menu = await screen.findByRole('menu');
    expect(within(menu).getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['XLIFF 1.2', 'XLIFF 2.0']);
    act(() => {
      fireEvent.keyDown(menu, { key: 'Escape' });
    });
    // no copies yet: nothing to exchange
    expect(within(screen.getByRole('article', { name: 'Romanian (ro) job' })).queryByRole('button', { name: 'Export XLIFF' })).toBeNull();

    const input = within(de).getByLabelText('XLIFF file for German (de)') as HTMLInputElement;
    await act(async () => {
      fireEvent.change(input, { target: { files: [new File(['<xliff/>'], 'spring-de.xlf', { type: 'application/xml' })] } });
    });
    const report = await screen.findByRole('dialog', { name: 'XLIFF imported · German (de)' });
    expect(report.textContent).toContain('7 texts saved in 2 copies, 30 unchanged');
    expect(report.textContent).toContain('The tags changed: <b> is missing');
    expect(client.calls).toContainEqual({ op: 'importXliff', arg: { language: 'de', size: 8 } });
  });

  it('a late project says so, and its due date can change', async () => {
    const client = new MemoryProjects();
    Object.assign(client.projects[0], { dueDate: Date.now() - 86_400_000, overdue: true, dueReminder: '/var/workflow/instances/x' });
    wrap(<ProjectView client={client} workflow={new FakeWorkflow()} name="spring" onBack={() => {}} />);
    const summary = await screen.findByRole('region', { name: 'Project' });
    expect(summary.textContent).toMatch(/Due .* · late · reminder in the Inbox/);
    fireEvent.click(within(summary).getByText('Change'));
    act(() => {
      fireEvent.change(within(summary).getByLabelText('Due date'), { target: { value: '2099-01-15' } });
    });
    await act(async () => {
      fireEvent.click(within(summary).getByRole('button', { name: 'Save' }));
    });
    const call = client.calls.find((c) => c.op === 'setDueDate');
    expect(new Date(call!.arg as number).getFullYear()).toBe(2099);
    await waitFor(() => expect(screen.getByRole('region', { name: 'Project' }).textContent).not.toContain('late'));
  });
});
