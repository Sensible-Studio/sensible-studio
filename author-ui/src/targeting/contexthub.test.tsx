import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { ContextHubConsole, linesToValues, problemsOf, toSettings, valuesToLines } from './ContextHubConsole';
import { ContextHubBar } from './ContextHubBar';
import { propertyOptions } from './ExpressionEditor';
import { emulatedDevice, emulateDevice, tagNames, type ContextHubSettings, type HubModule, type HubStore, type TargetingClient } from './api';
import { placeOfUrl, urlOf } from '../shell/places';

// jsdom has no CSS.escape, which Spectrum's menus use for their item ids
(globalThis as { CSS?: { escape(v: string): string } }).CSS ??= { escape: (v) => v };

const defaults = (): ContextHubSettings => ({
  conf: '/conf/wknd',
  from: null,
  custom: false,
  canEdit: true,
  stores: [
    { name: 'profile', type: 'profile', enabled: true, initial: {} },
    { name: 'geolocation', type: 'geolocation', enabled: true, initial: {} },
    { name: 'surferinfo', type: 'surferinfo', enabled: true, initial: {} },
  ],
  modules: [
    { title: 'Age', store: 'profile', property: 'age', type: 'number' },
    { title: 'Device', store: 'surferinfo', property: 'device', type: 'select', options: ['desktop', 'mobile'] },
  ],
  properties: ['profile/age', 'surferinfo/device'],
});

function fakeClient(start = defaults()) {
  const saved: Array<{ stores: HubStore[]; modules: HubModule[] }> = [];
  let current = start;
  const client = {
    saved,
    async contexthub() {
      return current;
    },
    async saveContexthub(_conf: string, s: { stores: HubStore[]; modules: HubModule[] }) {
      saved.push(s);
      current = { ...current, custom: true, stores: s.stores, modules: s.modules, modifiedBy: 'admin' };
      return current;
    },
    async resetContexthub() {
      current = defaults();
      return current;
    },
    async personas() {
      return [];
    },
    async segments() {
      return { conf: '/conf/wknd', segments: [] };
    },
  };
  return client as unknown as TargetingClient & { saved: typeof saved };
}

