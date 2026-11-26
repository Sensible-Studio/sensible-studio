import { ActionButton, Button, ButtonGroup, Content, Divider, Heading, Item, Picker, Text, TextField, Tooltip, TooltipTrigger, useDialogContainer } from '@adobe/react-spectrum';
import Crop from '@spectrum-icons/workflow/Crop';
import Delete from '@spectrum-icons/workflow/Delete';
import Circle from '@spectrum-icons/workflow/Circle';
import Polygon from '@spectrum-icons/workflow/Polygon';
import Rectangle from '@spectrum-icons/workflow/Rectangle';
import Link from '@spectrum-icons/workflow/Link';
import FlipHorizontal from '@spectrum-icons/workflow/FlipHorizontal';
import FlipVertical from '@spectrum-icons/workflow/FlipVertical';
import RotateRight from '@spectrum-icons/workflow/RotateRight';
import Undo from '@spectrum-icons/workflow/Undo';
import { useEffect, useRef, useState } from 'react';
import type { PostEntry } from '../../granite';
import type { InplaceConfig } from '../api/StudioClient';
import { PagePickerButton } from '../../assets/pagePicker';
import { StudioDialog } from '../../shell/StudioDialog';

/** What AEM's image editor stores on the component. */
export interface ImageEdits {
  /** `x1,y1,x2,y2` in the coordinates of the web rendition (1280 px wide at most). */
  crop: string | null;
  /** Degrees, a multiple of 90. */
  rotate: number;
  flipH: boolean;
  flipV: boolean;
  /** Image map areas, in the web rendition's pixels. */
  areas: MapArea[];
}

/** One area of the image map, as AEM's `imageMap` stores it (`rect`, `circle` or `poly`). */
export interface MapArea {
  shape: 'rect' | 'circle' | 'poly';
  /** rect: x1,y1,x2,y2; circle: cx,cy,r; poly: x1,y1,x2,y2,… */
  coords: number[];
  href: string;
  target: string;
  alt: string;
}

/**
 * AEM's `imageMap` property: `[rect(x1,y1,x2,y2)"href"|"target"|"alt"|(rx1,ry1,rx2,ry2)]…`, the last group the
 * coordinates as fractions of the image (`w`, `h`: the image the pixels refer to).
 */
export function formatImageMap(areas: MapArea[], w: number, h: number): string | null {
  if (!areas.length) return null;
  const q = (s: string) => `"${s.replace(/"/g, '')}"`;
  const frac = (a: MapArea) =>
    a.shape === 'circle'
      ? [a.coords[0] / w, a.coords[1] / h, a.coords[2] / w]
      : a.coords.map((c, i) => c / (i % 2 === 0 ? w : h));
  return areas
    .map((a) => `[${a.shape}(${a.coords.map(Math.round).join(',')})${q(a.href)}|${q(a.target)}|${q(a.alt)}|(${frac(a).map((f) => f.toFixed(4)).join(',')})]`)
    .join('');
}

export function parseImageMap(value: unknown): MapArea[] {
  if (typeof value !== 'string' || !value) return [];
  const out: MapArea[] = [];
  const re = /\[(rect|circle|poly)\(([^)]*)\)"([^"]*)"\|"([^"]*)"\|"([^"]*)"(?:\|\([^)]*\))?\]/g;
  for (const m of value.matchAll(re)) {
    const coords = m[2].split(',').map((n) => Number(n.trim()));
    if (coords.some((n) => !Number.isFinite(n))) continue;
    out.push({ shape: m[1] as MapArea['shape'], coords, href: m[3], target: m[4], alt: m[5] });
  }
  return out;
}

/**
 * The areas of a map drawn on the asset itself (Media's image editor), in the pixels of a `w`×`h` image: only the
 * map's relative coordinates are used, so the size the asset's pixels referred to does not matter.
 */
export function areasFromAssetMap(value: unknown, w: number, h: number): MapArea[] {
  if (typeof value !== 'string' || !value) return [];
  const out: MapArea[] = [];
  const re = /\[(rect|circle|poly)\([^)]*\)"([^"]*)"\|"([^"]*)"\|"([^"]*)"\|\(([^)]*)\)\]/g;
  for (const m of value.matchAll(re)) {
    const rel = m[5].split(',').map((n) => Number(n.trim()));
    if (rel.some((n) => !Number.isFinite(n))) continue;
    const shape = m[1] as MapArea['shape'];
    if ((shape === 'rect' && rel.length !== 4) || (shape === 'circle' && rel.length !== 3) || (shape === 'poly' && (rel.length < 6 || rel.length % 2))) continue;
    const coords = shape === 'circle' ? [rel[0] * w, rel[1] * h, rel[2] * w] : rel.map((f, i) => f * (i % 2 === 0 ? w : h));
    out.push({ shape, coords: coords.map(Math.round), href: m[2], target: m[3], alt: m[4] });
  }
  return out;
}

