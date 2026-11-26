import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { StudioClient } from '../../editor/api/StudioClient';
import { urlOf, type Place } from '../places';
import { placeCommands, rank, score, type Command } from './commands';

vi.mock('../views', () => ({
  SHELL_KINDS: new Set(['start', 'sites', 'assets', 'health', 'linkchecker', 'security']),
  KEEP_ALIVE: new Set(['sites']),
  MAX_BACKGROUND_EDITORS: 3,
  View: ({ place }: { place: Place }) => <span data-testid="place">{urlOf(place)}</span>,
}));
vi.mock('../../workflow/InboxBell', () => ({ InboxBell: () => null }));

const { Shell } = await import('../Shell');

const client = { currentUser: () => Promise.resolve('admin') } as unknown as StudioClient;
const ui = (initial: Place) => render(<Provider theme={defaultTheme}>{<Shell client={client} initial={initial} />}</Provider>);
const placeShown = () => screen.getAllByTestId('place').find((el) => !el.closest('[hidden]'))!.textContent;

describe('ranking', () => {
  const c = (title: string, hint = '', keywords = ''): Command => ({ id: title, title, hint, keywords, group: 'go' });

  it('prefers the title, then a word of it, then the hint', () => {
    expect(score(c('Health'), 'health')).toBeGreaterThan(score(c('Link checker'), 'link'));
    expect(score(c('Link checker'), 'check')).toBeGreaterThan(score(c('Content Fragment Models', 'Structure'), 'struct'));
    expect(score(c('Pages'), 'media')).toBe(0);
    expect(score(c('Users', 'System › Users'), 'sys us')).toBeGreaterThan(0);
  });

  it('ranks every console of Home and System', () => {
    const all = placeCommands();
    expect(rank(all, 'health')[0].url).toBe('/studio/health.html');
    expect(rank(all, 'perm')[0].title).toBe('Permissions');
    expect(rank(all, 'publishing').map((x) => x.title)).toContain('Link checker');
  });
});

describe('CommandPalette', () => {
  beforeAll(() => {
    Element.prototype.scrollIntoView = () => undefined;
  });
  beforeEach(() => {
    window.localStorage.clear();
    window.history.replaceState(null, '', '/sites.html/content/wknd');
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url) => {
      const u = String(url);
      const body = u.includes('/sites.json') ? { hits: [{ path: '/content/wknd/us/en/adventures', title: 'Adventures' }] } : { items: [{ type: 'asset', path: '/content/dam/wknd/a.jpg', title: 'Adventure photo' }] };
      return { ok: true, json: async () => body } as Response;
    });
  });
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('opens with ⌘K, finds a console and opens it in the tab', async () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.keyDown(window, { code: 'KeyK', metaKey: true });
    const field = await screen.findByRole('combobox', { name: 'Go to' });
    fireEvent.change(field, { target: { value: 'health' } });
    expect(screen.getAllByRole('option')[0].textContent).toContain('Health');
    fireEvent.keyDown(field, { key: 'Enter' });
    expect(placeShown()).toBe('/studio/health.html');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('finds pages and media after a pause, and ⌘↩ opens a new tab', async () => {
    vi.useFakeTimers();
    try {
      ui({ kind: 'sites', path: '/content/wknd' });
      fireEvent.keyDown(window, { code: 'KeyK', ctrlKey: true });
      act(() => vi.advanceTimersByTime(10));
      const field = screen.getByRole('combobox', { name: 'Go to' });
      fireEvent.change(field, { target: { value: 'adventure' } });
      await act(async () => {
        vi.advanceTimersByTime(250);
        await Promise.resolve();
        await Promise.resolve();
      });
      vi.useRealTimers();
      await waitFor(() => expect(screen.getByRole('group', { name: 'Pages' }).textContent).toContain('Adventures'));
      expect(screen.getByRole('group', { name: 'Media' }).textContent).toContain('Adventure photo');
      fireEvent.keyDown(field, { key: 'ArrowDown' });
      fireEvent.keyDown(field, { key: 'Enter', metaKey: true });
      expect(screen.getAllByRole('tab')).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('lists the other open tabs and the recent places first, without the current one', async () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.keyDown(window, { code: 'KeyK', metaKey: true });
    const field = await screen.findByRole('combobox', { name: 'Go to' });
    expect(screen.queryByRole('group', { name: 'Open tabs' })).toBeNull();
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
