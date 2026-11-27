import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  Menu,
  MenuTrigger,
  Picker,
  ProgressCircle,
  TextField,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import Close from '@spectrum-icons/workflow/Close';
import Crop from '@spectrum-icons/workflow/Crop';
import Delete from '@spectrum-icons/workflow/Delete';
import FlipHorizontal from '@spectrum-icons/workflow/FlipHorizontal';
import FlipVertical from '@spectrum-icons/workflow/FlipVertical';
import ImageMapCircle from '@spectrum-icons/workflow/ImageMapCircle';
import ImageMapRectangle from '@spectrum-icons/workflow/ImageMapRectangle';
import Polygon from '@spectrum-icons/workflow/Polygon';
import Redo from '@spectrum-icons/workflow/Redo';
import RotateCCW from '@spectrum-icons/workflow/RotateCCW';
import RotateCW from '@spectrum-icons/workflow/RotateCW';
import Undo from '@spectrum-icons/workflow/Undo';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type Key, type PointerEvent as ReactPointerEvent, type ReactElement } from 'react';
import type { AssetDetails, DamClient } from './api';
import {
  addCorner,
  areaForward,
  areaInverse,
  CROP_RATIOS,
  defaultPolygon,
  dragArea,
  dragCorner,
  dragBox,
  finalAreas,
  initialCrop,
  isIdentity,
  parseImageMap,
  removeCorner,
  renderEdited,
  sizeAfter,
  type AreaTarget,
  type Box,
  type EditOperation,
  type Handle,
  type MapArea,
  type Size,
} from './imageEdit';
import { PagePickerButton } from './pagePicker';
import type { AssetsRoute } from './route';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { ScreenFrame } from '../shell/ScreenFrame';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: DamClient;
  path: string;
  navigate(route: AssetsRoute): void;
  /** Image loading, replaceable in tests (jsdom has no image decoding). */
  loadImage?: (url: string) => Promise<HTMLImageElement>;
}

/** Formats studio-dam can edit. */
export const EDITABLE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

export function isEditable(asset: { type: string; mimeType?: string | null; contentFragment?: boolean; state?: string | null }): boolean {
  return asset.type === 'asset' && !asset.contentFragment && EDITABLE_TYPES.includes(asset.mimeType ?? '') && asset.state !== 'processing';
}

function defaultLoadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error(`Cannot load ${url}`));
    img.src = url;
  });
}

/** One undo step: the operations so far and the image map (in source space). */
interface Snapshot {
  ops: EditOperation[];
  areas: MapArea[];
}

type Mode = { kind: 'main' } | { kind: 'crop'; ratio: number | null; box: Box } | { kind: 'map'; areas: MapArea[]; selected: number | null };

/**
 * `/mnt/overlay/dam/gui/content/assets/assetedit.html/<asset>`: crop (free or at a ratio),
 * quarter turns, flips and image maps, with undo and redo. Nothing changes until Finish, which
 * sends the operations to Sensible Studio; studio-dam applies them to the original and the renditions follow.
 */