/** The asset a rendition URL belongs to (`…/asset.jpg/_jcr_content/renditions/…` → `…/asset.jpg`). */
export const assetOfRendition = (src: string) => (src.includes('/_jcr_content/') ? src.slice(0, src.indexOf('/_jcr_content/')) : null);

/** The map stored on the asset: `jcr:content/metadata/imageMap`, or the `jcr:content/imageMap` it had before it moved. */
export async function loadAssetMap(src: string): Promise<string | null> {
  const asset = assetOfRendition(src);
  if (!asset) return null;
  for (const rel of ['/_jcr_content/metadata.json', '/_jcr_content.json']) {
    try {
      const res = await fetch(asset + rel, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (!res.ok) continue;
      const map = ((await res.json()) as Record<string, unknown>).imageMap;
      if (typeof map === 'string' && map.trim()) return map;
    } catch {
      /* no map */
    }
  }
  return null;
}

export interface ImageEditorFeatures {
  crop: boolean;
  rotate: boolean;
  flip: boolean;
  map: boolean;
  ratios: Array<{ name: string; ratio: number }>;
}

type Node = Record<string, unknown>;
const node = (v: unknown): Node | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Node) : undefined);

/** The image editor plugins the component's `cq:inplaceEditing` turns on. */
export function imageEditorFeatures(ipe: InplaceConfig | undefined): ImageEditorFeatures {
  const name = typeof ipe?.configPath === 'string' && ipe.configPath ? ipe.configPath : 'inplaceEditingConfig';
  const plugins = node(node(ipe?.[name])?.plugins) ?? {};
  const on = (plugin: string) => {
    const f = node(plugins[plugin])?.features;
    if (f === undefined) return plugin === 'crop' || plugin === 'rotate';
    const list = Array.isArray(f) ? f.map(String) : String(f).split(',');
    return !list.some((x) => x.trim() === '-');
  };
  const ratios: Array<{ name: string; ratio: number }> = [];
  const ar = node(node(plugins.crop)?.aspectRatios);
  if (ar) {
    for (const v of Object.values(ar)) {
      const r = node(v);
      const ratio = Number(r?.ratio);
      if (r && ratio > 0) ratios.push({ name: String(r.name ?? ratio), ratio });
    }
  }
  return { crop: on('crop'), rotate: on('rotate'), flip: on('flip'), map: on('map'), ratios };
}

/** Reads the stored edits of a component. */
export function readImageEdits(content: Record<string, unknown>): ImageEdits {
  const rotate = Number(content.imageRotate ?? 0);
  return {
    crop: typeof content.imageCrop === 'string' && content.imageCrop ? content.imageCrop : null,
    rotate: Number.isFinite(rotate) ? ((rotate % 360) + 360) % 360 : 0,
    flipH: content.imageFlipHorizontal === true || content.imageFlipHorizontal === 'true',
    flipV: content.imageFlipVertical === true || content.imageFlipVertical === 'true',
    areas: parseImageMap(content.imageMap),
  };
}

/** The Sling POST that stores (or clears) the edits, as AEM's editor writes them. */
export function imageEditEntries(edits: ImageEdits, image?: { w: number; h: number }): PostEntry[] {
  const out: PostEntry[] = [];
  const map = image ? formatImageMap(edits.areas, image.w, image.h) : null;
  if (map) out.push({ name: './imageMap', value: map });
  else out.push({ name: './imageMap@Delete', value: 'true' });
  if (edits.crop) out.push({ name: './imageCrop', value: edits.crop });
  else out.push({ name: './imageCrop@Delete', value: 'true' });
  if (edits.rotate) out.push({ name: './imageRotate', value: String(edits.rotate) });
  else out.push({ name: './imageRotate@Delete', value: 'true' });
  for (const [k, v] of [
    ['imageFlipHorizontal', edits.flipH],
    ['imageFlipVertical', edits.flipV],
  ] as const) {
    if (v) out.push({ name: `./${k}`, value: 'true' }, { name: `./${k}@TypeHint`, value: 'Boolean' });
    else out.push({ name: `./${k}@Delete`, value: 'true' });
  }
  // Empty values make Sling stamp the time and user. The image URL carries the component's
  // jcr:lastModified, so without this the page keeps showing the cached, uncropped image.
  out.push({ name: './jcr:lastModified', value: '' }, { name: './jcr:lastModifiedBy', value: '' });
  return out;
}

interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

const parseCrop = (s: string | null): Rect | null => {
  if (!s) return null;
  const [x1, y1, x2, y2] = s.split('/')[0].split(',').map((n) => Number(n.trim()));
  if ([x1, y1, x2, y2].some((n) => !Number.isFinite(n)) || x2 <= x1 || y2 <= y1) return null;
  return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
};
const formatCrop = (r: Rect) => `${Math.round(r.x)},${Math.round(r.y)},${Math.round(r.x + r.w)},${Math.round(r.y + r.h)}`;

interface Props {
  title: string;
  /** URL of the web rendition the crop is drawn on. */
  src: string;
  features: ImageEditorFeatures;
  initial: ImageEdits;
  onSave(edits: ImageEdits, image: { w: number; h: number }): Promise<void>;
  /** Reads the map drawn on the asset (offered as a start when the component has none); tests replace it. */
  assetMap?(src: string): Promise<string | null>;
}

/**
 * AEM's in-place image editor, as a dialog: crop (free or with the configured aspect ratios),
 * rotate by 90°, flip, and a reset. Edits are stored on the component; the image servlet applies them.
 */
export function ImageEditorDialog({ title, src, features, initial, onSave, assetMap = loadAssetMap }: Props) {
  const dialog = useDialogContainer();
  const [edits, setEdits] = useState<ImageEdits>(initial);
  const [cropping, setCropping] = useState(false);
  // image map: the tool that draws the next area, the area being edited, the polygon in progress
  const [mapTool, setMapTool] = useState<'rect' | 'circle' | 'poly' | null>(null);
  const [areaIndex, setAreaIndex] = useState<number | null>(null);
  const [polyPoints, setPolyPoints] = useState<number[]>([]);
  const mapping = mapTool !== null || areaIndex !== null;
  const [ratio, setRatio] = useState<number | null>(null);
  const [natural, setNatural] = useState<{ w: number; h: number } | null>(null);
  const [rect, setRect] = useState<Rect | null>(() => parseCrop(initial.crop));
  const [busy, setBusy] = useState(false);
  const imgRef = useRef<HTMLImageElement>(null);
  const dragRef = useRef<{ kind: 'draw' | 'move'; startX: number; startY: number; origin: Rect | null } | null>(null);
  const areaDragRef = useRef<{ startX: number; startY: number } | null>(null);

  useEffect(() => setRect(parseCrop(edits.crop)), [edits.crop]);

  // the map drawn on the asset in Media, offered while the component has no areas of its own
  const [fromAsset, setFromAsset] = useState<string | null>(null);
  useEffect(() => {
    if (!features.map) return;
    let live = true;
    assetMap(src).then(
      (m) => live && setFromAsset(m),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [assetMap, src, features.map]);
  const assetAreas = natural && fromAsset ? areasFromAssetMap(fromAsset, natural.w, natural.h) : [];

  // image coordinates ↔ screen: the image is shown scaled to fit
  const scale = () => {
    const img = imgRef.current;
    if (!img || !natural) return 1;
    return img.clientWidth / natural.w;
  };
  const toImage = (e: React.PointerEvent) => {
    const img = imgRef.current!;
    const r = img.getBoundingClientRect();
    const s = scale();
    return { x: (e.clientX - r.left) / s, y: (e.clientY - r.top) / s };
  };
  const clampRect = (r: Rect): Rect => {
    if (!natural) return r;
    const w = Math.min(r.w, natural.w);
    const h = Math.min(r.h, natural.h);
    return { x: Math.max(0, Math.min(r.x, natural.w - w)), y: Math.max(0, Math.min(r.y, natural.h - h)), w, h };
  };

  const onPointerDown = (e: React.PointerEvent) => {
    if (mapTool && natural) {
      e.preventDefault();
      const p = toImage(e);
      if (mapTool === 'poly') {
        setPolyPoints((pts) => [...pts, p.x, p.y]);
        return;
      }
      (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
      areaDragRef.current = { startX: p.x, startY: p.y };
      return;
    }
    if (!cropping || !natural) return;
    e.preventDefault();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const p = toImage(e);
    const inside = rect && p.x >= rect.x && p.x <= rect.x + rect.w && p.y >= rect.y && p.y <= rect.y + rect.h;
    dragRef.current = { kind: inside ? 'move' : 'draw', startX: p.x, startY: p.y, origin: rect };
  };
  const onPointerMove = (e: React.PointerEvent) => {
    const ad = areaDragRef.current;
    if (ad && mapTool && mapTool !== 'poly' && natural) {
      const p = toImage(e);
      const area: MapArea =
        mapTool === 'rect'
          ? { shape: 'rect', coords: [Math.min(ad.startX, p.x), Math.min(ad.startY, p.y), Math.max(ad.startX, p.x), Math.max(ad.startY, p.y)], href: '', target: '', alt: '' }
          : { shape: 'circle', coords: [ad.startX, ad.startY, Math.hypot(p.x - ad.startX, p.y - ad.startY)], href: '', target: '', alt: '' };
      setEdits((s) => {
        const areas = [...s.areas];
        if (areaIndex !== null && areaIndex < areas.length && areas[areaIndex].href === '' && areas[areaIndex].alt === '') areas[areaIndex] = area;
        else {
          areas.push(area);
          setAreaIndex(areas.length - 1);
        }
        return { ...s, areas };
      });
      return;
    }
    const d = dragRef.current;
    if (!d || !natural) return;
    const p = toImage(e);
    if (d.kind === 'move' && d.origin) {
      setRect(clampRect({ ...d.origin, x: d.origin.x + (p.x - d.startX), y: d.origin.y + (p.y - d.startY) }));
      return;
    }
    let w = Math.abs(p.x - d.startX);
    let h = Math.abs(p.y - d.startY);
    if (ratio) h = w * ratio;
    const x = p.x < d.startX ? d.startX - w : d.startX;
    const y = p.y < d.startY || ratio ? (p.y < d.startY ? d.startY - h : d.startY) : d.startY;
    if (w > 4 && h > 4) setRect(clampRect({ x, y, w, h }));
  };
  const onPointerUp = () => {
    dragRef.current = null;
    if (areaDragRef.current) {
      areaDragRef.current = null;
      setMapTool(null);
    }
  };

  const finishPolygon = () => {
    if (polyPoints.length >= 6) {
      setEdits((s) => ({ ...s, areas: [...s.areas, { shape: 'poly', coords: polyPoints, href: '', target: '', alt: '' }] }));
      setAreaIndex(edits.areas.length);
    }
    setPolyPoints([]);
    setMapTool(null);
  };

  const updateArea = (i: number, changes: Partial<MapArea>) =>
    setEdits((s) => ({ ...s, areas: s.areas.map((a, j) => (j === i ? { ...a, ...changes } : a)) }));
  const removeArea = (i: number) => {
    setEdits((s) => ({ ...s, areas: s.areas.filter((_, j) => j !== i) }));
    setAreaIndex(null);
  };

  const applyCrop = () => {
    setEdits((s) => ({ ...s, crop: rect ? formatCrop(rect) : null }));
    setCropping(false);
  };

  const save = async () => {
    if (!natural) return;
    setBusy(true);
    try {
      await onSave(edits, natural);
      dialog.dismiss();
    } finally {
      setBusy(false);
    }
  };

  const s = scale();
  const transform = `rotate(${edits.rotate}deg) scaleX(${edits.flipH ? -1 : 1}) scaleY(${edits.flipV ? -1 : 1})`;
  const shown = cropping ? rect : parseCrop(edits.crop);

  return (
    <StudioDialog size="L" UNSAFE_className="studio-imageeditor">
      <Heading>{title}</Heading>
      <Divider />
      <Content>
        <div className="studio-imageeditor__toolbar" role="toolbar" aria-label="Image tools">
          {features.crop && !cropping && (
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet aria-label="Crop" onPress={() => setCropping(true)}>
                <Crop />
              </ActionButton>
              <Tooltip>Crop</Tooltip>
            </TooltipTrigger>
          )}
          {features.crop && !cropping && edits.crop && (
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet aria-label="Remove crop" onPress={() => setEdits((e) => ({ ...e, crop: null }))}>
                <Text>Remove crop</Text>
              </ActionButton>
              <Tooltip>Show the whole image again (rotation, flips and the image map stay)</Tooltip>
            </TooltipTrigger>
          )}
          {cropping && (
            <>
              <Picker
                aria-label="Aspect ratio"
                isQuiet
                selectedKey={ratio === null ? 'free' : String(ratio)}
                onSelectionChange={(k) => setRatio(k === 'free' ? null : Number(k))}
              >
                {[{ name: 'Free crop', ratio: 0 }, ...features.ratios].map((r) => (
                  <Item key={r.ratio === 0 ? 'free' : String(r.ratio)}>{r.name}</Item>
                ))}
              </Picker>
              <Text UNSAFE_className="studio-imageeditor__hint">Drag on the image to crop; drag the selection to move it.</Text>
              <span className="studio-imageeditor__gap" />
              <Button variant="secondary" onPress={() => setCropping(false)}>
                Cancel crop
              </Button>
              {edits.crop && (
                <Button
                  variant="secondary"
                  onPress={() => {
                    setEdits((e) => ({ ...e, crop: null }));
                    setCropping(false);
                  }}
                >
                  Remove crop
                </Button>
              )}
              <Button variant="accent" onPress={applyCrop}>
                Apply
              </Button>
            </>
          )}
          {!cropping && features.rotate && (
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet aria-label="Rotate right" onPress={() => setEdits((e) => ({ ...e, rotate: (e.rotate + 90) % 360 }))}>
                <RotateRight />
              </ActionButton>
              <Tooltip>Rotate right</Tooltip>
            </TooltipTrigger>
          )}
          {!cropping && features.flip && (
            <>
              <TooltipTrigger delay={400}>
                <ActionButton isQuiet aria-label="Flip horizontal" onPress={() => setEdits((e) => ({ ...e, flipH: !e.flipH }))}>
                  <FlipHorizontal />
                </ActionButton>
                <Tooltip>Flip horizontal</Tooltip>
              </TooltipTrigger>
              <TooltipTrigger delay={400}>
                <ActionButton isQuiet aria-label="Flip vertical" onPress={() => setEdits((e) => ({ ...e, flipV: !e.flipV }))}>
                  <FlipVertical />
                </ActionButton>
                <Tooltip>Flip vertical</Tooltip>
              </TooltipTrigger>
            </>
          )}
          {!cropping && features.map && (
            <>
              <span className="studio-imageeditor__sep" />
              <TooltipTrigger delay={400}>
                <ActionButton isQuiet aria-label="Map: rectangle" aria-pressed={mapTool === 'rect'} UNSAFE_className={mapTool === 'rect' ? 'is-active' : undefined} onPress={() => { setPolyPoints([]); setMapTool(mapTool === 'rect' ? null : 'rect'); setAreaIndex(null); }}>
                  <Rectangle />
                </ActionButton>
                <Tooltip>Add a rectangular area: drag on the image</Tooltip>
              </TooltipTrigger>
              <TooltipTrigger delay={400}>
                <ActionButton isQuiet aria-label="Map: circle" aria-pressed={mapTool === 'circle'} UNSAFE_className={mapTool === 'circle' ? 'is-active' : undefined} onPress={() => { setPolyPoints([]); setMapTool(mapTool === 'circle' ? null : 'circle'); setAreaIndex(null); }}>
                  <Circle />
                </ActionButton>
                <Tooltip>Add a circular area: drag from the centre</Tooltip>
              </TooltipTrigger>
              <TooltipTrigger delay={400}>
                <ActionButton isQuiet aria-label="Map: polygon" aria-pressed={mapTool === 'poly'} UNSAFE_className={mapTool === 'poly' ? 'is-active' : undefined} onPress={() => { setPolyPoints([]); setMapTool(mapTool === 'poly' ? null : 'poly'); setAreaIndex(null); }}>
                  <Polygon />
                </ActionButton>
                <Tooltip>Add a polygon: click the corners, then Finish</Tooltip>
              </TooltipTrigger>
              {edits.areas.length === 0 && assetAreas.length > 0 && !mapTool && (
                <TooltipTrigger delay={400}>
                  <Button
                    variant="secondary"
                    onPress={() => {
                      setEdits((e) => ({ ...e, areas: assetAreas }));
                      setAreaIndex(null);
                    }}
                  >
                    Use the map from Media ({assetAreas.length})
                  </Button>
                  <Tooltip>Start from the areas drawn on this image in Media; you can change them here</Tooltip>
                </TooltipTrigger>
              )}
              {mapTool === 'poly' && (
                <Button variant="accent" onPress={finishPolygon} isDisabled={polyPoints.length < 6}>
                  Finish polygon
                </Button>
              )}
            </>
          )}
          {!cropping && (
            <TooltipTrigger delay={400}>
              <ActionButton
                isQuiet
                aria-label="Reset"
                isDisabled={!edits.crop && !edits.rotate && !edits.flipH && !edits.flipV && !edits.areas.length}
                onPress={() => {
                  setEdits({ crop: null, rotate: 0, flipH: false, flipV: false, areas: [] });
                  setAreaIndex(null);
                }}
              >
                <Undo />
              </ActionButton>
              <Tooltip>Remove crop, rotation, flips and the image map</Tooltip>
            </TooltipTrigger>
          )}
        </div>
        <div className={`studio-imageeditor__stage${cropping || mapTool ? ' is-cropping' : ''}`} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp}>
          <div className="studio-imageeditor__canvas" style={{ transform: cropping || mapping ? undefined : transform }}>
            <img ref={imgRef} src={src} alt="" draggable={false} onLoad={(e) => setNatural({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })} />
            {shown && natural && (
              <div className="studio-imageeditor__crop" style={{ left: shown.x * s, top: shown.y * s, width: shown.w * s, height: shown.h * s }} />
            )}
            {natural && (edits.areas.length > 0 || polyPoints.length > 0) && (
              <svg className="studio-imageeditor__map" viewBox={`0 0 ${natural.w} ${natural.h}`} preserveAspectRatio="none">
                {edits.areas.map((a, i) => {
                  const cls = `studio-imageeditor__area${i === areaIndex ? ' is-selected' : ''}`;
                  const onClick = (e: React.MouseEvent) => {
                    e.stopPropagation();
                    if (!mapTool) setAreaIndex(i);
                  };
                  if (a.shape === 'rect') return <rect key={i} className={cls} x={a.coords[0]} y={a.coords[1]} width={a.coords[2] - a.coords[0]} height={a.coords[3] - a.coords[1]} onClick={onClick} />;
                  if (a.shape === 'circle') return <circle key={i} className={cls} cx={a.coords[0]} cy={a.coords[1]} r={a.coords[2]} onClick={onClick} />;
                  return <polygon key={i} className={cls} points={a.coords.join(' ')} onClick={onClick} />;
                })}
                {polyPoints.length >= 2 && <polyline className="studio-imageeditor__area is-drawing" points={polyPoints.join(' ')} />}
              </svg>
            )}
          </div>
        </div>
        {areaIndex !== null && edits.areas[areaIndex] && (
          <div className="studio-imageeditor__areaform">
            <Link size="S" />
            <TextField aria-label="Link" placeholder="Path or URL" value={edits.areas[areaIndex].href} onChange={(v) => updateArea(areaIndex, { href: v })} flex={2} />
            <PagePickerButton value={edits.areas[areaIndex].href} onPick={(p) => updateArea(areaIndex, { href: p })} />
            <Picker aria-label="Target" isQuiet selectedKey={edits.areas[areaIndex].target || '_self'} onSelectionChange={(k) => updateArea(areaIndex, { target: k === '_self' ? '' : String(k) })}>
              <Item key="_self">Same tab</Item>
              <Item key="_blank">New tab</Item>
            </Picker>
            <TextField aria-label="Alternative text" placeholder="Alternative text" value={edits.areas[areaIndex].alt} onChange={(v) => updateArea(areaIndex, { alt: v })} flex={1} />
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet aria-label="Remove area" onPress={() => removeArea(areaIndex)}>
                <Delete />
              </ActionButton>
              <Tooltip>Remove this area</Tooltip>
            </TooltipTrigger>
          </div>
        )}
        <Text UNSAFE_className="studio-imageeditor__status">
          {edits.crop ? `Crop ${edits.crop}` : 'No crop'}
          {edits.rotate ? ` · rotated ${edits.rotate}°` : ''}
          {edits.flipH ? ' · flipped horizontally' : ''}
          {edits.flipV ? ' · flipped vertically' : ''}
          {edits.areas.length ? ` · ${edits.areas.length} map area${edits.areas.length === 1 ? '' : 's'}` : ''}
        </Text>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void save()} isPending={busy} isDisabled={cropping}>
          Done
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
