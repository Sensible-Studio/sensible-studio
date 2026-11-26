import { describe, expect, it } from 'vitest';
import { byPage } from './referenceGroups';

describe('byPage', () => {
  it('groups references by the page they are on', () => {
    const pages = byPage([
      { path: '/content/a/jcr:content/root/teaser', property: 'linkURL', page: '/content/a', pageTitle: 'A', value: '/content/x' },
      { path: '/content/a/jcr:content/root/text', property: 'text', page: '/content/a', pageTitle: 'A', value: '<a href="/content/x.html">' },
      { path: '/conf/site/settings/x', property: 'path', value: '/content/x' },
    ]);
    expect(pages).toEqual([
      { page: '/conf/site/settings/x', title: 'x', properties: ['path'], count: 1 },
      { page: '/content/a', title: 'A', properties: ['linkURL', 'text'], count: 2 },
    ]);
  });
});
