import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { routeUrl, type AssetsRoute } from './route';
import { MemoryDam } from './testing/MemoryDam';
import { hasAction, pressAction } from '../testing/selection';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

const SURF = '/content/dam/wknd/surf.jpg';
const SKI = '/content/dam/wknd/ski.jpg';
const ZIP = '/content/dam/wknd/shoot.zip';

function demo(): MemoryDam {
  return new MemoryDam()
    .folder('/content/dam/wknd', 'WKND Site')
    .asset(SURF, {}, { 'dc:title': 'Surf in Bali' })
    .asset(SKI, {}, { 'dc:title': 'Ski' })
    .asset(ZIP, { mimeType: 'application/zip', thumbnail: null, width: null, height: null });
}

function mount(dam: MemoryDam, route: AssetsRoute) {
  window.history.replaceState(null, '', routeUrl(route));
  return render(
    <Provider theme={defaultTheme}>
      <AssetsApp client={dam} initial={route} />
    </Provider>,
  );
}

async function select(...names: RegExp[]) {
  for (const name of names) fireEvent.click(within(await screen.findByRole('gridcell', { name })).getByRole('button', { name: 'Select' }));
}
const bar = () => within(screen.getByRole('toolbar', { name: 'Selection actions' }));

describe('checkout', () => {
  it('checks files out and in from the selection', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await select(/Surf in Bali/);
    await pressAction('Checkout');
    await waitFor(() => expect(dam.calls).toContain(`checkout ${SURF}`));
    const card = await screen.findByRole('gridcell', { name: /Surf in Bali/ });
    expect(await within(card).findByTitle('Checked out by admin')).toBeTruthy();
    await select(/Surf in Bali/);
    expect(await hasAction('Checkout')).toBe(false);
    await pressAction('Check In');
    await waitFor(() => expect(dam.calls).toContain(`checkin ${SURF}`));
  });

  it('shows who has the file on its page', async () => {
    const dam = demo().asset(SURF, { checkedOutBy: 'bob' }, { 'dc:title': 'Surf in Bali' });
    mount(dam, { kind: 'details', path: SURF, properties: false });
    expect(await screen.findByText(/Checked out by bob/)).toBeTruthy();
  });
});

describe('relate', () => {
  it('relates the selection and unrelates from the rail', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await select(/Surf in Bali/, /^Ski/);
    await pressAction('Relate');
    expect(await screen.findByText('2 files related')).toBeTruthy();
    cleanup();

    mount(dam, { kind: 'details', path: SKI, properties: false });
    fireEvent.click(await screen.findByRole('button', { name: 'Related' }));
    const rail = await screen.findByRole('complementary', { name: 'Related' });
    expect(within(rail).getByText('Surf in Bali')).toBeTruthy();
    fireEvent.click(within(rail).getByRole('button', { name: 'Unrelate Surf in Bali' }));
    await waitFor(() => expect(dam.calls).toContain(`unrelate ${SKI} ${SURF}`));
    expect(await within(rail).findByText(/No related files/)).toBeTruthy();
  });
});

describe('extract archive', () => {
  it('extracts into a new folder', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await select(/shoot.zip/);
    await pressAction('Extract Archive');
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Keep both' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Extract' }));
    await waitFor(() => expect(dam.calls).toContain(`extract ${ZIP} folder rename`));
    expect(await screen.findByText('2 files extracted, 1 skipped into shoot')).toBeTruthy();
    expect(await screen.findByRole('gridcell', { name: /shoot, folder/ })).toBeTruthy();
  });
});

