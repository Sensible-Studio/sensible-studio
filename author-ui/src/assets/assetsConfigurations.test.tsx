import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DamItem } from './api';
import { DEFAULT_ASSETS_CONFIG, drmPreviewUrl, needsLicense, mimeAllowed, resetAssetsConfig, validityLabel, type AssetsConfigurations } from './assetsConfig';
import { AssetsConfigurationsPage, cardSummary } from './assetsConfigurations';
import { DownloadDialog } from './bulk';
import { assetsRoute, routeUrl } from './route';
import { ShareLinkDialog, validityChoices } from './share';
import { MemoryDam } from './testing/MemoryDam';

const config = (patch: Partial<AssetsConfigurations> = {}): AssetsConfigurations => ({ ...structuredClone(DEFAULT_ASSETS_CONFIG), admin: true, ...patch });

type Call = { url: string; body?: URLSearchParams };
let calls: Call[] = [];
let serverConfig: AssetsConfigurations;
let licenses: unknown = { enabled: false, usageTermsUrl: '', licenses: [] };

beforeEach(() => {
  calls = [];
  serverConfig = config();
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body instanceof URLSearchParams ? init.body : undefined;
    calls.push({ url, body });
    if (url.includes('op=licenses')) return new Response(JSON.stringify(licenses));
    if (body?.get('op') === 'saveAssetsConfiguration') {
      const c = JSON.parse(body.get('config') ?? '{}');
      if (body.get('name') === 'uploadrestrictions') serverConfig = { ...serverConfig, uploadRestrictions: c };
      if (body.get('name') === 'omnisearch') serverConfig = { ...serverConfig, omnisearch: c };
      return new Response(JSON.stringify(serverConfig));
    }
    return new Response(JSON.stringify(serverConfig));
  });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  resetAssetsConfig();
});

const wrap = (ui: React.ReactNode) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

