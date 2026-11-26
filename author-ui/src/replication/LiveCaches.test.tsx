import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { LiveCacheInfo } from './api';
import { LiveCaches, activity } from './LiveCaches';

afterEach(cleanup);

const running: LiveCacheInfo = {
  id: 'live_cache',
  title: 'Live cache',
  target: 'http://127.0.0.1:4520',
  enabled: true,
  ok: true,
  via: 'publish',
  viaTitle: 'Default Agent',
  files: { plain: 40, held: 2, stale: 5 },
  hits: 12,
  stored: 42,
  passed: 3,
  stale: 0,
  denied: 7,
  errors: 0,
  lastInvalidation: { action: 'Activate', path: '/content/wknd/us/en/faqs', removed: 3, at: Date.UTC(2026, 10, 20, 18, 16) },
  publish: [
    { url: 'http://127.0.0.1:4503', skipped: false },
    { url: 'http://127.0.0.1:4504', skipped: true },
  ],
};

class MemoryCaches {
  cleared: string[] = [];
  constructor(
    public list: LiveCacheInfo[],
    public canClear = true,
    private readonly answer = { ok: true, message: 'OK, 42 removed' },
  ) {}
  async caches() {
    return { caches: this.list, canClear: this.canClear };
  }
  async clearCache(id: string, via?: string) {
    this.cleared.push(`${via ?? '-'}/${id}`);
    if (this.answer.ok) this.list = this.list.map((c) => ({ ...c, files: { plain: 0, held: 0, stale: 0 } }));
    return { ...this.answer, caches: this.list };
  }
}

const mount = (client: MemoryCaches) =>
  render(
    <Provider theme={defaultTheme}>
      <LiveCaches client={client} tick={0} />
    </Provider>,
  );

describe('the Live cache in the Publishing console', () => {
  it('is not there when there is no cache', async () => {
    const { container } = mount(new MemoryCaches([]));
    await waitFor(() => expect(container.querySelector('.studio-replication__caches')).toBeNull());
    expect(screen.queryByText('Cache in front of the published site')).toBeNull();
  });

  it('says what the cache holds, what it was told and where it fetches from', async () => {
    mount(new MemoryCaches([running]));
    const card = (await screen.findByText('Live cache')).closest('.studio-settings-card') as HTMLElement;
    expect(within(card).getByText(/behind Default Agent/)).toBeTruthy();
    expect(within(card).getByText('http://127.0.0.1:4520')).toBeTruthy();
    expect(within(card).getByText('Running, and told about every publication')).toBeTruthy();
    expect(within(card).getByText(/^42 pages and files/)).toBeTruthy();
    expect(within(card).getByText(/5 kept from before the last publications/)).toBeTruthy();
    expect(within(card).getByText('/content/wknd/us/en/faqs')).toBeTruthy();
    expect(within(card).getByText(/3 files taken out/)).toBeTruthy();
    expect(within(card).getByText(/not answering, left out for now/)).toBeTruthy();
    expect(within(card).getByText('Degraded')).toBeTruthy();
  });

  it('lists the sites behind it, with their redirects, protected pages and vanity URLs', async () => {
    mount(
      new MemoryCaches([
        {
          ...running,
          graced: 4,
          redirected: 9,
          vanityUrls: { count: 2, readAt: Date.UTC(2026, 10, 23, 12, 0) },
          sites: [
            { name: 'wknd', hosts: ['wknd.localhost', 'www.wknd.example'], roots: ['/content/wknd'], files: { plain: 30, held: 2, stale: 0 }, tracked: 25, redirects: { map: '/content/dam/wknd/redirectmap.txt', rules: 4 } },
            { name: 'brand2', hosts: ['brand2.localhost'], roots: ['/content/brand2'], files: { plain: 1, held: 0, stale: 0 }, protected: { globs: ['/content/brand2/en/club*'], files: 1 } },
          ],
        },
      ]),
    );
    const card = (await screen.findByText('Live cache')).closest('.studio-settings-card') as HTMLElement;
    expect(within(card).getByText('wknd')).toBeTruthy();
    expect(within(card).getByText('wknd.localhost, www.wknd.example')).toBeTruthy();
    expect(within(card).getByText(/32 files · 25 saying what they show · 4 redirects from \/content\/dam\/wknd\/redirectmap.txt/)).toBeTruthy();
    expect(within(card).getByText(/protected \/content\/brand2\/en\/club\* \(1 kept\)/)).toBeTruthy();
    expect(within(card).getByText(/2 vanity URLs let through/)).toBeTruthy();
    expect(within(card).getByText(/9 redirected by a redirect map/)).toBeTruthy();
  });

  it('shows a cache that several publish instances tell once', async () => {
    mount(new MemoryCaches([running, { ...running, via: 'publish2', viaTitle: 'Second publish' }]));
    await screen.findByText('Live cache');
    expect(screen.getAllByText('Live cache')).toHaveLength(1);
    expect(screen.getByText(/behind Default Agent, Second publish/)).toBeTruthy();
  });

  it('says so when the cache does not answer or is not told', async () => {
    mount(
      new MemoryCaches([
        { id: 'a', title: 'Cache A', ok: false, message: 'HTTP 404 from http://x/_live/status', enabled: true },
        { ...running, id: 'b', title: 'Cache B', enabled: false },
      ]),
    );
    const a = (await screen.findByText('Cache A')).closest('.studio-settings-card') as HTMLElement;
    expect(within(a).getByText(/Not answering: HTTP 404/)).toBeTruthy();
    expect(within(a).queryByRole('button', { name: 'Clear cache' })).toBeNull();
    const b = screen.getByText('Cache B').closest('.studio-settings-card') as HTMLElement;
    expect(within(b).getByText(/its target is disabled/)).toBeTruthy();
  });

  it('empties a cache after asking, the way the cache was reported', async () => {
    const client = new MemoryCaches([running]);
    mount(client);
    const card = (await screen.findByText('Live cache')).closest('.studio-settings-card') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: 'Clear cache' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(client.cleared).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Clear cache' }));
    await waitFor(() => expect(client.cleared).toEqual(['publish/live_cache']));
    expect(await within(card).findByText(/^0 pages and files/)).toBeTruthy();
  });

  it('offers no clearing to who may not', async () => {
    mount(new MemoryCaches([running], false));
    await screen.findByText('Live cache');
    expect(screen.queryByRole('button', { name: 'Clear cache' })).toBeNull();
  });

  it('puts the activity in words', () => {
    expect(activity({ id: 'x', title: 'x', ok: true })).toBe('nothing asked of it yet');
    expect(activity({ id: 'x', title: 'x', ok: true, stored: 1, hits: 0, stale: 2 })).toBe('1 answer fetched and kept · 2 served as they were while the publish did not answer');
  });
});
