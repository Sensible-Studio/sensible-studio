import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Link, LinkCheckerClient, LinkDetails, LinkList, ListQuery } from './api';
import { ownerUrl } from './api';
import { LinkCheckerConsole } from './LinkCheckerConsole';

const LINKS: Link[] = [
  { url: 'https://gone.example/page', kind: 'external', status: 'invalid', code: 404, error: '404 Not Found', referrerCount: 2, owners: ['/content/wknd/us/en/faqs'], lastChecked: new Date().toISOString() },
  { url: '/content/wknd/us/en/old.html', kind: 'internal', status: 'invalid', error: 'Not found in the repository', referrerCount: 1, owners: ['/content/wknd/us/en'] },
  { url: 'https://new.example/', kind: 'external', status: 'pending', referrerCount: 1, owners: ['/content/wknd/us/en'] },
  { url: 'https://ok.example/', kind: 'external', status: 'valid', code: 200, referrerCount: 1, owners: ['/content/wknd/us/en'] },
];

class FakeClient implements LinkCheckerClient {
  calls: string[] = [];
  async list(q: ListQuery): Promise<LinkList> {
    this.calls.push(`list ${q.status ?? 'all'} ${q.kind ?? 'all'}`);
    const links = LINKS.filter((l) => (!q.status || q.status === 'all' || l.status === q.status) && (!q.kind || q.kind === 'all' || l.kind === q.kind));
    return {
      links,
      total: links.length,
      counts: { all: 4, invalid: 2, pending: 1, valid: 1 },
      progress: { running: false, done: 0, total: 0 },
      checkPeriodHours: 24,
    };
  }
  async info(url: string): Promise<LinkDetails> {
    const l = LINKS.find((x) => x.url === url)!;
    return { ...l, referrers: [{ owner: '/content/wknd/us/en/faqs', ownerTitle: 'FAQs', ownerKind: 'page', node: '/content/wknd/us/en/faqs/jcr:content/root/text', component: 'root/text', property: 'text' }] };
  }
  async scan() {
    this.calls.push('scan');
    return true;
  }
  async check(urls: string[]) {
    this.calls.push(`check ${urls.join(',')}`);
    return true;
  }
  async checkAll() {
    this.calls.push('checkAll');
    return true;
  }
  async remove(urls: string[]) {
    this.calls.push(`remove ${urls.join(',')}`);
    return urls.length;
  }
}

function mount(client: LinkCheckerClient) {
  return render(
    <Provider theme={defaultTheme}>
      <LinkCheckerConsole client={client} />
    </Provider>,
  );
}

describe('link checker console', () => {
  afterEach(cleanup);

  it('lists the links, broken first, with the counts per status', async () => {
    mount(new FakeClient());
    expect(await screen.findByText('https://gone.example/page')).toBeTruthy();
    expect(screen.getByText('404 Not Found')).toBeTruthy();
    expect(screen.getByText('Not found in the repository')).toBeTruthy();
    expect(screen.getByRole('radio', { name: 'Broken 2' })).toBeTruthy();
    expect(screen.getByText('2 places')).toBeTruthy();
  });

  it('filters by status and kind', async () => {
    const client = new FakeClient();
    mount(client);
    await screen.findByText('https://gone.example/page');
    fireEvent.click(screen.getByRole('radio', { name: 'Not checked 1' }));
    await waitFor(() => expect(screen.queryByText('https://gone.example/page')).toBeNull());
    expect(screen.getByText('https://new.example/')).toBeTruthy();
    expect(client.calls).toContain('list pending all');
  });

  it('shows where a link is used and checks it again', async () => {
    const client = new FakeClient();
    mount(client);
    fireEvent.click(await screen.findByText('https://gone.example/page'));
    const panel = await screen.findByRole('complementary', { name: 'Link details' });
    expect(await within(panel).findByText('FAQs')).toBeTruthy();
    expect(within(panel).getByText('root/text · text')).toBeTruthy();
    await act(async () => {
      fireEvent.click(within(panel).getByRole('button', { name: 'Check again' }));
    });
    expect(client.calls).toContain('check https://gone.example/page');
  });

  it('checks or removes the selection, and scans content', async () => {
    const client = new FakeClient();
    mount(client);
    await screen.findByText('https://gone.example/page');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select https://ok.example/' }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Remove from list' }));
    });
    expect(client.calls).toContain('remove https://ok.example/');
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Scan content/ }));
    });
    expect(client.calls).toContain('scan');
  });

  it('opens what a link is written in where it is edited', () => {
    expect(ownerUrl({ owner: '/content/wknd/us/en', ownerKind: 'page' })).toBe('/editor.html/content/wknd/us/en.html');
    expect(ownerUrl({ owner: '/content/dam/a.jpg', ownerKind: 'media' })).toBe('/assetdetails.html/content/dam/a.jpg');
    expect(ownerUrl({ owner: '/content/dam/r', ownerKind: 'record' })).toBe('/editor.html/content/dam/r');
  });
});
