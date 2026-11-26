import { beforeEach, describe, expect, it } from 'vitest';
import { newTabPlace, readNewTab, storeNewTab } from './prefs';
import { boot } from './tabs';
import { urlOf } from './places';
import { takesSidebar, treePathOf } from './Sidebar';
import { movedBefore } from '../start/YourWork';

describe('where a new tab opens', () => {
  beforeEach(() => window.localStorage.clear());

  it('is Home unless the author chose otherwise', () => {
    const state = boot(null, { kind: 'sites', path: '/content/wknd' });
    expect(readNewTab()).toEqual({ kind: 'home' });
    expect(newTabPlace(state).kind).toBe('start');
    storeNewTab({ kind: 'current' });
    expect(urlOf(newTabPlace(state))).toBe('/sites.html/content/wknd');
    storeNewTab({ kind: 'fixed', url: '/assets.html/content/dam', title: 'Media' });
    expect(urlOf(newTabPlace(state))).toBe('/assets.html/content/dam');
    storeNewTab({ kind: 'home' });
    expect(readNewTab()).toEqual({ kind: 'home' });
    window.localStorage.setItem('studio.newtab', '{broken');
    expect(readNewTab()).toEqual({ kind: 'home' });
  });
});

describe('the sidebar', () => {
  it('knows the content path of a place', () => {
    expect(treePathOf({ kind: 'sites', path: '/content/wknd/en' })).toBe('/content/wknd/en');
    expect(treePathOf({ kind: 'editor', path: '/content/wknd/en/faqs.html' })).toBe('/content/wknd/en/faqs');
    expect(treePathOf({ kind: 'assets', route: { kind: 'browse', path: '/content/dam/wknd' } })).toBe('/content/dam/wknd');
    expect(treePathOf({ kind: 'assets', route: { kind: 'profiles' } })).toBe('');
    expect(treePathOf({ kind: 'start' } as never)).toBe('');
  });

  it('is for the document screens, not for the consoles or Home', () => {
    expect(takesSidebar({ kind: 'editor', path: '/content/wknd/en' })).toBe(true);
    expect(takesSidebar({ kind: 'cf-editor', path: '/content/dam/x' })).toBe(true);
    expect(takesSidebar({ kind: 'cf-model-editor', path: '/conf/x' })).toBe(true);
    expect(takesSidebar({ kind: 'sites', path: '/content' })).toBe(false);
    expect(takesSidebar({ kind: 'replication' })).toBe(false);
    expect(takesSidebar({ kind: 'start' })).toBe(false);
  });
});

describe('reordering by drag', () => {
  it('puts the dragged row where the target is', () => {
    expect(movedBefore(['a', 'b', 'c', 'd'], 'a', 'c')).toEqual(['b', 'c', 'a', 'd']);
    expect(movedBefore(['a', 'b', 'c', 'd'], 'd', 'b')).toEqual(['a', 'd', 'b', 'c']);
    expect(movedBefore(['a', 'b'], 'a', 'a')).toEqual(['a', 'b']);
    expect(movedBefore(['a', 'b'], 'x', 'a')).toEqual(['a', 'b']);
  });
});
