import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DamHttpClient } from './api';
import { changedFields } from './AssetDetails';
import { AssetsApp } from './AssetsApp';
import { nodeName } from './dialogs';
import { formatBytes, isNew, typeLabel } from './format';
import { expiryLabel } from './views';
import { assetsRoute, routeUrl } from './route';
import { MemoryDam } from './testing/MemoryDam';
import { pressAction } from '../testing/selection';

afterEach(cleanup);

// jsdom has no CSS.escape; React Spectrum menus and tabs use it to find their items.
if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

function demo(): MemoryDam {
  return new MemoryDam()
    .folder('/content/dam/wknd', 'WKND Site')
    .folder('/content/dam/wknd/adventures', 'Adventures')
    .asset('/content/dam/wknd/surf.jpg', { size: 443802, width: 1000, height: 667 }, { 'dc:title': 'Surf in Bali', 'dc:subject': ['surf'] })
    .asset('/content/dam/wknd/guide.pdf', { mimeType: 'application/pdf', thumbnail: null, width: null, height: null });
}

function mount(dam: MemoryDam, path = '/content/dam/wknd', kind: 'browse' | 'details' = 'browse', properties = false) {
  window.history.replaceState(null, '', routeUrl(kind === 'browse' ? { kind, path } : { kind, path, properties }));
  return render(
    <Provider theme={defaultTheme}>
      <AssetsApp client={dam} initial={kind === 'browse' ? { kind, path } : { kind, path, properties }} />
    </Provider>,
  );
}

const card = (name: RegExp) => screen.getByRole('gridcell', { name });

describe('routes', () => {
  it('reads AEM asset URLs', () => {
    expect(assetsRoute({ pathname: '/assets.html/content/dam/wknd', hash: '' })).toEqual({ kind: 'browse', path: '/content/dam/wknd' });
    expect(assetsRoute({ pathname: '/assets.html', hash: '' })).toEqual({ kind: 'browse', path: '/content/dam' });
    expect(assetsRoute({ pathname: '/assetdetails.html/content/dam/a%20b.jpg', hash: '#properties' })).toEqual({
      kind: 'details',
      path: '/content/dam/a b.jpg',
      properties: true,
    });
    expect(assetsRoute({ pathname: '/assets.html/apps/x', hash: '' })).toEqual({ kind: 'browse', path: '/content/dam' });
    expect(assetsRoute({ pathname: '/editor.html/content/x.html', hash: '' })).toBeNull();
    expect(routeUrl({ kind: 'details', path: '/content/dam/a b.jpg', properties: true })).toBe('/assetdetails.html/content/dam/a%20b.jpg#properties');
  });
});

describe('format', () => {
  it('formats like the AEM cards', () => {
    // Sizes of WKND assets as AEM shows them.
    expect(formatBytes(443802)).toBe('433.4 KB');
    expect(formatBytes(496684)).toBe('485 KB');
    expect(formatBytes(2_400_000)).toBe('2.3 MB');
    expect(formatBytes(900)).toBe('900 B');
    expect(typeLabel({ type: 'asset', path: '', name: '', title: '', mimeType: 'image/png' })).toBe('IMAGE');
    expect(typeLabel({ type: 'asset', path: '', name: '', title: '', mimeType: 'application/pdf' })).toBe('DOCUMENT');
    expect(isNew({ type: 'asset', path: '', name: '', title: '', created: new Date(Date.now() - 3600_000).toISOString() })).toBe(true);
    expect(nodeName('Ski Touring – Mont Blanc 2026')).toBe('ski-touring-mont-blanc-2026');
  });

  it('sends only changed metadata fields', () => {
    const base = { 'dc:title': 'A', 'dc:description': '', 'dc:language': '', 'dc:rights': '', 'dc:subject': ['x'], 'dc:creator': [], 'dc:contributor': [], 'cq:tags': [], 'prism:expirationDate': '' };
    expect(changedFields(base, { ...base, 'dc:title': 'B', 'dc:subject': [] })).toEqual({ 'dc:title': 'B', 'dc:subject': [] });
    expect(changedFields(base, { ...base, 'prism:expirationDate': '2026-12-31' })).toEqual({ 'prism:expirationDate': '2026-12-31' });
  });

  it('mark files that expired or expire within a week', () => {
    const now = Date.parse('2026-11-23T12:00:00Z');
    expect(expiryLabel({ expires: '2026-11-01T23:59:59Z', expired: true }, now)).toBe('Expired');
    expect(expiryLabel({ expires: '2026-11-23T23:59:59Z', expired: false }, now)).toBe('Expires today');
    expect(expiryLabel({ expires: '2026-11-24T23:59:59Z', expired: false }, now)).toBe('Expires in 1 day');
    expect(expiryLabel({ expires: '2026-11-27T23:59:59Z', expired: false }, now)).toBe('Expires in 4 days');
    expect(expiryLabel({ expires: '2027-01-01T00:00:00Z', expired: false }, now)).toBeNull();
    expect(expiryLabel({}, now)).toBeNull();
  });
});