describe('processing profiles', () => {
  it('creates a profile and applies it to a folder', async () => {
    const dam = demo();
    mount(dam, { kind: 'profiles' });
    expect(window.location.pathname).toBe('/mnt/overlay/dam/gui/content/processingprofilepage.html/conf/global/settings/dam/processing');
    expect(await screen.findByText('No processing profiles')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('form');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Title' }), { target: { value: 'Web Banners' } });
    const save = within(dialog).getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true); // the rendition has no name yet
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Name' }), { target: { value: 'Hero Banner' } });
    await waitFor(() => expect(save.disabled).toBe(false));
    fireEvent.click(save);
    await waitFor(() => expect(dam.calls).toContain('saveProfile web-banners "Web Banners" hero-banner.jpeg:1200x1200'));
    fireEvent.click(screen.getByRole('button', { name: /^Close/ }));
    expect(await screen.findByText('hero-banner.jpeg (1200 x 1200)')).toBeTruthy();
    cleanup();

    mount(dam, { kind: 'browse', path: '/content/dam' });
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /WKND Site/ })).getByRole('button', { name: 'Select' }));
    fireEvent.click(bar().getByRole('button', { name: 'Properties' }));
    const props = await screen.findByRole('dialog');
    const picker = await within(props).findByRole('button', { name: /Processing Profile/ });
    await waitFor(() => expect((picker as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(picker);
    fireEvent.click(await screen.findByRole('option', { name: 'Web Banners' }));
    fireEvent.click(within(props).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain('folderProfile /content/dam/wknd /conf/global/settings/dam/processing/web-banners'));
  });
});

