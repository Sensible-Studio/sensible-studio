import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { assetsRoute, routeUrl } from '../assets/route';
import { SearchFormsPage } from '../assets/searchForms';
import { MemoryDam } from '../assets/testing/MemoryDam';
import { DEFAULT_PAGE_FACETS, type PageFacet, type PageSearchForm, type SiteRailClient } from './rail/api';
import { tagPathOfId } from './rail/PageFacets';
import { pageFacetProblems, toPageFacets, type PageDraft } from './searchForm';

// jsdom has no CSS.escape, which Spectrum's menu uses for its item ids
(globalThis as { CSS?: { escape(v: string): string } }).CSS ??= { escape: (v) => v };

/** Just what the search form editor asks of the site rail. */
function fakeRail() {
  const calls: string[] = [];
  let form: PageSearchForm = { facets: DEFAULT_PAGE_FACETS, custom: false, canEdit: true };
  const rail = {
    calls,
    async searchForm() {
      return form;
    },
    async saveSearchForm(facets: PageFacet[]) {
      calls.push(`save ${facets.map((f) => (f.property ? `${f.type}(${f.property})` : f.type)).join(',')}`);
      form = { facets, custom: true, canEdit: true, modifiedBy: 'admin' };
      return form;
    },
    async resetSearchForm() {
      form = { facets: DEFAULT_PAGE_FACETS, custom: false, canEdit: true };
      return form;
    },
    async propertyValues(path: string, property: string) {
      calls.push(`values ${path} ${property}`);
      return { property, values: [{ value: 'Europe', count: 3 }, { value: 'Asia', count: 1 }], more: false, truncated: false };
    },
    async templates() {
      return [{ path: '/conf/wknd/settings/wcm/templates/page', title: 'Content Page' }];
    },
  };
  return rail as unknown as SiteRailClient & { calls: string[] };
}

describe('search forms of Pages', () => {
  it('has its own address next to the Media one', () => {
    const url = routeUrl({ kind: 'searchforms', form: 'sites' });
    expect(url).toBe('/libs/cq/core/content/tools/customsearch.html/conf/global/settings/cq/search/facets/sites');
    expect(assetsRoute({ pathname: url, hash: '' })).toEqual({ kind: 'searchforms', form: 'sites' });
    expect(assetsRoute({ pathname: routeUrl({ kind: 'searchforms' }), hash: '' })).toEqual({ kind: 'searchforms' });
  });

  it('says what keeps the form from being saved', () => {
    const d = (f: Partial<PageDraft>): PageDraft => ({ key: 1, type: 'text', label: 'x', ...f });
    expect(pageFacetProblems([d({ type: 'locked', label: 'Locked' })])).toEqual([]);
    expect(pageFacetProblems([d({ property: 'a/b' })])[0]).toMatch(/not a page property/);
    expect(pageFacetProblems([d({ type: 'options', property: 'region', optionsText: '' })])[0]).toMatch(/give the values/);
    expect(toPageFacets([d({ type: 'options', label: ' Region ', property: 'region', optionsText: 'eu = Europe' })])).toEqual([
      { type: 'options', label: 'Region', property: 'region', options: [{ value: 'eu', label: 'Europe' }] },
    ]);
    expect(tagPathOfId('wknd:')).toBe('/content/cq:tags/wknd');
    expect(tagPathOfId('wknd:activity/surfing')).toBe('/content/cq:tags/wknd/activity/surfing');
  });

  it('adds a filter on a page property with the values the pages have, and saves', async () => {
    const rail = fakeRail();
    render(
      <Provider theme={defaultTheme}>
        <SearchFormsPage client={new MemoryDam()} navigate={() => {}} form="sites" siteRail={rail} />
      </Provider>,
    );
    await screen.findByText(/This is the default form/);
    expect(screen.getByRole('heading', { name: 'Pages search' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove Linked copy' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add filter' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /Values of a property/ }));
    const added = screen.getByRole('listitem', { name: /Filter 6/ });
    fireEvent.change(within(added).getByRole('textbox', { name: /Property/ }), { target: { value: 'region' } });
    fireEvent.change(within(added).getByRole('textbox', { name: /Label/ }), { target: { value: 'Region' } });
    fireEvent.click(within(added).getByRole('button', { name: /Read the values from the pages/ }));
    await within(added).findByText(/2 values added/);
    expect(rail.calls).toContain('values /content region');
    // the preview shows the values to tick
    const preview = screen.getByRole('complementary', { name: 'Preview' });
    expect(within(preview).getByRole('checkbox', { name: 'Europe' })).toBeTruthy();
    expect(within(preview).queryByRole('button', { name: /Linked copy/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(rail.calls).toContain('save path,template,tags,modified,locked,options(region)'));
    await screen.findByText(/Changed by admin/);
  });
});
