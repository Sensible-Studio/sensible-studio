import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { presetParams, presetUrl } from './dynamicmedia';
import { dmBase, EMPTY_FORM, formOf, presetProblems } from './presetEditor';
import { assetsRoute, routeUrl, type AssetsRoute } from './route';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

const SURF = '/content/dam/wknd/surf.jpg';

function demo(): MemoryDam {
  const dam = new MemoryDam().folder('/content/dam/wknd', 'WKND Site').asset(SURF, { mimeType: 'image/jpeg', width: 2000, height: 1000 }, { 'dc:title': 'Surf in Bali' });
  dam.imagePresetList = [{ name: 'banner', title: 'Banner', params: { wid: '1600', hei: '600', fit: 'crop' }, query: 'wid=1600&hei=600&fit=crop' }];
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

describe('dynamic media', () => {
  it('has a presets page and builds URLs', () => {
    expect(routeUrl({ kind: 'imagepresets' })).toBe('/mnt/overlay/dam/gui/content/s7dam/presets/imagepresets.html/conf/global/settings/dam/dm/presets');
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/s7dam/presets/imagepresets.html', hash: '' })).toEqual({ kind: 'imagepresets' });
    expect(presetUrl('/is/image/wknd/surf', 'banner', { fmt: 'webp' })).toBe('/is/image/wknd/surf?$banner$&fmt=webp');
    expect(presetParams({ wid: 800, hei: null, fmt: 'png', qlt: 80, fit: 'fit', upscale: false, sharpen: true, usm: '', bgc: '#fff000' })).toEqual({
      wid: '800',
      fmt: 'png',
      op_sharpen: '1',
      bgc: 'fff000',
    });
    expect(presetParams({ wid: 300, hei: 300, fmt: 'webp', qlt: 70, fit: 'crop', upscale: true, sharpen: true, usm: '1.5,2,0', bgc: '' })).toEqual({
      wid: '300',
      hei: '300',
      fmt: 'webp',
      qlt: '70',
      fit: 'crop,1',
      op_usm: '1.5,2,0',
    });
    expect(presetParams({ wid: 600, rotate: 90, flip: 'h', blur: 2.5, brightness: -10, contrast: 20 })).toEqual({
      wid: '600',
      fmt: 'jpeg',
      qlt: '80',
      rotate: '90',
      flip: 'h',
      op_blur: '2.5',
      op_brightness: '-10',
      op_contrast: '20',
    });
    expect(formOf({ name: 'x', title: 'x', params: { wid: '600', rotate: '90', op_blur: '2.5', fit: 'crop,1' }, query: '' } as never)).toMatchObject({ wid: 600, rotate: 90, blur: 2.5, fit: 'crop', upscale: true });
    expect(presetParams({ tone: 'sepia', saturation: -20, overlayColor: '#102030', overlayOpacity: 40, gradientTowards: 'b', gradientColor: '000000', gradientFrom: 0, gradientTo: 70, vignette: 30, radius: 24, borderWidth: 4, borderColor: 'FF0000' })).toEqual({
      fmt: 'jpeg',
      qlt: '80',
      op_saturation: '-20',
      op_sepia: '1',
      op_overlay: '102030,40',
      op_gradient: 'b,000000,0,70',
      op_vignette: '30',
      op_radius: '24',
      op_border: '4,ff0000',
    });
    expect(formOf({ name: 'x', title: 'x', params: { op_gradient: 'b,000000,0,70', op_border: '4,ff0000', op_grayscale: '1', op_overlay: '102030,40' }, query: '' } as never)).toMatchObject({
      gradientTowards: 'b', gradientTo: 70, borderWidth: 4, borderColor: 'ff0000', tone: 'grayscale', overlayOpacity: 40,
    });
    expect(presetProblems({ ...EMPTY_FORM, borderWidth: 2, borderColor: 'red' }, 'a', [], true)).toEqual(['Border colour is RRGGBB.']);
    expect(dmBase('/content/dam/wknd/surf.jpg')).toBe('/is/image/wknd/surf');
    expect(presetProblems({ ...EMPTY_FORM, fit: 'crop', wid: 100, hei: null }, 'a', [], true)).toEqual(['fit=crop needs both a width and a height.']);
  });

  it('lets admin create and delete presets', async () => {
    const dam = demo();
    mount(dam, { kind: 'imagepresets' });
    expect(await screen.findByText('wid=1600&hei=600&fit=crop')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('form', { name: /Image Preset/ });
    expect(dam.calls.some((c) => c.startsWith('search /content/dam') && c.includes('"image"'))).toBe(true);
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Title' }), { target: { value: 'Thumb Nail' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Width' }), { target: { value: '200' } });
    fireEvent.blur(within(dialog).getByRole('textbox', { name: 'Width' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls.some((c) => c.startsWith('saveImagePreset Thumb-Nail ') && c.includes('"wid":"200"'))).toBe(true));
    fireEvent.click(screen.getByRole('button', { name: /^Close/ }));
    expect(await screen.findByText('$Thumb-Nail$')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Delete Banner' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(dam.calls).toContain('deleteImagePreset banner'));
    cleanup();
    dam.admin = false;
    mount(dam, { kind: 'imagepresets' });
    expect(await screen.findByText('$Thumb-Nail$')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Create' })).toBeNull();
  });

  it('lists the presets of an image in the Renditions rail, with their URLs', async () => {
    const dam = demo();
    mount(dam, { kind: 'details', path: SURF, properties: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Renditions' }));
    const rail = await screen.findByRole('complementary', { name: 'Renditions' });
    expect(await within(rail).findByText('Banner')).toBeTruthy();
    fireEvent.click(within(rail).getByText('Banner'));
    await waitFor(() => expect((document.querySelector('.studio-assets__stage-img') as HTMLImageElement).getAttribute('src')).toBe('/is/image/wknd/surf?$banner$'));
    expect(within(rail).getByText('/is/image/wknd/surf')).toBeTruthy();
    expect(dam.calls).toContain(`imagePresets ${SURF}`);
  });
});
