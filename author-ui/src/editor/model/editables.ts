/**
 * The editables of the page in the content frame, read from the `data-studio-*` attributes Sensible Studio writes on
 * component decorations when a page renders with `wcmmode=edit`.
 */

export interface Editable {
  path: string;
  type: string;
  title: string;
  el: HTMLElement;
  parent: Editable | null;
  children: Editable[];
  depth: number;
  dialog: boolean;
  designDialog: boolean;
  /** A paragraph system: components can be inserted into it. */
  container: boolean;
  /** The "Drag components here" slot at the end of a container. */
  placeholder: HTMLElement | null;
  /** The layout grid (`.aem-Grid`) rendering this container's children. */
  grid: HTMLElement | null;
  drop?: string;
  dropAccept?: string;
  /** MSM: rolled out from a blueprint (`inherited`, read-only) or with its inheritance cancelled; local otherwise. */
  msm?: 'inherited' | 'cancelled';
}

export interface EditableTree {
  roots: Editable[];
  byPath: Map<string, Editable>;
  /** Document order. */
  all: Editable[];
}

export const EMPTY_TREE: EditableTree = { roots: [], byPath: new Map(), all: [] };

const SELECTOR = '[data-studio-path],[data-studio-grid]';

export function scanEditables(doc: Document | null | undefined): EditableTree {
  if (!doc?.body) return EMPTY_TREE;
  const byPath = new Map<string, Editable>();
  const all: Editable[] = [];
  const roots: Editable[] = [];
  const byElement = new Map<Element, Editable>();

  // Containers that render their own "new section" slot (Core's Container in simple layout includes `<path>/*`
  // as `wcm/foundation/components/parsys/newpar`): that slot is the container's placeholder, not a component.
  const newpars: HTMLElement[] = [];
  for (const el of Array.from(doc.querySelectorAll<HTMLElement>(SELECTOR))) {
    const path = el.dataset.studioPath;
    if (path?.endsWith('/*')) {
      newpars.push(el);
      continue;
    }
    if (!path) {
      // A grid without its own decorated container (e.g. included directly by a page component).
      const gridPath = el.dataset.studioGrid!;
      if (byPath.has(gridPath)) continue;
      const synthetic = make(el, gridPath, 'wcm/foundation/components/responsivegrid', 'Layout Container', true);
      synthetic.grid = el;
      add(synthetic, el);
      continue;
    }
    if (byPath.has(path)) continue; // same resource decorated twice (nested includes): keep the outer one
    add(
      make(el, path, el.dataset.studioType ?? '', el.dataset.studioTitle ?? '', el.dataset.studioContainer === 'true', {
        dialog: el.dataset.studioDialog === 'true',
        designDialog: el.dataset.studioDesignDialog === 'true',
        drop: el.dataset.studioDrop,
        dropAccept: el.dataset.studioDropAccept,
        msm: el.dataset.studioMsm === 'inherited' || el.dataset.studioMsm === 'cancelled' ? el.dataset.studioMsm : undefined,
      }),
      el,
    );
  }

  function add(e: Editable, el: HTMLElement) {
    let p: HTMLElement | null = el.parentElement;
    while (p && !byElement.has(p)) p = p.parentElement;
    const parent = p ? byElement.get(p)! : null;
    e.parent = parent;
    e.depth = parent ? parent.depth + 1 : 0;
    (parent ? parent.children : roots).push(e);
    byElement.set(el, e);
    byPath.set(e.path, e);
    all.push(e);
  }

  // Placeholders and grids belong to the container with the same path.
  for (const ph of Array.from(doc.querySelectorAll<HTMLElement>('[data-studio-placeholder]'))) {
    const owner = byPath.get(ph.dataset.studioPlaceholder!);
    if (owner) {
      owner.placeholder = ph;
      owner.container = true;
    }
  }
  for (const ph of newpars) {
    const owner = byPath.get(ph.dataset.studioPath!.slice(0, -2));
    if (owner && !owner.placeholder) {
      owner.placeholder = ph;
      owner.container = true;
    }
  }
  for (const grid of Array.from(doc.querySelectorAll<HTMLElement>('[data-studio-grid]'))) {
    const owner = byPath.get(grid.dataset.studioGrid!);
    if (owner && !owner.grid) owner.grid = grid;
  }
  // An empty container that rendered no slot of its own (an unlocked template container the page has no content
  // for yet: its `<path>/*` include does not resolve): give it the slot Core's container would have rendered.
  for (const e of all) {
    if (!e.container || e.placeholder || e.grid || e.children.length || e.msm === 'inherited') continue;
    const slot = doc.createElement('div');
    slot.className = 'new section aem-Grid-newComponent';
    slot.dataset.studioPath = `${e.path}/*`;
    (e.el.querySelector<HTMLElement>(':scope > .cmp-container') ?? e.el).appendChild(slot);
    e.placeholder = slot;
  }
  return { roots, byPath, all };
}

