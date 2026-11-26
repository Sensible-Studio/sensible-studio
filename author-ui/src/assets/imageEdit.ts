/**
 * Geometry of the image editor. Operations are applied in order, like studio-dam applies them;
 * every coordinate is a fraction (0..1) of the image as it is at that step.
 *
 * Image map areas are kept in the coordinates of the unedited original ("source" space) and
 * mapped forward through the operations for display, so undoing a crop or a rotation moves them
 * back automatically. On save they are mapped into the final image and clipped to it.
 */

export type EditOperation =
  | { op: 'rotate'; degrees: 90 | 180 | 270 }
  | { op: 'flip'; axis: 'horizontal' | 'vertical' }
  | { op: 'crop'; x: number; y: number; width: number; height: number };

export type AreaTarget = '' | '_self' | '_blank' | '_parent' | '_top';

/**
 * A map area. rect: x1, y1, x2, y2; circle: cx, cy, r (r as a fraction of the image width);
 * poly: x1, y1, x2, y2, … (three corners or more).
 */
export interface MapArea {
  shape: 'rect' | 'circle' | 'poly';
  coords: number[];
  href: string;
  target: AreaTarget;
  alt: string;
}

export interface Size {
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

/** Pixel size after `ops`, starting from `size`. */
export function sizeAfter(size: Size, ops: EditOperation[]): Size {
  let { width, height } = size;
  for (const o of ops) {
    if (o.op === 'rotate' && o.degrees !== 180) [width, height] = [height, width];
    if (o.op === 'crop') {
      width = Math.max(1, Math.round(width * o.width));
      height = Math.max(1, Math.round(height * o.height));
    }
  }
  return { width, height };
}

function forwardOne(p: Point, o: EditOperation): Point {
  switch (o.op) {
    case 'rotate':
      // Clockwise: the top-left corner goes to the top-right.
      if (o.degrees === 90) return { x: 1 - p.y, y: p.x };
      if (o.degrees === 180) return { x: 1 - p.x, y: 1 - p.y };
      return { x: p.y, y: 1 - p.x };
    case 'flip':
      return o.axis === 'horizontal' ? { x: 1 - p.x, y: p.y } : { x: p.x, y: 1 - p.y };
    case 'crop':
      return { x: (p.x - o.x) / o.width, y: (p.y - o.y) / o.height };
  }
}

function inverseOne(p: Point, o: EditOperation): Point {
  switch (o.op) {
    case 'rotate':
      if (o.degrees === 90) return { x: p.y, y: 1 - p.x };
      if (o.degrees === 180) return { x: 1 - p.x, y: 1 - p.y };
      return { x: 1 - p.y, y: p.x };
    case 'flip':
      return forwardOne(p, o);
    case 'crop':
      return { x: o.x + p.x * o.width, y: o.y + p.y * o.height };
  }
}

export function forward(p: Point, ops: EditOperation[]): Point {
  return ops.reduce(forwardOne, p);
}

export function inverse(p: Point, ops: EditOperation[]): Point {
  return ops.reduceRight(inverseOne, p);
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

function eachPoint(coords: number[], f: (p: Point) => Point): number[] {
  const out: number[] = [];
  for (let i = 0; i + 1 < coords.length; i += 2) {
    const q = f({ x: coords[i], y: coords[i + 1] });
    out.push(q.x, q.y);
  }
  return out;
}

/**
 * A polygon cut to the image (0..1 on both axes): what is left of it inside, corner by corner.
 * Fewer than three corners left means nothing of it is inside.
 */
export function clipPolygon(coords: number[]): number[] {
  let pts: Point[] = [];
  for (let i = 0; i + 1 < coords.length; i += 2) pts.push({ x: coords[i], y: coords[i + 1] });
  // one edge of the image at a time: keep what is on its inner side, add where the outline crosses it
  const edges: Array<{ inside(p: Point): boolean; cross(a: Point, b: Point): Point }> = [
    { inside: (p) => p.x >= 0, cross: (a, b) => ({ x: 0, y: a.y + ((b.y - a.y) * (0 - a.x)) / (b.x - a.x) }) },
    { inside: (p) => p.x <= 1, cross: (a, b) => ({ x: 1, y: a.y + ((b.y - a.y) * (1 - a.x)) / (b.x - a.x) }) },
    { inside: (p) => p.y >= 0, cross: (a, b) => ({ x: a.x + ((b.x - a.x) * (0 - a.y)) / (b.y - a.y), y: 0 }) },
    { inside: (p) => p.y <= 1, cross: (a, b) => ({ x: a.x + ((b.x - a.x) * (1 - a.y)) / (b.y - a.y), y: 1 }) },
  ];
  for (const e of edges) {
    const next: Point[] = [];
    for (let i = 0; i < pts.length; i++) {
      const a = pts[i];
      const b = pts[(i + 1) % pts.length];
      if (e.inside(a)) {
        next.push(a);
        if (!e.inside(b)) next.push(e.cross(a, b));
      } else if (e.inside(b)) next.push(e.cross(a, b));
    }
    pts = next;
    if (pts.length < 3) return [];
  }
  // a corner of the image the outline passes through comes out once per edge: keep one
  const same = (a: Point, b: Point) => Math.abs(a.x - b.x) < 1e-9 && Math.abs(a.y - b.y) < 1e-9;
  pts = pts.filter((p, i) => !same(p, pts[(i + pts.length - 1) % pts.length]));
  if (pts.length < 3) return [];
  return pts.flatMap((p) => [clamp01(p.x), clamp01(p.y)]);
}

/** The surface of a polygon, as a fraction of the image's. */
export function polygonArea(coords: number[]): number {
  let s = 0;
  const n = coords.length / 2;
  for (let i = 0; i < n; i++) {
    const j = (i + 1) % n;
    s += coords[2 * i] * coords[2 * j + 1] - coords[2 * j] * coords[2 * i + 1];
  }
  return Math.abs(s) / 2;
}

/** An area (source space) as it appears after `ops`; may lie partly outside 0..1. */
export function areaForward(a: MapArea, source: Size, ops: EditOperation[]): MapArea {
  if (a.shape === 'rect') {
    const p = forward({ x: a.coords[0], y: a.coords[1] }, ops);
    const q = forward({ x: a.coords[2], y: a.coords[3] }, ops);
    return { ...a, coords: [Math.min(p.x, q.x), Math.min(p.y, q.y), Math.max(p.x, q.x), Math.max(p.y, q.y)] };
  }
  if (a.shape === 'poly') return { ...a, coords: eachPoint(a.coords, (pt) => forward(pt, ops)) };
  const c = forward({ x: a.coords[0], y: a.coords[1] }, ops);
  // The radius is in pixels of the image, which no operation scales.
  const r = (a.coords[2] * source.width) / sizeAfter(source, ops).width;
  return { ...a, coords: [c.x, c.y, r] };
}

/** The inverse of {@link areaForward}: an area drawn on the edited image, back in source space. */
export function areaInverse(a: MapArea, source: Size, ops: EditOperation[]): MapArea {
  if (a.shape === 'rect') {
    const p = inverse({ x: a.coords[0], y: a.coords[1] }, ops);
    const q = inverse({ x: a.coords[2], y: a.coords[3] }, ops);
    return { ...a, coords: [Math.min(p.x, q.x), Math.min(p.y, q.y), Math.max(p.x, q.x), Math.max(p.y, q.y)] };
  }
  if (a.shape === 'poly') return { ...a, coords: eachPoint(a.coords, (pt) => inverse(pt, ops)) };
  const c = inverse({ x: a.coords[0], y: a.coords[1] }, ops);
  const r = (a.coords[2] * sizeAfter(source, ops).width) / source.width;
  return { ...a, coords: [c.x, c.y, r] };
}

/** Areas for the final image: mapped forward, clipped; those left outside are dropped. */
export function finalAreas(areas: MapArea[], source: Size, ops: EditOperation[]): MapArea[] {
  const out: MapArea[] = [];
  for (const a of areas) {
    const f = areaForward(a, source, ops);
    if (f.shape === 'rect') {
      const [x1, y1, x2, y2] = f.coords.map(clamp01);
      if (x2 - x1 > 0.001 && y2 - y1 > 0.001) out.push({ ...f, coords: [x1, y1, x2, y2] });
    } else if (f.shape === 'poly') {
      const coords = clipPolygon(f.coords);
      if (coords.length >= 6 && polygonArea(coords) > 0.000001) out.push({ ...f, coords });
    } else {
      const [cx, cy, r] = f.coords;
      if (cx >= 0 && cx <= 1 && cy >= 0 && cy <= 1 && r > 0.001) out.push(f);
    }
  }
  return out;
}

/**
 * Reads an `imageMap` property (Core Components notation). Only the relative coordinates in
 * parentheses are used; areas without them are skipped.
 */
export function parseImageMap(value: string | null | undefined): MapArea[] {
  if (!value) return [];
  const out: MapArea[] = [];
  const re = /\[(rect|circle|poly)\(([^)]*)\)"([^"]*)"\|"([^"]*)"\|"([^"]*)"(?:\|\(([^)]*)\))?\]/g;
  for (let m = re.exec(value); m; m = re.exec(value)) {
    if (!m[6]) continue;
    const coords = m[6].split(',').map(Number);
    const shape = m[1] as MapArea['shape'];
    const count = shape === 'poly' ? coords.length >= 6 && coords.length % 2 === 0 : coords.length === (shape === 'rect' ? 4 : 3);
    if (!count || coords.some((c) => !Number.isFinite(c))) continue;
    const target = (['', '_self', '_blank', '_parent', '_top'].includes(m[4]) ? m[4] : '') as AreaTarget;
    out.push({ shape, coords, href: m[3], target, alt: m[5] });
  }
  return out;
}