describe('ContextHub configuration', () => {
  afterEach(cleanup);

  it("has AEM's address", () => {
    expect(placeOfUrl('/libs/granite/contexthub/content/admin/contexthub.html/conf/wknd')).toEqual({ kind: 'contexthub', conf: '/conf/wknd' });
    expect(urlOf({ kind: 'contexthub', conf: null })).toBe('/libs/granite/contexthub/content/admin/contexthub.html');
  });

  it('reads values one per line', () => {
    expect(linesToValues('loyalty = gold\nage = 42\nvip = true\n\nbad line')).toEqual({ loyalty: 'gold', age: 42, vip: true });
    expect(valuesToLines({ a: 1, b: 'x' })).toBe('a = 1\nb = x');
  });

  it('says what keeps the settings from being saved', () => {
    const s = (p: Partial<HubStore> & { name: string; lines?: string }) => ({ type: 'generic' as const, enabled: true, initial: {}, key: 1, lines: '', ...p });
    const m = (p: Partial<HubModule> & { optionsText?: string }) => ({ title: 'X', store: 'profile', property: 'a', type: 'text' as const, key: 2, optionsText: '', ...p });
    expect(problemsOf([s({ name: 'profile', type: 'profile' })], [m({})])).toEqual([]);
    expect(problemsOf([s({ name: 'Bad' })], [])[0]).toMatch(/small letter/);
    expect(problemsOf([s({ name: 'w', type: 'json', url: 'ftp://x' })], [])[0]).toMatch(/address/);
    expect(problemsOf([s({ name: 'profile', type: 'profile' })], [m({ store: 'nope' })])[0]).toMatch(/choose a store/);
    expect(problemsOf([s({ name: 'profile', type: 'profile' })], [m({ type: 'select' })])[0]).toMatch(/options/);
    expect(toSettings([s({ name: 'w', type: 'json', url: ' /bin/x.json ', lines: 'temp = 0' })], [m({ type: 'select', optionsText: 'a, b,, ' })])).toEqual({
      stores: [{ name: 'w', type: 'json', enabled: true, initial: { temp: 0 }, url: '/bin/x.json', refresh: 60 }],
      modules: [{ title: 'X', store: 'profile', property: 'a', type: 'select', options: ['a', 'b'] }],
    });
    expect(propertyOptions(['campaign/source', 'profile/age']).filter((o) => o.id === 'profile/age')).toHaveLength(1);
  });

  it('adds a store of its own and a field for it, then saves', async () => {
    const client = fakeClient();
    render(
      <Provider theme={defaultTheme}>
        <ContextHubConsole client={client} initialConf="/conf/wknd" />
      </Provider>,
    );
    await screen.findByText(/The default; saved here/);
    // the location store is turned off
    fireEvent.click(within(screen.getByRole('listitem', { name: 'Store geolocation' })).getByRole('switch', { name: 'On' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add store' }));
    const store = screen.getByRole('listitem', { name: 'Store store1' });
    fireEvent.change(within(store).getByRole('textbox', { name: /^Name/ }), { target: { value: 'campaign' } });
    fireEvent.change(within(store).getByRole('textbox', { name: /Starts with/ }), { target: { value: 'source = newsletter' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add field' }));
    const rows = screen.getAllByRole('row');
    const row = rows[rows.length - 1];
    fireEvent.change(within(row).getByRole('textbox', { name: 'Label' }), { target: { value: 'Source' } });
    fireEvent.change(within(row).getByRole('textbox', { name: 'Property' }), { target: { value: 'source' } });
    // the new field's store is the first one until another is chosen; choose campaign
    fireEvent.click(within(row).getByRole('button', { name: /Store/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'campaign' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(client.saved).toHaveLength(1));
    const s = client.saved[0];
    expect(s.stores.find((x) => x.name === 'geolocation')?.enabled).toBe(false);
    expect(s.stores.find((x) => x.name === 'campaign')).toEqual({ name: 'campaign', type: 'generic', enabled: true, initial: { source: 'newsletter' } });
    expect(s.modules[2]).toEqual({ title: 'Source', store: 'campaign', property: 'source', type: 'text' });
    await screen.findByText(/Its own settings, changed by admin/);
  });

  it("keeps AEM's own stores and modes, with only whether they are on", async () => {
    const aem: ContextHubSettings = {
      ...defaults(),
      custom: true,
      madeHere: false,
      stores: [...defaults().stores, { name: 'segmentation', type: 'other', storeType: 'aem.segmentation', enabled: true, initial: {} }],
      modes: [{ name: 'persona', title: 'Persona', modules: ['granite.profile', 'contexthub.location'], items: [{ name: 'profile', title: 'Profile', type: 'granite.profile' }, { name: 'location', title: 'Location', type: 'contexthub.location' }] }],
    };
    const client = fakeClient(aem);
    render(
      <Provider theme={defaultTheme}>
        <ContextHubConsole client={client} initialConf="/conf/wknd" />
      </Provider>,
    );
    const seg = await screen.findByRole('listitem', { name: 'Store segmentation' });
    expect(within(seg).getByText('segmentation · aem.segmentation')).toBeTruthy();
    expect(within(seg).queryByRole('textbox')).toBeNull();
    // AEM's modes, with what the bar in the page does with each module
    expect(screen.getByText(/Profile \(the persona to look as\) · Location \(country and city\)/)).toBeTruthy();
    fireEvent.click(within(seg).getByRole('switch', { name: 'On' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(client.saved).toHaveLength(1));
    expect(client.saved[0].stores.find((s) => s.name === 'segmentation')).toEqual({ name: 'segmentation', type: 'other', enabled: false, initial: {} });
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(await screen.findByText(/The rest of wknd's configuration \(from AEM\) stays/)).toBeTruthy();
  });

  it("puts the configuration's fields in the bar of Preview", async () => {
    const client = fakeClient();
    render(
      <Provider theme={defaultTheme}>
        <ContextHubBar client={client} pagePath="/content/wknd/us/en" frame={() => null} onClose={() => {}} />
      </Provider>,
    );
    await waitFor(() => expect(screen.queryByRole('textbox', { name: 'First name' })).toBeNull());
    expect(screen.getByRole('textbox', { name: 'Age' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Device/ })).toBeTruthy();
  });

  it("shows AEM's modes in the bar of Preview and emulates a device", async () => {
    window.localStorage.clear();
    const client = fakeClient({
      ...defaults(),
      modes: [
        {
          name: 'device',
          title: 'Device',
          modules: ['device', 'orientation', 'tagcloud'],
          items: [
            { name: 'device', title: 'Device', type: 'contexthub.device' },
            { name: 'orientation', title: 'Orientation', type: 'contexthub.screen-orientation' },
            { name: 'tagcloud', title: 'Tag cloud', type: 'contexthub.tagcloud' },
            { name: 'offers', title: 'Offers', type: 'acme.offers' },
          ],
        },
      ],
    });
    const sizes: Array<{ width: number; height: number } | null> = [];
    render(
      <Provider theme={defaultTheme}>
        <ContextHubBar client={client} pagePath="/content/wknd/us/en" frame={() => null} onClose={() => {}} onDevice={(s) => sizes.push(s)} />
      </Provider>,
    );
    fireEvent.click(await screen.findByRole('tab', { name: 'Device' }));
    // the fields give way to the mode's modules; one this bar does not know is only named
    expect(screen.queryByRole('textbox', { name: 'Age' })).toBeNull();
    expect(screen.getByText('Tag cloud').closest('.studio-contexthub__module')?.textContent).toBe('Tag cloud none yet');
    expect(screen.getByText('Offers').closest('.is-off')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /This browser/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'iPhone' }));
    await waitFor(() => expect(sizes).toEqual([{ width: 390, height: 844 }]));
    const saved = JSON.parse(window.localStorage.getItem('studio.contexthub') ?? '{}');
    expect(saved.surferinfo).toMatchObject({ device: 'mobile', os: 'iOS', isMobile: true });
    expect(saved.emulators.currentDevice).toMatchObject({ id: 'iphone', orientation: 'portrait' });
    window.localStorage.clear();
  });

  it('emulates a device in the stores and gives the browser back', () => {
    const phone = emulateDevice({ surferinfo: { month: 11 } }, 'ipad', 'landscape');
    expect(emulatedDevice(phone)).toEqual({ id: 'ipad', orientation: 'landscape', width: 1180, height: 820 });
    expect(phone.surferinfo).toMatchObject({ month: 11, device: 'tablet', isTablet: true });
    const back = emulateDevice(phone, 'native', 'portrait');
    expect(back.emulators).toBeUndefined();
    expect(back.surferinfo).toEqual({ month: 11 });
    expect(emulatedDevice(back).id).toBe('native');
    expect(tagNames({ tags: { hiking: 2, surf: 5 } })).toEqual(['surf', 'hiking']);
    expect(tagNames(['a', { name: 'b' }])).toEqual(['a', 'b']);
    expect(tagNames(undefined)).toEqual([]);
    // a desktop is always landscape
    expect(emulatedDevice(emulateDevice({}, 'desktop', 'portrait'))).toMatchObject({ orientation: 'landscape', width: 1920, height: 1080 });
  });
});
