import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ActivityConsole, openUrl } from './ActivityConsole';

const ENTRIES = [
  { id: 'd/1', at: Date.now() - 60000, user: 'ana', type: 'published', path: '/content/wknd/en/faqs', kind: 'page', title: 'FAQs', exists: true },
  { id: 'd/2', at: Date.now() - 120000, user: 'bob', type: 'deleted', path: '/content/dam/old.png', kind: 'media', title: 'old.png', exists: false },
];

function answer(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));
}

afterEach(cleanup);

describe('activity console', () => {
  it('lists who did what, links what still exists, and filters by person on a click', async () => {
    const fetcher = vi.fn((_url: RequestInfo | URL) => answer({ entries: ENTRIES, more: false, keepDays: 90 }));
    render(
      <Provider theme={defaultTheme}>
        <ActivityConsole fetcher={fetcher as unknown as typeof fetch} />
      </Provider>,
    );
    expect((await screen.findByRole('link', { name: 'FAQs' })).getAttribute('href')).toBe('/editor.html/content/wknd/en/faqs.html');
    expect(screen.getByText('old.png').closest('a')).toBeNull();
    expect(screen.getByText('Kept for 90 days')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'ana' }));
    await waitFor(() => expect(fetcher.mock.calls.some(([u]) => String(u).includes('user=ana'))).toBe(true));
    fireEvent.click(screen.getByRole('radio', { name: 'Published' }));
    await waitFor(() => expect(fetcher.mock.calls.some(([u]) => String(u).includes('type=published'))).toBe(true));
  });

  it('says so when the log is not for this user', async () => {
    render(
      <Provider theme={defaultTheme}>
        <ActivityConsole fetcher={(() => answer({ error: 'The activity log is for administrators' }, 403)) as unknown as typeof fetch} />
      </Provider>,
    );
    expect(await screen.findByText('The activity log is for administrators')).toBeTruthy();
  });

  it('opens media in its details and the rest in the editor', () => {
    expect(openUrl({ kind: 'media', path: '/content/dam/a.png' })).toBe('/assetdetails.html/content/dam/a.png');
    expect(openUrl({ kind: 'block', path: '/content/experience-fragments/x/master' })).toBe('/editor.html/content/experience-fragments/x/master.html');
  });
});