/** Crop ratios offered by the editor (width : height); `null` is free-hand. */
export const CROP_RATIOS: Array<{ key: string; label: string; ratio: number | null }> = [
  { key: 'free', label: 'Free Hand', ratio: null },
  { key: '2:3', label: '2 : 3', ratio: 2 / 3 },
  { key: '3:5', label: '3 : 5', ratio: 3 / 5 },
  { key: '16:9', label: '16 : 9', ratio: 16 / 9 },
  { key: 'square', label: 'Square', ratio: 1 },
];

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * The first crop box: 80% of the image, centred, with the given pixel ratio (width / height)
 * if there is one.
 */
export function initialCrop(size: Size, ratio: number | null): Box {
  let w = 0.8;
  let h = 0.8;
  if (ratio) {
    // In pixels: w*W / (h*H) = ratio.
    const hForW = (w * size.width) / ratio / size.height;
    if (hForW <= 0.8) h = hForW;
    else w = (h * size.height * ratio) / size.width;
  }
  return { x: (1 - w) / 2, y: (1 - h) / 2, width: w, height: h };
}

export type Handle = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw' | 'move';

/** Smallest box side, as a fraction. */
const MIN = 0.02;

/**
 * Drags `handle` of `box` by (dx, dy) fractions, keeping it inside the image and, when `ratio`
 * is set, at that pixel ratio.
 */
