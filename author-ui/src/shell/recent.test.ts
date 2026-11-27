import { beforeEach, describe, expect, it } from 'vitest';
import { clearRecent, forgetVisit, iconOfUrl, isPinned, pin, readPinned, readRecent, recordVisit, reorderPins, RECENT_MAX, togglePin, unpin } from './recent';

describe('recent and pinned screens', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('keeps the last visit of each screen, newest first, up to the limit', () => {
    recordVisit('/sites.html/content', 'Pages', 1);
    recordVisit('/assets.html/content/dam', 'Media', 2);
    recordVisit('/sites.html/content', 'Pages · wknd', 3);
    expect(readRecent().map((v) => [v.url, v.title])).toEqual([
      ['/sites.html/content', 'Pages · wknd'],
      ['/assets.html/content/dam', 'Media'],
    ]);
    for (let i = 0; i < RECENT_MAX + 5; i++) recordVisit(`/editor.html/content/p${i}.html`, `p${i}`, 10 + i);
    expect(readRecent()).toHaveLength(RECENT_MAX);
    expect(readRecent()[0].url).toBe(`/editor.html/content/p${RECENT_MAX + 4}.html`);
    forgetVisit(readRecent()[0].url);
    expect(readRecent()[0].url).toBe(`/editor.html/content/p${RECENT_MAX + 3}.html`);
    clearRecent();
    expect(readRecent()).toEqual([]);
  });

  it('pins once, unpins, toggles and reorders', () => {
    pin('/replication.html', 'Publishing');
    pin('/replication.html', 'Publishing');
    pin('/health.html', 'Health');
    expect(readPinned().map((p) => p.url)).toEqual(['/replication.html', '/health.html']);
    expect(isPinned('/health.html')).toBe(true);
    reorderPins(['/health.html', '/replication.html']);
    expect(readPinned().map((p) => p.title)).toEqual(['Health', 'Publishing']);
    togglePin('/health.html', 'Health');
    expect(isPinned('/health.html')).toBe(false);
    unpin('/replication.html');
    expect(readPinned()).toEqual([]);
  });

  it('survives a broken store', () => {
    window.localStorage.setItem('studio.recent', '{not json');
    window.localStorage.setItem('studio.pinned', '[1, {"nope": true}, {"url": "/health.html", "title": "Health"}]');
    expect(readRecent()).toEqual([]);
    expect(readPinned()).toEqual([{ url: '/health.html', title: 'Health' }]);
  });

  it('knows the icon of a screen from its URL', () => {
    expect(iconOfUrl('/sites.html/content/wknd')).toBe('sites');
    expect(iconOfUrl('/sites.html/content/forms/wknd')).toBe('forms');
    expect(iconOfUrl('/editor.html/content/experience-fragments/x/master.html')).toBe('xf');
    expect(iconOfUrl('/assets.html/content/dam')).toBe('assets');
    expect(iconOfUrl('/replication.html')).toBe('live');
    expect(iconOfUrl('/studio/health.html')).toBe('health');
  });

  it('keeps one visit of a screen that completes its own address', () => {
    window.localStorage.clear();
    recordVisit('/audiences.html', 'Audiences', 1000);
    recordVisit('/audiences.html/conf/global', 'Audiences', 1400);
    expect(readRecent().map((v) => v.url)).toEqual(['/audiences.html/conf/global']);
    // later, or under another title, it is another visit
    recordVisit('/audiences.html', 'Audiences', 60000);
    expect(readRecent()).toHaveLength(2);
  });
});
