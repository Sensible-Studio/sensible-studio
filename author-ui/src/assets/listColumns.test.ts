import { describe, expect, it } from 'vitest';
import { listColumns } from './views';

const keys = (w: number) => listColumns(w).map((c) => c.key);

describe('the Media list in a narrow place', () => {
  it('shows every column when there is room', () => {
    expect(keys(1200)).toEqual(['name', 'type', 'dims', 'size', 'status', 'modified']);
  });
  it('drops columns rather than cutting them all', () => {
    expect(keys(800)).toEqual(['name', 'type', 'size', 'status', 'modified']);
    expect(keys(700)).toEqual(['name', 'type', 'size', 'modified']);
    expect(keys(600)).toEqual(['name', 'type', 'size']);
    expect(keys(500)).toEqual(['name', 'size']);
    expect(keys(300)).toEqual(['name']);
  });
});
