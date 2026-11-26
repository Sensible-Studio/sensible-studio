/**
 * The colour theme, per browser. Each theme is a set of values for the same variables (theme.scss),
 * switched by `data-studio-theme` on the document; the default has no attribute value of its own.
 */
export const THEMES = [
  { id: 'proof', label: 'Proof', description: 'Cool light workspace, ink-blue accent' },
  { id: 'ledger', label: 'Ledger', description: 'Warm light workspace, burgundy accent' },
  { id: 'night', label: 'Night Desk', description: 'Dark chrome, light working canvas' },
] as const;

export type ThemeId = (typeof THEMES)[number]['id'];

export const THEME_KEY = 'studio.theme';
export const DEFAULT_THEME: ThemeId = 'proof';

const isTheme = (v: unknown): v is ThemeId => THEMES.some((t) => t.id === v);

export function readTheme(): ThemeId {
  try {
    const v = window.localStorage.getItem(THEME_KEY);
    if (isTheme(v)) return v;
  } catch {
    /* the default */
  }
  return DEFAULT_THEME;
}

export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.studioTheme = id;
}

export function storeTheme(id: ThemeId) {
  applyTheme(id);
  try {
    if (id === DEFAULT_THEME) window.localStorage.removeItem(THEME_KEY);
    else window.localStorage.setItem(THEME_KEY, id);
  } catch {
    /* per-viewer convenience only */
  }
}
