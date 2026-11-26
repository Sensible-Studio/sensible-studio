import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { createDefaultRegistry, GraniteForm, lockKey, useGraniteForm, withoutInherited, type DialogResponse, type GraniteNode, type InheritanceModel } from '../granite';
import { describeResult } from './MsmClient';
import { pageInheritance } from './pageInheritance';
import type { MsmStatus } from './types';

const registry = createDefaultRegistry();
const FIXTURES = join(__dirname, '../../fixtures/dialogs');

afterEach(cleanup);

const node = (properties: Record<string, unknown>, children: GraniteNode[] = []): GraniteNode =>
  ({ name: 'f', properties, children }) as unknown as GraniteNode;

describe('lockKey', () => {
  it('locks properties by name and child nodes as a whole', () => {
    expect(lockKey(node({ name: './jcr:title' }))).toBe('jcr:title');
    expect(lockKey(node({ name: './navTitle' }))).toBe('navTitle');
    expect(lockKey(node({ name: './image/fileReference' }))).toBe('/image');
    expect(lockKey(node({ name: './cq:featuredimage/alt' }, [node({ 'cq-msm-lockable': '/cq:featuredimage' })]))).toBe('/cq:featuredimage');
    expect(lockKey(node({ name: './hideInNav@TypeHint' }))).toBe('hideInNav');
    expect(lockKey(node({ name: ':operation' }))).toBeUndefined();
    expect(lockKey(node({}))).toBeUndefined();
  });
});

const status = (over: Partial<MsmStatus['relationship']> = {}): MsmStatus => ({
  path: '/content/site/us/en',
  inLiveCopy: true,
  isSource: false,
  pageExclusions: ['jcr:.*', 'sling:(?!(resourceType|resourceSuperType)$).*', 'cq:(?!(designPath|template|lastTranslationUpdate|targetEngine|robotsTags)$).*', 'publishCampaignId'],
  relationship: {
    sourcePath: '/content/site/language-masters/en',
    targetPath: '/content/site/us/en',
    status: 'synced',
    inherited: true,
    cancelled: false,
    cancelledForChildren: false,
    cancelledByAncestor: false,
    cancelledProperties: ['navTitle'],
    sourceExisting: true,
    targetExisting: true,
    page: true,
    ...over,
  },
});

describe('pageInheritance', () => {
  it('chains only what syncs synchronise', async () => {
    const calls: Array<[string, boolean]> = [];
    const m = pageInheritance(status(), async (n, c) => void calls.push([n, c]))!;
    expect(m.state('jcr:title')).toBeUndefined(); // excluded by the contentUpdate defaults: the live copy's own
    expect(m.state('cq:tags')).toBeUndefined();
    expect(m.state('cq:template')).toBe('inherited');
    expect(m.state('pageTitle')).toBe('inherited');
    expect(m.state('navTitle')).toBe('cancelled');
    expect(m.state('/cq:featuredimage')).toBe('inherited'); // child nodes are synchronised whole
    await m.toggle('pageTitle');
    await m.toggle('navTitle');
    await m.toggle('/cq:featuredimage');
    expect(calls).toEqual([
      ['pageTitle', true],
      ['navTitle', false],
      ['cq:featuredimage', true],
    ]);
  });

  it('has nothing to lock outside linked copies, on local and on suspended pages', () => {
    const noop = async () => {};
    expect(pageInheritance(null, noop)).toBeNull();
    expect(pageInheritance({ path: '/x', inLiveCopy: false, isSource: true }, noop)).toBeNull();
    expect(pageInheritance(status({ inherited: false }), noop)).toBeNull();
    expect(pageInheritance(status({ cancelled: true, status: 'suspended' }), noop)).toBeNull();
  });
});

function PageProps({ dialog, inheritance }: { dialog: DialogResponse & { root: GraniteNode }; inheritance: InheritanceModel }) {
  const form = useGraniteForm(dialog.root, { pageTitle: 'Inherited title', navTitle: 'Own nav' }, registry, {});
  return (
    <Provider theme={defaultTheme}>
      <GraniteForm form={form} registry={registry} inheritance={inheritance} />
    </Provider>
  );
}

describe('inherited fields in the page properties dialog', () => {
  it('are read-only behind a chain that cancels and re-enables them', async () => {
    const dialog = JSON.parse(readFileSync(join(FIXTURES, 'wknd_components_page.json'), 'utf8')) as DialogResponse & { root: GraniteNode };
    const toggled: string[] = [];
    let cancelled = ['navTitle'];
    const model: InheritanceModel = {
      state: (k) => (k.startsWith('jcr:') ? undefined : cancelled.includes(k) ? 'cancelled' : 'inherited'),
      toggle: async (k) => {
        toggled.push(k);
        cancelled = cancelled.includes(k) ? cancelled.filter((c) => c !== k) : [...cancelled, k];
      },
    };
    const { container } = render(<PageProps dialog={dialog} inheritance={model} />);
    const page = container.querySelector('[data-msm-key="pageTitle"]')!;
    expect(page).not.toBeNull();
    expect(page.classList.contains('is-inherited')).toBe(true);
    expect((page.querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
    const nav = container.querySelector('[data-msm-key="navTitle"]')!;
    expect(nav.classList.contains('is-cancelled')).toBe(true);
    expect((nav.querySelector('fieldset') as HTMLFieldSetElement).disabled).toBe(false);
    // jcr:title is not synchronised: no chain at all
    expect(container.querySelector('[data-msm-key="jcr:title"]')).toBeNull();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Cancel inheritance of pageTitle' }));
    });
    expect(toggled).toEqual(['pageTitle']);
  });
});

describe('withoutInherited', () => {
  it('does not post the values of inherited fields', () => {
    const model: InheritanceModel = { state: (k) => (k === 'navTitle' ? 'cancelled' : k.startsWith('jcr:') ? undefined : 'inherited'), toggle: async () => {} };
    const entries = [
      { name: './jcr:title', value: 'T' },
      { name: './pageTitle', value: 'P' },
      { name: './navTitle', value: 'N' },
      { name: './hideInNav@Delete', value: '' },
      { name: './cq:featuredimage/alt', value: 'A' },
      { name: ':operation', value: 'x' },
    ];
    expect(withoutInherited(entries, model).map((e) => e.name)).toEqual(['./jcr:title', './navTitle', ':operation']);
    expect(withoutInherited(entries, null)).toHaveLength(entries.length);
  });
});

describe('describeResult', () => {
  it('sums a sync up', () => {
    expect(
      describeResult({ updated: ['/a', '/b'], created: ['/c'], deleted: [], skipped: ['/d'], conflicts: [], renamed: [], unknownActions: ['workflow'], nodes: 9 }),
    ).toBe('2 pages updated, 1 page created, 1 page skipped (suspended or excluded), unknown actions skipped: workflow');
    expect(describeResult({ updated: [], created: [], deleted: [], skipped: [], conflicts: [], renamed: [], unknownActions: [], nodes: 0 })).toBe('Nothing to sync');
  });
});