function make(
  el: HTMLElement,
  path: string,
  type: string,
  title: string,
  container: boolean,
  extra: Partial<Editable> = {},
): Editable {
  return {
    path,
    type,
    title: title || type.split('/').pop() || path.split('/').pop() || path,
    el,
    parent: null,
    children: [],
    depth: 0,
    dialog: false,
    designDialog: false,
    container,
    placeholder: null,
    grid: null,
    ...extra,
  };
}

/** The container a component sits in (its parent editable when that is a container). */
export function containerOf(e: Editable): Editable | null {
  return e.parent && e.parent.container ? e.parent : null;
}

export function isAncestorOrSelf(ancestor: string, path: string): boolean {
  return path === ancestor || path.startsWith(`${ancestor}/`);
}

/** Next sibling component inside the same container, or null when `e` is last. */
export function nextSibling(e: Editable): Editable | null {
  const siblings = e.parent?.children ?? [];
  const i = siblings.indexOf(e);
  return i >= 0 && i + 1 < siblings.length ? siblings[i + 1] : null;
}

export function ancestors(e: Editable): Editable[] {
  const out: Editable[] = [];
  for (let p = e.parent; p; p = p.parent) out.push(p);
  return out;
}

/** Does a mime type match an `accept` list of regular expressions (`image/.*`, `video/.*`)? */
export function accepts(accept: string | undefined, mimeType: string): boolean {
  if (!accept) return true;
  return accept
    .split(',')
    .map((a) => a.trim())
    .filter(Boolean)
    .some((a) => {
      try {
        return new RegExp(`^${a}$`).test(mimeType);
      } catch {
        return a === mimeType;
      }
    });
}

// ------------------------------------------------------------------------------------------ layout grid

export interface ColumnState {
  width: number;
  offset: number;
  hidden: boolean;
  newline: boolean;
}

/** Reads the column classes Sensible Studio's grid writes (`aem-GridColumn--<bp>--<n>`) for a breakpoint. */
export function columnState(el: HTMLElement, breakpoint: string, columns = 12): ColumnState {
  const read = (bp: string): ColumnState | null => {
    let width: number | null = null;
    let offset = 0;
    let hidden = false;
    let newline = false;
    for (const c of Array.from(el.classList)) {
      let m = c.match(new RegExp(`^aem-GridColumn--${bp}--(\\d+)$`));
      if (m) width = Number(m[1]);
      m = c.match(new RegExp(`^aem-GridColumn--offset--${bp}--(\\d+)$`));
      if (m) offset = Number(m[1]);
      if (c === `aem-GridColumn--${bp}--hide`) hidden = true;
      if (c === `aem-GridColumn--${bp}--newline`) newline = true;
    }
    return width === null && !hidden ? null : { width: width ?? columns, offset, hidden, newline };
  };
  return read(breakpoint) ?? read('default') ?? { width: columns, offset: 0, hidden: false, newline: false };
}

/** Rewrites the column classes for one breakpoint, so the page reflows while the author drags. */
export function applyColumnState(el: HTMLElement, breakpoint: string, state: ColumnState) {
  for (const c of Array.from(el.classList)) {
    if (c.startsWith(`aem-GridColumn--${breakpoint}--`) || c.startsWith(`aem-GridColumn--offset--${breakpoint}--`)) {
      el.classList.remove(c);
    }
  }
  if (state.hidden) {
    el.classList.add(`aem-GridColumn--${breakpoint}--hide`);
  } else {
    el.classList.add(`aem-GridColumn--${breakpoint}--${state.width}`);
    // Including offset 0: the `default` offset rules are outside any media query, so without an explicit 0 the
    // component keeps the desktop indent at this breakpoint and the left handle appears not to move it back.
    el.classList.add(`aem-GridColumn--offset--${breakpoint}--${state.offset}`);
  }
  if (state.newline) el.classList.add(`aem-GridColumn--${breakpoint}--newline`);
}

export function gridColumns(grid: HTMLElement | null): number {
  const m = grid ? Array.from(grid.classList).map((c) => c.match(/^aem-Grid--(\d+)$/)).find(Boolean) : null;
  return m ? Number(m[1]) : 12;
}

/** The breakpoint for a viewport width: the smallest whose `width` is at least `viewport`, else `default`. */
export function breakpointFor(viewport: number, breakpoints: Array<{ name: string; width?: number }>): string {
  const sorted = [...breakpoints].filter((b) => b.width).sort((a, b) => a.width! - b.width!);
  for (const b of sorted) if (viewport < b.width!) return b.name;
  return 'default';
}
