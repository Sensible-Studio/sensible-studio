import { describe, expect, it } from 'vitest';
import { collectPages, toCsv } from './CsvReport';
import type { SiteItem, SiteListing, StudioClient } from '../editor/api/StudioClient';

const page = (path: string, extra: Partial<SiteItem> = {}): SiteItem => ({ path, name: path.split('/').pop()!, title: path.split('/').pop()!, type: 'page', hasChildren: false, ...extra });

const tree: Record<string, SiteItem[]> = {
  '/content/site/en': [page('/content/site/en/about', { hasChildren: true, title: 'About, us' }), page('/content/site/en/faqs')],
  '/content/site/en/about': [page('/content/site/en/about/team')],
};
const client = { siteChildren: async (p: string) => ({ item: page(p), trail: [], children: tree[p] ?? [] }) as SiteListing } as unknown as StudioClient;

describe('CSV Report', () => {
  it('lists direct children, or more levels, parents first', async () => {
    expect((await collectPages(client, '/content/site/en', 1)).map((r) => r.item.path)).toEqual(['/content/site/en/about', '/content/site/en/faqs']);
    expect((await collectPages(client, '/content/site/en', 2)).map((r) => r.item.path)).toEqual(['/content/site/en/about', '/content/site/en/about/team', '/content/site/en/faqs']);
  });

  it('writes Title and Path, the chosen columns, quoted where needed', async () => {
    const rows = await collectPages(client, '/content/site/en', 1);
    rows[1].item.publication = 'published';
    rows[1].item.lastPublished = '2026-11-20T10:00:00.000Z';
    const csv = toCsv(rows, ['name', 'published', 'workflow', 'translated'], { '/content/site/en/faqs': 'Request for Review' }, { '/content/site/en/about': 'approved' });
    expect(csv.startsWith('﻿')).toBe(true);
    expect(csv.slice(1).split('\r\n')).toEqual([
      'Title,Path,Name,Published,Flow,Translated',
      '"About, us",/content/site/en/about,about,never,,approved',
      'faqs,/content/site/en/faqs,faqs,published 2026-11-20 10:00:00,Request for Review,',
      '',
    ]);
  });
});