export function ImageEditor({ client, path, navigate, loadImage = defaultLoadImage }: Props) {
  const [asset, setAsset] = useState<AssetDetails | null>(null);
  useScreenTitle(asset ? `Edit · ${asset.title}` : null, asset ? `${asset.title} | Sensible Studio Media | Edit file` : null);
  const [img, setImg] = useState<HTMLImageElement | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<Snapshot[]>([{ ops: [], areas: [] }]);
  const [index, setIndex] = useState(0);
  const [mode, setMode] = useState<Mode>({ kind: 'main' });
  const [saving, setSaving] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const a = await client.asset(path);
        if (!live) return;
        if (!isEditable(a)) {
          setError(a.state === 'processing' ? 'The file is still being processed.' : 'Only JPEG, PNG and WebP images can be edited.');
          setAsset(a);
          return;
        }
        const url = a.web ?? a.original;
        if (!url) throw new Error('The file has no image to edit.');
        const image = await loadImage(url);
        if (!live) return;
        setAsset(a);
        setImg(image);
        setHistory([{ ops: [], areas: parseImageMap(a.imageMap) }]);
        setIndex(0);
      } catch (e) {
        if (live) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      live = false;
    };
  }, [client, path, loadImage]);

  const current = history[index];
  /** Pixel size of the original before any edit, as displayed (EXIF orientation applied). */
  const source: Size = useMemo(() => {
    const iw = img?.naturalWidth || img?.width || 1;
    const ih = img?.naturalHeight || img?.height || 1;
    const w = asset?.width || iw;
    const h = asset?.height || ih;
    // tiff:ImageWidth/Length are the stored sizes; a turned photo shows them swapped.
    return (w > h) === (iw > ih) || w === h || iw === ih ? { width: w, height: h } : { width: h, height: w };
  }, [asset, img]);
  const size = sizeAfter(source, current.ops);
  const dirty = index > 0 && JSON.stringify(current) !== JSON.stringify(history[0]);
  const hasCrop = current.ops.some((o) => o.op === 'crop');

  const preview = useMemo(() => {
    if (!img) return null;
    try {
      // Shown as the canvas itself: encoding it (toDataURL) would cost more than drawing it.
      return renderEdited(img, current.ops, 2048);
    } catch {
      return null;
    }
  }, [img, current.ops]);

  const push = useCallback(
    (next: Snapshot) => {
      setHistory((h) => [...h.slice(0, index + 1), next]);
      setIndex((i) => i + 1);
    },
    [index],
  );
  const addOp = (op: EditOperation) => push({ ...current, ops: [...current.ops, op] });

  const undo = () => setIndex((i) => Math.max(0, i - 1));
  const redo = () => setIndex((i) => Math.min(history.length - 1, i + 1));
  /** Back where the editor was opened from (like AEM), or to the asset when opened directly. */
  const tab = useTab();
  const tabActive = useRef(true);
  tabActive.current = !tab || tab.active;
  const back = () => {
    if (tab) tab.leave({ kind: 'assets', route: { kind: 'details', path, properties: false } });
    else if ((window.history.state as { studioAssets?: boolean } | null)?.studioAssets) window.history.back();
    else navigate({ kind: 'details', path, properties: false });
  };

  const finish = async () => {
    if (!asset) return;
    setSaving(true);
    try {
      const ops = isIdentity(current.ops) ? [] : current.ops;
      const mapChanged = JSON.stringify(current.areas) !== JSON.stringify(history[0].areas);
      // A crop or turn moves the map with it, so it is rewritten whenever the image changes.
      const sendMap = mapChanged || (ops.length > 0 && history[0].areas.length > 0);
      if (ops.length === 0 && !sendMap) {
        back();
        return;
      }
      await client.edit(path, ops, sendMap ? finalAreas(current.areas, source, ops) : undefined);
      back();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  // ---- crop / map modes
  const startCrop = (key: Key) => {
    if (key === 'remove') {
      push({ ...current, ops: current.ops.filter((o) => o.op !== 'crop') });
      return;
    }
    const ratio = CROP_RATIOS.find((r) => r.key === key)?.ratio ?? null;
    setMode({ kind: 'crop', ratio, box: initialCrop(size, ratio) });
  };
  const confirmCrop = () => {
    if (mode.kind !== 'crop') return;
    const b = mode.box;
    setMode({ kind: 'main' });
    if (b.x < 0.0005 && b.y < 0.0005 && b.width > 0.999 && b.height > 0.999) return;
    addOp({ op: 'crop', x: b.x, y: b.y, width: b.width, height: b.height });
  };
  const startMap = () =>
    setMode({ kind: 'map', areas: current.areas.map((a) => areaForward(a, source, current.ops)), selected: null });
  const confirmMap = () => {
    if (mode.kind !== 'map') return;
    const areas = mode.areas.map((a) => areaInverse(a, source, current.ops));
    setMode({ kind: 'main' });
    if (JSON.stringify(areas) !== JSON.stringify(current.areas)) push({ ...current, areas });
  };
  const cancelMode = () => setMode({ kind: 'main' });

  // Keyboard: Enter / Escape in a tool, undo / redo otherwise.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!tabActive.current) return; // another tab is in front
      if ((e.target as HTMLElement | null)?.closest('input, textarea, [role="dialog"], [role="menu"], [role="listbox"]')) return;
      if (mode.kind === 'crop') {
        if (e.key === 'Enter') confirmCrop();
        if (e.key === 'Escape') cancelMode();
      } else if (mode.kind === 'map') {
        if (e.key === 'Enter') confirmMap();
        if (e.key === 'Escape') cancelMode();
        if ((e.key === 'Delete' || e.key === 'Backspace') && mode.selected !== null) {
          setMode({ ...mode, areas: mode.areas.filter((_, i) => i !== mode.selected), selected: null });
        }
      } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) redo();
        else undo();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  if (error && !img) {
    return (
      <div className="studio-imgedit studio-imgedit--message">
        <ScreenFrame identity={<strong>Image editor</strong>} onClose={back} closeLabel="Close image editor" />
        <ErrorState title={`Cannot edit ${asset?.title ?? path}`} action={{ label: 'Back to the file', onPress: back }}>{error}</ErrorState>
      </div>
    );
  }
  if (!img || !asset || !preview) {
    return (
      <div className="studio-imgedit studio-imgedit--message">
        <ScreenFrame identity={<strong>Image editor</strong>} onClose={back} closeLabel="Close image editor" />
        <LoadingState label="Loading image" />
      </div>
    );
  }

  return (
    <div className="studio-imgedit">
      {mode.kind === 'main' && (
        <ScreenFrame
          identity={<><h1>{asset.title}</h1><span className={`studio-state studio-state--${dirty ? 'modified' : 'saved'}`}>{dirty ? 'Modified' : 'Saved'}</span></>}
          tools={
            <>
            <MenuTrigger>
              <ToolButton label="Start Crop">
                <Crop />
              </ToolButton>
              <Menu onAction={startCrop} disabledKeys={hasCrop ? [] : ['remove']}>
                {[{ key: 'remove', label: 'Remove Crop' }, ...CROP_RATIOS].map((r) => (
                  <Item key={r.key}>{r.label}</Item>
                ))}
              </Menu>
            </MenuTrigger>
            <ToolButton label="Rotate Left" onPress={() => addOp({ op: 'rotate', degrees: 270 })}>
              <RotateCCW />
            </ToolButton>
            <ToolButton label="Rotate Right" onPress={() => addOp({ op: 'rotate', degrees: 90 })}>
              <RotateCW />
            </ToolButton>
            <ToolButton label="Flip Vertically" onPress={() => addOp({ op: 'flip', axis: 'vertical' })}>
              <FlipVertical />
            </ToolButton>
            <ToolButton label="Flip Horizontally" onPress={() => addOp({ op: 'flip', axis: 'horizontal' })}>
              <FlipHorizontal />
            </ToolButton>
            <ToolButton label="Image map" onPress={startMap}>
              <ImageMapRectangle />
            </ToolButton>
            </>
          }
          actions={
            <>
            <ToolButton label="Undo" isDisabled={index === 0} onPress={undo}>
              <Undo />
            </ToolButton>
            <ToolButton label="Redo" isDisabled={index === history.length - 1} onPress={redo}>
              <Redo />
            </ToolButton>
            </>
          }
          primary={<Button variant="accent" isDisabled={!dirty || saving} onPress={() => void finish()}>{saving ? <ProgressCircle size="S" isIndeterminate aria-label="Saving" /> : <Checkmark />} Finish</Button>}
          onClose={() => (dirty ? setConfirmLeave(true) : back())}
          closeLabel="Close image editor"
        />
      )}
      {mode.kind === 'crop' && (
        <div className="studio-imgedit__toolbar" role="toolbar" aria-label="Crop">
          <div className="studio-imgedit__tools">
            <ToolButton label="Crop" className="is-active">
              <Crop />
            </ToolButton>
            <span className="studio-imgedit__hint">{CROP_RATIOS.find((r) => r.ratio === mode.ratio)?.label}</span>
          </div>
          <div className="studio-imgedit__tools">
            <ToolButton label="Cancel" onPress={cancelMode}>
              <Close />
            </ToolButton>
            <ToolButton label="Confirm" onPress={confirmCrop}>
              <Checkmark />
            </ToolButton>
          </div>
        </div>
      )}
      {mode.kind === 'map' && (
        <div className="studio-imgedit__toolbar" role="toolbar" aria-label="Image Map">
          <div className="studio-imgedit__tools">
            <ToolButton
              label="Add Rectangular Map"
              onPress={() =>
                setMode({
                  ...mode,
                  areas: [...mode.areas, { shape: 'rect', coords: [0.2, 0.2, 0.8, 0.8], href: '', target: '', alt: '' }],
                  selected: mode.areas.length,
                })
              }
            >
              <ImageMapRectangle />
            </ToolButton>
            <ToolButton
              label="Add Circular Map"
              onPress={() => {
                const r = (0.3 * Math.min(size.width, size.height)) / size.width;
                setMode({ ...mode, areas: [...mode.areas, { shape: 'circle', coords: [0.5, 0.5, r], href: '', target: '', alt: '' }], selected: mode.areas.length });
              }}
            >
              <ImageMapCircle />
            </ToolButton>
            <ToolButton
              label="Add Polygonal Map"
              onPress={() =>
                setMode({ ...mode, areas: [...mode.areas, { shape: 'poly', coords: defaultPolygon(size), href: '', target: '', alt: '' }], selected: mode.areas.length })
              }
            >
              <Polygon />
            </ToolButton>
            {mode.selected !== null && mode.areas[mode.selected]?.shape === 'poly' && (
              <span className="studio-imgedit__hint">Drag a corner to shape it, a small dot to add a corner; double-click a corner to remove it</span>
            )}
          </div>
          <div className="studio-imgedit__tools">
            <ToolButton label="Cancel" onPress={cancelMode}>
              <Close />
            </ToolButton>
            <ToolButton label="Confirm" onPress={confirmMap}>
              <Checkmark />
            </ToolButton>
          </div>
        </div>
      )}
      {mode.kind === 'map' && mode.selected !== null && mode.areas[mode.selected] && (
        <AreaProperties
          key={mode.selected}
          area={mode.areas[mode.selected]}
          onConfirm={(a) => setMode({ ...mode, areas: mode.areas.map((x, i) => (i === mode.selected ? a : x)), selected: null })}
          onCancel={() => setMode({ ...mode, selected: null })}
          onDelete={() => setMode({ ...mode, areas: mode.areas.filter((_, i) => i !== mode.selected), selected: null })}
        />
      )}
      {error && (
        <div className="studio-imgedit__error" role="alert">
          {error}
        </div>
      )}
      <Stage size={size}>
        {(frame) => (
          <>
            <CanvasView canvas={preview} label={asset.title} />
            {mode.kind === 'crop' && (
              <CropOverlay
                box={mode.box}
                frame={frame}
                onChange={(box) => setMode({ ...mode, box })}
                drag={(b, h, dx, dy) => dragBox(b, h, dx, dy, size, mode.ratio)}
              />
            )}
            {mode.kind === 'map' &&
              mode.areas.map((a, i) =>
                a.shape === 'poly' ? (
                  <PolyOverlay
                    key={i}
                    area={a}
                    size={size}
                    frame={frame}
                    selected={mode.selected === i}
                    onSelect={() => setMode({ ...mode, selected: i })}
                    onChange={(next) => setMode({ ...mode, areas: mode.areas.map((x, k) => (k === i ? next : x)), selected: i })}
                  />
                ) : (
                <AreaOverlay
                  key={i}
                  area={a}
                  size={size}
                  frame={frame}
                  selected={mode.selected === i}
                  onSelect={() => setMode({ ...mode, selected: i })}
                  onChange={(next) => setMode({ ...mode, areas: mode.areas.map((x, k) => (k === i ? next : x)) })}
                />
                ),
              )}
          </>
        )}
      </Stage>
      <DialogContainer onDismiss={() => setConfirmLeave(false)}>
        {confirmLeave && (
          <StudioConfirmDialog title="Discard changes?" variant="destructive" primaryActionLabel="Discard" cancelLabel="Keep editing" onPrimaryAction={back}>
            The changes to this image have not been saved.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

/** Puts a ready canvas into the page, scaled to its box by CSS. */
function CanvasView({ canvas, label }: { canvas: HTMLCanvasElement; label: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const host = ref.current;
    if (!host) return undefined;
    canvas.className = 'studio-imgedit__canvas';
    host.replaceChildren(canvas);
    return () => {
      if (canvas.parentNode === host) host.removeChild(canvas);
    };
  }, [canvas]);
  return <div ref={ref} className="studio-imgedit__image" role="img" aria-label={label} />;
}

function ToolButton({
  label,
  children,
  onPress,
  isDisabled,
  className,
}: {
  label: string;
  children: ReactElement;
  onPress?: () => void;
  isDisabled?: boolean;
  className?: string;
}) {
  return (
    <TooltipTrigger delay={600}>
      <ActionButton isQuiet aria-label={label} onPress={onPress} isDisabled={isDisabled} UNSAFE_className={`studio-imgedit__btn ${className ?? ''}`}>
        {children}
      </ActionButton>
      <Tooltip>{label}</Tooltip>
    </TooltipTrigger>
  );
}

/** The displayed image's box inside the stage, in CSS pixels. */
interface Frame {
  width: number;
  height: number;
}

/** Centres the image at the largest size that fits (never above its own pixels). */
function Stage({ size, children }: { size: Size; children: (frame: Frame) => ReactElement }) {
  const ref = useRef<HTMLDivElement>(null);
  const [room, setRoom] = useState<Frame>({ width: 800, height: 600 });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const measure = () => setRoom({ width: el.clientWidth || 800, height: el.clientHeight || 600 });
    measure();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const pad = 48;
  const scale = Math.min(1, (room.width - pad * 2) / size.width, (room.height - pad * 2) / size.height);
  const frame = { width: Math.max(1, Math.round(size.width * scale)), height: Math.max(1, Math.round(size.height * scale)) };
  return (
    <div className="studio-imgedit__stage" ref={ref}>
      <div className="studio-imgedit__frame" style={{ width: frame.width, height: frame.height }}>
        {children(frame)}
      </div>
    </div>
  );
}

const HANDLES: Handle[] = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];

const HANDLE_LABEL: Record<Handle, string> = {
  n: 'Top',
  s: 'Bottom',
  e: 'Right',
  w: 'Left',
  ne: 'Top Right',
  nw: 'Top Left',
  se: 'Bottom Right',
  sw: 'Bottom Left',
  move: 'Move',
};

/** Pointer drag in fractions of the frame; `apply` gets the total movement since the press. */
function useDrag(frame: Frame, apply: (handle: Handle, dx: number, dy: number) => void) {
  return (handle: Handle) => (e: ReactPointerEvent<HTMLElement>) => {
    e.preventDefault();
    e.stopPropagation();
    const startX = e.clientX;
    const startY = e.clientY;
    const target = e.currentTarget;
    target.setPointerCapture?.(e.pointerId);
    const move = (ev: PointerEvent) => apply(handle, (ev.clientX - startX) / frame.width, (ev.clientY - startY) / frame.height);
    const up = () => {
      target.removeEventListener('pointermove', move);
      target.removeEventListener('pointerup', up);
      target.removeEventListener('pointercancel', up);
    };
    target.addEventListener('pointermove', move);
    target.addEventListener('pointerup', up);
    target.addEventListener('pointercancel', up);
  };
}

/** Arrow keys nudge the selection by 1% (Shift: 10%). */
function nudge(e: React.KeyboardEvent, apply: (dx: number, dy: number) => void) {
  const step = e.shiftKey ? 0.1 : 0.01;
  const d: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
  const v = d[e.key];
  if (v) {
    e.preventDefault();
    apply(v[0], v[1]);
  }
}

function pct(v: number): string {
  return `${v * 100}%`;
}

function CropOverlay({
  box,
  frame,
  onChange,
  drag,
}: {
  box: Box;
  frame: Frame;
  onChange(box: Box): void;
  drag(box: Box, handle: Handle, dx: number, dy: number): Box;
}) {
  const start = useRef(box);
  const onDown = useDrag(frame, (h, dx, dy) => onChange(drag(start.current, h, dx, dy)));
  const begin = (h: Handle) => (e: ReactPointerEvent<HTMLElement>) => {
    start.current = box;
    onDown(h)(e);
  };
  const style: CSSProperties = { left: pct(box.x), top: pct(box.y), width: pct(box.width), height: pct(box.height) };
  return (
    <div className="studio-imgedit__crop" aria-label="Crop area">
      <div
        className="studio-imgedit__cropbox"
        style={style}
        role="slider"
        tabIndex={0}
        aria-label="Crop selection"
        aria-valuetext={`${Math.round(box.width * 100)}% by ${Math.round(box.height * 100)}%`}
        onPointerDown={begin('move')}
        onKeyDown={(e) => nudge(e, (dx, dy) => onChange(drag(box, 'move', dx, dy)))}
      >
        <span className="studio-imgedit__grid" aria-hidden />
        {HANDLES.map((h) => (
          <span key={h} className={`studio-imgedit__handle is-${h}`} aria-label={`${HANDLE_LABEL[h]} handle`} onPointerDown={begin(h)} />
        ))}
      </div>
    </div>
  );
}

function AreaOverlay({
  area,
  size,
  frame,
  selected,
  onSelect,
  onChange,
}: {
  area: MapArea;
  size: Size;
  frame: Frame;
  selected: boolean;
  onSelect(): void;
  onChange(area: MapArea): void;
}) {
  const start = useRef(area);
  const onDown = useDrag(frame, (h, dx, dy) => onChange(dragArea(start.current, h, dx, dy, size)));
  const begin = (h: Handle) => (e: ReactPointerEvent<HTMLElement>) => {
    start.current = area;
    onSelect();
    onDown(h)(e);
  };
  let style: CSSProperties;
  if (area.shape === 'rect') {
    const [x1, y1, x2, y2] = area.coords;
    style = { left: pct(x1), top: pct(y1), width: pct(x2 - x1), height: pct(y2 - y1) };
  } else {
    const [cx, cy, r] = area.coords;
    const rh = (r * size.width) / size.height;
    style = { left: pct(cx - r), top: pct(cy - rh), width: pct(2 * r), height: pct(2 * rh) };
  }
  const handles: Handle[] = area.shape === 'rect' ? HANDLES : ['n', 'e', 's', 'w'];
  const label = `${area.shape === 'rect' ? 'Rectangular' : 'Circular'} map area${area.href ? ` to ${area.href}` : ''}`;
  return (
    <div
      className={`studio-imgedit__area is-${area.shape}${selected ? ' is-selected' : ''}`}
      style={style}
      role="button"
      tabIndex={0}
      aria-label={label}
      aria-pressed={selected}
      onPointerDown={begin('move')}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect();
        } else nudge(e, (dx, dy) => onChange(dragArea(area, 'move', dx, dy, size)));
      }}
    >
      {selected &&
        handles.map((h) => <span key={h} className={`studio-imgedit__handle is-${h}`} aria-label={`${HANDLE_LABEL[h]} handle`} onPointerDown={begin(h)} />)}
    </div>
  );
}

