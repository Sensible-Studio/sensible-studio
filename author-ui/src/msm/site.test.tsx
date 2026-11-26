import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { CreateSiteWizard } from './CreateSiteWizard';
import type { MsmClient } from './MsmClient';
import { PageVersions } from './PageVersions';
import type { CreateSite, PageRevision, RolloutResult } from './types';

afterEach(cleanup);

const EMPTY: RolloutResult = { updated: [], created: ['/content/sites/my-site'], deleted: [], skipped: [], conflicts: [], renamed: [], unknownActions: [], nodes: 3 };

function fake(overrides: Partial<MsmClient> = {}): MsmClient & { sites: CreateSite[] } {
  const sites: CreateSite[] = [];
  const base = {
    sites,
    status: async () => ({ path: '', inLiveCopy: false, isSource: false }),
    overview: async () => ({ path: '', liveCopies: [] }),
    configs: async () => [{ path: '/libs/msm/wcm/rolloutconfigs/default', name: 'default', title: 'Standard sync', trigger: 'rollout' as const, actions: [] }],
    rollout: async () => EMPTY,
    sync: async () => EMPTY,
    inheritance: async () => undefined,
    propertyInheritance: async () => undefined,
    createLiveCopy: async () => ({ path: '', result: EMPTY }),
    revisions: async () => [],
    restore: async () => [],
    blueprints: async () => [{ path: '/apps/msm/wknd_blueprint', title: 'WKND Site Source', sitePath: '/content/wknd/language-masters' }],
    blueprint: async () => ({
      path: '/apps/msm/wknd_blueprint',
      title: 'WKND Site Source',
      sitePath: '/content/wknd/language-masters',
      languages: [
        { path: '/content/wknd/language-masters/en', name: 'en', title: 'English' },
        { path: '/content/wknd/language-masters/fr', name: 'fr', title: 'French' },
      ],
      chapters: [
        { path: '/content/wknd/language-masters/en/magazine', name: 'magazine', title: 'Magazine' },
        { path: '/content/wknd/language-masters/en/faqs', name: 'faqs', title: 'FAQs' },
      ],
    }),
    createSite: async (r: CreateSite) => {
      sites.push(r);
      return { path: '/content/sites/my-site', result: EMPTY };
    },
    createBlueprint: async () => {
      throw new Error('a source is only created with the site');
    },
    siteStructure: async (sitePath: string) => ({
      path: '',
      title: '',
      sitePath,
      languages: [{ path: `${sitePath}/en`, name: 'en', title: 'English' }],
      chapters: [],
    }),
  };
  return Object.assign(base, overrides) as MsmClient & { sites: CreateSite[] };
}

const ui = (el: JSX.Element) => render(<Provider theme={defaultTheme}>{el}</Provider>);

describe('Create Site', () => {
  it('creates a linked copy site with the chosen languages and chapters', async () => {
    const msm = fake();
    let created = '';
    ui(<CreateSiteWizard msm={msm} parent="/content/sites" parentTitle="Pages" user="admin" onClose={() => {}} onCreated={(p) => (created = p)} />);
    fireEvent.click(await screen.findByRole('radio', { name: /WKND Site Source/ }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    });
    // all languages and chapters start chosen; leave French and FAQs out
    fireEvent.click(await screen.findByRole('checkbox', { name: 'French (fr)' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'FAQs (faqs)' }));
    fireEvent.change(screen.getByRole('textbox', { name: /^Title/ }), { target: { value: 'My Site' } });
    fireEvent.click(screen.getByRole('checkbox', { name: /^Linked copy/ }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    });
    expect(msm.sites).toEqual([
      {
        blueprint: '/apps/msm/wknd_blueprint',
        parent: '/content/sites',
        title: 'My Site',
        name: 'my-site',
        owner: 'admin',
        languages: ['/content/wknd/language-masters/en'],
        chapters: ['magazine'],
        liveCopy: true,
        rolloutConfigs: [],
      },
    ]);
    expect(created).toBe('/content/sites/my-site');
  });

  it('creates a new source only together with the site', async () => {
    const msm = fake();
    ui(<CreateSiteWizard msm={msm} parent="/content/sites" parentTitle="Pages" user="admin" onClose={() => {}} onCreated={() => {}} />);
    fireEvent.click(await screen.findByRole('radio', { name: /New source/ }));
    fireEvent.change(screen.getByRole('textbox', { name: /^Source title/ }), { target: { value: 'Brand' } });
    fireEvent.change(screen.getByRole('textbox', { name: /^Site root/ }), { target: { value: '/content/brand' } });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    });
    expect(await screen.findByText('Brand')).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: /^Title/ }), { target: { value: 'Brand US' } });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    });
    expect(msm.sites).toHaveLength(1);
    expect(msm.sites[0].blueprint).toBeUndefined();
    expect(msm.sites[0].newBlueprint).toEqual({ title: 'Brand', sitePath: '/content/brand' });
    expect(msm.sites[0].languages).toEqual(['/content/brand/en']);
    expect(msm.sites[0].liveCopy).toBe(false);
  });
});

describe('page versions', () => {
  it('lists versions and restores one after confirming', async () => {
    const v: PageRevision[] = [
      { id: 'v2', name: '1.1', comment: 'Before sync from /content/bp', created: '2026-10-17T08:00:00Z', createdBy: 'admin' },
      { id: 'v1', name: '1.0', label: 'launch', created: '2026-10-16T08:00:00Z' },
    ];
    const restored: string[] = [];
    const msm = fake({ revisions: async () => v, restore: async (_p, id) => (restored.push(id), v) });
    ui(<PageVersions msm={msm} path="/content/lc" />);
    expect(await screen.findByText('Before sync from /content/bp')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Restore version 1.0' }));
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Restore' }));
    });
    await waitFor(() => expect(restored).toEqual(['v1']));
  });
});
