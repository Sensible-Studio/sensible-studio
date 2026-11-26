import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { TagDetail, TagListing, TagsClient } from '../tags/api';
import { downloadUrl, tagIdOf, tagPathOf } from './api';
import { AssetsApp } from './AssetsApp';
import { common } from './bulk';
import { assetsRoute, routeUrl, type AssetsRoute } from './route';
import { TagsClientContext } from './tagsField';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

const SURF = '/content/dam/wknd/surf.jpg';
const SKI = '/content/dam/wknd/ski.jpg';

function demo(): MemoryDam {
  return new MemoryDam()
    .folder('/content/dam/wknd', 'WKND Site')
    .asset(SURF, {}, { 'dc:title': 'Surf in Bali', 'dc:subject': ['surf'], 'dc:language': 'en', 'cq:tags': ['wknd:activity/surfing'] })
    .asset(SKI, {}, { 'dc:title': 'Ski', 'dc:subject': ['ski', 'winter'], 'dc:language': 'en' });
}

/** Two namespaces' worth of tags. */
class Tags implements TagsClient {
  private readonly tree: Record<string, Array<{ name: string; title: string; hasChildren: boolean }>> = {
    '/content/cq:tags': [{ name: 'wknd', title: 'WKND', hasChildren: true }],
    '/content/cq:tags/wknd': [{ name: 'activity', title: 'Activity', hasChildren: true }],
    '/content/cq:tags/wknd/activity': [
      { name: 'surfing', title: 'Surfing', hasChildren: false },
      { name: 'skiing', title: 'Skiing', hasChildren: false },
    ],
  };

  async children(path: string): Promise<TagListing> {
    return {
      item: { path, name: path.slice(path.lastIndexOf('/') + 1), title: path, hasChildren: true },
      trail: [{ path, title: path }],
      children: (this.tree[path] ?? []).map((c) => ({ ...c, path: `${path}/${c.name}`, tagID: tagIdOf(`${path}/${c.name}`) ?? undefined })),
    };
  }

  async tag(path: string): Promise<TagDetail> {
    const parent = path.slice(0, path.lastIndexOf('/'));
    const t = (this.tree[parent] ?? []).find((c) => `${parent}/${c.name}` === path);
    if (!t) throw new Error('no such tag');
    return { path, name: t.name, title: t.title, hasChildren: t.hasChildren, localized: {}, languages: [] };
  }

  references = async () => [];
  create = async () => {
    throw new Error('read only');
  };
  update = this.create;
  move = this.create;
  merge = this.create;
  remove = this.create;
  currentUser = async () => 'admin';
}

function mount(dam: MemoryDam, route: AssetsRoute) {
  window.history.replaceState(null, '', routeUrl(route));
  return render(
    <Provider theme={defaultTheme}>
      <TagsClientContext.Provider value={new Tags()}>
        <AssetsApp client={dam} initial={route} />
      </TagsClientContext.Provider>
    </Provider>,
  );
}

async function selectCards(...names: RegExp[]) {
  for (const name of names) fireEvent.click(within(await screen.findByRole('gridcell', { name })).getByRole('button', { name: 'Select' }));
}

const bar = () => within(screen.getByRole('toolbar', { name: 'Selection actions' }));

describe('helpers', () => {
  it('converts tag IDs and builds download URLs', () => {
    expect(tagIdOf('/content/cq:tags/wknd/activity/surfing')).toBe('wknd:activity/surfing');
    expect(tagIdOf('/content/cq:tags/wknd')).toBe('wknd:');
    expect(tagIdOf('/content/dam')).toBeNull();
    expect(tagPathOf('wknd:activity/surfing')).toBe('/content/cq:tags/wknd/activity/surfing');
    expect(tagPathOf('wknd:')).toBe('/content/cq:tags/wknd');
    expect(downloadUrl(['/content/dam/a b', '/content/dam/c'], { renditions: true })).toBe(
      '/bin/studio/dam/download.zip?path=%2Fcontent%2Fdam%2Fa+b&path=%2Fcontent%2Fdam%2Fc&renditions=true',
    );
  });

  it('finds what several files have in common', () => {
    const c = common([{ metadata: { 'dc:title': 'A', 'dc:language': 'en', 'dc:subject': ['x'] } }, { metadata: { 'dc:title': 'B', 'dc:language': 'en', 'dc:subject': ['x'] } }]);
    expect(c.single['dc:title']).toBeNull();
    expect(c.single['dc:language']).toBe('en');
    expect(c.multi['dc:subject']).toEqual(['x']);
    expect(c.multi['cq:tags']).toEqual([]);
  });
});