describe('Assets Configurations', () => {
  it('is at AEM’s addresses', () => {
    expect(routeUrl({ kind: 'assetsconfigurations' })).toBe('/mnt/overlay/dam/gui/content/assetsconfigurations/list.html');
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/assetsconfigurations/list.html', hash: '' })).toEqual({ kind: 'assetsconfigurations' });
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/assetsconfigurations/editors/linkshare.html', hash: '' })).toEqual({
      kind: 'assetsconfigurations',
      editor: 'linkshare',
    });
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/assetsconfigurations/editors/nope.html', hash: '' })).toEqual({ kind: 'assetsconfigurations' });
  });

  it('says what each setting is now, and reads MIME families and validities', () => {
    const c = config({ uploadRestrictions: { allowedMimeTypes: ['image/*'] } });
    expect(cardSummary('uploadrestrictions', c)).toBe('Only image/*');
    expect(cardSummary('digitalrightsmanagement', c)).toBe('Off');
    expect(cardSummary('assetsomnisearch', c)).toBe('Counts up to 1000 · searches the current folder');
    expect(cardSummary('linkshare', c)).toBe('Originals · valid 7 days');
    expect(mimeAllowed([], 'video/mp4')).toBe(true);
    expect(mimeAllowed(['image/*', 'application/pdf'], 'image/png')).toBe(true);
    expect(mimeAllowed(['image/*'], 'video/mp4')).toBe(false);
    expect(validityLabel(1, 'years')).toBe('1 year');
    expect(validityChoices({ validityAmount: 12, validityUnit: 'hours' }).map((c) => c.key)).toEqual(['1 days', '7 days', '30 days', '90 days', '12 hours']);
    expect(validityChoices({ validityAmount: 7, validityUnit: 'days' })).toHaveLength(4);
  });

  it('lists the six settings and saves one', async () => {
    const dam = new MemoryDam();
    const navigate = vi.fn();
    const { unmount } = wrap(<AssetsConfigurationsPage client={dam} navigate={navigate} />);
    const list = await screen.findByRole('list', { name: 'Assets Configurations' });
    expect(within(list).getAllByRole('link').map((a) => a.querySelector('strong')?.textContent)).toEqual([
      'Upload Restrictions',
      'DRM Configuration',
      'System Watermarking Profile',
      'Assets Omnisearch Configuration',
      'Asset Duplication Detector',
      'Link Share',
    ]);
    fireEvent.click(within(list).getAllByRole('link')[0]);
    expect(navigate).toHaveBeenCalledWith({ kind: 'assetsconfigurations', editor: 'uploadrestrictions' });
    unmount();

    wrap(<AssetsConfigurationsPage client={dam} navigate={navigate} editor="uploadrestrictions" />);
    const form = await screen.findByRole('form', { name: 'Upload Restrictions' });
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save.hasAttribute('disabled')).toBe(true);
    fireEvent.change(within(form).getByRole('textbox', { name: 'Allowed MIME Types' }), { target: { value: 'jpeg' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add' }));
    expect(within(form).getByText(/A MIME type, as image\/png/)).toBeTruthy();
    fireEvent.click(within(form).getByRole('button', { name: 'image/*' }));
    fireEvent.click(within(form).getByRole('button', { name: 'application/pdf' }));
    fireEvent.click(save);
    await waitFor(() => expect(calls.some((c) => c.body?.get('name') === 'uploadrestrictions')).toBe(true));
    const sent = calls.find((c) => c.body?.get('name') === 'uploadrestrictions')!;
    expect(JSON.parse(sent.body!.get('config')!)).toEqual({ allowedMimeTypes: ['image/*', 'application/pdf'] });
    expect(await screen.findByText('Saved.')).toBeTruthy();
  });

  it('shows the values without Save to who is not an administrator', async () => {
    serverConfig = config({ admin: false });
    wrap(<AssetsConfigurationsPage client={new MemoryDam()} navigate={vi.fn()} editor="assetsomnisearch" />);
    expect(await screen.findByText('Only an administrator changes these settings.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
  });
});

const ITEM = (path: string, title: string): DamItem => ({ path, title, name: path.slice(path.lastIndexOf('/') + 1), type: 'asset' }) as unknown as DamItem;

describe('DRM previews', () => {
  it('asks for the marked preview of a licensed file, and says in the form whether a watermark is set', async () => {
    expect(drmPreviewUrl('/content/dam/a b.jpg', '2026-11-26')).toBe('/bin/studio/dam/drmpreview.img?path=%2Fcontent%2Fdam%2Fa+b.jpg&_ck=2026-11-26');
    expect(needsLicense(config({ drm: { enabled: true, usageTermsUrl: '' } }), { licensed: true })).toBe(true);
    expect(needsLicense(config({ drm: { enabled: true, usageTermsUrl: '' } }), {})).toBe(false);
    expect(needsLicense(config(), { licensed: true })).toBe(false);
    serverConfig = config({ drm: { enabled: true, usageTermsUrl: '' } });
    wrap(<AssetsConfigurationsPage client={new MemoryDam()} navigate={vi.fn()} editor="digitalrightsmanagement" />);
    expect(await screen.findByText(/No watermark is set: licensed pictures are previewed as they are/)).toBeTruthy();
  });
});

describe('DRM in the Download dialog', () => {
  it('asks to agree to each license and sends what was agreed to', async () => {
    licenses = {
      enabled: true,
      usageTermsUrl: 'https://example.com/terms',
      licenses: [
        { path: '/content/dam/a.jpg', title: 'A', text: 'Only on the web.' },
        { path: '/content/dam/b.jpg', title: 'B', url: 'https://example.com/license-b' },
      ],
    };
    wrap(<DownloadDialog items={[ITEM('/content/dam/a.jpg', 'A'), ITEM('/content/dam/b.jpg', 'B')]} close={vi.fn()} />);
    const section = await screen.findByRole('region', { name: 'Licenses' });
    expect(within(section).getByText('Only on the web.')).toBeTruthy();
    expect(within(section).getByRole('link', { name: 'Usage terms' }).getAttribute('href')).toBe('https://example.com/terms');
    const download = screen.getByRole('button', { name: 'Download' });
    expect(download.hasAttribute('disabled')).toBe(true);
    fireEvent.click(within(section).getAllByRole('checkbox', { name: 'Agree' })[0]);
    expect(download.hasAttribute('disabled')).toBe(false);
    fireEvent.click(within(section).getByRole('checkbox', { name: 'Agree to all' }));
    expect(within(section).getAllByRole('checkbox', { name: 'Agree' }).every((c) => (c as HTMLInputElement).checked)).toBe(true);
  });

  it('shows nothing when DRM is off', async () => {
    licenses = { enabled: false, usageTermsUrl: '', licenses: [] };
    wrap(<DownloadDialog items={[ITEM('/content/dam/a.jpg', 'A')]} close={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Download' }).hasAttribute('disabled')).toBe(false));
    expect(screen.queryByRole('region', { name: 'Licenses' })).toBeNull();
  });
});

describe('Link Share settings in the Share Link dialog', () => {
  it('starts with the settings and keeps read-only and hidden choices', async () => {
    resetAssetsConfig(
      config({
        linkShare: { ...DEFAULT_ASSETS_CONFIG.linkShare, includeOriginals: false, originalsMode: 'hidden', includeRenditions: true, renditionsMode: 'readonly', validityAmount: 12, validityUnit: 'hours' },
      }),
    );
    const dam = new MemoryDam();
    wrap(<ShareLinkDialog client={dam} items={[ITEM('/content/dam/a.jpg', 'A')]} close={vi.fn()} />);
    const renditions = (await screen.findByRole('checkbox', { name: 'Include renditions' })) as HTMLInputElement;
    await waitFor(() => expect(renditions.checked).toBe(true));
    expect(screen.queryByRole('checkbox', { name: 'Include originals' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Create Link' }));
    await waitFor(() => expect(dam.calls).toContain('share /content/dam/a.jpg 12 hours renditions no-originals'));
  });
});
