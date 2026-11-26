import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { ColorTagsField, COLOR_SWATCHES, COLORS_PROPERTY } from './colors';
import { DEFAULT_FACETS, SearchRail } from './search';

describe('color tags', () => {
  it('shows the colors of a file with their share', () => {
    render(
      <Provider theme={defaultTheme}>
        <ColorTagsField
          colors={[
            { name: 'blue', share: 0.62 },
            { name: 'white', share: 0.2 },
          ]}
        />
      </Provider>,
    );
    const group = screen.getByRole('group', { name: 'Color tags' });
    expect(group.textContent).toContain('Blue');
    expect(group.textContent).toContain('62%');
    expect(group.textContent).toContain('White');
  });

  it('shows nothing before the colors were found', () => {
    const { container } = render(<ColorTagsField colors={null} />);
    expect(container.textContent).toBe('');
  });

  it('is a filter of the default search form', () => {
    expect(DEFAULT_FACETS.map((f) => f.type)).toContain('colors');
    expect(COLOR_SWATCHES.map((c) => c.name)).toEqual([
      'red', 'orange', 'yellow', 'green', 'teal', 'blue', 'purple', 'pink', 'brown', 'beige', 'white', 'gray', 'black',
    ]);
  });

  it('filters the search by the colors ticked', () => {
    const changes: unknown[] = [];
    render(
      <Provider theme={defaultTheme}>
        <SearchRail
          query={{ meta: { [COLORS_PROPERTY]: ['white'] } }}
          scopeTitle="Media"
          onChange={(q) => changes.push(q)}
          facets={[{ type: 'colors', label: 'Color' }]}
        />
      </Provider>,
    );
    expect(screen.getByRole('checkbox', { name: 'White' })).toHaveProperty('checked', true);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Blue' }));
    // in the order of the picker
    expect(changes).toEqual([{ meta: { [COLORS_PROPERTY]: ['blue', 'white'] } }]);
  });
});
