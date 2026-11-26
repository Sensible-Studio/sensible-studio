import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { LiveCacheExplanation, LiveCacheInfo } from './api';
import { LiveCaches } from './LiveCaches';
import { slingParts, verdict } from './UrlTester';

afterEach(cleanup);

const cache: LiveCacheInfo = {
  id: 'live_cache',
  title: 'Live cache',
  target: 'http://127.0.0.1:4520',
  enabled: true,
  ok: true,
  via: 'publish',
  viaTitle: 'Default Agent',
  sites: [{ name: 'wknd', hosts: ['wknd.localhost', '*.wknd.example'], roots: ['/content/wknd'] }],
};

const page: LiveCacheExplanation = {
  summary: 'Allowed by filter rule 1 of 13. In the cache as a plain file: the web server answers it.',
  allowed: true,
  cacheable: true,
  inCache: 'plain',
  file: 'content/wknd/us/en.html',
  path: '/content/wknd/us/en.html',
  site: 'wknd',
  decidedBy: 'filter rule 1 of 13',
  sling: { path: '/content/wknd/us/en', selectors: [], extension: 'html', suffix: '' },
};

class Tester {
  asked: string[] = [];
  constructor(private readonly answer: { ok: boolean; message?: string; explain?: LiveCacheExplanation }) {}
  async caches() {
    return { caches: [cache], canClear: false };
  }
  async clearCache() {
    return { ok: true, message: '', caches: [cache] };
  }
  async explainUrl(id: string, via: string | undefined, url: string, options?: { method?: string; host?: string }) {
    this.asked.push(`${via}/${id} ${options?.method} ${options?.host} ${url}`);
    return this.answer;
  }
}

const open = async (client: Tester) => {
  render(
    <Provider theme={defaultTheme}>
      <LiveCaches client={client} tick={0} />
    </Provider>,
  );
  fireEvent.click(await screen.findByRole('button', { name: 'Test a URL' }));
  return screen.findByRole('dialog');
};

describe('the URL tester', () => {
  it('asks the cache the way it was reported, with the host and the method, and says what it would do', async () => {
    const client = new Tester({ ok: true, explain: page });
    const dialog = await open(client);
    expect(within(dialog).queryByRole('button', { name: 'Clear cache' })).toBeNull();
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'URL' }), { target: { value: 'content/wknd/us/en.html' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Test' }));
    await waitFor(() => expect(client.asked).toEqual(['publish/live_cache GET wknd.localhost /content/wknd/us/en.html']));
    expect(await within(dialog).findByText('Served by the web server')).toBeTruthy();
    expect(within(dialog).getByText(/Allowed by filter rule 1 of 13\. In the cache/)).toBeTruthy();
    expect(within(dialog).getByText('/content/wknd/us/en · extension html')).toBeTruthy();
    expect(within(dialog).getByText(/a file the web server serves/)).toBeTruthy();
  });

  it('shows what the cache could not answer', async () => {
    const dialog = await open(new Tester({ ok: false, message: 'The cache could not be asked: HTTP 403' }));
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'URL' }), { target: { value: '/x.html' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Test' }));
    expect(await within(dialog).findByText(/HTTP 403/)).toBeTruthy();
  });

  it('puts the outcome in one word', () => {
    expect(verdict({ summary: '', allowed: false, answer: 404 }).label).toBe('Refused (404)');
    expect(verdict({ summary: '', allowed: true, answer: 301, location: '/a.html' }).tone).toBe('redirect');
    expect(verdict({ summary: '', allowed: true, cacheable: false, inCache: 'plain' }).label).toBe('Always asked of the publish');
    expect(verdict({ summary: '', allowed: true, cacheable: true, inCache: null }).label).toBe('Fetched and kept');
    expect(verdict({ summary: '', allowed: true, cacheable: true, inCache: 'held' }).label).toBe('Answered from the cache');
    expect(verdict({ summary: '', allowed: true, cacheable: true, inCache: 'stale' }).label).toBe('Fetched again when asked');
    expect(slingParts({ summary: '', sling: { path: '/c/p', selectors: ['image', 'coreimg'], extension: 'jpeg', suffix: '/x.jpeg' } })).toBe(
      '/c/p · selectors image.coreimg · extension jpeg · suffix /x.jpeg',
    );
  });
});