describe('browser', () => {
  beforeEach(() => {
    try {
      window.localStorage.clear();
    } catch {
      // ignore
    }
  });

  it('shows folders and files as cards', async () => {
    mount(demo());
    expect(await screen.findByRole('gridcell', { name: /Adventures, folder/ })).toBeTruthy();
    const surf = card(/Surf in Bali, image/);
    expect(within(surf).getByText('SURF.JPG')).toBeTruthy();
    expect(within(surf).getByText('433.4 KB')).toBeTruthy();
    expect(within(surf).getByText('1000 x 667')).toBeTruthy();
    expect(within(card(/guide.pdf, document/)).getByText('DOCUMENT')).toBeTruthy();
    expect(screen.getByText('3 of 3')).toBeTruthy();
  });

  it('shows content fragments as text and opens them in the content fragment editor', async () => {
    const dam = demo().asset('/content/dam/wknd/yosemite', { contentFragment: true, thumbnail: null, mimeType: null, description: 'Granite cliffs' }, { 'dc:title': 'Yosemite' });
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign, pathname: window.location.pathname, hash: '' });
    try {
      mount(dam);
      const cf = await screen.findByRole('gridcell', { name: /Yosemite, content fragment/ });
      expect(within(cf).getByText('Granite cliffs')).toBeTruthy();
      fireEvent.click(cf);
      expect(assign).toHaveBeenCalledWith('/editor.html/content/dam/wknd/yosemite');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('opens folders and files on click', async () => {
    const dam = demo();
    mount(dam);
    fireEvent.click(await screen.findByRole('gridcell', { name: /Adventures, folder/ }));
    await waitFor(() => expect(window.location.pathname).toBe('/assets.html/content/dam/wknd/adventures'));
    expect(await screen.findByText('This folder is empty')).toBeTruthy();
    act(() => window.history.back());
  });

  it('selection replaces the bar with actions; Escape clears it', async () => {
    mount(demo());
    const surf = await screen.findByRole('gridcell', { name: /Surf in Bali/ });
    fireEvent.click(within(surf).getByRole('button', { name: 'Select' }));
    const bar = await screen.findByRole('toolbar', { name: 'Selection actions' });
    expect(within(bar).getByRole('button', { name: 'Properties' })).toBeTruthy();
    expect(within(bar).getByRole('button', { name: 'Download' })).toBeTruthy();
    expect(within(bar).getByText(/1 selected/)).toBeTruthy();
    // In selection mode a click selects instead of opening.
    fireEvent.click(card(/guide.pdf/));
    expect(within(bar).getByText(/2 selected/)).toBeTruthy();
    fireEvent.keyDown(window, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('toolbar', { name: 'Selection actions' })).toBeNull());
  });

  it('deletes after confirmation', async () => {
    const dam = demo();
    mount(dam);
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /guide.pdf/ })).getByRole('button', { name: 'Select' }));
    await pressAction('Delete');
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(dam.calls).toContain('delete /content/dam/wknd/guide.pdf'));
    await waitFor(() => expect(screen.queryByRole('gridcell', { name: /guide.pdf/ })).toBeNull());
  });

  it('creates folders with a generated name', async () => {
    const dam = demo();
    mount(dam);
    await screen.findByRole('gridcell', { name: /Adventures/ });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Folder' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Title/ }), { target: { value: 'Summer Ski!' } });
    expect(within(dialog).getByText('/content/dam/wknd/summer-ski')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(dam.calls).toContain('createFolder /content/dam/wknd/summer-ski "Summer Ski!"'));
    expect(await screen.findByRole('gridcell', { name: /Summer Ski!, folder/ })).toBeTruthy();
  });

  it('copies, then pastes into another folder', async () => {
    const dam = demo();
    mount(dam);
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /Surf in Bali/ })).getByRole('button', { name: 'Select' }));
    fireEvent.click(within(screen.getByRole('toolbar', { name: 'Selection actions' })).getByRole('button', { name: 'Copy' }));
    fireEvent.click(await screen.findByRole('gridcell', { name: /Adventures, folder/ }));
    fireEvent.click(await screen.findByRole('button', { name: /Paste \(1\)/ }));
    await waitFor(() => expect(dam.calls).toContain('copy /content/dam/wknd/surf.jpg -> /content/dam/wknd/adventures'));
    expect(await screen.findByRole('gridcell', { name: /Surf in Bali/ })).toBeTruthy();
  });

  it('uploads dropped files and shows them processing', async () => {
    const dam = demo();
    mount(dam);
    const main = (await screen.findByRole('gridcell', { name: /Adventures/ })).closest('main')!;
    const file = new File(['x'], 'new.jpg', { type: 'image/jpeg' });
    fireEvent.drop(main, { dataTransfer: { files: [file], types: ['Files'] } });
    await waitFor(() => expect(dam.calls).toContain('upload /content/dam/wknd/new.jpg'));
    const created = await screen.findByRole('gridcell', { name: /new.jpg/ });
    expect(within(created).getByText('Processing')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Uploads' })).toBeTruthy();
  });

  it('sorts on the server', async () => {
    const dam = demo();
    mount(dam);
    await screen.findByRole('gridcell', { name: /Adventures/ });
    fireEvent.click(screen.getByRole('button', { name: /Sort by/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Title' }));
    await waitFor(() => expect(dam.calls).toContain('list /content/dam/wknd title asc'));
  });
});

describe('details and properties', () => {
  it('shows the file with prev/next among its folder', async () => {
    mount(demo(), '/content/dam/wknd/surf.jpg', 'details');
    expect(await screen.findByRole('heading', { name: 'Surf in Bali' })).toBeTruthy();
    expect(await screen.findByText(/of 2 assets/)).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Surf in Bali' })).toBeTruthy();
  });

  it('saves edited Basic metadata', async () => {
    const dam = demo();
    mount(dam, '/content/dam/wknd/surf.jpg', 'details', true);
    const title = await screen.findByRole('textbox', { name: 'Title' });
    fireEvent.change(title, { target: { value: 'Surf at Uluwatu' } });
    const keywords = screen.getByRole('textbox', { name: 'Keywords' });
    fireEvent.change(keywords, { target: { value: 'ocean' } });
    fireEvent.keyDown(keywords, { key: 'Enter' });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(dam.calls).toContain('metadata /content/dam/wknd/surf.jpg {"dc:title":"Surf at Uluwatu","dc:subject":["surf","ocean"]}'),
    );
  });
});

describe('http client', () => {
  it('sends the CSRF header and encodes multi-valued metadata', async () => {
    const fetchMock = vi.fn(async () => new Response('{"changed":[]}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    try {
      await new DamHttpClient().saveMetadata('/content/dam/a.jpg', { 'dc:title': 'T', 'dc:subject': ['a', 'b'], 'dc:creator': [] });
      const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
      expect(url).toBe('/bin/studio/dam.json');
      expect((init.headers as Record<string, string>)['X-Studio-CSRF']).toBe('1');
      expect(String(init.body)).toBe('op=metadata&path=%2Fcontent%2Fdam%2Fa.jpg&dc%3Atitle=T&dc%3Asubject=a&dc%3Asubject=b&dc%3Acreator%40Delete=true&_charset_=utf-8');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('reports server errors with their message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"error":"\\"x\\" already exists"}', { status: 409 })));
    try {
      await expect(new DamHttpClient().createFolder('/content/dam', 'x', 'x')).rejects.toMatchObject({ status: 409, message: '"x" already exists' });
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
