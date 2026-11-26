import { describe, expect, it } from 'vitest';
import { assetsRoute, routeUrl } from './route';
import { byCreated, expiresIn } from './sharedLinks';

describe('shared links', () => {
  const now = Date.parse('2026-11-23T12:00:00Z');

  it('says when a link stops working', () => {
    expect(expiresIn('2026-11-30T12:00:00Z', now)).toBe('in 7 days');
    expect(expiresIn('2026-11-24T11:00:00Z', now)).toBe('in 23 hours');
    expect(expiresIn('2026-11-23T12:20:00Z', now)).toBe('within the hour');
    expect(expiresIn('2026-11-23T11:00:00Z', now)).toBe('expired');
  });

  it('lists the newest first', () => {
    const links = [
      { id: 'a', paths: [], expires: '', created: '2026-11-20T10:00:00Z' },
      { id: 'b', paths: [], expires: '', created: '2026-11-22T10:00:00Z' },
      { id: 'c', paths: [], expires: '' },
    ];
    expect([...links].sort(byCreated).map((l) => l.id)).toEqual(['b', 'a', 'c']);
  });

  it('has its own page in Media', () => {
    expect(routeUrl({ kind: 'sharedlinks' })).toBe('/mnt/overlay/dam/gui/content/sharedlinks.html/var/studio/dam/linkshare');
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/sharedlinks.html', hash: '' })).toEqual({ kind: 'sharedlinks' });
    expect(assetsRoute({ pathname: routeUrl({ kind: 'sharedlinks' }), hash: '' })).toEqual({ kind: 'sharedlinks' });
  });
});
