import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import type { Placement } from '../api/StudioClient';
import {
  applyColumnState,
  columnState,
  containerOf,
  gridColumns,
  type ColumnState,
  type Editable,
  type EditableTree,
} from '../model/editables';
import { currentDrag, endDrag, MIME, type DragPayload } from './dragPayload';
import { boxOf, dropTargetAt, hitTest, targetKey, toolbarPosition, type Box, type DropTarget, type Target } from './geometry';
import { useFrameTick } from './useFrameTick';

export type OverlayMode = 'edit' | 'layout';

export type DropPayload = DragPayload | { kind: 'move'; editable: Editable };

interface Props {
  mode: OverlayMode;
  /** CSS scale the emulated viewport is drawn at (1 = none): pointer positions are divided by it. */
  scale?: number;
  doc: Document | null;
  tree: EditableTree;
  selection: Target | null;
  /** Further selected components (group selection). */
  group?: Editable[];
  /** `toggle`: Cmd/Ctrl-click, adds to or removes from the group. */
  onSelect(target: Target | null, toggle?: boolean): void;
  onConfigure(e: Editable): void;
  /** Toolbar contents for the selection. */
  toolbar(target: Target): ReactNode;
  /** Edit mode: a selected container gets a + at its bottom left that inserts a component at its end. */
  onInsertInto?(container: Editable): void;
  /** A component is edited in place: the frame takes the pointer; the overlay only shows the selection and its toolbar. */
  inline?: boolean;
  /** May `payload` go into `container`? */
  allowed(container: Editable, payload: DropPayload): boolean;
  onDrop(payload: DropPayload, target: DropTarget): void;
  /** Layout mode: current breakpoint and where a resized column is saved. */
  breakpoint: string;
  onColumnChange(e: Editable, state: ColumnState): void;
  /** Template structure: components authors may edit on pages (outlined in orange, with an open lock). */
  unlocked?(e: Editable): boolean;
  /**
   * Template structure: what an unlocked container shows instead of "Drag components here" (its Allowed
   * Components); the container's slot grows to fit it.
   */
  containerPanel?(e: Editable): ReactNode | null;
  /** Containers whose "Drag components here" slot is not shown (locked parts of a template's initial content). */
  hidePlaceholder?(e: Editable): boolean;
  /** Drawn over the page and under the toolbar (the annotations in Edit mode); stops its own pointer events. */
  children?: ReactNode;
}

interface ResizeDrag {
  editable: Editable;
  edge: 'left' | 'right';
  startX: number;
  start: ColumnState;
  colWidth: number;
  columns: number;
  current: ColumnState;
}

const DRAG_THRESHOLD = 6;

/**
 * The layer over the content frame. It takes all pointer input in Edit and Layout mode (the page's own
 * links and scripts do not react), outlines what is under the pointer, shows the selection with its
 * toolbar, and handles dropping components and assets, moving components and resizing grid columns.
 */