/**
 * A polygonal area: the outline moves it, a corner's handle moves that corner, the small dot in
 * the middle of a side becomes a new corner when dragged, a double-click on a corner removes it.
 */
function PolyOverlay({
  area,
  size,
  frame,
  selected,
  onSelect,
  onChange,
}: {
  area: MapArea;
  size: Size;
  frame: Frame;
  selected: boolean;
  onSelect(): void;
  onChange(area: MapArea): void;
}) {
  const start = useRef(area);
  const corner = useRef(0);
  const onMove = useDrag(frame, (_h, dx, dy) => onChange(dragArea(start.current, 'move', dx, dy, size)));
  const onCorner = useDrag(frame, (_h, dx, dy) => onChange(dragCorner(start.current, corner.current, dx, dy)));
  const n = area.coords.length / 2;
  const points: Array<[number, number]> = [];
  for (let i = 0; i < n; i++) points.push([area.coords[2 * i], area.coords[2 * i + 1]]);
  const label = `Polygonal map area${area.href ? ` to ${area.href}` : ''}`;
  return (
    <>
      <svg className="studio-imgedit__polylayer" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden={false}>
        <polygon
          className={`studio-imgedit__poly${selected ? ' is-selected' : ''}`}
          points={points.map(([x, y]) => `${x * 100},${y * 100}`).join(' ')}
          role="button"
          tabIndex={0}
          aria-label={label}
          aria-pressed={selected}
          onPointerDown={(e) => {
            start.current = area;
            onSelect();
            onMove('move')(e as unknown as ReactPointerEvent<HTMLElement>);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              onSelect();
            } else nudge(e, (dx, dy) => onChange(dragArea(area, 'move', dx, dy, size)));
          }}
        />
      </svg>
      {selected &&
        points.map(([x, y], i) => {
          const [nx, ny] = points[(i + 1) % n];
          return (
            <span key={`m${i}`}
              className="studio-imgedit__handle is-mid"
              style={{ left: pct((x + nx) / 2), top: pct((y + ny) / 2) }}
              role="button"
              aria-label={`Add a corner after corner ${i + 1}`}
              onPointerDown={(e) => {
                // the new corner is the one being dragged from here on
                start.current = addCorner(area, i);
                corner.current = i + 1;
                onChange(start.current);
                onCorner('move')(e);
              }}
            />
          );
        })}
      {selected &&
        points.map(([x, y], i) => (
          <span
            key={`c${i}`}
            className="studio-imgedit__handle is-corner"
            style={{ left: pct(x), top: pct(y) }}
            role="button"
            tabIndex={0}
            aria-label={`Corner ${i + 1} of ${n}`}
            onPointerDown={(e) => {
              start.current = area;
              corner.current = i;
              onCorner('move')(e);
            }}
            onDoubleClick={() => onChange(removeCorner(area, i))}
            onKeyDown={(e) => {
              if ((e.key === 'Delete' || e.key === 'Backspace') && n > 3) {
                e.preventDefault();
                e.stopPropagation();
                onChange(removeCorner(area, i));
              } else nudge(e, (dx, dy) => onChange(dragCorner(area, i, dx, dy)));
            }}
          />
        ))}
    </>
  );
}

