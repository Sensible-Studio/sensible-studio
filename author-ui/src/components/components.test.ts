import { describe, expect, it } from 'vitest';
import { componentOfUrl, componentUrl, filterComponents, groupsOf, type ComponentSummary } from './api';

const c = (path: string, title: string, group?: string): ComponentSummary => ({
  path,
  title,
  group,
  resourceType: path.replace(/^\/(apps|libs)\//, ''),
  abbreviation: title.slice(0, 2),
});

const LIST = [
  c('/apps/wknd/components/accordion', 'Accordion', 'WKND.Content'),
  c('/libs/core/wcm/components/accordion/v1/accordion', 'Accordion (v1)', 'Core Content'),
  c('/libs/cq/gui/components/x', '3rdparty', '.hidden'),
  c('/libs/cq/security/components/accountmanager', 'accountmanager'),
];

describe('components console', () => {
  it('filters by words, group and location', () => {
    expect(filterComponents(LIST, { text: 'accordion', group: '', location: '' }).map((x) => x.title)).toEqual(['Accordion', 'Accordion (v1)']);
    expect(filterComponents(LIST, { text: 'core accordion', group: '', location: '' }).map((x) => x.title)).toEqual(['Accordion (v1)']);
    expect(filterComponents(LIST, { text: '', group: 'n/a', location: '' }).map((x) => x.title)).toEqual(['accountmanager']);
    expect(filterComponents(LIST, { text: '', group: '', location: 'apps' }).map((x) => x.title)).toEqual(['Accordion']);
  });

  it('lists the groups authors see first', () => {
    expect(groupsOf(LIST)).toEqual(['Core Content', 'WKND.Content', '.hidden', 'n/a']);
  });

  it('addresses a component as AEM does', () => {
    const url = componentUrl('/apps/wknd/components/accordion');
    expect(url).toBe('/mnt/overlay/wcm/core/content/sites/components/details.html/apps/wknd/components/accordion');
    expect(componentOfUrl(url)).toBe('/apps/wknd/components/accordion');
    expect(componentOfUrl('/libs/wcm/core/content/sites/components.html')).toBeNull();
  });
});
