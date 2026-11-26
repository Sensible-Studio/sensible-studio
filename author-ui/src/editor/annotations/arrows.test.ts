import { describe, expect, it } from 'vitest';
import { bend, curvePath, headOf, lengthOf, middleOf, simplify } from './arrows';

describe('arrows', () => {
  it('drops the jitter of a hand-drawn stroke and keeps its ends and corners', () => {
    const wobbly = [
      { x: 0, y: 0 },
      { x: 10, y: 1 },
      { x: 20, y: -1 },
      { x: 30, y: 0 },
      { x: 30, y: 40 },
    ];
    expect(simplify(wobbly)).toEqual([
      { x: 0, y: 0 },
      { x: 30, y: 0 },
      { x: 30, y: 40 },
    ]);
  });

  it('draws a smooth curve through the points, shortened before the head', () => {
    const pts = [
      { x: 0, y: 0 },
      { x: 50, y: 50 },
      { x: 100, y: 0 },
    ];
    const d = curvePath(pts);
    expect(d.startsWith('M0 0')).toBe(true);
    expect(d.match(/C/g)).toHaveLength(2);
    expect(d.endsWith('100 0')).toBe(true);
    expect(curvePath(pts, 10).endsWith('100 0')).toBe(false);
  });

  it('puts the head at the last point, pointing the way the stroke ends', () => {
    const [tip, a, b] = headOf([
      { x: 0, y: 0 },
      { x: 100, y: 0 },
    ]);
    expect(tip).toEqual({ x: 100, y: 0 });
    expect(a.x).toBeCloseTo(84);
    expect(b.x).toBeCloseTo(84);
    expect(Math.sign(a.y)).toBe(-Math.sign(b.y));
  });

  it('bends a stroke whose start moved, the tip staying where it was', () => {
    const pts = [
      { x: 0, y: 0 },
      { x: 50, y: 0 },
      { x: 100, y: 0 },
    ];
    const out = bend(pts, { x: 0, y: 20 });
    expect(out[0]).toEqual({ x: 0, y: 20 });
    expect(out[1]).toEqual({ x: 50, y: 10 });
    expect(out[2]).toEqual({ x: 100, y: 0 });
    expect(lengthOf(pts)).toBe(100);
    expect(middleOf(pts)).toEqual({ x: 50, y: 0 });
  });
});