describe('upload conflicts', () => {
  it('asks what to do with an existing name, then keeps both', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    const main = (await screen.findByRole('gridcell', { name: /Surf in Bali/ })).closest('main')!;
    fireEvent.drop(main, { dataTransfer: { files: [new File(['x'], 'surf.jpg', { type: 'image/jpeg' })], types: ['Files'] } });
    const choices = await screen.findByRole('group', { name: 'Conflict for surf.jpg' });
    expect(screen.getByText('1 name conflict')).toBeTruthy();
    fireEvent.click(within(choices).getByRole('button', { name: 'Keep both' }));
    await waitFor(() => expect(dam.calls).toContain('upload /content/dam/wknd/surf.jpg rename'));
    expect(dam.nodes.has('/content/dam/wknd/surf1.jpg')).toBe(true);
  });

  it('creates a version before replacing', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    const main = (await screen.findByRole('gridcell', { name: /Surf in Bali/ })).closest('main')!;
    fireEvent.drop(main, { dataTransfer: { files: [new File(['x'], 'surf.jpg', { type: 'image/jpeg' })], types: ['Files'] } });
    fireEvent.click(within(await screen.findByRole('group', { name: 'Conflict for surf.jpg' })).getByRole('button', { name: 'Create version' }));
    await waitFor(() => expect(dam.calls).toContain('upload /content/dam/wknd/surf.jpg version'));
    expect(dam.versionsOf.get(SURF)?.[0].comment).toBe('Before upload of a new version');
  });
});

