import { describe, expect, it } from 'vitest';
import { OWNER_KEY, takeOver } from './owner';
import { PINNED_KEY, RECENT_KEY } from './recent';
import { STORAGE_KEY } from './tabs';

function store(init: Record<string, string>) {
  const m = new Map(Object.entries(init));
  return { m, getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) };
}

describe('whose tabs the browser keeps', () => {
  const kept = { [STORAGE_KEY]: 'tabs', [RECENT_KEY]: 'recent', [PINNED_KEY]: 'pinned', 'studio.theme': 'night' };

  it('keeps them for the same person', () => {
    const s = store({ ...kept, [OWNER_KEY]: 'ana' });
    expect(takeOver(s, 'ana')).toBe(false);
    expect(s.m.get(STORAGE_KEY)).toBe('tabs');
  });

  it('puts them away when somebody else signs in, and leaves the look of the screens', () => {
    const s = store({ ...kept, [OWNER_KEY]: 'ana' });
    expect(takeOver(s, 'dan')).toBe(true);
    expect([s.m.has(STORAGE_KEY), s.m.has(RECENT_KEY), s.m.has(PINNED_KEY)]).toEqual([false, false, false]);
    expect(s.m.get('studio.theme')).toBe('night');
    expect(s.m.get(OWNER_KEY)).toBe('dan');
  });

  it('takes what was kept before anyone was noted to be this person\'s', () => {
    const s = store(kept);
    expect(takeOver(s, 'ana')).toBe(false);
    expect(s.m.get(RECENT_KEY)).toBe('recent');
    expect(s.m.get(OWNER_KEY)).toBe('ana');
  });

  it('does nothing without a session', () => {
    const s = store({ ...kept, [OWNER_KEY]: 'ana' });
    expect(takeOver(s, 'anonymous')).toBe(false);
    expect(takeOver(s, null)).toBe(false);
    expect(s.m.get(OWNER_KEY)).toBe('ana');
  });
});
