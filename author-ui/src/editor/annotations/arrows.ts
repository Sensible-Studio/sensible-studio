import type { Point } from './annotations';

/** Arrows: the geometry of a hand-drawn stroke, from the pointer's points to the SVG that draws it. */

const dist = (a: Point, b: Point) => Math.hypot(b.x - a.x, b.y - a.y);

/** Length of the polyline. */
export function lengthOf(points: Point[]): number {
  let l = 0;
  for (let i = 1; i < points.length; i++) l += dist(points[i - 1], points[i]);
  return l;
}

/** Distance from `p` to the segment `a`–`b`. */
function toSegment(p: Point, a: Point, b: Point): number {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const l2 = dx * dx + dy * dy;
  if (!l2) return dist(p, a);
  const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2));
  return dist(p, { x: a.x + t * dx, y: a.y + t * dy });
}

/** Fewer points for the same shape (Ramer–Douglas–Peucker, `tolerance` in px): the hand's jitter goes, the curve stays. */
export function simplify(points: Point[], tolerance = 2.5): Point[] {
  if (points.length <= 2) return points.slice();
  let worst = 0;
  let at = 0;
  const first = points[0];
  const last = points[points.length - 1];
  for (let i = 1; i < points.length - 1; i++) {
    const d = toSegment(points[i], first, last);
    if (d > worst) {
      worst = d;
      at = i;
    }
  }
  if (worst <= tolerance) return [first, last];
  const left = simplify(points.slice(0, at + 1), tolerance);
  const right = simplify(points.slice(at), tolerance);
  return [...left.slice(0, -1), ...right];
}

const r = (n: number) => Math.round(n * 10) / 10;

/**
 * SVG path data of a smooth curve through the points (Catmull–Rom turned into cubic Béziers), ending `trim` px before
 * the last point so the stroke does not poke through the arrow head.
 */
export function curvePath(points: Point[], trim = 0): string {
  if (points.length < 2) return '';
  const pts = trim > 0 ? trimEnd(points, trim) : points;
  let d = `M${r(pts[0].x)} ${r(pts[0].y)}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] ?? pts[i];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[i + 2] ?? p2;
    const c1 = { x: p1.x + (p2.x - p0.x) / 6, y: p1.y + (p2.y - p0.y) / 6 };
    const c2 = { x: p2.x - (p3.x - p1.x) / 6, y: p2.y - (p3.y - p1.y) / 6 };
    d += ` C${r(c1.x)} ${r(c1.y)} ${r(c2.x)} ${r(c2.y)} ${r(p2.x)} ${r(p2.y)}`;
  }
  return d;
}

/** The polyline shortened by `by` px at its end. */
function trimEnd(points: Point[], by: number): Point[] {
  const out = points.slice();
  let left = by;
  while (out.length > 1) {
    const b = out[out.length - 1];
    const a = out[out.length - 2];
    const d = dist(a, b);
    if (d > left) {
      const t = (d - left) / d;
      out[out.length - 1] = { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
      return out;
    }
    left -= d;
    out.pop();
  }
  return out;
}

/** The point `back` px before the end, walking back along the polyline. */
function pointBack(points: Point[], back: number): Point {
  let left = back;
  for (let i = points.length - 1; i > 0; i--) {
    const b = points[i];
    const a = points[i - 1];
    const d = dist(a, b);
    if (d >= left) {
      const t = (d - left) / d;
      return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
    }
    left -= d;
  }
  return points[0];
}

/** The three corners of the arrow head (tip first), pointing the way the stroke ends. */
export function headOf(points: Point[], size = 16): Point[] {
  const tip = points[points.length - 1];
  const from = pointBack(points, size * 1.2);
  let dx = tip.x - from.x;
  let dy = tip.y - from.y;
  const l = Math.hypot(dx, dy) || 1;
  dx /= l;
  dy /= l;
  const base = { x: tip.x - dx * size, y: tip.y - dy * size };
  const half = size * 0.55;
  return [tip, { x: base.x - dy * half, y: base.y + dx * half }, { x: base.x + dy * half, y: base.y - dx * half }];
}

/** The point halfway along the stroke (where its delete button goes). */
export function middleOf(points: Point[]): Point {
  return pointBack(points, lengthOf(points) / 2);
}

/**
 * The stroke after its start moved by `delta` with the tip staying put: every point moves by `delta` times what is
 * left of the stroke after it (1 at the start, 0 at the tip), so the curve keeps its shape.
 */
export function bend(points: Point[], delta: Point): Point[] {
  const total = lengthOf(points);
  if (!total) return points.map((p) => ({ x: p.x + delta.x, y: p.y + delta.y }));
  let walked = 0;
  return points.map((p, i) => {
    if (i > 0) walked += dist(points[i - 1], p);
    const w = 1 - walked / total;
    return { x: p.x + delta.x * w, y: p.y + delta.y * w };
  });
}

/** Points moved by `by`. */
export function shift(points: Point[], by: Point): Point[] {
  return points.map((p) => ({ x: p.x + by.x, y: p.y + by.y }));
}
