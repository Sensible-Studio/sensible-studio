import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CfConsole } from './CfConsole';
import { FixtureCfClient, type CfFixture } from './testing/FixtureCfClient';
import { hasAction, pressAction } from '../testing/selection';
import type { DamClient } from '../assets/api';

const fixture = JSON.parse(readFileSync(join(__dirname, '../../fixtures/cf/wknd-shared.json'), 'utf8')) as CfFixture;
const FOLDER = '/content/dam/wknd-shared/en/adventures/bali-surf-camp';
const BALI = `${FOLDER}/bali-surf-camp`;

/** Only what Copy and Move ask of Media's API. */
class DamStub {
  copied: Array<[string[], string]> = [];
  moved: Array<[string[], string]> = [];
  async folders() {
    return [{ path: '/content/dam', name: 'dam', title: 'Media', hasChildren: true }];
  }
  async references() {
    return [];
  }
  async copy(paths: string[], dest: string) {
    this.copied.push([paths, dest]);
    return paths.map((p) => `${dest}/${p.split('/').pop()}`);
  }
  async move(paths: string[], dest: string) {
    this.moved.push([paths, dest]);
    return { paths: paths.map((p) => `${dest}/${p.split('/').pop()}`), adjusted: 0 };
  }
}

beforeEach(() => {
  try {
    window.localStorage.setItem('studio.cf.view', 'list');
  } catch {
    /* ignore */
  }
});
afterEach(cleanup);

async function mount() {
  const client = new FixtureCfClient(structuredClone(fixture));
  const dam = new DamStub();
  render(
    <Provider theme={defaultTheme}>
      <CfConsole client={client} initialPath={FOLDER} dam={dam as unknown as DamClient} />
    </Provider>,
  );
  const box = await screen.findByRole('checkbox', { name: /Select Bali Surf Camp/ });
  await act(async () => {
    fireEvent.click(box);
  });
  await screen.findByRole('toolbar', { name: 'Selection actions' });
  return { client, dam };
}

describe('Content Fragments console: actions of a selected content fragment', () => {
  it('offers what AEM offers on a content fragment', async () => {
    await mount();
    for (const name of ['Open', 'Properties', 'Download', 'Publish', 'Copy', 'Move', 'Translate', 'Unpublish', 'Delete']) {
      expect(await hasAction(name), name).toBe(true);
    }
  });

  it('saves title, description and tags from Properties', async () => {
    const { client } = await mount();
    await pressAction('Properties');
    const form = await screen.findByRole('form', { name: 'Content Fragment properties' });
    const title = within(form).getByRole('textbox', { name: /Title/ });
    fireEvent.change(title, { target: { value: 'Bali Surf Camp (new)' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(async () => expect((await client.fragment(BALI)).title).toBe('Bali Surf Camp (new)'));
  });

  it('copies through Media into the folder chosen', async () => {
    const { dam } = await mount();
    await pressAction('Copy');
    fireEvent.click(await screen.findByRole('button', { name: 'Copy here' }));
    await waitFor(() => expect(dam.copied).toEqual([[[BALI], FOLDER]]));
  });
});