/** Picker keys cannot be empty: 'none' stands for no target. */
const TARGETS: Array<{ key: string; label: string }> = [
  { key: 'none', label: 'Target' },
  { key: '_self', label: 'Same Tab' },
  { key: '_blank', label: 'New Tab' },
  { key: '_parent', label: 'Parent Frame' },
  { key: '_top', label: 'Top Frame' },
];

/** Link of the selected area: path, alt text, target. */
function AreaProperties({
  area,
  onConfirm,
  onCancel,
  onDelete,
}: {
  area: MapArea;
  onConfirm(area: MapArea): void;
  onCancel(): void;
  onDelete(): void;
}) {
  const [href, setHref] = useState(area.href);
  const [alt, setAlt] = useState(area.alt);
  const [target, setTarget] = useState<AreaTarget>(area.target);
  const bad = /^\s*(javascript|data|vbscript):/i.test(href) || href.trim().startsWith('//');
  return (
    <div className="studio-imgedit__areabar" role="group" aria-label="Map area">
      <TextField
        label="Path"
        labelPosition="side"
        value={href}
        onChange={setHref}
        width="size-3600"
        validationState={bad ? 'invalid' : undefined}
        errorMessage="Use a path or an http(s), mailto or tel link"
      />
      <PagePickerButton value={href} onPick={setHref} records />
      <TextField label="Alt Text" labelPosition="side" value={alt} onChange={setAlt} width="size-2400" />
      <Picker aria-label="Target" selectedKey={target || 'none'} onSelectionChange={(k) => setTarget(k === 'none' ? '' : (k as AreaTarget))} width="size-1600" menuWidth="size-2000" items={TARGETS}>
        {(t) => <Item key={t.key}>{t.label}</Item>}
      </Picker>
      <span className="studio-imgedit__spacer" />
      <ToolButton label="Delete" className="is-warning" onPress={onDelete}>
        <Delete />
      </ToolButton>
      <ToolButton label="Cancel" onPress={onCancel}>
        <Close />
      </ToolButton>
      <ToolButton label="Confirm" isDisabled={bad} onPress={() => onConfirm({ ...area, href: href.trim(), alt: alt.trim(), target })}>
        <Checkmark />
      </ToolButton>
    </div>
  );
}
