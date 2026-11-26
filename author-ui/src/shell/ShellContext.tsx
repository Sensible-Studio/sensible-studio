import { createContext, useCallback, useContext, useEffect, type AnchorHTMLAttributes, type MouseEvent, type ReactNode } from 'react';
import { matchPlace, urlOf, type Place, type PlaceKind } from './places';
import type { ShellAction, ShellState } from './tabs';

/**
 * How screens move, in the shell and out of it. `useNavigate()` is the one way to go somewhere: in the
 * shell a place opens in the current tab (or a new one); outside it — a screen not converted yet, or a
 * URL that is not a place at all — it is the page load it always was. `<Link>` is an `<a href>` that does
 * the same on a click, so ⌘-click, middle-click and "Open in New Tab" keep working as in any browser.
 */
export interface NavigateOptions {
  newTab?: boolean;
  replace?: boolean;
  /** The pane to open in (a tab id, or `<tab id>#2` for the second pane of a split tab); the focused one otherwise. */
  tab?: string;
}

export type NavTarget = Place | string;

export interface ShellApi {
  state: ShellState;
  dispatch: (action: ShellAction) => void;
  /** The kinds the shell renders itself; the others load their screen as before. */
  kinds: ReadonlySet<PlaceKind>;
  navigate: (target: NavTarget, options?: NavigateOptions) => void;
  /** Full screen: the active view alone, the bar with the tabs hidden. */
  full: boolean;
  /** Full screen for the active tab's current place; it ends when that place or tab changes. */
  enterFull: () => void;
  /** Back to the normal layout (the bar with the tabs); the view stays where it is. */
  exitFull: () => void;
}

export const ShellContext = createContext<ShellApi | null>(null);

/** The shell, or null on a screen that still runs on its own. */
export const useShell = () => useContext(ShellContext);

export interface TabApi {
  id: string;
  place: Place;
  active: boolean;
  /** The tab has an entry before this one (Back and `leave` would go there). */
  canBack: boolean;
  /** The tab moved (the view navigated inside itself): pushed, or replacing the current entry. */
  setPlace: (place: Place, options?: { replace?: boolean }) => void;
  /** A better name for the tab than the place gives (the page's title once it is loaded). */
  setTitle: (title: string | undefined) => void;
  /**
   * A form is done (saved, cancelled): go back to where the author came from — the previous entry of the
   * tab — or, when the form was the first thing opened in this tab, to `fallback` in its place.
   */
  leave: (fallback: NavTarget) => void;
  /**
   * A form with unsaved changes guards its tab: before the shell moves the tab away from it (Back,
   * Forward, a link, closing the tab) it asks the guard, which resolves true to go on, false to stay.
   * Null lifts the guard; a view's unmount lifts it too.
   */
  setGuard: (guard: (() => Promise<boolean>) | null) => void;
  /** Close this tab (a document's own Close, when it was opened in a tab of its own). */
  close: () => void;
}

export const TabContext = createContext<TabApi | null>(null);

/** The tab a view lives in, or null outside the shell. */
export const useTab = () => useContext(TabContext);

/**
 * What a screen is called: the tab's title in the shell, the window's title outside it. `full` is the
 * window title (defaults to `<short> · Sensible Studio`); null leaves the title as it is (still loading).
 */
export function useScreenTitle(short: string | null | undefined, full?: string | null): void {
  const tab = useTab();
  useEffect(() => {
    if (!short) return;
    if (tab) tab.setTitle(short);
    else document.title = full ?? `${short} · Sensible Studio`;
    // the tab object changes on every shell render; only its identity matters here
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [short, full, tab?.id]);
}

/** A page load, as navigation was before the shell. */
export function load(target: NavTarget, options?: NavigateOptions): void {
  const url = typeof target === 'string' ? target : urlOf(target);
  if (options?.newTab) {
    if (!window.open(url, '_blank')) window.location.href = url;
  } else if (options?.replace) {
    window.location.replace(url);
  } else {
    window.location.href = url;
  }
}

/** The place a target names, when the shell renders that kind; null means "load it the old way". */
export function shellPlaceOf(target: NavTarget, kinds: ReadonlySet<PlaceKind>): Place | null {
  const place = typeof target === 'string' ? matchPlace(target) : target;
  return place && kinds.has(place.kind) ? place : null;
}

export function useNavigate(): (target: NavTarget, options?: NavigateOptions) => void {
  const shell = useShell();
  // a view navigates in its own pane, not in whichever pane happens to be focused
  const tab = useContext(TabContext);
  const pane = tab?.id;
  return useCallback((target, options) => (shell ? shell.navigate(target, pane && !options?.newTab ? { tab: pane, ...options } : options) : load(target, options)), [shell, pane]);
}

interface LinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> {
  to: NavTarget;
  /** Always in a new tab, as `target="_blank"` would. */
  newTab?: boolean;
  children?: ReactNode;
}

/** Decides whether a click asks for a new tab the way browsers do (⌘/Ctrl, the middle button). */
export const wantsNewTab = (e: { metaKey: boolean; ctrlKey: boolean; button: number }) => e.metaKey || e.ctrlKey || e.button === 1;

export function Link({ to, newTab, onClick, children, ...rest }: LinkProps) {
  const navigate = useNavigate();
  const shell = useShell();
  const url = typeof to === 'string' ? to : urlOf(to);
  const handle = (e: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(e);
    if (e.defaultPrevented || e.shiftKey || e.altKey) return; // shift/alt: the browser's own new window / download
    if (!shell || !shellPlaceOf(to, shell.kinds)) {
      if (newTab) {
        e.preventDefault();
        load(url, { newTab: true });
      }
      return; // a plain link to a screen outside the shell: the browser loads it
    }
    e.preventDefault();
    navigate(to, { newTab: newTab || wantsNewTab(e) });
  };
  return (
    <a href={url} onClick={handle} onAuxClick={(e) => e.button === 1 && handle(e)} {...rest}>
      {children}
    </a>
  );
}
