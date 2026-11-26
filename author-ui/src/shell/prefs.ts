import { HOME, paneOf, placeOf, type ShellState } from './tabs';
import { placeOfUrl, type Place } from './places';

/**
 * Where a new tab opens (the + of the tab strip, Alt+T), per browser: Home, a copy of where you are,
 * or always the same screen. Chosen in the user menu.
 */
export type NewTabPref = { kind: 'home' } | { kind: 'current' } | { kind: 'fixed'; url: string; title: string };

export const NEWTAB_KEY = 'studio.newtab';

export function readNewTab(): NewTabPref {
  try {
    const v = JSON.parse(window.localStorage.getItem(NEWTAB_KEY) ?? 'null') as NewTabPref | null;
    if (v && v.kind === 'current') return { kind: 'current' };
    if (v && v.kind === 'fixed' && typeof v.url === 'string') return { kind: 'fixed', url: v.url, title: typeof v.title === 'string' ? v.title : v.url };
  } catch {
    /* the default */
  }
  return { kind: 'home' };
}

export function storeNewTab(pref: NewTabPref) {
  try {
    if (pref.kind === 'home') window.localStorage.removeItem(NEWTAB_KEY);
    else window.localStorage.setItem(NEWTAB_KEY, JSON.stringify(pref));
  } catch {
    /* per-viewer convenience only */
  }
}

/** The place a new tab opens at, for the preference and the window as it is. */
export function newTabPlace(state: ShellState, pref: NewTabPref = readNewTab()): Place {
  if (pref.kind === 'current') return placeOf(paneOf(state));
  if (pref.kind === 'fixed') return placeOfUrl(pref.url);
  return HOME;
}
