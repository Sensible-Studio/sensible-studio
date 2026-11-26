import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { StudioClient, SiteItem, SiteListing } from '../../editor/api/StudioClient';
import {
  ago,
  ancestorsOf,
  DEFAULT_PAGE_FACETS,
  emptyCriteria,
  isFiltering,
  parseCriteria,
  readRail,
  sameCriteria,
  resultsCsv,
  storeRail,
  tagIdOf,
  diffWords,
  plainText,
  sentences,
  versionPreviewUrl,
  type FilterCriteria,
  type PageDiff,
  type PageFacet,
  type PageSearchForm,
  type PropertyValues,
  type RestoreResult,
  type PageLaunch,
  type RestoreTreeResult,
  type SavedSearch,
  type SearchResults,
  type TreeAtNode,
  type VersionedPage,
  type SiteRailClient,
  type SiteSummary,
  type Timeline,
} from './api';
import { ContentTree } from './ContentTree';
import { FilterRail, FilterResults } from './FilterRail';
import { RailSelector } from './RailSelector';
import { SiteInfo } from './SiteInfo';
import { ReferencesRail } from './SitesRail';
import { Inspector, readInspector, storeInspector } from '../Inspector';
import { fates } from './RestoreTreeDialog';
import { TimelineRail } from './TimelineRail';