export function Overlay(props: Props) {
  const { mode, doc, tree, selection, group = [], onSelect, onConfigure, toolbar, allowed, onDrop, breakpoint, onColumnChange, inline = false, unlocked, containerPanel, hidePlaceholder, onInsertInto } = props;
  const isUnlocked = (t: Target | null) => !!t && t.kind === 'editable' && !!unlocked?.(t.editable);
  useFrameTick(doc);
  const layerRef = useRef<HTMLDivElement>(null);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<Target | null>(null);
  const [drop, setDropState] = useState<{ payload: DropPayload; target: DropTarget | null } | null>(null);
  const dropRef = useRef<{ payload: DropPayload; target: DropTarget | null } | null>(null);
  const setDrop = (d: { payload: DropPayload; target: DropTarget | null } | null) => {
    dropRef.current = d;
    setDropState(d);
  };
  const pressRef = useRef<{ x: number; y: number; editable: Editable } | null>(null);
  const [resize, setResizeState] = useState<ResizeDrag | null>(null);
  // pointer events can arrive faster than React re-renders: handlers read the live value from the ref
  const resizeRef = useRef<ResizeDrag | null>(null);
  const setResize = (r: ResizeDrag | null) => {
    resizeRef.current = r;
    setResizeState(r);
  };
  const [toolbarSize, setToolbarSize] = useState({ width: 0, height: 0 });

  const layoutFilter = (e: Editable) => mode !== 'layout' || e.container || !!containerOf(e)?.grid;
  const scale = props.scale ?? 1;
  const point = (e: { clientX: number; clientY: number }) => {
    const r = layerRef.current!.getBoundingClientRect();
    return { x: (e.clientX - r.left) / scale, y: (e.clientY - r.top) / scale };
  };
  const hit = (x: number, y: number) => hitTest(tree, x, y, mode === 'layout' ? layoutFilter : undefined);

  // ------------------------------------------------------------------------------------------ pointer

  const onPointerMove = (e: React.PointerEvent) => {
    if (inline) return;
    const { x, y } = point(e);
    const resize = resizeRef.current;
    if (resize) {
      const delta = Math.round((e.clientX - resize.startX) / scale / resize.colWidth);
      const s = resize.start;
      const next =
        resize.edge === 'right'
          ? { ...s, width: clamp(s.width + delta, 1, resize.columns - s.offset) }
          : (() => {
              const offset = clamp(s.offset + delta, 0, s.offset + s.width - 1);
              return { ...s, offset, width: s.width - (offset - s.offset) };
            })();
      if (next.width !== resize.current.width || next.offset !== resize.current.offset) {
        applyColumnState(resize.editable.el, breakpoint, next);
        setResize({ ...resize, current: next });
      }
      return;
    }
    const press = pressRef.current;
    const drop = dropRef.current;
    if (press && !drop && Math.hypot(x - press.x, y - press.y) > DRAG_THRESHOLD && mode === 'edit' && containerOf(press.editable)) {
      setDrop({ payload: { kind: 'move', editable: press.editable }, target: null });
    }
    const dragging = dropRef.current;
    if (dragging?.payload.kind === 'move') {
      const payload = dragging.payload;
      setDrop({ payload, target: dropTargetAt(tree, x, y, (c) => allowed(c, payload), payload.editable) });
      return;
    }
    const h = hit(x, y);
    if (targetKey(h) !== targetKey(hover)) setHover(h);
  };

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0 || inline) return;
    const { x, y } = point(e);
    const h = hit(x, y);
    const toggle = (e.metaKey || e.ctrlKey) && mode === 'edit';
    onSelect(h, toggle);
    if (h?.kind === 'editable' && !toggle) {
      pressRef.current = { x, y, editable: h.editable };
      layerRef.current?.setPointerCapture(e.pointerId);
    }
  };

  const onPointerUp = (e: React.PointerEvent) => {
    pressRef.current = null;
    const resize = resizeRef.current;
    if (resize) {
      onPointerMove(e); // the release position counts
      const done = resizeRef.current ?? resize;
      const changed = done.current.width !== done.start.width || done.current.offset !== done.start.offset;
      if (changed) onColumnChange(done.editable, done.current);
      setResize(null);
      return;
    }
    if (dropRef.current?.payload.kind === 'move') {
      onPointerMove(e);
      const done = dropRef.current;
      if (done?.target) onDrop(done.payload, done.target);
      setDrop(null);
    }
  };

  const onDoubleClick = (e: React.MouseEvent) => {
    if (mode !== 'edit' || inline) return;
    const { x, y } = point(e);
    const h = hit(x, y);
    if (h?.kind === 'editable' && h.editable.dialog) onConfigure(h.editable);
    // on an empty drop zone a double click asks what to put there
    else if (h?.kind === 'placeholder' && onInsertInto && !hidePlaceholder?.(h.container)) onInsertInto(h.container);
  };

  const onWheel = (e: React.WheelEvent) => {
    doc?.defaultView?.scrollBy({ left: e.deltaX, top: e.deltaY });
  };

  // ------------------------------------------------------------------------------------------ side panel drops

  const onDragOver = (e: React.DragEvent) => {
    const payload = currentDrag();
    if (!payload || !e.dataTransfer.types.includes(MIME) || mode !== 'edit') return;
    e.preventDefault();
    const { x, y } = point(e);
    let target = dropTargetAt(tree, x, y, (c) => allowed(c, payload));
    if (payload.kind === 'asset') {
      const h = hitTest(tree, x, y);
      const onto = h?.kind === 'editable' ? assetTarget(h.editable, payload) : null;
      if (onto) {
        const box = boxOf(onto.el);
        if (box) target = { placement: { container: onto.parent?.path ?? '', before: null }, indicator: { kind: 'box', box }, onto };
      }
    }
    e.dataTransfer.dropEffect = target ? 'copy' : 'none';
    const prev = dropRef.current;
    if (targetKeyOfDrop(target) !== targetKeyOfDrop(prev?.target ?? null) || prev?.payload !== payload) setDrop({ payload, target });
  };

  const assetTarget = (e: Editable, payload: Extract<DragPayload, { kind: 'asset' }>) => {
    for (let c: Editable | null = e; c; c = c.parent) {
      if (c.drop && allowed(c, payload)) return c;
    }
    return null;
  };

  const onDropEvent = (e: React.DragEvent) => {
    e.preventDefault();
    const done = dropRef.current;
    if (done?.target) onDrop(done.payload, done.target);
    setDrop(null);
    endDrag();
  };

  // ------------------------------------------------------------------------------------------ layout handles

  const startResize = (e: React.PointerEvent, editable: Editable, edge: 'left' | 'right') => {
    e.stopPropagation();
    const grid = containerOf(editable)?.grid ?? null;
    const gb = boxOf(grid);
    if (!gb) return;
    const columns = gridColumns(grid);
    const start = columnState(editable.el, breakpoint, columns);
    layerRef.current?.setPointerCapture(e.pointerId);
    setResize({ editable, edge, startX: e.clientX, start, current: start, colWidth: gb.width / columns, columns });
  };

  // ------------------------------------------------------------------------------------------ render

  const selectionBox = selection ? boxOf(selection.kind === 'editable' ? selection.editable.el : selection.container.placeholder) : null;
  const bounds = { width: layerRef.current?.clientWidth ?? 0, height: layerRef.current?.clientHeight ?? 0 };

  useLayoutEffect(() => {
    const el = toolbarRef.current;
    if (!el) return;
    const size = { width: el.offsetWidth, height: el.offsetHeight };
    if (size.width !== toolbarSize.width || size.height !== toolbarSize.height) setToolbarSize(size);
  });

  const hoverBox = hover && !inline && targetKey(hover) !== targetKey(selection) ? boxOf(hover.kind === 'editable' ? hover.editable.el : hover.container.placeholder) : null;
  const moving = drop?.payload.kind === 'move' ? drop.payload.editable : null;

  return (
    <div
      ref={layerRef}
      className={`studio-overlay studio-overlay--${mode}${drop ? ' is-dragging' : ''}${resize ? ' is-resizing' : ''}${inline ? ' is-inline' : ''}`}
      onPointerMove={onPointerMove}
      onPointerDown={onPointerDown}
      onPointerUp={onPointerUp}
      onPointerLeave={() => !pressRef.current && setHover(null)}
      onDoubleClick={onDoubleClick}
      onWheel={onWheel}
      onDragOver={onDragOver}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) setDrop(null);
      }}
      onDrop={onDropEvent}
      onContextMenu={(e) => e.preventDefault()}
      data-testid="overlay"
    >
      {/* Placeholders of every container: "Drag components here". */}
      {mode === 'edit' &&
        tree.all.map((c) => {
          const b = c.placeholder && !hidePlaceholder?.(c) ? boxOf(c.placeholder) : null;
          if (!b) return null;
          const panel = containerPanel?.(c);
          if (panel) {
            return (
              <SlotPanel key={`${c.path}/*`} slot={c.placeholder!} box={b}>
                {panel}
              </SlotPanel>
            );
          }
          return (
            <div key={`${c.path}/*`} className="studio-ov-placeholder" style={place(b)}>
              <span>{placeholderText(c)}</span>
            </div>
          );
        })}

      {/* Empty components (Core's `cq-placeholder`, e.g. an Image without an asset): their name, like AEM. */}
      {mode === 'edit' &&
        tree.all.map((e) => {
          // while its text is being typed in place the component is not empty any more: no name over the words
          const typing = inline && selection?.kind === 'editable' && selection.editable.path === e.path;
          const text = typing ? null : emptyText(e);
          const b = text ? boxOf(e.el) : null;
          return b ? (
            <div key={`${e.path}#empty`} className="studio-ov-placeholder studio-ov-empty" style={place(b)}>
              <span>{text}</span>
            </div>
          ) : null;
        })}

      {mode === 'layout' &&
        tree.all
          .filter((e) => !e.container && containerOf(e)?.grid)
          .map((e) => {
            const b = boxOf(e.el);
            return b ? <div key={e.path} className="studio-ov-column" style={place(b)} /> : null;
          })}

      {mode === 'layout' && selection?.kind === 'editable' && <GridGuides editable={selection.editable} />}

      {hoverBox && hover && !drop && (
        <div className={`studio-ov-hover${isUnlocked(hover) ? ' is-unlocked' : ''}`} style={place(hoverBox)}>
          <span className="studio-ov-label studio-ov-label--hover">
            {isUnlocked(hover) && <LockGlyph />}
            {labelOf(hover)}
          </span>
        </div>
      )}

      {moving && boxOf(moving.el) && <div className="studio-ov-moving" style={place(boxOf(moving.el)!)} />}

      {group.map((g) => {
        const b = boxOf(g.el);
        return b ? (
          <div key={g.path} className="studio-ov-selected is-grouped" style={place(b)}>
            <span className="studio-ov-label">{g.title}</span>
          </div>
        ) : null;
      })}

      {selection && selectionBox && (
        <div
          className={`studio-ov-selected${selection.kind === 'placeholder' ? ' is-placeholder' : ''}${inline ? ' is-inline' : ''}${isUnlocked(selection) ? ' is-unlocked' : ''}${mode === 'edit' && !inline && selection.kind === 'editable' && selection.editable.container && onInsertInto && !hidePlaceholder?.(selection.editable) ? ' has-add' : ''}`}
          style={place(selectionBox)}
        >
          {!inline && (
            <span className="studio-ov-label">
              {isUnlocked(selection) && <LockGlyph />}
              {labelOf(selection)}
            </span>
          )}
          {mode === 'layout' && selection.kind === 'editable' && containerOf(selection.editable)?.grid && (
            <>
              <span className="studio-ov-handle studio-ov-handle--left" onPointerDown={(e) => startResize(e, selection.editable, 'left')} />
              <span className="studio-ov-handle studio-ov-handle--right" onPointerDown={(e) => startResize(e, selection.editable, 'right')} />
            </>
          )}
          {mode === 'edit' && !inline && selection.kind === 'editable' && selection.editable.container && onInsertInto && !hidePlaceholder?.(selection.editable) && (
            <button
              type="button"
              className="studio-ov-add"
              aria-label="Insert component"
              title="Insert component at the end"
              onPointerDown={(e) => e.stopPropagation()}
              onPointerUp={(e) => e.stopPropagation()}
              onClick={(e) => {
                e.stopPropagation();
                onInsertInto(selection.editable);
              }}
            >
              +
            </button>
          )}
        </div>
      )}

      {resize && selectionBox && (
        <div className="studio-ov-resize-info" style={{ left: selectionBox.left, top: selectionBox.top + selectionBox.height + 4 }}>
          {resize.current.width} / {resize.columns}
          {resize.current.offset > 0 && ` · offset ${resize.current.offset}`}
        </div>
      )}

      {drop?.target &&
        (drop.target.indicator.kind === 'line' ? (
          <div
            className="studio-ov-insert-line"
            style={{ left: drop.target.indicator.left, top: drop.target.indicator.top - 2, width: drop.target.indicator.width }}
          />
        ) : (
          <div className="studio-ov-drop-box" style={place(drop.target.indicator.box)} />
        ))}

      {props.children}

      {selection && selectionBox && !drop && !resize && (
        <div
          ref={toolbarRef}
          className="studio-ov-toolbar"
          style={toolbarPosition(selectionBox, toolbarSize, bounds)}
          onPointerDown={(e) => e.stopPropagation()}
          onPointerUp={(e) => e.stopPropagation()}
          onDoubleClick={(e) => e.stopPropagation()}
        >
          {toolbar(selection)}
        </div>
      )}
    </div>
  );
}

