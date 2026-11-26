import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { CfField } from './api';
import { CfEditor } from './CfEditor';
import { changes, slugify, tabsOf, toApiValue, toFormValue, validate } from './fields';
import { FixtureCfClient, type CfFixture } from './testing/FixtureCfClient';

const fixture = JSON.parse(readFileSync(join(__dirname, '../../fixtures/cf/wknd-shared.json'), 'utf8')) as CfFixture;
const MODELS = '/conf/wknd-shared/settings/dam/cfm/models';
const BALI = '/content/dam/wknd-shared/en/adventures/bali-surf-camp/bali-surf-camp';
const adventure = fixture.model[`${MODELS}/adventure`];
const author = fixture.model[`${MODELS}/author`];
const field = (fields: CfField[], name: string) => fields.find((f) => f.name === name)!;

describe('model fields (real WKND models)', () => {
  it('maps every element of the three models to an input', () => {
    for (const m of Object.values(fixture.model)) {
      for (const f of m.fields) expect(['text', 'longtext', 'number', 'boolean', 'date', 'enumeration', 'tags', 'content-reference', 'fragment-reference', 'json', 'tab']).toContain(f.kind);
    }
    expect(tabsOf(adventure.fields)).toHaveLength(1);
  });

  it('normalises stored values for the inputs and back', () => {
    const bali = fixture.fragment[BALI].values.master;
    expect(toFormValue(field(adventure.fields, 'groupSize'), bali.groupSize)).toBe(6);
    expect(toFormValue(field(adventure.fields, 'activity'), bali.activity)).toBe('Surfing');
    expect(toApiValue(field(adventure.fields, 'groupSize'), null)).toBeNull();
    const occupations = field(author.fields, 'occupations');
    expect(toFormValue(occupations, ['Writer'])).toEqual(['Writer']);
    expect(toApiValue(occupations, ['Writer', ' '])).toEqual(['Writer']);
  });

  it('validates like the server: required, model regex, number minimum', () => {
    const slug = field(adventure.fields, 'slug');
    expect(validate(slug, 'bali-surf-camp')).toBeNull();
    expect(validate(slug, 'Not A Slug')).toMatch(/Slug must be/);
    expect(validate(field(adventure.fields, 'title'), '')).toBe('This field is required.');
    expect(validate(field(adventure.fields, 'groupSize'), 0)).toMatch(/at least one/);
  });

  it('reports only what changed', () => {
    const initial = { title: 'A', groupSize: 6 as number | null };
    expect(changes(adventure.fields, initial, { ...initial, groupSize: 8 })).toEqual({ groupSize: 8 });
    expect(changes(adventure.fields, initial, { ...initial })).toEqual({});
    expect(slugify('Ski Touring – Mont Blanc!')).toBe('ski-touring-mont-blanc');
  });
});

describe('content fragment editor', () => {
  beforeAll(() => {
    // jsdom lacks these; Spectrum's overlays and virtualized lists need them
    globalThis.ResizeObserver ??= class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
    window.HTMLElement.prototype.scrollIntoView ??= () => {};
    // jsdom has no CSS.escape; Spectrum's Tabs use it
    (globalThis as { CSS?: { escape?: (s: string) => string } }).CSS ??= {};
    globalThis.CSS.escape ??= (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`);
  });
  afterEach(cleanup);

  const open = async (client: FixtureCfClient) => {
    render(
      <Provider theme={defaultTheme}>
        <CfEditor client={client} path={BALI} />
      </Provider>,
    );
    await waitFor(() => expect(screen.getAllByText('Bali Surf Camp').length).toBeGreaterThan(0));
  };

  it('shows the elements of the model with their values', async () => {
    await open(new FixtureCfClient(fixture));
    expect((screen.getByRole('textbox', { name: /^Slug/ }) as HTMLInputElement).value).toBe('bali-surf-camp');
    const rte = screen.getAllByRole('textbox', { name: 'Description' }).find((el) => el.getAttribute('contenteditable') === 'true')!;
    expect(rte.innerHTML).toContain('Surfing in Bali');
    expect(screen.getByRole('button', { name: 'Save' })).toHaveProperty('disabled', true);
  });

  it('saves only the changed element', async () => {
    const client = new FixtureCfClient(fixture);
    await open(client);
    const trip = screen.getByRole('textbox', { name: 'Trip Length' });
    act(() => {
      fireEvent.change(trip, { target: { value: '7 Days' } });
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(client.saves).toHaveLength(1));
    expect(client.saves[0]).toMatchObject({ path: BALI, variation: 'master', values: { tripLength: '7 Days' } });
    expect(Object.keys(client.saves[0].values)).toEqual(['tripLength']);
  });

  it('blocks saving an invalid change and shows the model message', async () => {
    const client = new FixtureCfClient(fixture);
    await open(client);
    act(() => {
      fireEvent.change(screen.getByRole('textbox', { name: /^Slug/ }), { target: { value: 'No Spaces Allowed' } });
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    expect(client.saves).toHaveLength(0);
    expect(screen.getAllByText(/Slug must be at least 5 characters/).length).toBeGreaterThan(0);
  });

  it('lists where the content fragment is used and what it uses', async () => {
    const ian = '/content/dam/wknd-shared/en/contributors/ian-provo';
    render(
      <Provider theme={defaultTheme}>
        <CfEditor client={new FixtureCfClient(fixture)} path={ian} />
      </Provider>,
    );
    await screen.findByRole('tab', { name: 'References' });
    await act(async () => {
      fireEvent.click(screen.getByRole('tab', { name: 'References' }));
    });
    const usedIn = await screen.findByRole('region', { name: 'Used in' });
    expect(usedIn.textContent).toContain('Alaskan Adventure');
    expect(usedIn.textContent).toContain('Article content fragment · wknd-shared/en/magazine/alaska-adventure');
    const uses = screen.getByRole('region', { name: 'Uses' });
    expect(uses.textContent).toContain('ian_provo.jpg');
    expect(uses.textContent).toContain('profilePicture');
  });

  it('creates a version, compares it with the current content fragment and restores it', async () => {
    const client = new FixtureCfClient(fixture);
    await open(client);
    await act(async () => {
      fireEvent.click(screen.getByRole('tab', { name: 'Versions' }));
    });
    await screen.findByText(/No versions yet/);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create version' }));
    });
    act(() => {
      fireEvent.change(screen.getByRole('textbox', { name: /Label/ }), { target: { value: 'Launch' } });
    });
    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Create' }));
    });
    await screen.findByText('Launch');
    expect(await client.versions(BALI)).toHaveLength(1);

    // change and save, then compare
    act(() => {
      fireEvent.change(screen.getByRole('textbox', { name: 'Trip Length' }), { target: { value: '9 Days' } });
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(client.saves).toHaveLength(1));
    const [v] = await client.versions(BALI);
    const { differences } = await import('./versions');
    const model = await client.model(fixture.fragment[BALI].model!);
    const diff = differences(model, await client.versionContent(BALI, v.name), await client.fragment(BALI));
    expect(diff).toEqual([{ name: 'tripLength', label: 'Trip Length', version: '6 Days', current: '9 Days' }]);

    // restore puts the version back and keeps the replaced state as a version
    const out = await client.restoreVersion(BALI, v.name);
    expect(out.saved.comment).toBe('Before restoring Launch');
    expect(out.fragment.values.master.tripLength).toBe('6 Days');
  });
});
