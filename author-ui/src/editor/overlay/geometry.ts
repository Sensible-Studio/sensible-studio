import { containerOf, isAncestorOrSelf, nextSibling, type Editable, type EditableTree } from '../model/editables';
import type { Placement } from '../api/StudioClient';

export interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * Position of an element in content-frame viewport coordinates (= overlay coordinates). An element whose own box
 * collapsed while its content shows (a teaser whose content floats, with nothing to clear it) gets the box of its
 * content, so what the author sees is what can be selected.
 */
export function boxOf(el: Element | null | undefined): Box | null {
  if (!el || !el.isConnected) return null;
  const r = el.getBoundingClientRect();
  if (r.width < 1 || r.height < 1) {
    const c = contentBox(el);
    if (c) return { left: Math.min(r.left, c.left), top: Math.min(r.top, c.top), width: Math.max(r.right, c.right) - Math.min(r.left, c.left), height: Math.max(r.bottom, c.bottom) - Math.min(r.top, c.top) };
    if (r.width < 1 && r.height < 1) return null;
  }
  return { left: r.left, top: r.top, width: r.width, height: r.height };
}

interface Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** The union of the boxes of the element's visible descendants (the nearest ones with a box), or null. */
function contentBox(el: Element): Rect | null {
  let out: Rect | null = null;
  for (const child of Array.from(el.children)) {
    const r = child.getBoundingClientRect();
    const box: Rect | null = r.width >= 1 && r.height >= 1 ? { left: r.left, top: r.top, right: r.right, bottom: r.bottom } : contentBox(child);
    if (!box) continue;
    out = out ? { left: Math.min(out.left, box.left), top: Math.min(out.top, box.top), right: Math.max(out.right, box.right), bottom: Math.max(out.bottom, box.bottom) } : box;
  }
  return out;
}

export const contains = (b: Box, x: number, y: number) => x >= b.left && x <= b.left + b.width && y >= b.top && y <= b.top + b.height;

export type Target = { kind: 'editable'; editable: Editable } | { kind: 'placeholder'; container: Editable };

export const targetKey = (t: Target | null) => (t ? (t.kind === 'editable' ? t.editable.path : `${t.container.path}/*`) : '');

/**
 * The deepest editable (or container placeholder) under a point. `filter` limits which editables can
 * be hit, e.g. only grid columns in Layout mode.
 */
export function hitTest(tree: EditableTree, x: number, y: number, filter?: (e: Editable) => boolean): Target | null {
  let best: Target | null = null;
  let bestDepth = -1;
  let bestArea = Infinity;
  for (const e of tree.all) {
    if (filter && !filter(e)) continue;
    const b = boxOf(e.el);
    if (b && contains(b, x, y)) {
      const area = b.width * b.height;
      if (e.depth > bestDepth || (e.depth === bestDepth && area < bestArea)) {
        best = { kind: 'editable', editable: e };
        bestDepth = e.depth;
        bestArea = area;
      }
    }
    if (e.placeholder && !filter) {
      const pb = boxOf(e.placeholder);
      if (pb && contains(pb, x, y) && e.depth + 1 > bestDepth) {
        best = { kind: 'placeholder', container: e };
        bestDepth = e.depth + 1;
        bestArea = pb.width * pb.height;
      }
    }
  }
  return best;
}

export interface DropTarget {
  placement: Placement;
  /** Line (insert between components) or box (the container's placeholder) to highlight. */
  indicator: { kind: 'line'; left: number; top: number; width: number } | { kind: 'box'; box: Box };
  /** For asset drops: the component under the pointer that takes the asset itself. */
  onto?: Editable;
}

/**
 * Where a component dragged to (x, y) would go. `allowed` says whether a container accepts it; `moving`
 * is the component being moved (it cannot be dropped into itself).
 */
export function dropTargetAt(
  tree: EditableTree,
  x: number,
  y: number,
  allowed: (container: Editable) => boolean,
  moving?: Editable,
): DropTarget | null {
  const hit = hitTest(tree, x, y);
  if (!hit) return null;
  if (hit.kind === 'placeholder') {
    const c = hit.container;
    if (moving && isAncestorOrSelf(moving.path, c.path)) return null;
    if (!allowed(c)) return null;
    const box = boxOf(c.placeholder)!;
    return { placement: { container: c.path, before: null }, indicator: { kind: 'box', box } };
  }
  // Walk up to the first component that sits directly in an accepting container.
  for (let e: Editable | null = hit.editable; e; e = e.parent) {
    const c = containerOf(e);
    if (!c) continue;
    if (moving && isAncestorOrSelf(moving.path, c.path)) continue;
    if (!allowed(c)) continue;
    const b = boxOf(e.el);
    if (!b) continue;
    const upper = y < b.top + b.height / 2;
    const before = upper ? e : nextSibling(e);
    const name = before ? before.path.slice(before.path.lastIndexOf('/') + 1) : null;
    return {
      placement: { container: c.path, before: name },
      indicator: { kind: 'line', left: b.left, top: upper ? b.top : b.top + b.height, width: b.width },
    };
  }
  return null;
}

/** Toolbar position: above the box, right-aligned, kept inside the overlay. */
export function toolbarPosition(box: Box, size: { width: number; height: number }, bounds: { width: number; height: number }) {
  // below the component, aligned with its left edge; above it when there is no room below, inside as a last resort
  const gap = 4;
  let top = box.top + box.height + gap;
  if (top + size.height > bounds.height - gap) top = box.top - size.height - gap;
  if (top < gap) top = Math.min(Math.max(gap, box.top + box.height - size.height - gap), bounds.height - size.height - gap);
  if (top < gap) top = gap;
  let left = box.left;
  left = Math.max(gap, Math.min(left, bounds.width - size.width - gap));
  return { top, left };
}