export function dragBox(box: Box, handle: Handle, dx: number, dy: number, size: Size, ratio: number | null): Box {
  if (handle === 'move') {
    return {
      ...box,
      x: Math.min(1 - box.width, Math.max(0, box.x + dx)),
      y: Math.min(1 - box.height, Math.max(0, box.y + dy)),
    };
  }
  let x1 = box.x;
  let y1 = box.y;
  let x2 = box.x + box.width;
  let y2 = box.y + box.height;
  if (handle.includes('w')) x1 = Math.min(x2 - MIN, Math.max(0, x1 + dx));
  if (handle.includes('e')) x2 = Math.max(x1 + MIN, Math.min(1, x2 + dx));
  if (handle.includes('n')) y1 = Math.min(y2 - MIN, Math.max(0, y1 + dy));
  if (handle.includes('s')) y2 = Math.max(y1 + MIN, Math.min(1, y2 + dy));
  if (!ratio) return { x: x1, y: y1, width: x2 - x1, height: y2 - y1 };

  // Keep the ratio: the dragged side leads, the other follows, anchored at the opposite edge.
  const k = size.width / size.height / ratio; // height fraction per width fraction
  let w = x2 - x1;
  let h = y2 - y1;
  const vertical = handle === 'n' || handle === 's';
  if (vertical) w = h / k;
  else h = w * k;
  // Shrink until it fits inside the image from its anchor.
  const anchorX = handle.includes('w') ? box.x + box.width : vertical ? box.x + box.width / 2 : box.x;
  const anchorY = handle.includes('n') ? box.y + box.height : handle === 'e' || handle === 'w' ? box.y + box.height / 2 : box.y;
  const maxW = handle.includes('w') ? anchorX : handle.includes('e') ? 1 - anchorX : 2 * Math.min(anchorX, 1 - anchorX);
  const maxH = handle.includes('n') ? anchorY : handle.includes('s') ? 1 - anchorY : 2 * Math.min(anchorY, 1 - anchorY);
  const scale = Math.min(1, maxW / w, maxH / h);
  w *= scale;
  h *= scale;
  const x = handle.includes('w') ? anchorX - w : handle.includes('e') ? anchorX : anchorX - w / 2;
  const y = handle.includes('n') ? anchorY - h : handle.includes('s') ? anchorY : anchorY - h / 2;
  return { x, y, width: w, height: h };
}

