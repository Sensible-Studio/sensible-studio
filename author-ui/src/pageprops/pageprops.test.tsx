import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ContentNode, DialogResponse, PostEntry } from '../granite';
import type { StudioClient } from '../editor/api/StudioClient';
import type { MsmClient } from '../msm/MsmClient';
import type { MsmStatus } from '../msm/types';
import type { TagsClient } from '../tags/api';
import { ago } from './MsmTabs';
import { PagePropertiesApp, returnUrl, tagIdOf, tagPathOf } from './PagePropertiesApp';
import { propertiesRoute, propertiesUrl, type PagePermissions, type PagePermissionsClient } from './api';

afterEach(cleanup);

const PAGE = '/content/wknd/us/en/magazine';
const dialog = JSON.parse(readFileSync(join(__dirname, '../../fixtures/dialogs/wknd_components_page.json'), 'utf8')) as DialogResponse;

const content: ContentNode = {
  'jcr:primaryType': 'cq:PageContent',
  'jcr:title': 'Magazine',
  'sling:resourceType': 'wknd/components/page',
  'cq:tags': ['wknd-shared:customer-journey/engage'],
};

function fakeClient(posts: Array<{ path: string; entries: PostEntry[] }>): StudioClient {
  return {
    getContent: async () => content,
    getDialog: async () => dialog,
    post: async (path: string, entries: PostEntry[]) => void posts.push({ path, entries }),
    listChildren: async () => [],
    assetUrl: (p: string) => p,
    assetThumbnail: (p: string) => p,
    assetInfo: async () => null,
    pageImage: async () => null,
  } as unknown as StudioClient;
}

const status: MsmStatus = {
  path: PAGE,
  inLiveCopy: true,
  isSource: false,
  liveCopy: { path: '/content/wknd/us/en', blueprintPath: '/content/wknd/language-masters/en', deep: true, root: false, exclusions: [], rolloutConfigs: [] },
  relationship: {
    sourcePath: '/content/wknd/language-masters/en/magazine',
    targetPath: PAGE,
    status: 'synced',
    inherited: true,
    cancelled: false,
    cancelledForChildren: false,
    cancelledByAncestor: false,
    cancelledProperties: [],
    sourceExisting: true,
    targetExisting: true,
    page: true,
    lastRolledOut: '2020-10-01T08:00:00Z',
    lastRolledOutBy: 'admin',
  },
  pageConfig: {
    root: false,
    deep: true,
    inheritRolloutConfigs: true,
    rolloutConfigs: [],
    effectiveRolloutConfigs: ['/libs/msm/wcm/rolloutconfigs/default'],
    excludedPaths: [],
  },
  pageExclusions: [],
};

function fakeMsm(calls: unknown[]): MsmClient {
  return {
    status: async () => status,
    configs: async () => [
      { path: '/libs/msm/wcm/rolloutconfigs/default', name: 'default', title: 'Standard sync rules', trigger: 'rollout', actions: [] },
      { path: '/libs/msm/wcm/rolloutconfigs/pushonmodify', name: 'pushonmodify', title: 'Push on modify', trigger: 'modification', actions: [] },
    ],
    liveCopyConfig: async (page: string, change: unknown) => {
      calls.push({ page, change });
      return status;
    },
    blueprintConfig: async () => status,
    propertyInheritance: async () => status,
  } as unknown as MsmClient;
}

const perms: PagePermissions = {
  acl: [{ principal: 'authors', title: 'Authors', group: true, read: true, modify: true, delete: false, replicate: false, create: false }],
  cug: { supported: true, principals: [] },
};
const permissions: PagePermissionsClient = {
  load: async () => perms,
  effective: async () => [],
  save: async () => perms,
  remove: async () => perms,
  setCug: async () => perms,
  findPrincipals: async () => [],
};

const tags = {
  children: async (path: string) => ({
    item: { path, name: 'engage', title: 'Engage', hasChildren: false },
    trail: [
      { path: '/content/cq:tags', title: 'Tags' },
      { path: '/content/cq:tags/wknd-shared', title: 'WKND Shared' },
      { path: '/content/cq:tags/wknd-shared/customer-journey', title: 'Customer Journey' },
      { path, title: 'Engage' },
    ],
    children: [],
  }),
} as unknown as TagsClient;

function mount(posts: Array<{ path: string; entries: PostEntry[] }> = [], msmCalls: unknown[] = []) {
  globalThis.fetch = vi.fn(async () => new Response('{}', { status: 404 })) as typeof fetch;
  render(
    <Provider theme={defaultTheme}>
      <PagePropertiesApp client={fakeClient(posts)} page={PAGE} msm={fakeMsm(msmCalls)} permissions={permissions} tags={tags} cloud={{ conf: async () => null, translation: async () => [] }} />
    </Provider>,
  );
}

