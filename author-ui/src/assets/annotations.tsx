import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode, type TextareaHTMLAttributes } from 'react';
import Brush from '@spectrum-icons/workflow/Brush';
import Circle from '@spectrum-icons/workflow/Circle';
import ArrowUpRight from '@spectrum-icons/workflow/ArrowUpRight';
import Rectangle from '@spectrum-icons/workflow/Rectangle';
import { curvePath, headOf, simplify } from '../editor/annotations/arrows';
import { mentionParts } from '../shell/notifications';
import type { AssetComment, CommentRegion, DamClient, NoteShape } from './api';

/** The colours a note on a picture can have (AEM's annotation colours, roughly). */
export const NOTE_COLORS = ['#e68619', '#d7373f', '#2d9d78', '#1473e6', '#7e4cc5'];

export interface CommentsState {
  comments: AssetComment[] | null;
  canComment: boolean;
  error: string | null;
  reload(): Promise<void>;
  add(text: string, region?: CommentRegion, color?: string): Promise<boolean>;
  remove(id: string): Promise<void>;
}

/** The comments of a file, shared by the Timeline and the picture. */
export function useComments(client: DamClient, path: string): CommentsState {
  const [comments, setComments] = useState<AssetComment[] | null>(null);
  const [canComment, setCanComment] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reload = useCallback(async () => {
    try {
      const r = await client.comments(path);
      setComments(r.comments);
      setCanComment(r.canComment);
      setError(null);
    } catch (e) {
      setComments([]);
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client, path]);
  useEffect(() => {
    setComments(null);
    void reload();
  }, [reload]);
  const add = useCallback(
    async (text: string, region?: CommentRegion, color?: string) => {
      try {
        await client.addComment(path, text, region, color);
        await reload();
        return true;
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return false;
      }
    },
    [client, path, reload],
  );
  const remove = useCallback(
    async (id: string) => {
      try {
        await client.deleteComment(path, id);
        await reload();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [client, path, reload],
  );
  return { comments, canComment, error, reload, add, remove };
}

/** The number a note carries on the picture and in the Timeline (notes only, oldest first). */
export function noteNumbers(comments: AssetComment[]): Map<string, number> {
  const out = new Map<string, number>();
  for (const c of comments) if (c.region) out.set(c.id, out.size + 1);
  return out;
}

/** The person a mention can name. */
export interface Person {
  id: string;
  name: string;
  email?: string;
}

/** Users whose id, name or e-mail address starts with or contains the text (the security console's list). */
export async function findPeople(q: string): Promise<Person[]> {
  const res = await fetch(`/bin/studio/security.json?${new URLSearchParams({ op: 'users', q })}`, { credentials: 'same-origin' });
  if (!res.ok) return [];
  const list = (await res.json()) as Array<{ id: string; name?: string; email?: string; system?: boolean; disabled?: boolean }>;
  return list.filter((u) => !u.system && !u.disabled && u.id !== 'anonymous').map((u) => ({ id: u.id, name: u.name || u.id, email: u.email }));
}

/** The @word being typed at the caret, if any: where it starts and what was typed after the @. */
export function mentionAt(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret);
  const m = /(^|[^\w@])@([\w.+@-]{0,60})$/.exec(before);
  return m ? { start: before.length - m[2].length - 1, query: m[2] } : null;
}

/**
 * A text box where @ suggests people (AEM's mentions): typing after an @ lists the users that match; Enter, Tab or a
 * click puts `@id ` in the text. Whoever is mentioned is told when the comment is saved.
 */
export function MentionInput({
  value,
  onChange,
  people = findPeople,
  ...rest
}: {
  value: string;
  onChange(v: string): void;
  people?(q: string): Promise<Person[]>;
} & Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'value' | 'onChange'>) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const [at, setAt] = useState<{ start: number; query: string } | null>(null);
  const [found, setFound] = useState<Person[]>([]);
  const [active, setActive] = useState(0);
  const latest = useRef(0);

  useEffect(() => {
    if (!at) {
      setFound([]);
      return;
    }
    const run = ++latest.current;
    const timer = window.setTimeout(() => {
      people(at.query).then(
        (list) => {
          if (run !== latest.current) return;
          setFound(list.slice(0, 6));
          setActive(0);
        },
        () => setFound([]),
      );
    }, 150);
    return () => window.clearTimeout(timer);
  }, [at, people]);

  const choose = (p: Person) => {
    if (!at) return;
    const end = at.start + 1 + at.query.length;
    const next = `${value.slice(0, at.start)}@${p.id} ${value.slice(end)}`;
    onChange(next);
    setAt(null);
    const caret = at.start + p.id.length + 2;
    window.requestAnimationFrame(() => ref.current?.setSelectionRange(caret, caret));
  };

  const open = !!at && found.length > 0;
  return (
    <div className="studio-assets__mention">
      <textarea
        {...rest}
        ref={ref}
        value={value}
        aria-autocomplete="list"
        aria-expanded={open}
        onChange={(e) => {
          onChange(e.target.value);
          setAt(mentionAt(e.target.value, e.target.selectionStart ?? e.target.value.length));
        }}
        onBlur={(e) => {
          // a click on a suggestion lands first
          window.setTimeout(() => setAt(null), 150);
          rest.onBlur?.(e);
        }}
        onKeyDown={(e) => {
          if (open) {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault();
              setActive((i) => (i + (e.key === 'ArrowDown' ? 1 : found.length - 1)) % found.length);
              return;
            }
            if (e.key === 'Enter' || e.key === 'Tab') {
              e.preventDefault();
              choose(found[active]);
              return;
            }
            if (e.key === 'Escape') {
              e.preventDefault();
              setAt(null);
              return;
            }
          }
          rest.onKeyDown?.(e);
        }}
      />
      {open && (
        <ul className="studio-assets__mention-list" role="listbox" aria-label="People">
          {found.map((p, i) => (
            <li
              key={p.id}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(p);
              }}
            >
              <strong>{p.name}</strong>
              <small>{p.email ?? p.id}</small>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** A comment's text with its @mentions set apart. */
export function MentionText({ text }: { text: string }) {
  return (
    <>
      {mentionParts(text).map((p, i) =>
        p.mention ? (
          <span key={i} className="studio-assets__mentioned">
            {p.text}
          </span>
        ) : (
          <span key={i}>{p.text}</span>
        ),
      )}
    </>
  );
}

/** Asks the video on the stage to go to a moment (a note on a video, chosen in the Timeline). */
export const SEEK_EVENT = 'studio:seek-media';

export function seekTo(time: number) {
  window.dispatchEvent(new CustomEvent<number>(SEEK_EVENT, { detail: time }));
}

/** 75 → "1:15" */
export function timeLabel(s: number): string {
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${String(sec).padStart(2, '0')}`;
}

/** Comments in the Timeline: write one, read them, remove one's own. */
export function CommentsSection({
  state,
  highlight,
  onHighlight,
  people,
}: {
  state: CommentsState;
  highlight?: string | null;
  onHighlight?(id: string | null): void;
  people?(q: string): Promise<Person[]>;
}) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const numbers = noteNumbers(state.comments ?? []);
  return (
    <section className="studio-assets__comments" aria-label="Comments">
      <h3>Comments</h3>
      {state.canComment && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (!text.trim()) return;
            setBusy(true);
            void state.add(text).then((ok) => {
              setBusy(false);
              if (ok) setText('');
            });
          }}
        >
          <MentionInput
            aria-label="Add a comment"
            placeholder="Add a comment; @ names someone to tell"
            value={text}
            rows={2}
            disabled={busy}
            people={people}
            onChange={setText}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                (e.currentTarget.form as HTMLFormElement | null)?.requestSubmit();
              }
            }}
          />
          <button type="submit" className="studio-assets__linkbtn" disabled={busy || !text.trim()}>
            Comment
          </button>
        </form>
      )}
      {state.error && <p className="studio-assets__dialog-error">{state.error}</p>}
      {state.comments === null ? (
        <p className="studio-assets__muted">Loading…</p>
      ) : state.comments.length === 0 ? (
        <p className="studio-assets__muted">No comments yet.</p>
      ) : (
        <ol className="studio-assets__comment-list">
          {[...state.comments].reverse().map((c) => (
            <li
              key={c.id}
              className={highlight === c.id ? 'is-highlighted' : undefined}
              onMouseEnter={() => c.region && onHighlight?.(c.id)}
              onMouseLeave={() => c.region && onHighlight?.(null)}
            >
              <div className="studio-assets__comment-head">
                {c.region && (
                  <span className="studio-assets__note-number" style={{ background: c.color ?? NOTE_COLORS[0] }}>
                    {numbers.get(c.id)}
                  </span>
                )}
                <strong>{c.region ? 'Annotation' : 'Comment'}</strong>
                {c.region?.time !== undefined && (
                  <button type="button" className="studio-assets__note-time" onClick={() => seekTo(c.region!.time!)} aria-label={`Go to ${timeLabel(c.region.time)}`}>
                    {timeLabel(c.region.time)}
                  </button>
                )}
                <small>
                  {new Date(c.created).toLocaleString()}
                  {c.author ? ` · ${c.author}` : ''}
                </small>
                {c.mine && (
                  <button type="button" className="studio-assets__comment-remove" aria-label={`Remove comment: ${c.text}`} onClick={() => void state.remove(c.id)}>
                    ×
                  </button>
                )}
              </div>
              <p>
                <MentionText text={c.text} />
              </p>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** What a drag draws: a box (the default), an ellipse, an arrow, or a line by hand (AEM's annotation shapes). */
export const NOTE_TOOLS: Array<{ id: NoteShape; label: string; icon: ReactNode }> = [
  { id: 'rect', label: 'Box', icon: <Rectangle /> },
  { id: 'ellipse', label: 'Ellipse', icon: <Circle /> },
  { id: 'arrow', label: 'Arrow', icon: <ArrowUpRight /> },
  { id: 'free', label: 'Draw', icon: <Brush /> },
];

/** A note on a video shows around its moment: this many seconds before and after it. */
export const NOTE_WINDOW = 0.75;

const round = (n: number) => Math.round(n * 10000) / 10000;
const clamp01 = (n: number) => Math.min(1, Math.max(0, n));

/** The box points fit in. */
export function boxOf(points: Array<[number, number]>): { x: number; y: number; w: number; h: number } {
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const x = Math.min(...xs);
  const y = Math.min(...ys);
  return { x: round(x), y: round(y), w: round(Math.max(Math.max(...xs) - x, 0.001)), h: round(Math.max(Math.max(...ys) - y, 0.001)) };
}

/** The notes a stage shows: on a video only those of the moment shown (notes without a moment always). */
export function notesAt(comments: AssetComment[], time: number | undefined): AssetComment[] {
  return comments.filter((c) => c.region && (time === undefined || c.region.time === undefined || Math.abs(c.region.time - time) <= NOTE_WINDOW));
}

/** The marks of the notes, drawn in the stage's pixels (an arrow's head and a line's width do not stretch with it). */
function Marks({ notes, size, highlight, draft, draftColor }: { notes: AssetComment[]; size: { w: number; h: number }; highlight?: string | null; draft?: CommentRegion | null; draftColor: string }) {
  const { w: W, h: H } = size;
  const mark = (r: CommentRegion, color: string, key: string, cls: string) => {
    if (r.shape === 'arrow' || r.shape === 'free') {
      const pts = (r.points ?? []).map(([x, y]) => ({ x: x * W, y: y * H }));
      if (pts.length < 2) return null;
      if (r.shape === 'free') {
        const d = pts.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');
        return <path key={key} className={cls} d={d} stroke={color} />;
      }
      const head = headOf(pts, 14);
      return (
        <g key={key} className={cls}>
          <path d={curvePath(pts, 10)} stroke={color} />
          <polygon points={head.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')} fill={color} stroke={color} />
        </g>
      );
    }
    return null;
  };
  return (
    <svg className="studio-assets__marks" width={W} height={H} viewBox={`0 0 ${W} ${H}`} aria-hidden="true">
      {notes.map((c) => mark(c.region!, c.color ?? NOTE_COLORS[0], c.id, highlight === c.id ? 'is-highlighted' : ''))}
      {draft && mark(draft, draftColor, 'draft', 'is-draft')}
    </svg>
  );
}

/**
 * A picture or a video with its notes: numbered marks in their colour. In annotate mode a drag draws the mark of the
 * chosen tool (a box, an ellipse, an arrow, a line by hand), then the note is written next to it; on a video the note
 * keeps the moment it was drawn at, and shows around that moment.
 */
export function AnnotatedStage({
  children,
  state,
  annotating,
  highlight,
  onHighlight,
  time,
  onDrawStart,
  controlsHeight = 0,
  people,
}: {
  children: ReactNode;
  state: CommentsState;
  annotating: boolean;
  highlight?: string | null;
  onHighlight?(id: string | null): void;
  /** a video: the moment shown (seconds); notes keep it */
  time?: number;
  /** a mark starts: a video pauses */
  onDrawStart?(): void;
  /** a video: the height of its controls at the bottom, which stay usable */
  controlsHeight?: number;
  people?(q: string): Promise<Person[]>;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [tool, setTool] = useState<NoteShape>('rect');
  const [drag, setDrag] = useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null);
  const [stroke, setStroke] = useState<Array<[number, number]> | null>(null);
  const [draft, setDraft] = useState<CommentRegion | null>(null);
  const [text, setText] = useState('');
  const [color, setColor] = useState(NOTE_COLORS[0]);
  const [busy, setBusy] = useState(false);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const numbers = noteNumbers(state.comments ?? []);

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => {
      const r = el.getBoundingClientRect();
      setSize((s) => (Math.abs(s.w - r.width) < 0.5 && Math.abs(s.h - r.height) < 0.5 ? s : { w: r.width, h: r.height }));
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    if (!annotating) {
      setDraft(null);
      setDrag(null);
      setStroke(null);
    }
  }, [annotating]);

  const at = (e: React.PointerEvent) => {
    const r = box.current!.getBoundingClientRect();
    return { x: clamp01((e.clientX - r.left) / r.width), y: clamp01((e.clientY - r.top) / r.height), r };
  };
  const region = (d: { x0: number; y0: number; x1: number; y1: number }): CommentRegion => ({
    x: round(Math.min(d.x0, d.x1)),
    y: round(Math.min(d.y0, d.y1)),
    w: round(Math.abs(d.x1 - d.x0)),
    h: round(Math.abs(d.y1 - d.y0)),
  });
  const withTime = (r: CommentRegion): CommentRegion => (time === undefined ? r : { ...r, time: Math.round(time * 100) / 100 });

  const finish = () => {
    if (drag && (tool === 'rect' || tool === 'ellipse')) {
      const r = region(drag);
      // a click without a drag makes a small mark where it was
      const small = r.w < 0.01 || r.h < 0.01;
      setDraft(withTime({ ...(small ? { x: Math.min(r.x, 0.94), y: Math.min(r.y, 0.94), w: 0.06, h: 0.06 } : r), ...(tool === 'ellipse' ? { shape: 'ellipse' } : {}) }));
    } else if (drag && tool === 'arrow') {
      let pts: Array<[number, number]> = [
        [round(drag.x0), round(drag.y0)],
        [round(drag.x1), round(drag.y1)],
      ];
      // a click points at the spot from up and to the left
      if (Math.hypot(drag.x1 - drag.x0, drag.y1 - drag.y0) < 0.01) pts = [[round(clamp01(drag.x1 - 0.08)), round(clamp01(drag.y1 - 0.08))], pts[1]];
      setDraft(withTime({ ...boxOf(pts), shape: 'arrow', points: pts }));
    } else if (stroke && stroke.length >= 2) {
      // the hand's jitter goes, the shape stays (in pixels, then back to fractions)
      const W = size.w || 1000;
      const H = size.h || 1000;
      const kept = simplify(stroke.map(([x, y]) => ({ x: x * W, y: y * H })), 1.5)
        .slice(0, 400)
        .map((p): [number, number] => [round(p.x / W), round(p.y / H)]);
      setDraft(withTime({ ...boxOf(kept), shape: 'free', points: kept }));
    }
    setDrag(null);
    setStroke(null);
    setText('');
  };

  const notes = notesAt(state.comments ?? [], time);
  const shown = drag && (tool === 'rect' || tool === 'ellipse') ? { ...region(drag), shape: tool } : draft && (draft.shape ?? 'rect') !== 'arrow' && draft.shape !== 'free' ? draft : null;
  const drawnDraft: CommentRegion | null = drag && tool === 'arrow'
    ? { x: 0, y: 0, w: 0, h: 0, shape: 'arrow', points: [[drag.x0, drag.y0], [drag.x1, drag.y1]] }
    : stroke
      ? { x: 0, y: 0, w: 0, h: 0, shape: 'free', points: stroke }
      : draft && (draft.shape === 'arrow' || draft.shape === 'free')
        ? draft
        : null;
  const anchor = (r: CommentRegion) => (r.points?.length ? { x: r.points[0][0], y: r.points[0][1] } : { x: r.x, y: r.y });

  return (
    <div className="studio-assets__annotated-wrap">
      {annotating && (
        <div className="studio-assets__note-tools" role="radiogroup" aria-label="Mark">
          {NOTE_TOOLS.map((t) => (
            <button key={t.id} type="button" role="radio" aria-checked={tool === t.id} aria-label={t.label} title={t.label} onClick={() => setTool(t.id)}>
              {t.icon}
            </button>
          ))}
          <span className="studio-assets__note-hint">{time === undefined ? 'Drag on the picture, then write the note. @ names someone to tell.' : 'Pause on the frame, drag on it, then write the note.'}</span>
        </div>
      )}
      <div
        ref={box}
        className={`studio-assets__annotated${annotating ? ' is-annotating' : ''}`}
        onPointerDown={(e) => {
          if (!annotating || draft || e.button !== 0) return;
          const p = at(e);
          // the video's own controls stay usable
          if (controlsHeight && e.clientY > p.r.bottom - controlsHeight) return;
          onDrawStart?.();
          (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
          if (tool === 'free') setStroke([[round(p.x), round(p.y)]]);
          else setDrag({ x0: p.x, y0: p.y, x1: p.x, y1: p.y });
          e.preventDefault();
        }}
        onPointerMove={(e) => {
          if (drag) {
            const p = at(e);
            setDrag({ ...drag, x1: p.x, y1: p.y });
          } else if (stroke) {
            const p = at(e);
            const last = stroke[stroke.length - 1];
            if (Math.hypot(p.x - last[0], p.y - last[1]) > 0.003) setStroke([...stroke, [round(p.x), round(p.y)]]);
          }
        }}
        onPointerUp={() => {
          if (drag || stroke) finish();
        }}
      >
        {children}
        <Marks notes={notes} size={size.w ? size : { w: 1000, h: 1000 }} highlight={highlight} draft={drawnDraft} draftColor={color} />
        {notes.map((c) => {
          const r = c.region!;
          const drawn = r.shape === 'arrow' || r.shape === 'free';
          const a = anchor(r);
          return (
            <div
              key={c.id}
              className={`studio-assets__note studio-assets__note--${r.shape ?? 'rect'}${highlight === c.id ? ' is-highlighted' : ''}`}
              style={{
                left: `${r.x * 100}%`,
                top: `${r.y * 100}%`,
                width: `${r.w * 100}%`,
                height: `${r.h * 100}%`,
                borderColor: drawn ? 'transparent' : c.color,
              }}
              title={`${c.text}${c.author ? ` (${c.author})` : ''}`}
              onMouseEnter={() => onHighlight?.(c.id)}
              onMouseLeave={() => onHighlight?.(null)}
            >
              <span
                className="studio-assets__note-number"
                style={{
                  background: c.color,
                  ...(drawn ? { left: `calc(${r.w ? ((a.x - r.x) / r.w) * 100 : 0}% - 10px)`, top: `calc(${r.h ? ((a.y - r.y) / r.h) * 100 : 0}% - 10px)` } : {}),
                }}
              >
                {numbers.get(c.id)}
              </span>
            </div>
          );
        })}
        {shown && (
          <div
            className={`studio-assets__note is-draft studio-assets__note--${shown.shape ?? 'rect'}`}
            style={{ left: `${shown.x * 100}%`, top: `${shown.y * 100}%`, width: `${shown.w * 100}%`, height: `${shown.h * 100}%`, borderColor: color }}
          />
        )}
        {draft && (
          <form
            className="studio-assets__note-form"
            style={{ left: `${Math.min(draft.x * 100, 60)}%`, top: `${Math.min((draft.y + draft.h) * 100, 75)}%` }}
            onPointerDown={(e) => e.stopPropagation()}
            onSubmit={(e) => {
              e.preventDefault();
              if (!text.trim()) return;
              setBusy(true);
              void state.add(text, draft, color).then((ok) => {
                setBusy(false);
                if (ok) setDraft(null);
              });
            }}
          >
            <MentionInput aria-label="Note" placeholder="What about this part? @ names someone" value={text} rows={2} autoFocus people={people} onChange={setText} />
            <div className="studio-assets__note-colors" role="radiogroup" aria-label="Colour">
              {NOTE_COLORS.map((c) => (
                <button key={c} type="button" role="radio" aria-checked={c === color} aria-label={`Colour ${c}`} style={{ background: c }} onClick={() => setColor(c)} />
              ))}
            </div>
            <div className="studio-assets__note-actions">
              {draft.time !== undefined && <span className="studio-assets__muted">at {timeLabel(draft.time)}</span>}
              <button type="button" onClick={() => setDraft(null)} disabled={busy}>
                Cancel
              </button>
              <button type="submit" disabled={busy || !text.trim()}>
                Add note
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

/** A picture with its notes (see {@link AnnotatedStage}). */
export function AnnotatedPicture({
  src,
  alt,
  ...rest
}: {
  src: string;
  alt: string;
  state: CommentsState;
  annotating: boolean;
  highlight?: string | null;
  onHighlight?(id: string | null): void;
  people?(q: string): Promise<Person[]>;
}) {
  return (
    <AnnotatedStage {...rest}>
      <img className="studio-assets__stage-img" src={src} alt={alt} draggable={false} />
    </AnnotatedStage>
  );
}
