import { afterEach, describe, expect, it } from 'vitest';
import { applyTheme, readTheme, storeTheme, THEME_KEY, THEMES } from './pref';

afterEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.studioTheme;
});

describe('theme preference', () => {
  it('is Proof until one is chosen, and ignores a value it does not know', () => {
    expect(readTheme()).toBe('proof');
    window.localStorage.setItem(THEME_KEY, 'neon');
    expect(readTheme()).toBe('proof');
  });

  it('stores the choice and puts it on the document', () => {
    storeTheme('ledger');
    expect(readTheme()).toBe('ledger');
    expect(document.documentElement.dataset.studioTheme).toBe('ledger');
  });

  it('keeps nothing for the default', () => {
    storeTheme('night');
    storeTheme('proof');
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull();
    expect(document.documentElement.dataset.studioTheme).toBe('proof');
  });

  it('has three themes, each applicable', () => {
    expect(THEMES.map((t) => t.id)).toEqual(['proof', 'ledger', 'night']);
    applyTheme('night');
    expect(document.documentElement.dataset.studioTheme).toBe('night');
  });
});
