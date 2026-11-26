import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { CfModelEditor } from './CfModelEditor';
import { CfModels } from './CfModels';
import { checkFields, newField, propertyName, uniqueName } from './modelTypes';
import { FixtureCfClient, type CfFixture } from './testing/FixtureCfClient';

const fixture = JSON.parse(readFileSync(join(__dirname, '../../fixtures/cf/wknd-shared.json'), 'utf8')) as CfFixture;
const MODELS = '/conf/wknd-shared/settings/dam/cfm/models';
const ADVENTURE = `${MODELS}/adventure`;

describe('model helpers', () => {
  it('proposes element names from labels, as AEM', () => {
    expect(propertyName('What to Bring')).toBe('whatToBring');
    expect(propertyName('Prix réduit (€)')).toBe('prixReduit');
    expect(propertyName('1st place')).toBe('_1stPlace');
    expect(uniqueName('title', ['title', 'title2'])).toBe('title3');
  });

  it('checks fields like the server', () => {
    const a = newField('text', []);
    const b = { ...newField('text', []), name: a.name };
    const e = { ...newField('enumeration', []), options: [] };
    const n = { ...newField('number', []), validation: { min: 5, max: 1 } };
    expect(checkFields([a, b, e, n, newField('tab', [])]).map((x) => x.field)).toEqual(['fields.1.name', 'fields.2.options', 'fields.3.validation.max']);
  });
});

describe('model editor', () => {
  beforeAll(() => {
    globalThis.ResizeObserver ??= class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
    window.HTMLElement.prototype.scrollIntoView ??= () => {};
  });
  afterEach(cleanup);

  const open = async (client: FixtureCfClient) => {
    render(
      <Provider theme={defaultTheme}>
        <CfModelEditor client={client} path={ADVENTURE} />
      </Provider>,
    );
    await screen.findByRole('grid', { name: 'Elements' });
  };

  it('lists the elements of the model in order', async () => {
    await open(new FixtureCfClient(fixture));
    const rows = within(screen.getByRole('grid', { name: 'Elements' })).getAllByRole('row');
    expect(rows[0].textContent).toContain('Title');
    expect(rows[1].textContent).toContain('slug · Single line text');
    expect(screen.getByRole('button', { name: 'Save' })).toHaveProperty('disabled', true);
  });

  it('adds a data type after the selection; the name follows the label; saves with the ids kept', async () => {
    const client = new FixtureCfClient(fixture);
    await open(client);
    const palette = screen.getByRole('grid', { name: 'Data types' });
    await act(async () => {
      fireEvent.click(within(palette).getByText('Number'));
    });
    const label = await screen.findByRole('textbox', { name: /Field label/ });
    act(() => {
      fireEvent.change(label, { target: { value: 'Max Participants' } });
    });
    expect((screen.getByRole('textbox', { name: /Property name/ }) as HTMLInputElement).value).toBe('maxParticipants');
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(client.modelSaves).toHaveLength(1));
    const fields = client.modelSaves[0].fields;
    const added = fields[fields.length - 1];
    expect(added).toMatchObject({ kind: 'number', name: 'maxParticipants', label: 'Max Participants', valueType: 'long' });
    expect(added.id).toBeUndefined();
    expect(fields.slice(0, -1).every((f) => !!f.id)).toBe(true);
  });

  it('blocks saving a duplicate element name and points at it', async () => {
    const client = new FixtureCfClient(fixture);
    await open(client);
    const rows = within(screen.getByRole('grid', { name: 'Elements' })).getAllByRole('row');
    await act(async () => {
      fireEvent.click(within(rows[1]).getByText(/Slug/));
    });
    const name = await screen.findByRole('textbox', { name: /Property name/ });
    act(() => {
      fireEvent.change(name, { target: { value: 'title' } });
    });
    expect(screen.getAllByText('Another element is named title.').length).toBeGreaterThan(0);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    expect(client.modelSaves).toHaveLength(0);
  });
});

describe('models console', () => {
  afterEach(cleanup);

  it('lists the models of a configuration with their status, in the consoles\' views', async () => {
    const client = new FixtureCfClient(fixture);
    await client.setModelStatus(`${MODELS}/author`, false);
    window.localStorage.setItem('studio.models.view', 'list');
    render(
      <Provider theme={defaultTheme}>
        <CfModels client={client} initialPath="/conf/wknd-shared" />
      </Provider>,
    );
    const table = (await screen.findByRole('table')) as HTMLElement;
    const text = table.textContent ?? '';
    expect(text).toContain('Adventure');
    expect(text).toContain('Article');
    expect(text).toMatch(/Author.*Disabled/);
    expect(screen.getByRole('navigation', { name: 'Location' }).textContent).toContain('Content Fragment Models');
    // the root lists the configurations as folders
    window.localStorage.setItem('studio.models.view', 'column');
    cleanup();
    render(
      <Provider theme={defaultTheme}>
        <CfModels client={client} initialPath="/conf" />
      </Provider>,
    );
    expect(await screen.findByRole('list', { name: 'Content Fragment Models' })).toBeTruthy();
    expect(screen.getAllByText('wknd-shared').length).toBeGreaterThan(0);
  });
});