describe('timeline', () => {
  it('saves a labelled version and reverts to it', async () => {
    const dam = demo();
    mount(dam, { kind: 'details', path: SURF, properties: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Timeline' }));
    const rail = await screen.findByRole('complementary', { name: 'Timeline' });
    expect(await within(rail).findByText('No versions yet.')).toBeTruthy();
    fireEvent.change(within(rail).getByRole('textbox', { name: 'Label' }), { target: { value: 'Approved' } });
    fireEvent.change(within(rail).getByRole('textbox', { name: 'Comment' }), { target: { value: 'For the summer campaign' } });
    fireEvent.click(within(rail).getByRole('button', { name: 'Save as Version' }));
    expect(await within(rail).findByText('Approved')).toBeTruthy();
    expect(within(rail).getByText('For the summer campaign')).toBeTruthy();
    expect(dam.calls).toContain(`createVersion ${SURF} "Approved" (For the summer campaign)`);

    fireEvent.click(within(rail).getByRole('button', { name: 'Revert to 1.0' }));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Revert' }));
    await waitFor(() => expect(dam.calls).toContain(`restoreVersion ${SURF} 1.0`));
    expect(await within(rail).findByText('Before restoring version 1.0')).toBeTruthy();
  });
});

describe('tags', () => {
  it('shows tag titles and adds a tag from the picker', async () => {
    const dam = demo();
    mount(dam, { kind: 'details', path: SURF, properties: true });
    const tags = await screen.findByRole('grid', { name: 'Tags' });
    expect(await within(tags).findByText('Surfing')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add tags' }));
    const picker = await screen.findByRole('dialog');
    fireEvent.click(await within(picker).findByRole('button', { name: 'Open WKND' }));
    fireEvent.click(await within(picker).findByRole('button', { name: 'Open Activity' }));
    fireEvent.click(await within(picker).findByText('Skiing'));
    fireEvent.click(within(picker).getByRole('button', { name: 'Select' }));
    expect(await within(tags).findByText('Skiing')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain(`metadata ${SURF} {"cq:tags":["wknd:activity/surfing","wknd:activity/skiing"]}`));
  });
});

describe('several files', () => {
  it('appends keywords to each file and sets a common field', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await selectCards(/Surf in Bali/, /Ski/);
    fireEvent.click(bar().getByRole('button', { name: 'Properties' }));
    const dialog = await screen.findByRole('dialog');
    const title = await within(dialog).findByRole('textbox', { name: 'Title' });
    expect(title.getAttribute('placeholder')).toBe('Mixed values');
    expect((within(dialog).getByRole('textbox', { name: 'Language' }) as HTMLInputElement).value).toBe('en');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Copyright' }), { target: { value: '© WKND' } });
    const keywords = within(dialog).getByRole('textbox', { name: 'Keywords' });
    fireEvent.change(keywords, { target: { value: 'outdoor' } });
    fireEvent.keyDown(keywords, { key: 'Enter' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain(`metadata ${SKI} {"dc:rights":"© WKND","dc:subject":["outdoor"]} append dc:subject`));
    expect(dam.nodes.get(SKI)!.metadata['dc:subject']).toEqual(['ski', 'winter', 'outdoor']);
    expect(dam.nodes.get(SURF)!.metadata['dc:subject']).toEqual(['surf', 'outdoor']);
    expect(dam.nodes.get(SURF)!.metadata['dc:title']).toBe('Surf in Bali');
    expect(await screen.findByText('Properties of 2 files saved')).toBeTruthy();
  });

  it('downloads several items as one ZIP', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await selectCards(/Surf in Bali/, /Ski/);
    fireEvent.click(bar().getByRole('button', { name: 'Download' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Renditions' }));
    let href = '';
    const click = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function (this: HTMLAnchorElement) {
      href = this.getAttribute('href') ?? '';
    };
    try {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Download' }));
    } finally {
      HTMLAnchorElement.prototype.click = click;
    }
    expect(href).toBe(downloadUrl([SURF, SKI], { renditions: true }));
  });
});

describe('search', () => {
  it('round-trips the search through the URL', () => {
    const route: AssetsRoute = { kind: 'browse', path: '/content/dam/wknd', search: { fulltext: 'surf bali', types: ['image', 'video'], tags: ['wknd:a'], modified: '7d', size: 'small', orientation: 'landscape' } };
    const url = new URL(routeUrl(route), 'http://x');
    expect(url.pathname).toBe('/assets.html/content/dam/wknd');
    expect(assetsRoute({ pathname: url.pathname, search: url.search, hash: '' })).toEqual(route);
    expect(assetsRoute({ pathname: '/assets.html/content/dam', search: '?modified=bogus', hash: '' })).toEqual({ kind: 'browse', path: '/content/dam' });
    expect(assetsRoute({ pathname: '/assets.html/content/dam', search: '?fulltext=', hash: '' })).toEqual({ kind: 'browse', path: '/content/dam', search: {} });
  });

  it('searches below the folder and narrows with the filters', async () => {
    const dam = demo().asset('/content/dam/wknd/tall.jpg', { width: 400, height: 900 }, { 'dc:title': 'Tall surf' });
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await screen.findByRole('gridcell', { name: /Surf in Bali/ });
    const box = screen.getByRole('searchbox', { name: 'Search' });
    fireEvent.change(box, { target: { value: 'surf' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(dam.calls).toContain('search /content/dam/wknd {"fulltext":"surf"}'));
    expect(window.location.search).toBe('?fulltext=surf');
    expect(await screen.findByRole('gridcell', { name: /Tall surf/ })).toBeTruthy();
    expect(screen.queryByRole('gridcell', { name: /^Ski/ })).toBeNull();

    const rail = screen.getByRole('complementary', { name: 'Filters' });
    fireEvent.click(within(rail).getByRole('radio', { name: 'Portrait' }));
    await waitFor(() => expect(dam.calls).toContain('search /content/dam/wknd {"fulltext":"surf","orientation":"portrait"}'));
    await waitFor(() => expect(screen.queryByRole('gridcell', { name: /Surf in Bali/ })).toBeNull());
    expect(screen.getByRole('gridcell', { name: /Tall surf/ })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Close Search' }));
    expect(await screen.findByRole('gridcell', { name: /^Ski/ })).toBeTruthy();
    expect(window.location.search).toBe('');
  });
});

describe('duplicate detection', () => {
  it('asks before uploading a file that is in the Media already', async () => {
    const dam = demo();
    dam.sameContent.set('copy.jpg', ['/content/dam/wknd/surf.jpg']);
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    const main = (await screen.findByRole('gridcell', { name: /Surf in Bali/ })).closest('main')!;
    fireEvent.drop(main, {
      dataTransfer: { files: [new File(['x'], 'copy.jpg', { type: 'image/jpeg' }), new File(['y'], 'new.jpg', { type: 'image/jpeg' })], types: ['Files'] },
    });
    const group = await screen.findByRole('group', { name: 'Duplicate copy.jpg' });
    expect(screen.getByText(/already in the DAM: \/content\/dam\/wknd\/surf.jpg/)).toBeTruthy();
    await waitFor(() => expect(dam.calls).toContain('upload /content/dam/wknd/new.jpg'));
    expect(dam.calls).not.toContain('upload /content/dam/wknd/copy.jpg');
    fireEvent.click(within(group).getByRole('button', { name: 'Upload anyway' }));
    await waitFor(() => expect(dam.calls).toContain('upload /content/dam/wknd/copy.jpg'));
  });
});

describe('references rail', () => {
  it('lists the pages that use the file', async () => {
    const dam = demo();
    dam.refs.set(SURF, [{ path: '/content/wknd/us/en/jcr:content/root/image', property: 'fileReference', page: '/content/wknd/us/en', pageTitle: 'WKND Home', value: SURF }]);
    mount(dam, { kind: 'details', path: SURF, properties: false });
    fireEvent.click(await screen.findByRole('button', { name: 'References' }));
    const rail = await screen.findByRole('complementary', { name: 'References' });
    expect(await within(rail).findByRole('link', { name: 'WKND Home' })).toBeTruthy();
  });
});
