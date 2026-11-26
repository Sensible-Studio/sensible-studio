import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { StudioClient } from '../editor/api/StudioClient';
import { urlOf, type Place } from './places';
import { FullScreenButton } from './CloseButton';
import { Link, useTab } from './ShellContext';
import { STORAGE_KEY } from './tabs';

// The shell's mechanics, with a stand-in for the views: a line with the place and a link onwards.
vi.mock('./views', () => ({
  SHELL_KINDS: new Set(['start', 'sites', 'editor', 'page-properties']),
  KEEP_ALIVE: new Set(['sites', 'editor']),
  MAX_BACKGROUND_EDITORS: 3,
  View: ({ place }: { place: Place }) => {
    const tab = useTab()!;
    return (
      <div data-testid="view">
        <span data-testid="place">{urlOf(place)}</span>
        <Link to={{ kind: 'sites', path: `${place.kind === 'sites' ? place.path : '/content'}/next` }}>next</Link>
        <Link to="/assets.html/content/dam">assets</Link>
        <Link to={{ kind: 'page-properties', item: '/content/wknd/us' }}>properties</Link>
        <button type="button" onClick={() => tab.leave({ kind: 'sites', path: '/content/wknd' })}>
          done
        </button>
        <button type="button" onClick={() => tab.setGuard(() => Promise.resolve(window.guardAnswer))}>
          guard
        </button>
        <FullScreenButton />
      </div>
    );
  },
}));
vi.mock('../workflow/InboxBell', () => ({ InboxBell: () => null }));

declare global {
  interface Window {
    guardAnswer: boolean;
  }
}

const { Shell } = await import('./Shell');

const client = { currentUser: () => Promise.resolve('admin') } as unknown as StudioClient;
const at = (url: string) => window.history.replaceState(null, '', url);
const ui = (initial: Place) => render(<Provider theme={defaultTheme}>{<Shell client={client} initial={initial} />}</Provider>);
const visible = () => screen.getAllByTestId('view').find((el) => !el.closest('[hidden]'))!;
const placeShown = () => visible().querySelector('[data-testid="place"]')!.textContent;
const tabs = () => screen.getAllByRole('tab').map((t) => t.textContent);

