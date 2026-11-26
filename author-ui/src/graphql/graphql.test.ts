import { describe, expect, it } from 'vitest';
import { prettify, typeName } from './api';

describe('GraphQL helpers', () => {
  it('formats a query one field per line, arguments on the field line', () => {
    const q = 'query ($limit: Int) { adventureList(\n offset: 2\n limit: $limit) { items { title # the title\n price } } }';
    expect(prettify(q)).toBe(
      ['query ($limit: Int) {', '  adventureList(offset: 2, limit: $limit) {', '    items {', '      title # the title', '      price', '    }', '  }', '}', ''].join('\n'),
    );
  });

  it('names introspected types as written in a query', () => {
    expect(typeName({ kind: 'NON_NULL', name: null, ofType: { kind: 'LIST', name: null, ofType: { kind: 'OBJECT', name: 'Adventure' } } })).toBe('[Adventure]!');
  });
});
