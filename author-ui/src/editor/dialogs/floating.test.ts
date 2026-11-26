import { describe, expect, it } from 'vitest';
import { clampPlace } from './ComponentDialog';

describe('the floating component dialog', () => {
  const size = { width: 640, height: 500 };
  const view = { width: 1400, height: 900 };

  it('stays where it is put while its title band is on screen', () => {
    expect(clampPlace({ x: 300, y: 120 }, size, view)).toEqual({ x: 300, y: 120 });
  });

  it('cannot be lost off an edge: a piece of the band always stays reachable', () => {
    expect(clampPlace({ x: -2000, y: -50 }, size, view)).toEqual({ x: 48 - 640, y: 0 });
    expect(clampPlace({ x: 5000, y: 5000 }, size, view)).toEqual({ x: 1400 - 48, y: 900 - 48 });
  });
});