// jsdom has no CSS.escape, which Spectrum menus use
if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = { escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`) };
}

afterEach(cleanup);
beforeEach(() => {
  try {
    window.localStorage.clear();
  } catch {
    // ignore
  }
});

const inProvider = (ui: React.ReactElement) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

const page = (path: string, title: string, extra: Partial<SiteItem> = {}): SiteItem => ({
  path,
  name: path.slice(path.lastIndexOf('/') + 1),
  title,
  type: 'page',
  hasChildren: false,
  ...extra,
});

/** Just the StudioClient methods the rails use. */
function fakeClient(tree: Record<string, SiteItem[]>): StudioClient {
  return {
    siteChildren: async (path: string): Promise<SiteListing> => ({
      item: page(path, path),
      trail: [],
      children: tree[path] ?? [],
    }),
    references: async () => [],
    assetUrl: (p: string) => p,
  } as unknown as StudioClient;
}

class FakeRail implements SiteRailClient {
  comments: Array<{ id: string; message: string }> = [];
  versions = 0;
  searches: FilterCriteria[] = [];
  timeline = async (): Promise<Timeline> => ({
    lockOwner: 'editor',
    events: [
      ...this.comments.map((c) => ({ kind: 'comment' as const, id: c.id, title: 'Comment', text: c.message, user: 'admin', time: new Date().toISOString(), mine: true })),
      { kind: 'workflow', id: '/var/workflow/instances/1', title: 'Flow started: Review', user: 'admin', time: '2026-10-01T10:00:00.000Z', status: 'RUNNING', instance: '/var/workflow/instances/1' },
      { kind: 'version', id: 'v1', title: 'Version 1.0', time: '2026-09-01T10:00:00.000Z', version: '1.0' },
      { kind: 'activity', id: 'created', title: 'Page created', user: 'admin', time: '2026-08-01T10:00:00.000Z' },
      {
        kind: 'launch',
        id: '/content/launches/2026/10/18/spring:created',
        title: 'Release created: Spring',
        user: 'editor',
        time: '2026-07-01T10:00:00.000Z',
        launch: '/content/launches/2026/10/18/spring',
        pageInLaunch: '/content/launches/2026/10/18/spring/content/content/wknd/en',
        liveDate: '2026-11-01T10:00:00.000Z',
        promoted: false,
      },
      {
        kind: 'annotation',
        id: '/content/wknd/en/jcr:content/root/title/cq:annotations/1_1',
        title: 'Annotation',
        text: 'Shorter title?',
        user: 'reviewer',
        time: '2026-06-01T10:00:00.000Z',
        component: 'root/title',
        color: 'orange',
      },
    ],
  });
  launchesOf = async (path: string): Promise<PageLaunch[]> =>
    path === '/content/wknd/en'
      ? [{ path: '/content/launches/2026/10/18/spring', title: 'Spring', pageInLaunch: '/content/launches/2026/10/18/spring/content/content/wknd/en', liveDate: '2026-11-01T10:00:00.000Z', createdBy: 'editor' }]
      : [];
  comment = async (_path: string, message: string) => {
    this.comments.push({ id: `/var/studio/comments/c${this.comments.length}`, message });
  };
  deleteComment = async (id: string) => {
    this.comments = this.comments.filter((c) => c.id !== id);
  };
  saveVersion = async () => ({ id: 'v2', version: `1.${++this.versions}` });
  site = async (): Promise<SiteSummary> => ({
    path: '/content/wknd',
    title: 'WKND Site',
    isPage: true,
    conf: '/conf/wknd',
    languages: [{ path: '/content/wknd/language-masters/en', title: 'English', language: 'en' }],
    pages: 42,
    liveCopyPages: 3,
    lockedPages: 0,
    lastModified: { path: '/content/wknd/language-masters/en/faqs', title: 'FAQs', time: new Date().toISOString() },
    templates: [
      { path: '/conf/wknd/settings/wcm/templates/page', title: 'Content Page', status: 'enabled' },
      { path: '/conf/wknd/settings/wcm/templates/old', title: 'Old Page', status: 'disabled' },
    ],
  });
  templates = async () => [{ path: '/conf/wknd/settings/wcm/templates/page', title: 'Content Page' }];
  search = async (c: FilterCriteria): Promise<SearchResults> => {
    this.searches.push(c);
    return { total: 1, truncated: false, items: [page('/content/wknd/en/surf', 'Surf Camp', { lastModifiedBy: 'editor' })] };
  };
  saved: SavedSearch[] = [{ name: 'Locked pages', criteria: { ...emptyCriteria('/content/wknd'), locked: true } }];
  savedSearches = async () => [...this.saved];
  saveSearch = async (name: string, criteria: FilterCriteria) => {
    const s = { name, criteria };
    this.saved = [...this.saved.filter((x) => x.name !== name), s];
    return s;
  };
  deleteSearch = async (name: string) => {
    this.saved = this.saved.filter((x) => x.name !== name);
  };
  form: PageSearchForm = { facets: DEFAULT_PAGE_FACETS, custom: false, canEdit: true };
  searchForm = async () => this.form;
  saveSearchForm = async (facets: PageFacet[]) => {
    this.form = { facets, custom: true, canEdit: true, modifiedBy: 'admin' };
    return this.form;
  };
  resetSearchForm = async () => {
    this.form = { facets: DEFAULT_PAGE_FACETS, custom: false, canEdit: true };
    return this.form;
  };
  propertyValues = async (_path: string, property: string): Promise<PropertyValues> => ({
    property,
    values: property === 'region' ? [{ value: 'Europe', count: 3 }, { value: 'Asia', count: 1 }] : [],
    more: false,
    truncated: false,
  });
  restored: Array<[string, string]> = [];
  reverted: Array<[string, string]> = [];
  trees: Array<{ path: string; date: string; preserve: boolean }> = [];
  deleted: string[] = [];
  versionsInside = async (): Promise<VersionedPage[]> => [
    {
      path: '/content/wknd/en/faqs',
      name: 'faqs',
      title: 'FAQs',
      deleted: false,
      versions: [
        { id: 'f2', name: '1.1', comment: 'second', created: '2026-10-02T10:00:00.000Z' },
        { id: 'f1', name: '1.0', label: 'launch', comment: 'first', created: '2026-10-01T10:00:00.000Z' },
      ],
    },
    { path: '/content/wknd/en/old', name: 'old', title: 'Old Page', deleted: true, deletedBy: 'admin', deletedAt: new Date().toISOString(), versions: [{ id: 'o1', name: '1.0', comment: 'Deleted by admin', created: '2026-10-03T10:00:00.000Z' }] },
    { path: '/content/wknd/en/gone', name: 'gone', title: 'Gone Page', deleted: true, foundAt: new Date().toISOString(), versions: [{ id: 'g1', name: '1.0', created: '2026-10-03T10:00:00.000Z' }] },
  ];
  restoreVersions = async (choices: Array<[string, string]>): Promise<RestoreResult[]> => {
    this.restored.push(...choices);
    return choices.map(([path]) => ({ path, status: path.endsWith('/old') ? ('recreated' as const) : ('restored' as const) }));
  };
  treeAt = async (path: string): Promise<TreeAtNode> => ({
    path,
    name: 'en',
    title: 'English',
    page: true,
    exists: true,
    version: { id: 'e1', name: '1.0', created: '2026-10-01T10:00:00.000Z' },
    children: [
      { path: `${path}/later`, name: 'later', title: 'Later', page: true, exists: true, children: [] },
      { path: `${path}/old`, name: 'old', title: 'Old Page', page: true, exists: false, version: { id: 'o1', name: '1.0', created: '2026-10-01T10:00:00.000Z' }, children: [] },
    ],
  });
  restoreTree = async (path: string, date: string, preserve: boolean): Promise<RestoreTreeResult> => {
    this.trees.push({ path, date, preserve });
    return { restored: 1, recreated: 1, removed: preserve ? 0 : 1, failed: [] };
  };
  restore = async (path: string, version: string) => {
    this.reverted.push([path, version]);
  };
  deletePages = async (paths: string[]) => {
    this.deleted.push(...paths);
  };
  diffs: Array<[string, string, string]> = [];
  diff = async (path: string, from: string, to: string): Promise<PageDiff> => {
    this.diffs.push([path, from, to]);
    return {
      from,
      to,
      changes: [
        { node: '', kind: 'changed', property: 'jcr:title', before: 'English', after: 'English, edited' },
        { node: 'root/text', kind: 'changed', resourceType: 'wknd/components/text', property: 'text', before: '<p>Hello there, big</p>', after: '<p>Hello world, big</p>' },
        { node: 'root/teaser', kind: 'removed', resourceType: 'wknd/components/teaser', properties: { 'jcr:title': 'Old teaser' } },
      ],
    };
  };
}

describe('rail helpers', () => {
  it('turns tag paths into tag IDs', () => {
    expect(tagIdOf('/content/cq:tags/wknd/activity/surfing')).toBe('wknd:activity/surfing');
    expect(tagIdOf('/content/cq:tags/wknd')).toBe('wknd:');
  });

  it('tells how long ago', () => {
    const now = Date.parse('2026-10-17T12:00:00Z');
    expect(ago('2026-10-17T11:59:30Z', now)).toBe('just now');
    expect(ago('2026-10-17T11:55:00Z', now)).toBe('5 minutes ago');
    expect(ago('2026-10-17T09:00:00Z', now)).toBe('3 hours ago');
    expect(ago('2026-10-16T12:00:00Z', now)).toBe('yesterday');
    expect(ago('2026-10-07T12:00:00Z', now)).toBe('10 days ago');
    expect(ago('not a date', now)).toBe('not a date');
  });

  it('knows whether a filter is set', () => {
    const c = emptyCriteria('/content/wknd');
    expect(isFiltering(c)).toBe(false);
    expect(isFiltering({ ...c, text: '  ' })).toBe(false);
    expect(isFiltering({ ...c, text: 'surf' })).toBe(true);
    expect(isFiltering({ ...c, locked: true })).toBe(true);
    expect(isFiltering({ ...c, tags: ['wknd:'] })).toBe(true);
  });

  it('writes a CSV report', () => {
    const csv = resultsCsv([page('/content/a', 'A, "quoted"', { tags: ['x:y'] } as Partial<SiteItem>), page('/content/b', 'B', { liveCopy: 'inherited', lockOwner: 'me' })]);
    expect(csv.split('\n')[0]).toBe('Title,Path,Template,Modified,Modified By,Tags,Locked By,Linked copy');
    expect(csv).toContain('"A, ""quoted""",/content/a,,,,x:y,,');
    expect(csv).toContain('B,/content/b,,,,,me,yes');
  });

  it('remembers the rail and lists ancestors', () => {
    expect(readRail()).toBe('content');
    storeRail('filter');
    expect(readRail()).toBe('filter');
    expect(ancestorsOf('/content/wknd/us/en')).toEqual(['/content/wknd', '/content/wknd/us', '/content/wknd/us/en']);
    expect(ancestorsOf('/conf/wknd')).toEqual([]);
  });
});

describe('rail selector', () => {
  it('switches rails with the menu and the shortcuts', async () => {
    const onChange = vi.fn();
    inProvider(<RailSelector rail="tree" onChange={onChange} />);
    expect(screen.getByRole('button', { name: 'Rail: Content Tree' })).toBeTruthy();
    fireEvent.keyDown(window, { key: '™', code: 'Digit2', altKey: true });
    expect(onChange).toHaveBeenLastCalledWith('filter');
    fireEvent.keyDown(window, { key: '¡', code: 'Digit1', altKey: true });
    expect(onChange).toHaveBeenLastCalledWith('tree');
    fireEvent.keyDown(window, { key: '`' });
    expect(onChange).toHaveBeenLastCalledWith('content');
    onChange.mockClear();
    const input = document.createElement('input');
    document.body.appendChild(input);
    fireEvent.keyDown(input, { key: '`' });
    expect(onChange).not.toHaveBeenCalled();
    input.remove();
  });
});

