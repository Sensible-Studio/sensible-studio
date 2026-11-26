import { describe, expect, it } from 'vitest';
import { checkboxEntries, inferCheckboxTypeHint, multiEntries, prefixEntry, scalarEntries } from './slingPost';

describe('Sling POST serialization', () => {
  it('posts scalars, with type hints only for non-empty values', () => {
    expect(scalarEntries('./title', 'Hi')).toEqual([{ name: './title', value: 'Hi' }]);
    expect(scalarEntries('./size', '3', 'Long')).toEqual([
      { name: './size', value: '3' },
      { name: './size@TypeHint', value: 'Long' },
    ]);
    expect(scalarEntries('./size', '', 'Long')).toEqual([{ name: './size', value: '' }]);
  });

  it('posts checkboxes like Granite: value, uncheckedValue or @Delete', () => {
    const base = { value: 'true', deleteHint: true };
    expect(checkboxEntries('./x', true, base)).toEqual([{ name: './x', value: 'true' }]);
    expect(checkboxEntries('./x', false, base)).toEqual([{ name: './x@Delete', value: '' }]);
    expect(checkboxEntries('./x', false, { ...base, uncheckedValue: 'false', typeHint: 'Boolean' })).toEqual([
      { name: './x', value: 'false' },
      { name: './x@TypeHint', value: 'Boolean' },
    ]);
    expect(checkboxEntries('./x', false, { ...base, deleteHint: false })).toEqual([]);
  });

  it('types checkboxes from their declared values', () => {
    expect(inferCheckboxTypeHint(true, false)).toBe('Boolean');
    expect(inferCheckboxTypeHint('true', undefined)).toBeUndefined();
  });

  it('posts multi values as String[] and deletes when empty', () => {
    expect(multiEntries('./tags', ['a', '', 'b'])).toEqual([
      { name: './tags', value: 'a' },
      { name: './tags', value: 'b' },
      { name: './tags@TypeHint', value: 'String[]' },
    ]);
    expect(multiEntries('./tags', [])).toEqual([{ name: './tags@Delete', value: '' }]);
  });

  it('re-roots composite multifield entries', () => {
    expect(prefixEntry({ name: './link', value: '/x' }, './actions/item0')).toEqual({ name: './actions/item0/link', value: '/x' });
    expect(prefixEntry({ name: 'text@Delete', value: '' }, './actions/item1/')).toEqual({ name: './actions/item1/text@Delete', value: '' });
  });
});