describe('page properties', () => {
  it('has the dialog tabs and Sensible Studio tabs in AEM order', async () => {
    mount();
    const list = await screen.findByRole('tablist', { name: 'Page properties' });
    const names = within(list).getAllByRole('tab').map((t) => t.textContent);
    expect(names.slice(0, 5)).toEqual(['Basic', 'Advanced', 'Images', 'Cloud Services', 'Permissions']);
    expect(names).toContain('Permissions');
    expect(names).toContain('Linked copy');
    expect(names.indexOf('Permissions')).toBeLessThan(names.indexOf('Linked copy'));
    expect(screen.getByRole('heading', { level: 1, name: 'Magazine' })).toBeTruthy();
  });

  it('shows tags with their titles', async () => {
    mount();
    expect(await screen.findByText('WKND Shared : Customer Journey / Engage')).toBeTruthy();
  });

  it('lists the permissions of the page', async () => {
    mount();
    fireEvent.click(await screen.findByRole('tab', { name: 'Permissions' }));
    expect(await screen.findByText('Authors')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Edit Closed User Group' })).toBeTruthy();
  });

  it('saves the form and the linked copy configuration', async () => {
    const posts: Array<{ path: string; entries: PostEntry[] }> = [];
    const calls: unknown[] = [];
    mount(posts, calls);
    fireEvent.click(await screen.findByRole('tab', { name: 'Linked copy' }));
    expect(screen.getByText('/content/wknd/language-masters/en/magazine')).toBeTruthy();
    expect(screen.getByText('Administrator')).toBeTruthy();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Inherit sync rules from the parent' }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(posts.length).toBe(1));
    expect(posts[0].path).toBe(`${PAGE}/jcr:content`);
    expect(calls).toEqual([{ page: PAGE, change: { rolloutConfigs: ['/libs/msm/wcm/rolloutconfigs/default'] } }]);
  });
});

describe('helpers', () => {
  it('maps tag IDs and paths', () => {
    expect(tagPathOf('wknd-shared:customer-journey/engage')).toBe('/content/cq:tags/wknd-shared/customer-journey/engage');
    expect(tagIdOf('/content/cq:tags/wknd-shared/customer-journey/engage')).toBe('wknd-shared:customer-journey/engage');
    expect(tagIdOf('/content/cq:tags/wknd-shared')).toBe('wknd-shared:');
    expect(tagPathOf('plain')).toBe('/content/cq:tags/default/plain');
  });

  it('routes AEM properties URLs', () => {
    expect(propertiesUrl(PAGE)).toBe('/mnt/overlay/wcm/core/content/sites/properties.html?item=%2Fcontent%2Fwknd%2Fus%2Fen%2Fmagazine');
    expect(propertiesRoute({ pathname: '/mnt/overlay/wcm/core/content/sites/properties.html', search: `?item=${PAGE}` })).toBe(PAGE);
    expect(propertiesRoute({ pathname: '/sites.html', search: `?item=${PAGE}` })).toBeNull();
    const initial = '/conf/wknd/settings/wcm/templates/t/initial';
    expect(propertiesRoute({ pathname: '/mnt/overlay/wcm/core/content/sites/properties.html', search: `?item=${initial}` })).toBe(initial);
    expect(propertiesRoute({ pathname: '/mnt/overlay/wcm/core/content/sites/properties.html', search: '?item=/conf/wknd/settings/wcm/policies' })).toBeNull();
    expect(returnUrl(PAGE, '')).toBe('/sites.html/content/wknd/us/en');
  });

  it('says how long ago', () => {
    const now = Date.parse('2026-10-17T00:00:00Z');
    expect(ago('2020-10-01T08:00:00Z', now)).toBe('6 years ago');
    expect(ago(undefined, now)).toBe('Never');
  });
});

describe('Cloud Services tab', () => {
  it('shows the translation configurations that apply and which one the page uses', async () => {
    const { CloudServicesTab } = await import('./CloudServicesTab');
    const cloud = {
      conf: async () => '/conf/wknd',
      translation: async () => [
        { name: '/conf/wknd/settings/cloudconfigs/translation/claude', cloudConfig: true, conf: '/conf/wknd', connector: 'studio-ai', label: 'Claude for WKND', available: false, problem: 'No API key', default: true },
        { name: 'studio-pseudo', label: 'Pseudo translation (testing)', available: true, default: false },
      ],
    };
    render(
      <Provider theme={defaultTheme}>
        <CloudServicesTab client={cloud} page={PAGE} />
      </Provider>,
    );
    expect(await screen.findByText('Translation Integration')).toBeTruthy();
    expect(screen.getByText('Claude for WKND')).toBeTruthy();
    expect(screen.getByText('No API key')).toBeTruthy();
    expect(screen.getByText('Used for this page')).toBeTruthy();
    expect(screen.getByText('Instance connector (OSGi)')).toBeTruthy();
    expect(screen.getByText('Manage').getAttribute('href')).toBe('/cloudservices/translation.html/conf/wknd');
  });

  it('explains when the page has no configuration of its own', async () => {
    const { CloudServicesTab } = await import('./CloudServicesTab');
    const cloud = { conf: async () => null, translation: async () => [{ name: 'studio-pseudo', label: 'Pseudo', available: true, default: true }] };
    render(
      <Provider theme={defaultTheme}>
        <CloudServicesTab client={cloud} page={PAGE} />
      </Provider>,
    );
    expect(await screen.findByText(/Content is translated with the instance's connector/)).toBeTruthy();
    expect(screen.getByText('Create a configuration').getAttribute('href')).toBe('/cloudservices/translation.html/conf/global');
  });
});

describe('page properties layout', () => {
  it('scrolls itself, since the app body does not', () => {
    const scss = readFileSync(join(__dirname, 'pageprops.scss'), 'utf8');
    const root = scss.slice(scss.indexOf('.studio-pp {'), scss.indexOf('}', scss.indexOf('.studio-pp {')));
    expect(root).toContain('overflow-y: auto');
    expect(root).toContain('height: 100vh');
  });
});
