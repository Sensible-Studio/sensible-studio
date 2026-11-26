import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { MemoryDam } from './testing/MemoryDam';
import { assetsRoute, parseSearch, routeUrl, searchString } from './route';
import { facetProblems, optionsToText, SearchFormsPage, textToOptions, toFacets, type DraftFacet } from './searchForms';
import { isFiltering, SearchRail } from './search';

// jsdom has no CSS.escape, which Spectrum's menu uses for its item ids
(globalThis as { CSS?: { escape(v: string): string } }).CSS ??= { escape: (v) => v };

describe('search forms', () => {
  it('has its own page', () => {
    expect(routeUrl({ kind: 'searchforms' })).toBe('/libs/cq/core/content/tools/customsearch.html/conf/global/settings/cq/search/facets');
    expect(assetsRoute({ pathname: '/libs/cq/core/content/tools/customsearch.html', hash: '' })).toEqual({ kind: 'searchforms' });
    expect(assetsRoute({ pathname: routeUrl({ kind: 'searchforms' }), hash: '' })).toEqual({ kind: 'searchforms' });
  });

  it('keeps the metadata filters in the address', () => {
    const q = { fulltext: 'surf', meta: { 'dam:status': ['approved', 'rejected'] }, metaText: { 'dc:creator': 'Jane = Doe' } };
    const s = searchString(q);
    expect(parseSearch(s.slice(1))).toEqual(q);
    expect(isFiltering({ meta: { 'dam:status': ['approved'] } })).toBe(true);
    expect(isFiltering({ metaText: { 'dc:creator': ' ' } })).toBe(false);
  });

  it('reads values one per line', () => {
    expect(textToOptions('approved = Approved\nrejected\n\napproved\n  changesRequested=Changes  ')).toEqual([
      { value: 'approved', label: 'Approved' },
      { value: 'rejected', label: 'rejected' },
      { value: 'changesRequested', label: 'Changes' },
    ]);
    expect(optionsToText([{ value: 'a', label: 'A' }, { value: 'b', label: 'b' }])).toBe('a = A\nb');
  });

  it('says what keeps the form from being saved', () => {
    const d = (f: Partial<DraftFacet>): DraftFacet => ({ key: 1, type: 'text', label: 'x', ...f });
    expect(facetProblems([d({ type: 'types', label: 'File type' })])).toEqual([]);
    expect(facetProblems([d({ property: 'jcr:content/../x' })])[0]).toMatch(/not a metadata property/);
    expect(facetProblems([d({ property: 'dc:creator' }), d({ property: 'dc:creator', key: 2 })])).toEqual(['Two filters of the same kind on dc:creator.']);
    expect(facetProblems([d({ type: 'options', property: 'dam:status', optionsText: '' })])[0]).toMatch(/give the values/);
    expect(facetProblems([d({ type: 'tags', label: 'Tags', root: 'not a tag' })])[0]).toMatch(/tag ID/);
    expect(
      toFacets([d({ type: 'options', label: ' Status ', property: 'dam:status ', optionsText: 'approved = Approved', description: ' ' })]),
    ).toEqual([{ type: 'options', label: 'Status', property: 'dam:status', options: [{ value: 'approved', label: 'Approved' }] }]);
  });

  it('lays out the rail as the form says', () => {
    const changes: unknown[] = [];
    render(
      <Provider theme={defaultTheme}>
        <SearchRail
          query={{}}
          scopeTitle="Media"
          onChange={(q) => changes.push(q)}
          facets={[
            { type: 'options', label: 'Status', property: 'dam:status', options: [{ value: 'approved', label: 'Approved' }] },
            { type: 'size', label: 'How big' },
          ]}
        />
      </Provider>,
    );
    expect(screen.queryByText('File type')).toBeNull();
    expect(screen.getByText('How big')).toBeTruthy();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Approved' }));
    expect(changes).toEqual([{ meta: { 'dam:status': ['approved'] } }]);
  });

  it('adds a filter on a property and saves the form', async () => {
    const dam = new MemoryDam();
    render(
      <Provider theme={defaultTheme}>
        <SearchFormsPage client={dam} navigate={() => {}} />
      </Provider>,
    );
    await screen.findByText(/This is the default form/);
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toHaveProperty('disabled', true);
    // the orientation filter goes
    fireEvent.click(screen.getByRole('button', { name: 'Remove Orientation' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add filter' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /Text in a property/ }));
    const added = screen.getByRole('listitem', { name: /Filter 6/ });
    fireEvent.change(within(added).getByRole('textbox', { name: /Property/ }), { target: { value: 'dc:creator' } });
    fireEvent.change(within(added).getByRole('textbox', { name: /Label/ }), { target: { value: 'Photographer' } });
    // the preview shows it
    expect(within(screen.getByRole('complementary', { name: 'Preview' })).getByText('Photographer')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(dam.calls).toContain('saveSearchForm types,tags,modified,size,colors,text(dc:creator)'));
    expect((await dam.searchForm()).custom).toBe(true);
    await screen.findByText(/Changed by admin/);
  });
});