/** Moves or resizes an area (in the coordinates it is displayed in). */
export function dragArea(a: MapArea, handle: Handle, dx: number, dy: number, size: Size): MapArea {
  if (a.shape === 'rect') {
    const box = dragBox(
      { x: a.coords[0], y: a.coords[1], width: a.coords[2] - a.coords[0], height: a.coords[3] - a.coords[1] },
      handle,
      dx,
      dy,
      size,
      null,
    );
    return { ...a, coords: [box.x, box.y, box.x + box.width, box.y + box.height] };
  }
  if (a.shape === 'poly') {
    if (handle !== 'move') return a;
    // moved as a whole, as far as its outermost corners allow
    const xs = a.coords.filter((_, i) => i % 2 === 0);
    const ys = a.coords.filter((_, i) => i % 2 === 1);
    const mx = Math.max(-Math.min(...xs), Math.min(1 - Math.max(...xs), dx));
    const my = Math.max(-Math.min(...ys), Math.min(1 - Math.max(...ys), dy));
    return { ...a, coords: a.coords.map((c, i) => c + (i % 2 === 0 ? mx : my)) };
  }
  const [cx, cy, r] = a.coords;
  if (handle === 'move') return { ...a, coords: [clamp01(cx + dx), clamp01(cy + dy), r] };
  // Any handle changes the radius: by the horizontal or vertical distance, whichever it sits on.
  const rh = (r * size.width) / size.height; // radius as a fraction of the height
  let nr = r;
  if (handle === 'e') nr = r + dx;
  if (handle === 'w') nr = r - dx;
  if (handle === 's') nr = ((rh + dy) * size.height) / size.width;
  if (handle === 'n') nr = ((rh - dy) * size.height) / size.width;
  return { ...a, coords: [cx, cy, Math.max(0.01, Math.min(1, nr))] };
}