/** An open padlock, before the label of an unlocked structure component. */
function LockGlyph() {
  return (
    <svg className="studio-lock-glyph" width="10" height="10" viewBox="0 0 10 10" aria-label="Unlocked" role="img">
      <rect x="1" y="4.5" width="8" height="5" rx="1" fill="currentColor" />
      <path d="M3 4.5V3a2 2 0 0 1 3.9-.6" fill="none" stroke="currentColor" strokeWidth="1.2" />
    </svg>
  );
}

/**
 * Content drawn over a container's empty slot (an unlocked container's Allowed Components). The slot in the page
 * grows to the content's height, so the page around it makes room, as AEM's template editor does.
 */
function SlotPanel({ slot, box, children }: { slot: HTMLElement; box: Box; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const fit = () => {
      const h = `${Math.ceil(el.scrollHeight)}px`;
      if (slot.style.minHeight !== h) slot.style.minHeight = h;
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [slot]);
  return (
    <div
      ref={ref}
      className="studio-ov-panel"
      style={{ left: box.left, top: box.top, width: box.width }}
    >
      {children}
    </div>
  );
}

/** Translucent columns of the grid the selected component sits in. */
function GridGuides({ editable }: { editable: Editable }) {
  const grid = containerOf(editable)?.grid ?? null;
  const b = boxOf(grid);
  if (!b) return null;
  const columns = gridColumns(grid);
  return (
    <div className="studio-ov-grid" style={{ ...place(b), gridTemplateColumns: `repeat(${columns}, 1fr)` }}>
      {Array.from({ length: columns }, (_, i) => (
        <span key={i} />
      ))}
    </div>
  );
}

function labelOf(t: Target): string {
  return t.kind === 'editable' ? t.editable.title : 'Drag components here';
}

const place = (b: Box) => ({ left: b.left, top: b.top, width: b.width, height: b.height });
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

function targetKeyOfDrop(t: DropTarget | null): string {
  if (!t) return '';
  return `${t.placement.container}|${t.placement.before ?? ''}|${t.onto?.path ?? ''}`;
}

export type { Target, DropTarget, Placement };

/** The `data-emptytext` of the component's own `cq-placeholder` (not one of a component inside it), if it shows one. */
function emptyText(e: Editable): string | null {
  for (const ph of Array.from(e.el.querySelectorAll<HTMLElement>('.cq-placeholder'))) {
    if (ph.closest('[data-studio-path]') !== e.el) continue;
    return ph.dataset.emptytext || e.title || null;
  }
  return null;
}

/**
 * The text of a container's slot, as AEM's editor shows it: the container's own `data-placeholder-text` (Core's
 * Carousel, Tabs and Accordion: "Please add Carousel components here"), else "Drag components here".
 */
function placeholderText(c: Editable): string {
  const owner = c.placeholder?.closest<HTMLElement>('[data-placeholder-text]');
  const text = owner && c.el.contains(owner) ? owner.dataset.placeholderText : '';
  return text || 'Drag components here';
}
