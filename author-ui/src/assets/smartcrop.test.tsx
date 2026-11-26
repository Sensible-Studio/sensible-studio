import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { assetsRoute, routeUrl, type AssetsRoute } from './route';
import { clampCrop, cropHeight, profileProblems } from './smartcrop';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

// jsdom has no PointerEvent: a MouseEvent carries the coordinates the editor reads
if (!(globalThis as { PointerEvent?: unknown }).PointerEvent) {
  (globalThis as unknown as { PointerEvent: typeof MouseEvent }).PointerEvent = MouseEvent;
}

const SURF = '/content/dam/wknd/surf.jpg';
const PROFILE = '/conf/global/settings/dam/dm/imageprofiles/web';

function demo(): MemoryDam {
  const dam = new MemoryDam()
    .folder('/content/dam/wknd', 'WKND Site')
    .asset(SURF, { mimeType: 'image/jpeg', width: 1000, height: 500, web: '/web.jpg' }, { 'dc:title': 'Surf in Bali' });
  dam.imageProfileList = [{ path: PROFILE, name: 'web', title: 'Web', ratios: [{ name: 'Square', width: 1, height: 1 }] }];
  dam.smartCropStore.set(SURF, [
    { name: 'Square', left: 0.25, top: 0, width: 0.5, height: 1, imageWidth: 1000, imageHeight: 500, ratioWidth: 1, ratioHeight: 1, manual: false, url: '/is/image/wknd/surf:Square' },
  ]);
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

describe('focus crop', () => {
  it('keeps the proportions and stays inside the image', () => {
    const square = { imageWidth: 1000, imageHeight: 500, ratioWidth: 1, ratioHeight: 1 };
    expect(cropHeight(square, 0.5)).toBe(1);
    expect(clampCrop(square, 0.9, 0.5, 0.5)).toEqual({ left: 0.5, top: 0, width: 0.5, height: 1 });
    expect(clampCrop(square, 0, 0, 1)).toEqual({ left: 0, top: 0, width: 0.5, height: 1 });
    expect(profileProblems('', [])).toEqual(['The profile needs a title or name.', 'Add at least one crop.']);
    expect(profileProblems('web', [{ name: 'a', width: 1, height: 1 }, { name: 'a', width: 0, height: 1 }])).toHaveLength(2);
    expect(routeUrl({ kind: 'imageprofiles' })).toBe('/mnt/overlay/dam/gui/content/imageprofiles.html/conf/global/settings/dam/dm/imageprofiles');
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/imageprofiles.html', hash: '' })).toEqual({ kind: 'imageprofiles' });
  });

  it('has a profiles page for admin', async () => {
    const dam = demo();
    mount(dam, { kind: 'imageprofiles' });
    expect(await screen.findByText('Square (1:1)')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Title' }), { target: { value: 'Social' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Name' }), { target: { value: 'Story' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Height' }), { target: { value: '2' } });
    fireEvent.blur(within(dialog).getByRole('textbox', { name: 'Height' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain('saveImageProfile social Story:1x2'));
    expect(await screen.findByText('Story (1:2)')).toBeTruthy();
  });

  it('is chosen in the folder properties, and queues the images', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam' });
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /WKND Site/ })).getByRole('button', { name: 'Properties' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(await within(dialog).findByRole('button', { name: /Image Profile/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Web' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain(`folderImageProfile /content/dam/wknd ${PROFILE}`));
  });

  it('lists the crops of an image and lets an author move one', async () => {
    const dam = demo();
    mount(dam, { kind: 'details', path: SURF, properties: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Renditions' }));
    const rail = await screen.findByRole('complementary', { name: 'Renditions' });
    expect(await within(rail).findByText('Square')).toBeTruthy();
    fireEvent.click(within(rail).getByText('Square'));
    await waitFor(() => expect((document.querySelector('.studio-assets__stage-img') as HTMLImageElement).getAttribute('src')).toBe('/is/image/wknd/surf:Square'));
    fireEvent.click(within(rail).getByRole('button', { name: 'Edit focus crops' }));
    const dialog = await screen.findByRole('dialog', { name: 'Focus crop' });
    const img = within(dialog).getByRole('presentation', { hidden: true }) as HTMLImageElement;
    Object.defineProperty(img, 'clientWidth', { value: 400 });
    Object.defineProperty(img, 'clientHeight', { value: 200 });
    fireEvent.load(img);
    const box = await within(dialog).findByRole('img', { name: 'Square crop' });
    expect(box.style.left).toBe('100px');
    expect((within(dialog).getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.pointerDown(box, { clientX: 100, clientY: 50, pointerId: 1 });
    fireEvent.pointerMove(dialog.querySelector('.studio-smartcrop__stage')!, { clientX: 0, clientY: 50, pointerId: 1 });
    fireEvent.pointerUp(dialog.querySelector('.studio-smartcrop__stage')!);
    await waitFor(() => expect(box.style.left).toBe('0px'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain(`smartCrop ${SURF} Square 0.000,0.000,0.500`));
    expect(await within(rail).findByText('Square · manual')).toBeTruthy();
  });
});