describe('Shell', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
    at('/sites.html/content/wknd');
  });
  afterEach(cleanup);

  it('boots with one tab at the opened URL and leaves the URL alone', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    expect(tabs()).toHaveLength(1);
    expect(placeShown()).toBe('/sites.html/content/wknd');
    expect(window.location.pathname).toBe('/sites.html/content/wknd');
    expect(document.title).toBe('Pages · wknd · Sensible Studio');
    expect((screen.getByRole('button', { name: 'Back' }) as HTMLButtonElement).disabled).toBe(true);
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY)!).tabs).toHaveLength(1);
  });

  it('pushes a link on the tab, and Back and Forward walk it', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(screen.getByRole('link', { name: 'next' }));
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
    expect(window.location.pathname).toBe('/sites.html/content/wknd/next');
    expect(tabs()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(placeShown()).toBe('/sites.html/content/wknd');
    expect(window.location.pathname).toBe('/sites.html/content/wknd');
    fireEvent.click(screen.getByRole('button', { name: 'Forward' }));
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
  });

  it('opens a ⌘-clicked link in a new tab and keeps the first where it was', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(screen.getByRole('link', { name: 'next' }), { metaKey: true });
    expect(tabs()).toHaveLength(2);
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
    fireEvent.click(screen.getAllByRole('tab')[0]);
    expect(placeShown()).toBe('/sites.html/content/wknd');
    expect(window.location.pathname).toBe('/sites.html/content/wknd');
  });

  it('leaves links to screens outside the shell to the browser', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    const a = screen.getByRole('link', { name: 'assets' }) as HTMLAnchorElement;
    expect(a.getAttribute('href')).toBe('/assets.html/content/dam');
    const ev = fireEvent.click(a); // not prevented: the browser would load it
    expect(ev).toBe(true);
    expect(tabs()).toHaveLength(1);
  });

  it('adds a Start tab with + and closes tabs back to the neighbour', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(screen.getByRole('button', { name: 'New tab' }));
    expect(tabs()).toHaveLength(2);
    expect(placeShown()).toBe('/studio/start.html');
    fireEvent.click(screen.getByRole('button', { name: /^Close Home/ }));
    expect(tabs()).toHaveLength(1);
    expect(placeShown()).toBe('/sites.html/content/wknd');
  });

  it('follows the browser landing on a recorded entry', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(screen.getByRole('link', { name: 'next' }));
    const state = window.history.state as { studio: { tab: string; index: number } };
    expect(state.studio.index).toBe(1);
    act(() => {
      at('/sites.html/content/wknd');
      window.dispatchEvent(new PopStateEvent('popstate', { state: { studio: { tab: state.studio.tab, index: 0 } } }));
    });
    expect(placeShown()).toBe('/sites.html/content/wknd');
    expect((screen.getByRole('button', { name: 'Forward' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('restores the tabs on the next visit without duplicating the opened URL', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(screen.getByRole('link', { name: 'next' }), { metaKey: true });
    cleanup();
    at('/sites.html/content/wknd/next');
    ui({ kind: 'sites', path: '/content/wknd/next' });
    expect(tabs()).toHaveLength(2);
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
    cleanup();
    at('/studio/start.html');
    ui({ kind: 'start' });
    expect(tabs()).toHaveLength(3); // a URL nobody showed: one more tab
  });

  it('switches tabs with Alt+digit and goes back with Alt+Left', () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(screen.getByRole('link', { name: 'next' }));
    fireEvent.click(screen.getByRole('link', { name: 'next' }), { metaKey: true });
    fireEvent.keyDown(window, { code: 'Digit1', altKey: true });
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
    fireEvent.keyDown(window, { code: 'ArrowLeft', altKey: true });
    expect(placeShown()).toBe('/sites.html/content/wknd');
  });

  it('leaves a form back to the entry before it, or to its fallback when it was opened first', () => {
    ui({ kind: 'sites', path: '/content/wknd/us' });
    fireEvent.click(screen.getByRole('link', { name: 'properties' }));
    expect(placeShown()).toBe('/mnt/overlay/wcm/core/content/sites/properties.html?item=%2Fcontent%2Fwknd%2Fus');
    fireEvent.click(screen.getByRole('button', { name: 'done' }));
    expect(placeShown()).toBe('/sites.html/content/wknd/us');
    expect((screen.getByRole('button', { name: 'Forward' }) as HTMLButtonElement).disabled).toBe(false);
    cleanup();
    window.localStorage.clear();
    at('/mnt/overlay/wcm/core/content/sites/properties.html?item=%2Fcontent%2Fwknd%2Fus');
    ui({ kind: 'page-properties', item: '/content/wknd/us' });
    fireEvent.click(screen.getByRole('button', { name: 'done' }));
    expect(placeShown()).toBe('/sites.html/content/wknd');
    expect((screen.getByRole('button', { name: 'Back' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('asks a guarded tab before moving it away, and stays when the guard says no', async () => {
    ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(screen.getByRole('link', { name: 'next' }));
    fireEvent.click(screen.getByRole('button', { name: 'guard' }));
    window.guardAnswer = false;
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    await act(() => Promise.resolve());
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
    fireEvent.click(screen.getByRole('link', { name: 'next' }));
    await act(() => Promise.resolve());
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
    fireEvent.click(screen.getByRole('button', { name: /^Close/ }));
    await act(() => Promise.resolve());
    expect(tabs()).toHaveLength(1);
    window.guardAnswer = true;
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    await act(() => Promise.resolve());
    expect(placeShown()).toBe('/sites.html/content/wknd');
  });

  it('stays in full screen while the author moves on, until Exit full screen', () => {
    const bar = () => document.querySelector<HTMLElement>('header.studio-shellbar')!;
    const { unmount } = ui({ kind: 'sites', path: '/content/wknd' });
    fireEvent.click(within(visible()).getByRole('button', { name: 'Full screen' }));
    expect(bar().hidden).toBe(true);
    // a link, then the screen's own leave: still full screen
    fireEvent.click(within(visible()).getByRole('link', { name: 'next' }));
    expect(placeShown()).toBe('/sites.html/content/wknd/next');
    expect(bar().hidden).toBe(true);
    fireEvent.click(within(visible()).getByRole('link', { name: 'properties' }));
    fireEvent.click(within(visible()).getByRole('button', { name: 'done' }));
    expect(bar().hidden).toBe(true);
    // a reload keeps it
    unmount();
    ui({ kind: 'sites', path: '/content/wknd' });
    expect(bar().hidden).toBe(true);
    fireEvent.click(within(visible()).getByRole('button', { name: 'Exit full screen' }));
    expect(bar().hidden).toBe(false);
    unmount();
  });
});
