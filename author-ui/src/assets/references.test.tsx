import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { referrers } from './references';
import { routeUrl } from './route';
import { MemoryDam } from './testing/MemoryDam';
import { pressAction } from '../testing/selection';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

const SURF = '/content/dam/wknd/surf.jpg';

function demo(): MemoryDam {
  const dam = new MemoryDam()
    .folder('/content/dam/wknd', 'WKND Site')
    .folder('/content/dam/wknd/adventures', 'Adventures')
    .asset(SURF, { width: 1000, height: 667 }, { 'dc:title': 'Surf in Bali' });
  dam.refs.set(SURF, [
    { path: '/content/wknd/us/en/jcr:content/root/teaser', property: 'fileReference', page: '/content/wknd/us/en', pageTitle: 'WKND Home', value: SURF },
    { path: '/content/wknd/us/en/jcr:content/root/image', property: 'fileReference', page: '/content/wknd/us/en', pageTitle: 'WKND Home', value: SURF },
    { path: '/content/wknd/us/de/jcr:content/root/image', property: 'fileReference', page: '/content/wknd/us/de', pageTitle: 'Deutsch', value: SURF },
    { path: '/content/dam/wknd/jcr:content/folderThumbnail/jcr:content', property: 'dam:folderThumbnailPaths', value: SURF },
  ]);
  return dam;
}

function mount(dam: MemoryDam) {
  window.history.replaceState(null, '', routeUrl({ kind: 'browse', path: '/content/dam/wknd' }));
  return render(
    <Provider theme={defaultTheme}>
      <AssetsApp client={dam} initial={{ kind: 'browse', path: '/content/dam/wknd' }} />
    </Provider>,
  );
}

async function select(name: RegExp, action: string) {
  fireEvent.click(within(await screen.findByRole('gridcell', { name })).getByRole('button', { name: 'Select' }));
  await pressAction(action);
}

describe('references', () => {
  it('groups by page and ignores folder thumbnails and self-references', () => {
    const dam = demo();
    const list = referrers(dam.refs.get(SURF)!, [SURF]);
    expect(list.map((r) => [r.key, r.resources.length])).toEqual([
      ['/content/wknd/us/de', 1],
      ['/content/wknd/us/en', 2],
    ]);
    expect(referrers(dam.refs.get(SURF)!, ['/content/wknd/us'])).toEqual([]);
  });

  it('warns before deleting a referenced file', async () => {
    mount(demo());
    await select(/Surf in Bali/, 'Delete');
    const dialog = await screen.findByRole('alertdialog');
    expect(await within(dialog).findByText('Referenced by 2 pages')).toBeTruthy();
    expect(within(dialog).getByRole('link', { name: 'WKND Home' }).getAttribute('href')).toBe('/editor.html/content/wknd/us/en.html');
  });

  it('adjusts the ticked pages when moving', async () => {
    const dam = demo();
    mount(dam);
    await select(/Surf in Bali/, 'Move');
    const dialog = await screen.findByRole('dialog');
    const deutsch = await within(dialog).findByRole('checkbox', { name: 'Adjust Deutsch' });
    expect((deutsch as HTMLInputElement).checked).toBe(true);
    fireEvent.click(deutsch);
    fireEvent.click(within(dialog).getByRole('button', { name: /Adventures/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move' }));
    await waitFor(() => expect(dam.calls).toContain(`move ${SURF} -> /content/dam/wknd/adventures`));
    expect(dam.adjusted).toEqual([['/content/wknd/us/en/jcr:content/root/teaser', '/content/wknd/us/en/jcr:content/root/image']]);
    expect(await screen.findByText(/1 item moved, 2 references adjusted/)).toBeTruthy();
  });
});