describe('metadata csv', () => {
  it('imports a CSV and lists the rows it skipped', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await screen.findByRole('gridcell', { name: /Surf in Bali/ });
    fireEvent.click(screen.getByRole('button', { name: 'Metadata' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Import Metadata (CSV)' }));
    const dialog = await screen.findByRole('dialog');
    const input = dialog.querySelector('input[type="file"]') as HTMLInputElement;
    const csv = new File(['assetPath,dc:title {{String}}\r\n/content/dam/wknd/surf.jpg,New\r\n/content/dam/nope.jpg,X\r\n'], 'm.csv', { type: 'text/csv' });
    fireEvent.change(input, { target: { files: [csv] } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Import' }));
    await waitFor(() => expect(dam.calls).toContain('importMetadata m.csv'));
    expect(await within(dialog).findByText('1 file updated.')).toBeTruthy();
    expect(within(dialog).getByText(/not a file/)).toBeTruthy();
  });
});

describe('collections', () => {
  it('adds the selection to a new collection and removes from it', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await select(/Surf in Bali/, /^Ski/);
    await pressAction('Add to Collection');
    let dialog = await screen.findByRole('dialog');
    fireEvent.click(await within(dialog).findByRole('button', { name: 'New Collection' }));
    dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Title/ }), { target: { value: 'Summer' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('2 items added to Summer')).toBeTruthy();
    expect(dam.calls).toContain(`addToCollection /content/dam/collections/summer ${SURF},${SKI}`);
    cleanup();

    mount(dam, { kind: 'collections' });
    expect(window.location.pathname).toBe('/mnt/overlay/dam/gui/content/collections.html/content/dam/collections');
    fireEvent.click(within(await screen.findByRole('gridcell', { name: 'Summer' })).getByText('Summer'));
    expect(window.location.pathname).toBe('/mnt/overlay/dam/gui/content/collections/collectiondetails.html/content/dam/collections/summer');
    await select(/^Ski/);
    fireEvent.click(bar().getByRole('button', { name: /Remove from Collection/ }));
    expect(await screen.findByText('1 item removed from the collection')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('gridcell', { name: /^Ski/ })).toBeNull());
    expect(screen.getByRole('gridcell', { name: /Surf in Bali/ })).toBeTruthy();
  });

  it('saves a search as a smart collection', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd', search: { fulltext: 'surf' } });
    fireEvent.click(await screen.findByRole('button', { name: /Save as Smart Collection/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Title/ }), { target: { value: 'Surf' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(dam.calls).toContain('createCollection "Surf" smart /content/dam/wknd {"fulltext":"surf"}'));
    cleanup();
    mount(dam, { kind: 'collection', path: '/content/dam/collections/surf' });
    expect(await screen.findByRole('gridcell', { name: /Surf in Bali/ })).toBeTruthy();
    expect(screen.getByText(/Smart · “surf”/)).toBeTruthy();
  });
});

describe('metadata schemas', () => {
  it('builds the Properties form from the folder schema', async () => {
    const dam = demo();
    await dam.saveSchema('brand', 'Brand', [
      {
        title: 'Brand',
        fields: [
          { label: 'Title', property: 'dc:title', type: 'text', required: true },
          { label: 'Campaign', property: 'wknd:campaign', type: 'select', options: [{ value: 'summer', text: 'Summer' }, { value: 'winter', text: 'Winter' }] },
          { label: 'Rating', property: 'xmp:Rating', type: 'number' },
          { label: 'Approved', property: 'wknd:approved', type: 'checkbox' },
        ],
      },
    ]);
    await dam.setFolderSchema('/content/dam/wknd', '/conf/global/settings/dam/adminui-extension/metadataschema/brand');
    mount(dam, { kind: 'details', path: SURF, properties: true });
    expect(await screen.findByRole('tab', { name: 'Brand' })).toBeTruthy();
    expect(screen.queryByRole('tab', { name: 'Basic' })).toBeNull();
    const title = (await screen.findByRole('textbox', { name: /Title/ })) as HTMLInputElement;
    expect(title.value).toBe('Surf in Bali');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Approved' }));
    fireEvent.change(title, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Required: Title')).toBeTruthy();
    fireEvent.change(title, { target: { value: 'Surf' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain(`fields ${SURF} {"dc:title":"Surf","wknd:approved":true}`));
  });

  it('creates a schema from the default fields', async () => {
    const dam = demo();
    mount(dam, { kind: 'schemas' });
    expect(window.location.pathname).toBe('/mnt/overlay/dam/gui/content/metadataschemaeditor/schemalist.html/conf/global/settings/dam/adminui-extension/metadataschema');
    fireEvent.click(await screen.findByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('form');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Title' }), { target: { value: 'Photo Shoot' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add Field' }));
    const fields = within(dialog).getAllByRole('group', { name: /^Field / });
    const last = fields[fields.length - 1];
    fireEvent.change(within(last).getByRole('textbox', { name: 'Label' }), { target: { value: 'Photographer' } });
    fireEvent.change(within(last).getByRole('textbox', { name: 'Property' }), { target: { value: 'photoshop:Credit' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls.some((c) => c.startsWith('saveSchema photo-shoot "Photo Shoot" Basic[dc:title:text') && c.includes('photoshop:Credit:text'))).toBe(true));
    fireEvent.click(screen.getByRole('button', { name: /^Close/ }));
    expect(await screen.findByText('Basic (8)')).toBeTruthy();
  });
});

describe('share link', () => {
  it('creates a link, shows it once and revokes it', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    await select(/Surf in Bali/);
    await pressAction('Share Link');
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create Link' }));
    await waitFor(() => expect(dam.calls).toContain(`share ${SURF} 7d`));
    const link = (await within(dialog).findByRole('textbox', { name: 'Link' })) as HTMLInputElement;
    expect(link.value).toBe(`${window.location.origin}/linkshare.html?sh=token-id1`);
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(dam.calls).toContain('revoke id1'));
  });
});

describe('folder thumbnail', () => {
  it('chooses a file as the folder card image', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam' });
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /WKND Site/ })).getByRole('button', { name: 'Select' }));
    fireEvent.click(bar().getByRole('button', { name: 'Properties' }));
    const props = await screen.findByRole('dialog');
    const picker = await within(props).findByRole('button', { name: /Thumbnail/ });
    await waitFor(() => expect((picker as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(picker);
    fireEvent.click(await screen.findByRole('option', { name: 'Ski' }));
    fireEvent.click(within(props).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain(`folderThumbnail /content/dam/wknd ${SKI}`));
  });
});