/** Moves one corner of a polygon (kept inside the image). */
export function dragCorner(a: MapArea, corner: number, dx: number, dy: number): MapArea {
  const coords = a.coords.slice();
  coords[2 * corner] = clamp01(coords[2 * corner] + dx);
  coords[2 * corner + 1] = clamp01(coords[2 * corner + 1] + dy);
  return { ...a, coords };
}

/** A new corner in the middle of the side that starts at `corner`; the polygon keeps its outline. */
export function addCorner(a: MapArea, corner: number): MapArea {
  const n = a.coords.length / 2;
  const j = (corner + 1) % n;
  const coords = a.coords.slice();
  coords.splice(2 * corner + 2, 0, (a.coords[2 * corner] + a.coords[2 * j]) / 2, (a.coords[2 * corner + 1] + a.coords[2 * j + 1]) / 2);
  return { ...a, coords };
}

/** Without one corner; a polygon keeps at least three. */
export function removeCorner(a: MapArea, corner: number): MapArea {
  if (a.coords.length <= 6) return a;
  const coords = a.coords.slice();
  coords.splice(2 * corner, 2);
  return { ...a, coords };
}

/** A regular polygon of `corners` corners that looks regular on an image of `size`, in its middle. */
export function defaultPolygon(size: Size, corners = 5): number[] {
  const r = 0.3 * Math.min(size.width, size.height);
  const out: number[] = [];
  for (let i = 0; i < corners; i++) {
    const t = -Math.PI / 2 + (2 * Math.PI * i) / corners;
    out.push(0.5 + (r * Math.cos(t)) / size.width, 0.5 + (r * Math.sin(t)) / size.height);
  }
  return out;
}

/** True when the operations leave the image as it was (e.g. four right turns). */
export function isIdentity(ops: EditOperation[]): boolean {
  const probe = [
    { x: 0.1, y: 0.2 },
    { x: 0.9, y: 0.7 },
  ];
  return ops.every((o) => o.op !== 'crop') && probe.every((p) => {
    const q = forward(p, ops);
    return Math.abs(q.x - p.x) < 1e-9 && Math.abs(q.y - p.y) < 1e-9;
  });
}

/** Draws `img` with `ops` applied onto a new canvas (at most `maxSide` pixels on the long side). */
export function renderEdited(img: CanvasImageSource & { width: number; height: number }, ops: EditOperation[], maxSide = 2048): HTMLCanvasElement {
  const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
  let canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(img.width * scale));
  canvas.height = Math.max(1, Math.round(img.height * scale));
  canvas.getContext('2d')?.drawImage(img, 0, 0, canvas.width, canvas.height);
  for (const o of ops) {
    const next = document.createElement('canvas');
    const ctx = next.getContext('2d');
    if (o.op === 'rotate') {
      const quarter = o.degrees !== 180;
      next.width = quarter ? canvas.height : canvas.width;
      next.height = quarter ? canvas.width : canvas.height;
      ctx?.translate(next.width / 2, next.height / 2);
      ctx?.rotate((o.degrees * Math.PI) / 180);
      ctx?.drawImage(canvas, -canvas.width / 2, -canvas.height / 2);
    } else if (o.op === 'flip') {
      next.width = canvas.width;
      next.height = canvas.height;
      if (o.axis === 'horizontal') {
        ctx?.translate(next.width, 0);
        ctx?.scale(-1, 1);
      } else {
        ctx?.translate(0, next.height);
        ctx?.scale(1, -1);
      }
      ctx?.drawImage(canvas, 0, 0);
    } else {
      const sx = Math.round(o.x * canvas.width);
      const sy = Math.round(o.y * canvas.height);
      next.width = Math.max(1, Math.round(o.width * canvas.width));
      next.height = Math.max(1, Math.round(o.height * canvas.height));
      ctx?.drawImage(canvas, sx, sy, next.width, next.height, 0, 0, next.width, next.height);
    }
    canvas = next;
  }
  return canvas;
}