describe('content tree', () => {
  it('opens down to the current page and navigates', async () => {
    const client = fakeClient({
      '/content': [page('/content/wknd', 'WKND Site', { hasChildren: true }), { ...page('/content/campaigns', 'Campaigns'), type: 'folder' }],
      '/content/wknd': [page('/content/wknd/en', 'English', { hasChildren: true })],
      '/content/wknd/en': [page('/content/wknd/en/faqs', 'FAQs')],
    });
    const onNavigate = vi.fn();
    inProvider(<ContentTree client={client} path="/content/wknd/en" onNavigate={onNavigate} />);
    const tree = await screen.findByRole('tree', { name: 'Content tree' });
    await within(tree).findByText('FAQs');
    const current = screen.getAllByRole('treeitem').find((li) => li.getAttribute('aria-selected') === 'true');
    expect(current?.textContent).toContain('English');
    fireEvent.click(screen.getByRole('button', { name: 'Collapse WKND Site' }));
    expect(screen.queryByText('FAQs')).toBeNull();
    fireEvent.click(screen.getByText('Campaigns'));
    expect(onNavigate).toHaveBeenCalledWith('/content/campaigns', expect.objectContaining({ path: '/content/campaigns' }));
  });
});

describe('timeline', () => {
  it('asks for a page', () => {
    inProvider(<TimelineRail rail={new FakeRail()} page={null} />);
    expect(screen.getByText('Select a page to display its timeline.')).toBeTruthy();
  });

  it('lists the events, adds and deletes comments, saves versions', async () => {
    const rail = new FakeRail();
    const onChanged = vi.fn();
    inProvider(<TimelineRail rail={rail} page={{ path: '/content/wknd/en', title: 'English' }} onChanged={onChanged} />);
    const list = await screen.findByRole('list', { name: 'Timeline of English' });
    await within(list).findByText('Page created');
    expect(within(list).getByText('Version 1.0')).toBeTruthy();
    expect(within(list).getByText('running')).toBeTruthy();
    expect(screen.getByText(/Locked by editor/)).toBeTruthy();

    const field = screen.getByRole('textbox', { name: 'Comment' });
    fireEvent.change(field, { target: { value: 'Check the hero image' } });
    fireEvent.keyDown(field, { key: 'Enter' });
    await within(list).findByText('Check the hero image');
    expect(rail.comments).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Delete comment' }));
    await waitFor(() => expect(within(list).queryByText('Check the hero image')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'More actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as Version' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Label' }), { target: { value: 'Before release' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(rail.versions).toBe(1);
  });
});

describe('launches', () => {
  it('shows the releases of a page in the timeline, with a filter of their own', async () => {
    inProvider(<TimelineRail rail={new FakeRail()} page={{ path: '/content/wknd/en', title: 'English' }} />);
    const list = await screen.findByRole('list', { name: 'Timeline of English' });
    const created = await within(list).findByText('Release created: Spring');
    const link = created.closest('li')?.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/editor.html/content/launches/2026/10/18/spring/content/content/wknd/en.html');
    expect(link?.textContent).toBe('Open in release');

    fireEvent.click(screen.getByRole('button', { name: /Show All/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Releases' }));
    await waitFor(() => expect(within(list).queryByText('Page created')).toBeNull());
    expect(within(list).getByText('Release created: Spring')).toBeTruthy();
  });

  it('shows the annotations of a page in the timeline, with a filter of their own', async () => {
    inProvider(<TimelineRail rail={new FakeRail()} page={{ path: '/content/wknd/en', title: 'English' }} />);
    const list = await screen.findByRole('list', { name: 'Timeline of English' });
    const note = await within(list).findByText('Shorter title?');
    const item = note.closest('li') as HTMLElement;
    expect(within(item).getByText('Annotation')).toBeTruthy();
    expect((note as HTMLElement).style.borderLeftColor).toBe('orange');
    expect(within(item).getByText(/On title/).getAttribute('title')).toBe('root/title');
    expect(within(item).getByRole('link', { name: 'Open in editor' }).getAttribute('href')).toBe('/editor.html/content/wknd/en.html');

    fireEvent.click(screen.getByRole('button', { name: /Show All/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Annotations' }));
    await waitFor(() => expect(within(list).queryByText('Release created: Spring')).toBeNull());
    expect(within(list).getByText('Shorter title?')).toBeTruthy();
  });

  it('lists the releases of the selected page in the References rail', async () => {
    const english = page('/content/wknd/en', 'English');
    inProvider(
      <ReferencesRail client={fakeClient({})} siteRail={new FakeRail()} selection={[english]} onChanged={() => {}} />,
    );
    const section = await screen.findByRole('region', { name: 'Releases' });
    expect(within(section).getByText('Releases (1)')).toBeTruthy();
    expect(within(section).getByRole('link', { name: 'Spring' }).getAttribute('href')).toBe(
      '/editor.html/content/launches/2026/10/18/spring/content/content/wknd/en.html',
    );
    cleanup();
    // a page in no launch: no section
    inProvider(
      <ReferencesRail client={fakeClient({})} siteRail={new FakeRail()} selection={[page('/content/wknd/de', 'Deutsch')]} onChanged={() => {}} />,
    );
    await screen.findByRole('region', { name: 'References' });
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole('region', { name: 'Releases' })).toBeNull();
  });
});

describe('restore', () => {
  const english = { path: '/content/wknd/en', title: 'English' };

  it('reverts a page to a version from its timeline entry', async () => {
    const rail = new FakeRail();
    const onChanged = vi.fn();
    inProvider(<TimelineRail rail={rail} page={english} onChanged={onChanged} />);
    const entry = await screen.findByRole('button', { name: /Version 1\.0/ });
    fireEvent.click(entry);
    fireEvent.click(await screen.findByRole('button', { name: 'Revert to this Version' }));
    await waitFor(() => expect(rail.reverted).toEqual([['/content/wknd/en', 'v1']]));
    expect(onChanged).toHaveBeenCalled();
  });

  it('restores chosen versions inside a page, deleted pages too', async () => {
    const rail = new FakeRail();
    inProvider(<TimelineRail rail={rail} page={english} />);
    await screen.findByText('Page created');
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Restore Version' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('Old Page');
    expect(within(dialog).getAllByText('Deleted')).toHaveLength(2);
    expect(within(dialog).getByText(/deleted outside Pages, noticed just now/)).toBeTruthy();
    expect(within(dialog).getByText('second')).toBeTruthy();
    const restore = within(dialog).getByRole('button', { name: 'Restore' });
    expect(restore.hasAttribute('disabled')).toBe(true);
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Preview Old Page in this version' }));
    expect(open).toHaveBeenCalledWith('/bin/studio/versionpreview.html?path=%2Fcontent%2Fwknd%2Fen%2Fold&version=o1', '_blank', 'noopener');
    open.mockRestore();
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Select all' }));
    expect(within(dialog).getByText('3 selected page(s)')).toBeTruthy();
    fireEvent.click(restore);
    await within(dialog).findByText('Restored (was deleted)');
    expect(rail.restored).toEqual([
      ['/content/wknd/en/faqs', 'f2'],
      ['/content/wknd/en/old', 'o1'],
      ['/content/wknd/en/gone', 'g1'],
    ]);
    expect(within(dialog).getByRole('button', { name: 'Done' })).toBeTruthy();
  });

  it('restores a tree to a date, preserving non versioned pages or not', async () => {
    const rail = new FakeRail();
    inProvider(<TimelineRail rail={rail} page={english} />);
    await screen.findByText('Page created');
    fireEvent.click(screen.getByRole('button', { name: 'More actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Restore Tree' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText(/Later \(later, not versioned\)/);
    expect(within(dialog).getByText('Not versioned: kept as it is')).toBeTruthy();
    expect(within(dialog).getByText('Deleted since: comes back')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Preserve Non Versioned Pages' }));
    expect(within(dialog).getByText('Not versioned: deleted (it can be restored later)')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Restore' }));
    await waitFor(() => expect(rail.trees).toHaveLength(1));
    expect(rail.trees[0].preserve).toBe(false);
    expect(Number.isNaN(Date.parse(rail.trees[0].date))).toBe(false);
  });

  it('decides the fate of each node like the server', () => {
    const v = { id: 'x', name: '1.0', created: '2026-01-01T00:00:00Z' };
    const tree: TreeAtNode = {
      path: '/r', name: 'r', title: 'R', page: true, exists: true, children: [
        { path: '/r/a', name: 'a', title: 'A', page: true, exists: true, children: [{ path: '/r/a/b', name: 'b', title: 'B', page: true, exists: true, version: v, children: [] }] },
        { path: '/r/gone', name: 'gone', title: 'Gone', page: true, exists: false, fallback: v, children: [{ path: '/r/gone/c', name: 'c', title: 'C', page: true, exists: false, version: v, children: [] }] },
        { path: '/r/new', name: 'new', title: 'New', page: true, exists: true, children: [] },
      ],
    };
    const keep = fates(tree, true);
    expect(keep.get('/r')).toBe('keep');
    expect(keep.get('/r/a')).toBe('keep');
    expect(keep.get('/r/a/b')).toBe('restore');
    expect(keep.get('/r/gone')).toBe('container');
    expect(keep.get('/r/gone/c')).toBe('recreate');
    expect(keep.get('/r/new')).toBe('keep');
    const exact = fates(tree, false);
    expect(exact.get('/r')).toBe('keep');
    expect(exact.get('/r/a')).toBe('keep');
    expect(exact.get('/r/new')).toBe('remove');
  });
});

describe('preview and compare', () => {
  it('diffs words and reads rich text as text', () => {
    expect(diffWords('Hello there big world', 'Hello brave big world')).toEqual([
      { type: 'same', text: 'Hello ' },
      { type: 'removed', text: 'there ' },
      { type: 'added', text: 'brave ' },
      { type: 'same', text: 'big world' },
    ]);
    expect(diffWords('', 'new')).toEqual([{ type: 'added', text: 'new' }]);
    expect(diffWords('same', 'same')).toEqual([{ type: 'same', text: 'same' }]);
    expect(diffWords('one two three four five', 'six seven three eight nine')).toEqual([
      { type: 'removed', text: 'one two three four five' },
      { type: 'added', text: 'six seven three eight nine' },
    ]);
    // sentences that stayed anchor the comparison: a rewritten sentence does not drag its neighbours along
    expect(diffWords('Keep this. One two three four five. Keep that.', 'Keep this. Six seven eight nine ten. Keep that.')).toEqual([
      { type: 'same', text: 'Keep this. ' },
      { type: 'removed', text: 'One two three four five. ' },
      { type: 'added', text: 'Six seven eight nine ten. ' },
      { type: 'same', text: 'Keep that.' },
    ]);
    // a mostly rewritten paragraph keeps the sentence that stayed (as a whole it used to be old → new)
    const old = 'We travel far. Alpha beta gamma delta. Epsilon zeta eta theta. Iota kappa lambda mu.';
    const now = 'We travel far. One two three four. Five six seven eight. Nine ten eleven twelve.';
    expect(diffWords(old, now)[0]).toEqual({ type: 'same', text: 'We travel far. ' });
    // a long text with one changed word still gets the word marked
    const long = Array.from({ length: 80 }, (_, i) => `Sentence number ${i} says something rather ordinary.`).join(' ');
    const parts = diffWords(long, long.replace('number 40 says', 'number 40 shouts'));
    expect(parts.filter((p) => p.type !== 'same').map((p) => p.text.trim())).toEqual(['says', 'shouts']);
    expect(sentences('Version 1.2 is out. Next!  Done')).toEqual(['Version 1.2 is out. ', 'Next!  ', 'Done']);
    expect(plainText('<p>Hello&nbsp;<b>world</b></p>')).toBe('Hello world');
    expect(plainText('plain')).toBe('plain');
    expect(versionPreviewUrl('/content/a b', 'v1')).toBe('/bin/studio/versionpreview.html?path=%2Fcontent%2Fa+b&version=v1');
  });

  it('previews a version and compares it to the current page', async () => {
    const rail = new FakeRail();
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    try {
      inProvider(<TimelineRail rail={rail} page={{ path: '/content/wknd/en', title: 'English' }} />);
      fireEvent.click(await screen.findByRole('button', { name: /Version 1\.0/ }));
      fireEvent.click(await screen.findByRole('button', { name: 'Preview Version' }));
      expect(open).toHaveBeenCalledWith('/bin/studio/versionpreview.html?path=%2Fcontent%2Fwknd%2Fen&version=v1', '_blank', 'noopener');

      fireEvent.click(screen.getByRole('button', { name: 'Compare to Current' }));
      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByTitle('English, current').getAttribute('src')).toBe('/content/wknd/en.html?wcmmode=disabled');
      expect(within(dialog).getByTitle(/English, version 1\.0/).getAttribute('src')).toContain('version=v1');
      await waitFor(() => expect(rail.diffs).toEqual([['/content/wknd/en', 'v1', 'current']]));
      // highlighting inside the frames is on by default (the frames themselves are tested in highlight.test.ts)
      expect((within(dialog).getByRole('switch', { name: 'Highlight changes' }) as HTMLInputElement).checked).toBe(true);
      fireEvent.click(within(dialog).getByRole('tab', { name: 'Changes' }));
      expect(within(dialog).queryByRole('switch', { name: 'Highlight changes' })).toBeNull();
      const changes = await within(dialog).findByRole('list', { name: 'Changes' });
      expect(within(changes).getByText('Page properties')).toBeTruthy();
      const text = within(changes).getByText('root/text').closest('li');
      expect(text?.querySelector('del')?.textContent).toBe('there, ');
      expect(text?.querySelector('ins')?.textContent).toBe('world, ');
      expect(within(changes).getByText('Removed')).toBeTruthy();
      expect(within(changes).getByText('Old teaser')).toBeTruthy();
    } finally {
      open.mockRestore();
    }
  });
});

describe('site', () => {
  it('summarises the site of the path', async () => {
    const onNavigate = vi.fn();
    inProvider(<SiteInfo rail={new FakeRail()} path="/content/wknd/en" onNavigate={onNavigate} />);
    await screen.findByText('WKND Site');
    expect(screen.getByText('42')).toBeTruthy();
    expect(screen.getByText('Linked copy pages')).toBeTruthy();
    expect(screen.queryByText('Locked pages')).toBeNull();
    expect(screen.getByText('(disabled)')).toBeTruthy();
    fireEvent.click(screen.getByText('FAQs'));
    expect(onNavigate).toHaveBeenCalledWith('/content/wknd/language-masters/en');
    fireEvent.click(screen.getByText('English'));
    expect(onNavigate).toHaveBeenLastCalledWith('/content/wknd/language-masters/en');
  });

  it('only for sites', () => {
    inProvider(<SiteInfo rail={new FakeRail()} path="/content/dam/wknd" onNavigate={() => {}} />);
    expect(screen.getByText('Select an item to see the site controls.')).toBeTruthy();
  });
});

describe('filter', () => {
  it('searches after a pause and clears', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const rail = new FakeRail();
      const onResults = vi.fn();
      let criteria = emptyCriteria('/content/wknd');
      const { rerender } = inProvider(<FilterRail rail={rail} criteria={criteria} onCriteria={(c) => (criteria = c)} onResults={onResults} />);
      expect(onResults).toHaveBeenLastCalledWith(null);
      fireEvent.change(screen.getByRole('searchbox', { name: 'Full text' }), { target: { value: 'surf' } });
      expect(criteria.text).toBe('surf');
      rerender(
        <Provider theme={defaultTheme}>
          <FilterRail rail={rail} criteria={criteria} onCriteria={(c) => (criteria = c)} onResults={onResults} />
        </Provider>,
      );
      await act(async () => {
        vi.advanceTimersByTime(300);
      });
      await waitFor(() => expect(onResults).toHaveBeenLastCalledWith(expect.objectContaining({ total: 1 })));
      expect(rail.searches[0].text).toBe('surf');
      fireEvent.click(screen.getByRole('button', { name: 'Clear Filters' }));
      expect(isFiltering(criteria)).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows the filters of the search form, filters on page properties too', async () => {
    const rail = new FakeRail();
    rail.form = {
      custom: true,
      facets: [
        { type: 'options', label: 'Region', property: 'region', options: [{ value: 'eu', label: 'Europe' }, { value: 'asia', label: 'Asia' }] },
        { type: 'text', label: 'Browser title', property: 'pageTitle', description: 'What the tab shows' },
        { type: 'tags', label: 'Topics' },
      ],
    };
    const seen: FilterCriteria[] = [];
    function Host() {
      const [criteria, setCriteria] = useState(emptyCriteria('/content/wknd'));
      return (
        <FilterRail
          rail={rail}
          criteria={criteria}
          onCriteria={(c) => {
            seen.push(c);
            setCriteria(c);
          }}
          onResults={() => {}}
        />
      );
    }
    inProvider(<Host />);
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Europe' }));
    // the built-in filters the form left out are not there
    expect(screen.queryByRole('textbox', { name: 'Path' })).toBeNull();
    expect(screen.queryByRole('checkbox', { name: 'Locked pages only' })).toBeNull();
    expect(screen.getByText('Topics')).toBeTruthy();
    expect(screen.getByText('What the tab shows')).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: /Browser title/ }), { target: { value: 'bali' } });
    const last = seen[seen.length - 1];
    expect(last.meta).toEqual({ region: ['eu'] });
    expect(last.metaText).toEqual({ pageTitle: 'bali' });
    expect(isFiltering(last)).toBe(true);
    await waitFor(() => expect(rail.searches.some((c) => c.metaText?.pageTitle === 'bali')).toBe(true));
    // saved searches keep them
    expect(parseCriteria(JSON.stringify(last))).toEqual(last);
    expect(sameCriteria(last, { ...last, meta: { region: ['eu'] }, metaText: { pageTitle: 'bali ' } })).toBe(true);
    expect(sameCriteria(last, { ...last, meta: {} })).toBe(false);
  });

  it('saves, opens and deletes searches', async () => {
    const rail = new FakeRail();
    const seen: FilterCriteria[] = [];
    function Host() {
      const [criteria, setCriteria] = useState(emptyCriteria('/content/wknd'));
      return (
        <FilterRail
          rail={rail}
          criteria={criteria}
          onCriteria={(c) => {
            seen.push(c);
            setCriteria(c);
          }}
          onResults={() => {}}
        />
      );
    }
    inProvider(<Host />);
    expect((screen.getByRole('button', { name: /Save Search/ }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(screen.getByRole('searchbox', { name: 'Full text' }), { target: { value: 'surf' } });
    fireEvent.click(screen.getByRole('button', { name: /Save Search/ }));
    const dialog = await screen.findByRole('dialog');
    const name = within(dialog).getByRole('textbox', { name: 'Name' }) as HTMLInputElement;
    expect(name.value).toBe('surf');
    fireEvent.change(name, { target: { value: 'Surf pages' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(rail.saved.map((x) => x.name)).toEqual(['Locked pages', 'Surf pages']);
    expect(rail.saved[1].criteria.text).toBe('surf');

    // the search matching the current filters shows as chosen, and can be deleted
    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete saved search Surf pages' })).toBeTruthy());
    expect(screen.getAllByText('Surf pages').length).toBeGreaterThan(0);

    // choosing another one sets its filters
    fireEvent.click(screen.getByRole('button', { name: /Saved Searches/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Locked pages' }));
    expect(seen[seen.length - 1]).toEqual(expect.objectContaining({ locked: true, text: '' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete saved search Locked pages' }));
    await waitFor(() => expect(rail.saved.map((x) => x.name)).toEqual(['Surf pages']));
  });

  it('reads saved criteria defensively', () => {
    expect(parseCriteria('{"text":"surf","tags":["wknd:a",3],"modifiedDays":7,"liveCopy":"x","locked":"yes"}')).toEqual({
      path: '/content',
      text: 'surf',
      template: null,
      tags: ['wknd:a'],
      modifiedDays: 7,
      liveCopy: '',
      locked: false,
    });
    expect(parseCriteria('not json')).toEqual(emptyCriteria('/content'));
    expect(sameCriteria({ ...emptyCriteria('/c'), tags: ['b', 'a'], text: 'x ' }, { ...emptyCriteria('/c'), tags: ['a', 'b'], text: 'x' })).toBe(true);
  });

  it('lists the results', () => {
    const onOpen = vi.fn();
    const client = fakeClient({});
    inProvider(
      <FilterResults client={client} results={{ total: 3, truncated: true, items: [page('/content/wknd/en/surf', 'Surf Camp')] }} selected={[]} onOpen={onOpen} onToggle={() => {}} />,
    );
    expect(screen.getByText('1 of 3+')).toBeTruthy();
    fireEvent.click(screen.getByText('Surf Camp'));
    expect(onOpen).toHaveBeenCalled();
    cleanup();
    inProvider(<FilterResults client={client} results={{ total: 0, truncated: false, items: [] }} selected={[]} onOpen={onOpen} onToggle={() => {}} />);
    expect(screen.getByText('No page matches the filters.')).toBeTruthy();
  });
});

describe('inspector', () => {
  it('shows the selected page, switches tabs and remembers them', async () => {
    const onTab = vi.fn();
    const onEdit = vi.fn();
    const english = page('/content/wknd/en', 'English', { template: '/conf/wknd/settings/wcm/templates/page', templateTitle: 'Page' });
    inProvider(
      <Inspector
        tab="properties"
        onTab={onTab}
        onClose={() => {}}
        client={fakeClient({})}
        siteRail={new FakeRail()}
        location={page('/content/wknd', 'WKND')}
        selection={[english]}
        onNavigate={() => {}}
        onChanged={() => {}}
        onEdit={onEdit}
        onProperties={() => {}}
      />,
    );
    const panel = screen.getByRole('complementary', { name: 'Inspector' });
    expect(within(panel).getAllByText('English').length).toBeGreaterThan(0);
    expect(within(panel).getByText('/content/wknd/en')).toBeTruthy();
    // the kind above the title, the template as a pill and in the list
    expect(within(panel).getAllByText('Page').length).toBeGreaterThan(1);
    expect(within(panel).getByText('Not published', { selector: '.studio-pill' })).toBeTruthy();
    fireEvent.click(within(panel).getByRole('button', { name: /^Edit/ }));
    expect(onEdit).toHaveBeenCalledWith(english);
    fireEvent.click(within(panel).getByRole('radio', { name: 'References' }));
    expect(onTab).toHaveBeenLastCalledWith('references');
    cleanup();
    // nothing selected: where you are
    inProvider(
      <Inspector
        tab="references"
        onTab={onTab}
        onClose={() => {}}
        client={fakeClient({})}
        siteRail={new FakeRail()}
        location={page('/content/wknd', 'WKND')}
        selection={[]}
        onNavigate={() => {}}
        onChanged={() => {}}
        onEdit={onEdit}
        onProperties={() => {}}
      />,
    );
    expect(within(screen.getByRole('complementary', { name: 'Inspector' })).getByText('WKND')).toBeTruthy();
    await screen.findByRole('region', { name: 'References' });
    // never chosen: open on Properties; closed stays closed
    window.localStorage.removeItem('studio.sites.inspector');
    expect(readInspector()).toBe('properties');
    storeInspector('timeline');
    expect(readInspector()).toBe('timeline');
    storeInspector(null);
    expect(readInspector()).toBeNull();
  });
});
