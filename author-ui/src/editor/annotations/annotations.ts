import type { ContentNode, PostEntry } from '../../granite';

/** A note an author left on a component (`<component>/cq:annotations/<name>`), as AEM stores it. */
export interface Annotation {
  path: string;
  /** The component the note is on. */
  component: string;
  text: string;
  /** Hex colour of the marker (AEM's default is blue). */
  color: string;
  /** Position of the marker inside the component, in px from its top left corner. */
  x: number;
  y: number;
  created?: string;
  createdBy?: string;
  /** Arrows drawn from the marker, in the colour of the note. */
  arrows?: Arrow[];
}

export interface Point {
  x: number;
  y: number;
}

/**
 * An arrow drawn from an annotation (`<annotation>/sketches/<name>`): the points of the stroke, in px from the top left
 * corner of the annotation's component (the same frame as the marker's `x`, `y`); the head is at the last point.
 */
export interface Arrow {
  path: string;
  points: Point[];
}

export const DEFAULT_COLOR = '#3287d2';

/** Marker colours offered next to the note. */
export const COLORS = ['#3287d2', '#2d9d78', '#e68619', '#d7373f', '#9256d9', '#6e6e6e'];

const ANNOTATIONS = 'cq:annotations';
/** Child node of an annotation that holds its arrows. */
export const SKETCHES = 'sketches';

/** All annotations below a content root, in document order. */
export function collectAnnotations(content: ContentNode | null, root: string): Annotation[] {
  const out: Annotation[] = [];
  if (!content) return out;
  const walk = (node: ContentNode, path: string) => {
    for (const [name, value] of Object.entries(node)) {
      if (!value || typeof value !== 'object' || Array.isArray(value)) continue;
      const child = value as ContentNode;
      if (name === ANNOTATIONS) {
        for (const [n, a] of Object.entries(child)) {
          if (!a || typeof a !== 'object' || Array.isArray(a)) continue;
          const ann = a as ContentNode;
          out.push({
            path: `${path}/${ANNOTATIONS}/${n}`,
            component: path,
            text: String(ann.text ?? ''),
            color: typeof ann.color === 'string' && ann.color ? ann.color : DEFAULT_COLOR,
            x: Number(ann.x ?? 0),
            y: Number(ann.y ?? 0),
            created: ann['jcr:created'] ? String(ann['jcr:created']) : undefined,
            createdBy: ann['jcr:createdBy'] ? String(ann['jcr:createdBy']) : undefined,
            arrows: arrowsOf(ann[SKETCHES], `${path}/${ANNOTATIONS}/${n}/${SKETCHES}`),
          });
        }
        continue;
      }
      if (name.startsWith('jcr:') || name.startsWith('rep:')) continue;
      walk(child, `${path}/${name}`);
    }
  };
  walk(content, root);
  return out;
}

/** Name of a new annotation, like AEM's: its number on the component and the time. */
export function annotationName(existingOnComponent: number, now = Date.now()): string {
  return `${existingOnComponent + 1}_${now}`;
}

/** The Sling POST that creates an annotation at (x, y) inside a component. */
export function createEntries(x: number, y: number): PostEntry[] {
  return [
    { name: 'jcr:primaryType', value: 'nt:unstructured' },
    // empty: the Sling POST servlet fills in who and when, as AEM stores them (the Sites timeline shows them)
    { name: 'jcr:created', value: '' },
    { name: 'jcr:createdBy', value: '' },
    { name: 'jcr:lastModified', value: '' },
    { name: 'jcr:lastModifiedBy', value: '' },
    { name: 'text', value: '' },
    { name: 'x', value: String(Math.round(x)) },
    { name: 'x@TypeHint', value: 'Long' },
    { name: 'y', value: String(Math.round(y)) },
    { name: 'y@TypeHint', value: 'Long' },
  ];
}

function arrowsOf(node: unknown, base: string): Arrow[] {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return [];
  const out: Arrow[] = [];
  for (const [name, v] of Object.entries(node as ContentNode)) {
    if (!v || typeof v !== 'object' || Array.isArray(v)) continue;
    const points = parsePoints((v as ContentNode).points);
    if (points.length >= 2) out.push({ path: `${base}/${name}`, points });
  }
  return out;
}

/** `"x1,y1 x2,y2 …"` → points; anything unreadable is skipped. */
export function parsePoints(v: unknown): Point[] {
  if (typeof v !== 'string') return [];
  return v
    .trim()
    .split(/\s+/)
    .map((pair) => pair.split(',').map(Number))
    .filter((xy) => xy.length === 2 && xy.every(Number.isFinite))
    .map(([x, y]) => ({ x, y }));
}

/** Points as stored: whole pixels, `"x1,y1 x2,y2 …"`. */
export function formatPoints(points: Point[]): string {
  return points.map((p) => `${Math.round(p.x)},${Math.round(p.y)}`).join(' ');
}

/** Name of a new arrow below an annotation. */
export function arrowName(now = Date.now()): string {
  return `arrow_${now}`;
}

/** The Sling POST that creates an arrow (at `<annotation>/sketches/<name>`). */
export function arrowEntries(points: Point[]): PostEntry[] {
  return [
    { name: 'jcr:primaryType', value: 'nt:unstructured' },
    { name: 'points', value: formatPoints(points) },
    { name: 'arrow', value: 'true' },
    { name: 'arrow@TypeHint', value: 'Boolean' },
  ];
}
