import { ActionButton, Button, ButtonGroup, Content, DialogContainer, Divider, Heading, TextArea, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import Delete from '@spectrum-icons/workflow/Delete';
import DragHandle from '@spectrum-icons/workflow/DragHandle';
import { useEffect, useRef, useState } from 'react';
import type { EditableTree } from '../model/editables';
import { boxOf, hitTest } from '../overlay/geometry';
import { useFrameTick } from '../overlay/useFrameTick';
import { COLORS, type Annotation, type Arrow, type Point } from './annotations';
import { bend, curvePath, headOf, lengthOf, middleOf, shift, simplify } from './arrows';
import { StudioDialog } from '../../shell/StudioDialog';

/** Where a marker was dropped: its component (maybe another one) and position there, and its arrows reshaped. */
export interface AnnotationMove {
  component: string;
  x: number;
  y: number;
  arrows: { arrow: Arrow; points: Point[] }[];
}

interface Props {
  doc: Document | null;
  /** CSS scale the emulated viewport is drawn at (1 = none): pointer positions are divided by it. */
  scale?: number;
  tree: EditableTree;
  annotations: Annotation[];
  onChange(annotation: Annotation, changes: { text?: string; color?: string }): Promise<void>;
  onDelete(annotation: Annotation): Promise<void>;
  /** A marker was dragged somewhere else (by its grip). */
  onMove?(annotation: Annotation, to: AnnotationMove): Promise<void>;
  /** An arrow was drawn from a marker (points in the frame of the annotation's component). */
  onAddArrow?(annotation: Annotation, points: Point[]): Promise<void>;
  onDeleteArrow?(arrow: Arrow): Promise<void>;
  /** The annotation whose note is open (just created ones open at once). */
  open: string | null;
  onOpen(path: string | null): void;
}

/** Arrow head length, px. */
const HEAD = 16;
/** Strokes shorter than this are a slip, not an arrow. */
const MIN_ARROW = 24;
/** A marker moved less than this was clicked, not dragged. */
const MIN_MOVE = 3;

interface Drag {
  path: string;
  /** Where the pointer holds the marker, from its top left corner. */
  offset: Point;
  from: Point;
  at: Point;
  pointer: Point;
  saving?: boolean;
}

interface Drawing {
  path: string;
  points: Point[];
  saving?: boolean;
}

const stop = (e: React.SyntheticEvent) => e.stopPropagation();

/** The handle keeps the pointer while it is dragged (a pointer the browser no longer tracks cannot be captured). */
const capture = (e: React.PointerEvent) => {
  try {
    (e.currentTarget as Element).setPointerCapture?.(e.pointerId);
  } catch {
    // moves outside the handle are then missed, the drag still ends on release
  }
};

/**
 * The annotations over the page in Edit mode: a marker per note on its component, showing the note's first words (all
 * of it on hover, the note itself on click), and the arrows drawn from it. A marker moves by its grip (onto another
 * component too) and draws an arrow from its arrow handle. Only markers, notes and arrows take the pointer: the page
 * underneath stays editable.
 */
export function AnnotationsLayer(props: Props) {
  const { doc, tree, annotations, onChange, onDelete, onMove, onAddArrow, onDeleteArrow, open, onOpen, scale = 1 } = props;
  useFrameTick(doc);
  const layerRef = useRef<HTMLDivElement>(null);
  /** The marker under the mouse: its whole note shows next to it. */
  const [peek, setPeek] = useState<string | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [drawing, setDrawing] = useState<Drawing | null>(null);
  const [selectedArrow, setSelectedArrow] = useState<string | null>(null);
  // a press anywhere else closes the note and lets go of the selected arrow
  const openRef = useRef(open);
  openRef.current = open;
  useEffect(() => {
    const onDown = (e: PointerEvent) => {
      const t = e.target as Element | null;
      if (t?.closest?.('.studio-annotate__note, .studio-annotate__marker, .studio-annotate__hit, .studio-annotate__arrow-delete, [role="dialog"]')) return;
      setSelectedArrow(null);
      if (openRef.current) onOpen(null);
    };
    window.addEventListener('pointerdown', onDown, true);
    return () => window.removeEventListener('pointerdown', onDown, true);
  }, [onOpen]);

  /** The pointer in the layer's frame (the page's CSS px). */
  const layerPoint = (e: { clientX: number; clientY: number }): Point => {
    const r = layerRef.current?.getBoundingClientRect() ?? { left: 0, top: 0 };
    return { x: (e.clientX - r.left) / scale, y: (e.clientY - r.top) / scale };
  };

  const markers = annotations
    .map((a, i) => {
      const owner = tree.byPath.get(a.component);
      const b = owner ? boxOf(owner.el) : null;
      if (!b) return null;
      const left = b.left + Math.min(a.x, Math.max(0, b.width - 12));
      const top = b.top + Math.min(a.y, Math.max(0, b.height - 12));
      const moving = drag?.path === a.path ? drag : null;
      const delta = moving ? { x: moving.at.x - left, y: moving.at.y - top } : null;
      const arrows = (a.arrows ?? []).map((arrow) => {
        const abs = shift(arrow.points, { x: b.left, y: b.top });
        return { arrow, points: delta ? bend(abs, delta) : abs };
      });
      return { a, i, box: b, left: moving ? moving.at.x : left, top: moving ? moving.at.y : top, arrows };
    })
    .filter((m): m is NonNullable<typeof m> => !!m);
  type Marker = (typeof markers)[number];
  const current = drag ? null : (markers.find((m) => m.a.path === open) ?? null);
  const peeked = peek && !drag && !drawing ? (markers.find((m) => m.a.path === peek) ?? null) : null;

  // ---------------------------------------------------------------------------------------- moving a marker

  const startDrag = (e: React.PointerEvent, m: Marker) => {
    if (e.button !== 0 || !onMove) return;
    e.stopPropagation();
    e.preventDefault();
    capture(e);
    const p = layerPoint(e);
    setPeek(null);
    setDrag({ path: m.a.path, offset: { x: p.x - m.left, y: p.y - m.top }, from: { x: m.left, y: m.top }, at: { x: m.left, y: m.top }, pointer: p });
  };

  const moveDrag = (e: React.PointerEvent) => {
    e.stopPropagation();
    setDrag((d) => {
      if (!d || d.saving) return d;
      const p = layerPoint(e);
      return { ...d, pointer: p, at: { x: p.x - d.offset.x, y: p.y - d.offset.y } };
    });
  };

  const endDrag = async (e: React.PointerEvent, m: Marker) => {
    e.stopPropagation();
    const d = drag;
    if (!d || d.saving || !onMove) return;
    const p = layerPoint(e);
    const at = { x: p.x - d.offset.x, y: p.y - d.offset.y };
    if (Math.hypot(at.x - d.from.x, at.y - d.from.y) < MIN_MOVE) {
      setDrag(null);
      return;
    }
    // dropped on another component: the note goes there (the deepest component under the pointer)
    const hit = hitTest(tree, p.x, p.y);
    const target = hit?.kind === 'editable' && boxOf(hit.editable.el) ? hit.editable : tree.byPath.get(m.a.component);
    const tb = target ? boxOf(target.el) : null;
    if (!target || !tb) {
      setDrag(null);
      return;
    }
    const delta = { x: at.x - d.from.x, y: at.y - d.from.y };
    const arrows = (m.a.arrows ?? []).map((arrow) => ({
      arrow,
      points: shift(bend(shift(arrow.points, { x: m.box.left, y: m.box.top }), delta), { x: -tb.left, y: -tb.top }),
    }));
    setDrag({ ...d, at, saving: true });
    try {
      await onMove(m.a, { component: target.path, x: Math.round(at.x - tb.left), y: Math.round(at.y - tb.top), arrows });
    } finally {
      setDrag(null);
    }
  };

  // ---------------------------------------------------------------------------------------- drawing an arrow

  const startDraw = (e: React.PointerEvent, m: Marker) => {
    if (e.button !== 0 || !onAddArrow) return;
    e.stopPropagation();
    e.preventDefault();
    capture(e);
    setPeek(null);
    setSelectedArrow(null);
    setDrawing({ path: m.a.path, points: [layerPoint(e)] });
  };

  const moveDraw = (e: React.PointerEvent) => {
    e.stopPropagation();
    setDrawing((d) => {
      if (!d || d.saving) return d;
      const p = layerPoint(e);
      const last = d.points[d.points.length - 1];
      return Math.hypot(p.x - last.x, p.y - last.y) < 3 ? d : { ...d, points: [...d.points, p] };
    });
  };

  const endDraw = async (e: React.PointerEvent, m: Marker) => {
    e.stopPropagation();
    const d = drawing;
    if (!d || d.saving || !onAddArrow) return;
    const p = layerPoint(e);
    const points = simplify([...d.points, p]);
    if (lengthOf(points) < MIN_ARROW) {
      setDrawing(null);
      return;
    }
    setDrawing({ ...d, points, saving: true });
    try {
      await onAddArrow(m.a, shift(points, { x: -m.box.left, y: -m.box.top }));
    } finally {
      setDrawing(null);
    }
  };

  const selected = selectedArrow ? markers.flatMap((m) => m.arrows.map((x) => ({ m, ...x }))).find((x) => x.arrow.path === selectedArrow) : undefined;

  return (
    <div ref={layerRef} className={`studio-annotate${drag ? ' is-moving' : ''}${drawing ? ' is-drawing' : ''}`} data-testid="annotations">
      <svg className="studio-annotate__arrows" aria-hidden="true">
        {markers.flatMap((m) =>
          m.arrows.map(({ arrow, points }) => (
            <g key={arrow.path} className={`studio-annotate__arrow${arrow.path === selectedArrow ? ' is-selected' : ''}`} style={{ color: m.a.color }}>
              <ArrowShape points={points} />
              {onDeleteArrow && (
                <path
                  className="studio-annotate__hit"
                  d={curvePath(points)}
                  onPointerDown={(e) => {
                    e.stopPropagation();
                    setSelectedArrow(arrow.path);
                  }}
                  onPointerUp={stop}
                  onClick={stop}
                  onDoubleClick={stop}
                />
              )}
            </g>
          )),
        )}
        {drawing && (
          <g className="studio-annotate__arrow is-drawing" style={{ color: markers.find((m) => m.a.path === drawing.path)?.a.color }}>
            <ArrowShape points={drawing.points.length > 1 ? drawing.points : [drawing.points[0], drawing.points[0]]} />
          </g>
        )}
      </svg>
      {selected && onDeleteArrow && (
        <button
          type="button"
          className="studio-annotate__arrow-delete"
          aria-label="Delete arrow"
          style={{ left: middleOf(selected.points).x - 12, top: middleOf(selected.points).y - 12 }}
          onPointerDown={stop}
          onPointerUp={stop}
          onClick={(e) => {
            e.stopPropagation();
            setSelectedArrow(null);
            void onDeleteArrow(selected.arrow);
          }}
        >
          <Close size="XS" />
        </button>
      )}
      {markers.map((m) => {
        const short = excerpt(m.a.text);
        const isOpen = m.a.path === open;
        const isDragged = drag?.path === m.a.path;
        return (
          <div
            key={m.a.path}
            className={`studio-annotate__marker${isOpen ? ' is-open' : ''}${m.a.text ? '' : ' is-empty'}${isDragged ? ' is-dragged' : ''}`}
            style={{ left: m.left, top: m.top, ['--studio-annotation-color' as string]: m.a.color }}
            onPointerDown={stop}
            onPointerUp={stop}
            onPointerMove={stop}
            onDoubleClick={stop}
          >
            {onMove && (
              <button
                type="button"
                className="studio-annotate__grip"
                aria-label="Move annotation"
                title="Drag to move"
                onPointerDown={(e) => startDrag(e, m)}
                onPointerMove={moveDrag}
                onPointerUp={(e) => void endDrag(e, m)}
                onPointerCancel={() => setDrag(null)}
                onClick={stop}
              >
                <DragHandle size="XS" />
              </button>
            )}
            <button
              type="button"
              className="studio-annotate__open"
              aria-label={`Annotation ${m.i + 1}${m.a.text ? `: ${m.a.text}` : ''}`}
              onMouseEnter={() => setPeek(m.a.path)}
              onMouseLeave={() => setPeek((p) => (p === m.a.path ? null : p))}
              onClick={(e) => {
                e.stopPropagation();
                setPeek(null);
                onOpen(isOpen ? null : m.a.path);
              }}
            >
              <span className="studio-annotate__dot" aria-hidden="true" />
              <span className="studio-annotate__excerpt">{short.text || 'Empty note'}</span>
              {short.cut && <span className="studio-annotate__more" aria-hidden="true">…</span>}
            </button>
            {onAddArrow && (
              <button
                type="button"
                className="studio-annotate__arrow-handle"
                aria-label="Draw an arrow"
                title="Drag to draw an arrow"
                onPointerDown={(e) => startDraw(e, m)}
                onPointerMove={moveDraw}
                onPointerUp={(e) => void endDraw(e, m)}
                onPointerCancel={() => setDrawing(null)}
                onClick={stop}
              >
                <svg viewBox="0 0 12 12" width="12" height="12" aria-hidden="true">
                  <path d="M2 10 C3 5 6 3 10 2" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                  <path d="M6.5 1.2 L10.6 1.6 L9.4 5.6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
            )}
          </div>
        );
      })}
      {peeked && peeked.a.path !== open && (
        <div
          className="studio-annotate__peek"
          role="tooltip"
          style={{ left: Math.min(peeked.left, Math.max(8, (doc?.defaultView?.innerWidth ?? 600) - 292)), top: peeked.top + 30 }}
        >
          <p className="studio-annotate__peek-text">{peeked.a.text || 'Empty note'}</p>
          {(peeked.a.createdBy || peeked.a.created) && (
            <span className="studio-annotate__meta">
              {peeked.a.createdBy ?? ''}
              {peeked.a.created ? ` · ${new Date(peeked.a.created).toLocaleString()}` : ''}
            </span>
          )}
          {!!peeked.a.arrows?.length && <span className="studio-annotate__meta"> · {peeked.a.arrows.length === 1 ? '1 arrow' : `${peeked.a.arrows.length} arrows`}</span>}
        </div>
      )}
      {current && (
        <Note
          key={current.a.path}
          annotation={current.a}
          style={{ left: Math.min(current.left + 20, Math.max(8, (doc?.defaultView?.innerWidth ?? 600) - 320)), top: current.top + 30 }}
          onChange={(c) => onChange(current.a, c)}
          onDelete={() => onDelete(current.a)}
          onDone={() => onOpen(null)}
        />
      )}
    </div>
  );
}

/** A hand-drawn arrow: the smoothed stroke and a filled head at its end. */
function ArrowShape({ points }: { points: Point[] }) {
  const long = lengthOf(points) > HEAD;
  return (
    <>
      <path className="studio-annotate__stroke" d={curvePath(points, long ? HEAD * 0.7 : 0)} />
      {long && <polygon className="studio-annotate__head" points={headOf(points, HEAD).map((p) => `${p.x},${p.y}`).join(' ')} />}
    </>
  );
}

function Note({
  annotation,
  style,
  onChange,
  onDelete,
  onDone,
}: {
  annotation: Annotation;
  style: React.CSSProperties;
  onChange(c: { text?: string; color?: string }): Promise<void>;
  onDelete(): Promise<void>;
  /** Done: the note is saved and closed */
  onDone(): void;
}) {
  const [text, setText] = useState(annotation.text);
  const [confirm, setConfirm] = useState(false);
  // what was last sent, so that the blur before a click on Done does not save twice
  const saved = useRef(annotation.text);
  const latest = useRef(text);
  latest.current = text;
  /** Deleted: nothing is saved on the way out (it would bring the node back). */
  const gone = useRef(false);
  useEffect(() => {
    setText(annotation.text);
    saved.current = annotation.text;
  }, [annotation.text]);
  const save = () => {
    if (gone.current || latest.current === saved.current) return Promise.resolve();
    saved.current = latest.current;
    return onChange({ text: latest.current });
  };
  // closed by a press elsewhere: what was typed is kept
  const saveRef = useRef(save);
  saveRef.current = save;
  useEffect(() => () => void saveRef.current(), []);
  const done = async () => {
    await save();
    onDone();
  };
  return (
    <div
      className="studio-annotate__note"
      style={style}
      onClick={stop}
      onMouseMove={stop}
      onWheel={stop}
      onPointerDown={stop}
      onPointerUp={stop}
      onPointerMove={stop}
      onDoubleClick={stop}
    >
      <div className="studio-annotate__tools">
        <div className="studio-annotate__colors" role="radiogroup" aria-label="Colour">
          {COLORS.map((c) => (
            <button
              key={c}
              type="button"
              role="radio"
              aria-checked={c === annotation.color}
              aria-label={c}
              className={`studio-annotate__color${c === annotation.color ? ' is-selected' : ''}`}
              style={{ background: c }}
              onClick={() => void onChange({ color: c })}
            />
          ))}
        </div>
        <TooltipTrigger delay={400}>
          <ActionButton isQuiet aria-label="Delete annotation" onPress={() => setConfirm(true)}>
            <Delete />
          </ActionButton>
          <Tooltip>Delete</Tooltip>
        </TooltipTrigger>
      </div>
      <TextArea aria-label="Annotation" placeholder="Add a note" value={text} onChange={setText} onBlur={() => void save()} autoFocus width="100%" />
      <div className="studio-annotate__foot" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 6 }}>
        <span className="studio-annotate__meta">
          {annotation.createdBy ? `${annotation.createdBy}` : ''}
          {annotation.created ? ` · ${new Date(annotation.created).toLocaleString()}` : ''}
        </span>
        <Button variant="accent" onPress={() => void done()}>
          Done
        </Button>
      </div>
      <DialogContainer onDismiss={() => setConfirm(false)}>
        {confirm && (
          <StudioDialog size="S">
            <Heading>Delete annotation</Heading>
            <Divider />
            <Content>The annotation and its arrows are removed from the page.</Content>
            <ButtonGroup>
              <Button variant="secondary" onPress={() => setConfirm(false)}>
                Cancel
              </Button>
              <Button
                variant="negative"
                onPress={() => {
                  gone.current = true;
                  void onDelete();
                }}
              >
                Delete
              </Button>
            </ButtonGroup>
          </StudioDialog>
        )}
      </DialogContainer>
    </div>
  );
}

const EXCERPT_WORDS = 4;
const EXCERPT_CHARS = 28;

/** The first few words of a note, for its marker; `cut` when the note goes on. */
export function excerpt(text: string): { text: string; cut: boolean } {
  const words = text.trim().split(/\s+/).filter(Boolean);
  let out = '';
  let n = 0;
  for (const w of words) {
    const next = out ? `${out} ${w}` : w;
    if (n === EXCERPT_WORDS || (out && next.length > EXCERPT_CHARS)) break;
    out = next;
    n++;
  }
  // one very long word: cut inside it
  if (out.length > EXCERPT_CHARS) out = out.slice(0, EXCERPT_CHARS);
  return { text: out, cut: out.length < words.join(' ').length };
}
