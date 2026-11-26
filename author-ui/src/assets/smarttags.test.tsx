import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { assetsRoute, routeUrl, type AssetsRoute } from './route';
import { percent } from './smarttags';
import { MemoryDam } from './testing/MemoryDam';
import { hasAction, pressAction } from '../testing/selection';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

const SURF = '/content/dam/wknd/surf.jpg';

function demo(): MemoryDam {
  const dam = new MemoryDam().folder('/content/dam/wknd', 'WKND Site').asset(SURF, {}, { 'dc:title': 'Surf in Bali', 'cq:tags': [] });
  dam.smart.set(SURF, {
    tags: [
      { id: 'wknd:activity/surfing', title: 'Surfing', confidence: 0.81 },
      { id: 'wknd:season/summer', title: 'Summer', confidence: 0.07 },
    ],
    keywords: ['beach', 'ocean'],
  });
  return dam;
}

function mount(dam: MemoryDam, route: AssetsRoute) {
  window.history.replaceState(null, '', routeUrl(route));
  return render(
    <Provider theme={defaultTheme}>
      <AssetsApp client={dam} initial={route} />
    </Provider>,
  );
}

describe('auto tags', () => {
  it('have their own page', () => {
    expect(routeUrl({ kind: 'smarttags' })).toBe('/mnt/overlay/dam/gui/content/smarttags.html/conf/global/settings/dam/smarttags');
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/smarttags.html', hash: '' })).toEqual({ kind: 'smarttags' });
    expect(percent(0.814)).toBe('81%');
    expect(percent(null)).toBe('');
  });

  it('show in the properties, to apply or remove', async () => {
    const dam = demo();
    mount(dam, { kind: 'details', path: SURF, properties: true });
    const group = await screen.findByRole('group', { name: 'Auto tags' });
    expect(within(group).getByText('Surfing')).toBeTruthy();
    expect(within(group).getByText('81%')).toBeTruthy();
    expect(within(group).getByText('beach')).toBeTruthy();
    fireEvent.click(within(group).getByRole('button', { name: 'Apply Surfing as a tag' }));
    await waitFor(() => expect(dam.calls).toContain(`smartTagApply ${SURF} wknd:activity/surfing`));
    await waitFor(() => expect(within(group).queryByText('Surfing')).toBeNull());
    fireEvent.click(within(group).getByRole('button', { name: 'Remove beach' }));
    await waitFor(() => expect(dam.calls).toContain(`smartTagRemove ${SURF} keyword:beach`));
    // the applied tag is in the form now: saving keeps it
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls.some((c) => c.startsWith(`metadata ${SURF}`) && c.includes('wknd:activity/surfing'))).toBe(true));
  });

  it('are set up by admin: model, tags to choose from, tagging a folder', async () => {
    const dam = demo();
    mount(dam, { kind: 'smarttags' });
    const model = await screen.findByRole('region', { name: 'Image model' });
    expect(within(model).getByText(/Not installed/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Tag now' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(model).getByRole('button', { name: 'Download and install' }));
    await waitFor(() => expect(dam.calls).toContain('smartTagsInstall'));
    expect(await within(model).findByText(/Installed/)).toBeTruthy();
    fireEvent.click(screen.getByRole('switch', { name: /Tag images when they are uploaded/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(dam.calls).toContain('smartTagsSettings {"enabled":true,"tagRoots":[],"keywords":true,"max":5,"minConfidence":0.05}'),
    );
    fireEvent.change(screen.getByRole('textbox', { name: 'Folder' }), { target: { value: '/content/dam/wknd/' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tag now' }));
    await waitFor(() => expect(dam.calls).toContain('smartTag /content/dam/wknd'));
    expect(await screen.findByText('1 file queued for Auto tags')).toBeTruthy();
  });

  it('offer the model that reads descriptions in any language, apart', async () => {
    const dam = demo();
    mount(dam, { kind: 'smarttags' });
    const search = await screen.findByRole('region', { name: 'Search in any language' });
    expect(within(search).getByText(/about 1.5 GB/)).toBeTruthy();
    fireEvent.click(within(search).getByRole('button', { name: 'Download and install' }));
    await waitFor(() => expect(dam.calls).toContain('searchModelInstall'));
    expect(dam.calls).not.toContain('smartTagsInstall');
    expect(await within(search).findByText(/Installed/)).toBeTruthy();
  });

  it('run from the folder, on what is selected, once the model is there', async () => {
    const dam = demo().folder('/content/dam/wknd/ski', 'Ski');
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /Surf in Bali/ })).getByRole('button', { name: 'Select' }));
    expect(await hasAction('Auto tags')).toBe(false);
    cleanup();
    dam.smartInstalled = true;
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /Surf in Bali/ })).getByRole('button', { name: 'Select' }));
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /Ski/ })).getByRole('button', { name: 'Select' }));
    await pressAction('Auto tags');
    await waitFor(() => expect(dam.calls.some((c) => c.startsWith('smartTag ') && c.includes(SURF) && c.includes('/content/dam/wknd/ski'))).toBe(true));
    expect(await screen.findByText('2 files queued for Auto tags')).toBeTruthy();
  });

  it('are switched on or off in the folder properties, and run from there', async () => {
    const dam = demo().folder('/content/dam/wknd/ski', 'Ski');
    dam.smartInstalled = true;
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /Ski/ })).getByRole('button', { name: 'Properties' }));
    const dialog = await screen.findByRole('dialog');
    const picker = await within(dialog).findByRole('button', { name: /Inherit \(Off\)/ });
    fireEvent.click(picker);
    fireEvent.click(await screen.findByRole('option', { name: 'On' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tag this folder now' }));
    await waitFor(() => expect(dam.calls).toContain('smartTag /content/dam/wknd/ski'));
    expect(await within(dialog).findByText('1 image queued for Auto tags')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain('folderSmartTags /content/dam/wknd/ski on'));
  });
});
