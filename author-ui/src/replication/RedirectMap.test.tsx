import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { RedirectMapDialog, parseRedirectMap, type RedirectMapIO } from './RedirectMap';

afterEach(cleanup);

describe('a redirect map', () => {
  it('is read line by line as the cache reads it', () => {
    const { rules, bad } = parseRedirectMap(
      '# c\n\n/a /b\n/c https://x.test/y 307\n/a /other\nrelative /x\n/d //evil.test\n/e /f 200\n/g /h 301 extra\n/i javascript:x\n/j /k|l\n',
    );
    expect(rules).toEqual([
      { source: '/a', target: '/b', status: 301 },
      { source: '/c', target: 'https://x.test/y', status: 307 },
    ]);
    expect(bad.map((b) => b.line)).toEqual([6, 7, 8, 9, 10, 11]);
    expect(bad[0].why).toBe('the source is not a path');
    expect(bad[2].why).toMatch(/the status is not/);
  });

  it('is edited, checked, saved and published', async () => {
    const calls: string[] = [];
    let stored = '/old.html /new.html\n';
    const io: RedirectMapIO = {
      read: async () => stored,
      save: async (map, text) => {
        calls.push(`save ${map}`);
        stored = text;
      },
      publish: async (map) => {
        calls.push(`publish ${map}`);
      },
    };
    let closed = false;
    render(
      <Provider theme={defaultTheme}>
        <RedirectMapDialog map="/content/dam/wknd/redirectmap.txt" site="wknd" io={io} onClose={() => (closed = true)} />
      </Provider>,
    );
    const area = (await screen.findByLabelText('Redirect rules')) as HTMLTextAreaElement;
    expect(area.value).toBe('/old.html /new.html\n');
    expect(screen.getByText(/^1 redirect\./)).toBeTruthy();
    fireEvent.change(area, { target: { value: '/old.html /new.html\nnot-a-path /x\n' } });
    expect(await screen.findByText(/Line 2: the source is not a path/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save and publish' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(area, { target: { value: '/old.html /new.html\n/promo /deals.html 302\n' } });
    await waitFor(() => expect(screen.getByText(/^2 redirects\./)).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Save and publish' }));
    await waitFor(() => expect(closed).toBe(true));
    expect(calls).toEqual(['save /content/dam/wknd/redirectmap.txt', 'publish /content/dam/wknd/redirectmap.txt']);
    expect(stored).toContain('/promo /deals.html 302');
  });
});
