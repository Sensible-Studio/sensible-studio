import { defaultTheme, DialogContainer, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { CfEditor } from './CfEditor';
import { languageName, languageRoot, pathIn } from './languages';
import { FixtureCfClient, type CfFixture } from './testing/FixtureCfClient';
import { TranslateDialog } from './translation';

const fixture = JSON.parse(readFileSync(join(__dirname, '../../fixtures/cf/wknd-shared.json'), 'utf8')) as CfFixture;
const ALASKA = '/content/dam/wknd-shared/en/magazine/alaska-adventure/alaskan-adventures';
const ALASKA_FR = '/content/dam/wknd-shared/fr/magazine/alaska-adventure/alaskan-adventures';

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  window.HTMLElement.prototype.scrollIntoView ??= () => {};
  (globalThis as { CSS?: { escape?: (s: string) => string } }).CSS ??= {};
  globalThis.CSS.escape ??= (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`);
});
afterEach(cleanup);

describe('language helpers', () => {
  it('finds language roots and copy paths like the server', () => {
    expect(languageRoot(ALASKA)).toEqual({ root: '/content/dam/wknd-shared/en', language: 'en', relative: 'magazine/alaska-adventure/alaskan-adventures' });
    expect(pathIn(ALASKA, 'fr')).toBe(ALASKA_FR);
    expect(languageRoot('/content/dam/wknd-shared/adventures/x')).toBeNull();
    expect(languageName('fr')).toBe('French');
    expect(languageName('pt_br')).toBe('Brazilian Portuguese');
    expect(languageName('adventures')).toBeNull();
  });
});

describe('translate dialog', () => {
  const open = (client: FixtureCfClient) =>
    render(
      <Provider theme={defaultTheme}>
        <DialogContainer onDismiss={() => {}}>
          <TranslateDialog client={client} paths={[ALASKA]} />
        </DialogContainer>
      </Provider>,
    );

  it('translates into a new language with the chosen connector and lists the copies', async () => {
    const client = new FixtureCfClient(fixture);
    open(client);
    const language = await screen.findByRole('textbox', { name: /Language/ });
    act(() => {
      fireEvent.change(language, { target: { value: 'fr' } });
    });
    expect(screen.getByText(/French: a new language folder is created/)).toBeTruthy();
    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Translate' }));
    });
    await screen.findByRole('list', { name: 'Results' });
    expect(client.providerPaths).toEqual([ALASKA]);
    expect(client.translateRequests[0]).toMatchObject({ paths: [ALASKA], languages: ['fr'], provider: 'studio-ai', includeReferences: true, force: false });
    expect(screen.getByText(/fr · created · 3 translated/)).toBeTruthy();
    expect((await client.fragment(ALASKA_FR)).title).toBe('[fr] Alaskan Adventures');
  });

  it('explains a connector without API key and offers another', async () => {
    const client = new FixtureCfClient(fixture);
    client.missingCredentials = true;
    open(client);
    await screen.findByRole('textbox', { name: /Language/ });
    // the AI connector is listed as not set up; the pseudo connector is chosen instead
    expect(within(screen.getByRole('dialog')).getAllByText(/Pseudo translation/).length).toBeGreaterThan(0);
    act(() => {
      fireEvent.change(screen.getByRole('textbox', { name: /Language/ }), { target: { value: 'de' } });
    });
    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Translate' }));
    });
    await waitFor(() => expect(client.translateRequests).toHaveLength(1));
    expect(client.translateRequests[0].provider).toBe('studio-pseudo');
  });
});

describe('languages tab', () => {
  it('shows a language version with its source, status and approve', async () => {
    const client = new FixtureCfClient(fixture);
    await client.translate({ paths: [ALASKA], languages: ['fr'], provider: 'studio-pseudo' });
    render(
      <Provider theme={defaultTheme}>
        <CfEditor client={client} path={ALASKA_FR} />
      </Provider>,
    );
    await screen.findByRole('tab', { name: 'Languages' });
    await act(async () => {
      fireEvent.click(screen.getByRole('tab', { name: 'Languages' }));
    });
    const box = await screen.findByRole('region', { name: 'Translation' });
    expect(box.textContent).toContain(ALASKA);
    expect(box.textContent).toContain('English → French');
    expect(box.textContent).toContain('Translated, to review');
    await act(async () => {
      fireEvent.click(within(box).getByRole('button', { name: 'Approve' }));
    });
    await waitFor(() => expect(screen.getByRole('region', { name: 'Translation' }).textContent).toContain('Approved'));
  });

  it('lists the copies of a source content fragment', async () => {
    const client = new FixtureCfClient(fixture);
    await client.translate({ paths: [ALASKA], languages: ['fr'], provider: 'studio-pseudo' });
    render(
      <Provider theme={defaultTheme}>
        <CfEditor client={client} path={ALASKA} />
      </Provider>,
    );
    await screen.findByRole('tab', { name: 'Languages' });
    await act(async () => {
      fireEvent.click(screen.getByRole('tab', { name: 'Languages' }));
    });
    const copies = await screen.findByRole('list', { name: 'Language versions' });
    expect(copies.textContent).toContain('French (fr)');
    expect(copies.textContent).toContain('Translated, to review');
  });
});
