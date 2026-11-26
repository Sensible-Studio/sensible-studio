import { ActionButton, Item, NumberField, Picker, Section, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import DeviceDesktop from '@spectrum-icons/workflow/DeviceDesktop';
import DevicePhone from '@spectrum-icons/workflow/DevicePhone';
import DeviceRotateLandscape from '@spectrum-icons/workflow/DeviceRotateLandscape';
import DeviceRotatePortrait from '@spectrum-icons/workflow/DeviceRotatePortrait';
import DeviceTablet from '@spectrum-icons/workflow/DeviceTablet';
import { useEffect, useRef, type Key, type PointerEvent as ReactPointerEvent } from 'react';
import type { Breakpoint } from '../api/StudioClient';

/** A device of the emulator: CSS pixels in portrait. `native` is the desktop (no emulation). */
export interface Device {
  id: string;
  title: string;
  /** Viewport width in CSS px; `null` fills the editor (desktop). */
  width: number | null;
  height: number | null;
  kind?: 'phone' | 'tablet' | 'other';
}

export type Orientation = 'portrait' | 'landscape';

export const MIN_SIZE = 240;
export const MAX_SIZE = 4096;

/**
 * The devices, like the device toolbar of Chrome DevTools: Responsive (any size, resizable by hand),
 * the current phones and tablets, and the desktop (no emulation). Sizes are CSS pixels in portrait.
 */
export const DEVICES: Device[] = [
  { id: 'native', title: 'Desktop', width: null, height: null },
  { id: 'responsive', title: 'Responsive', width: 400, height: 800, kind: 'other' },
  { id: 'iphone-se', title: 'iPhone SE', width: 375, height: 667, kind: 'phone' },
  { id: 'iphone-12-pro', title: 'iPhone 12 Pro', width: 390, height: 844, kind: 'phone' },
  { id: 'iphone-14-pro-max', title: 'iPhone 14 Pro Max', width: 430, height: 932, kind: 'phone' },
  { id: 'pixel-7', title: 'Pixel 7', width: 412, height: 915, kind: 'phone' },
  { id: 'galaxy-s8-plus', title: 'Samsung Galaxy S8+', width: 360, height: 740, kind: 'phone' },
  { id: 'galaxy-s20-ultra', title: 'Samsung Galaxy S20 Ultra', width: 412, height: 915, kind: 'phone' },
  { id: 'galaxy-fold', title: 'Galaxy Z Fold 5', width: 344, height: 882, kind: 'phone' },
  { id: 'ipad-mini', title: 'iPad Mini', width: 768, height: 1024, kind: 'tablet' },
  { id: 'ipad-air', title: 'iPad Air', width: 820, height: 1180, kind: 'tablet' },
  { id: 'ipad-pro', title: 'iPad Pro', width: 1024, height: 1366, kind: 'tablet' },
  { id: 'surface-pro-7', title: 'Surface Pro 7', width: 912, height: 1368, kind: 'tablet' },
  { id: 'surface-duo', title: 'Surface Duo', width: 540, height: 720, kind: 'tablet' },
  { id: 'nest-hub', title: 'Nest Hub', width: 1024, height: 600, kind: 'other' },
  { id: 'nest-hub-max', title: 'Nest Hub Max', width: 1280, height: 800, kind: 'other' },
];

/** The viewport width a device gives in an orientation (null: the desktop fills the editor). */
export function deviceWidth(device: Device, orientation: Orientation): number | null {
  if (device.width === null || device.height === null) return null;
  return orientation === 'portrait' ? device.width : device.height;
}

/** The viewport height a device gives in an orientation (null: the desktop fills the editor). */
export function deviceHeight(device: Device, orientation: Orientation): number | null {
  if (device.width === null || device.height === null) return null;
  return orientation === 'portrait' ? device.height : device.width;
}

/** A size typed or dragged into the Responsive device, kept within what the emulator can show. */
export function clampSize(n: number): number {
  return Math.min(MAX_SIZE, Math.max(MIN_SIZE, Math.round(n)));
}

/**
 * The Responsive device with a new viewport size. The size is stored in portrait terms, so in
 * landscape the two are swapped before storing; `width`/`height` are what the user sees.
 */
export function responsiveWith(device: Device, orientation: Orientation, width: number, height: number): Device {
  const w = clampSize(width);
  const h = clampSize(height);
  return orientation === 'portrait' ? { ...device, width: w, height: h } : { ...device, width: h, height: w };
}

/** The breakpoint a viewport width falls in, by the template's breakpoints (largest width each applies to). */
export function breakpointOf(viewport: number, breakpoints: Breakpoint[]): Breakpoint | null {
  const sized = breakpoints.filter((b) => b.width).sort((a, b) => a.width! - b.width!);
  for (const b of sized) if (viewport < b.width!) return b;
  return null;
}

/** A width preset of the Responsive device: one segment of the strip under the toolbar. */
export interface Preset {
  title: string;
  width: number;
}

/** The five widths of the strip, from a phone to a large desktop: together they walk through every template layout. */
export const PRESETS: Preset[] = [
  { title: 'Phone', width: 360 },
  { title: 'Tablet', width: 768 },
  { title: 'Desktop S', width: 1024 },
  { title: 'Desktop M', width: 1280 },
  { title: 'Desktop L', width: 1920 },
];

/** How the emulated viewport is scaled on the stage: fit to the stage (never above 1) or a fixed factor. */
export type Zoom = 'fit' | number;

export const ZOOMS: number[] = [0.5, 0.75, 1, 1.25, 1.5];

/** The scale that fits a viewport width into the stage (room left for the drag handles); never above 1. */
export function fitScale(viewportWidth: number, stageWidth: number, margin = 48): number {
  if (viewportWidth <= 0 || stageWidth <= 0) return 1;
  return Math.min(1, Math.max(0.1, (stageWidth - margin) / viewportWidth));
}

/** The scale in effect for a zoom setting. */
export function effectiveScale(zoom: Zoom, viewportWidth: number, stageWidth: number): number {
  return zoom === 'fit' ? fitScale(viewportWidth, stageWidth) : zoom;
}

/** A band of the preset strip: `from`/`to` are distances from the strip's centre, in stage px, on one side. */
export interface PresetBand {
  preset: Preset;
  side: 'left' | 'right' | 'center';
  from: number;
  to: number;
}

interface Props {
  device: Device;
  orientation: Orientation;
  onDevice(device: Device): void;
  onOrientation(orientation: Orientation): void;
  viewportWidth: number;
  breakpoints: Breakpoint[];
  breakpoint: string;
  /** Layout mode: the template's breakpoints can be picked and the current one is shown. */
  showBreakpoints: boolean;
  zoom: Zoom;
  onZoom(zoom: Zoom): void;
  /** The scale the viewport is drawn at (what "Fit to window" came to). */
  scale: number;
}

/**
 * Device emulation, like the device toolbar of Chrome DevTools: a device picker (Responsive, phones,
 * tablets, Desktop), the viewport size as two fields that can be typed into when the device is
 * Responsive, Rotate, the zoom and, in Layout mode, which of the template's breakpoints the viewport
 * width falls in (a fact about the width, not a choice: the strip below shows where each one starts).
 * The Responsive viewport also has drag handles (`ViewportHandles`) on its right and bottom edges.
 */
export function EmulatorBar(props: Props) {
  const { device, orientation, onDevice, onOrientation, viewportWidth, breakpoints, breakpoint, showBreakpoints, zoom, onZoom, scale } = props;
  const width = deviceWidth(device, orientation);
  const height = deviceHeight(device, orientation);
  const responsive = device.id === 'responsive';
  const current = breakpoints.find((b) => b.name === breakpoint);
  const phones = DEVICES.filter((d) => d.kind === 'phone');
  const tablets = DEVICES.filter((d) => d.kind === 'tablet');
  const others = DEVICES.filter((d) => d.kind === 'other' && d.id !== 'responsive');

  const pick = (k: Key | null) => {
    const d = DEVICES.find((x) => x.id === k);
    if (d) onDevice(d);
  };

  return (
    <div className="studio-emulator" role="toolbar" aria-label="Device emulation">
      <span className="studio-emulator__icon" aria-hidden="true">
        {width === null ? <DeviceDesktop size="S" /> : (width >= 600 && !responsive) ? <DeviceTablet size="S" /> : <DevicePhone size="S" />}
      </span>
      <Picker aria-label="Device" isQuiet selectedKey={device.id} onSelectionChange={pick} UNSAFE_className="studio-emulator__picker">
        <Section title="Dimensions">
          <Item key="responsive">Responsive</Item>
          <Item key="native">Desktop</Item>
        </Section>
        <Section title="Phones">
          {phones.map((d) => (
            <Item key={d.id} textValue={d.title}>
              {d.title}
            </Item>
          ))}
        </Section>
        <Section title="Tablets">
          {tablets.map((d) => (
            <Item key={d.id} textValue={d.title}>
              {d.title}
            </Item>
          ))}
        </Section>
        <Section title="Other">
          {others.map((d) => (
            <Item key={d.id} textValue={d.title}>
              {d.title}
            </Item>
          ))}
        </Section>
      </Picker>

      <span className="studio-emulator__size">
        {width !== null && height !== null ? (
          <>
            <NumberField
              aria-label="Viewport width"
              isQuiet
              hideStepper
              minValue={MIN_SIZE}
              maxValue={MAX_SIZE}
              value={width}
              isReadOnly={!responsive}
              onChange={(v) => Number.isFinite(v) && onDevice(responsiveWith(device, orientation, v, height))}
              UNSAFE_className="studio-emulator__field"
            />
            <span className="studio-emulator__times">×</span>
            <NumberField
              aria-label="Viewport height"
              isQuiet
              hideStepper
              minValue={MIN_SIZE}
              maxValue={MAX_SIZE}
              value={height}
              isReadOnly={!responsive}
              onChange={(v) => Number.isFinite(v) && onDevice(responsiveWith(device, orientation, width, v))}
              UNSAFE_className="studio-emulator__field"
            />
          </>
        ) : (
          <span className="studio-emulator__muted">{Math.round(viewportWidth)} px wide · fills the editor</span>
        )}
      </span>

      <TooltipTrigger delay={500}>
        <ActionButton
          isQuiet
          aria-label="Rotate"
          isDisabled={width === null}
          onPress={() => onOrientation(orientation === 'portrait' ? 'landscape' : 'portrait')}
        >
          {orientation === 'portrait' ? <DeviceRotatePortrait /> : <DeviceRotateLandscape />}
        </ActionButton>
        <Tooltip>Rotate</Tooltip>
      </TooltipTrigger>

      {width !== null && (
        <Picker
          aria-label="Zoom"
          isQuiet
          selectedKey={zoom === 'fit' ? 'fit' : String(zoom)}
          onSelectionChange={(k) => onZoom(k === 'fit' ? 'fit' : Number(k))}
          UNSAFE_className="studio-emulator__picker studio-emulator__zoom"
        >
          {[{ key: 'fit', label: zoom === 'fit' ? `Fit to window · ${Math.round(scale * 100)}%` : 'Fit to window' }, ...ZOOMS.map((z) => ({ key: String(z), label: `${Math.round(z * 100)}%` }))].map((o) => (
            <Item key={o.key} textValue={o.label}>
              {o.label}
            </Item>
          ))}
        </Picker>
      )}

      {showBreakpoints && breakpoints.length > 0 && (
        <>
          <span className="studio-emulator__sep" aria-hidden="true" />
          <span className="studio-emulator__label">Layout</span>
          <span className="studio-emulator__breakpoint" title="The template's breakpoint the viewport width falls in: it decides which column widths apply">
            {current ? `${current.title} (below ${current.width} px)` : 'Default (desktop)'}
          </span>
        </>
      )}
    </div>
  );
}

interface HandlesProps {
  device: Device;
  orientation: Orientation;
  onDevice(device: Device): void;
  /** The scale the viewport is drawn at: a pointer moved by `dx` on screen moved `dx / scale` in viewport px. */
  scale?: number;
}

/**
 * The drag handles of the Responsive viewport, like Chrome's: the right edge changes the width, the
 * bottom edge the height, the corner both. The viewport is centered, so a pointer moved by `dx`
 * changes the width by `2·dx`. While dragging, an overlay keeps the iframe from eating the pointer.
 */
export function ViewportHandles({ device, orientation, onDevice, scale = 1 }: HandlesProps) {
  const drag = useRef<{ axis: 'x' | 'y' | 'xy'; startX: number; startY: number; width: number; height: number } | null>(null);

  useEffect(() => {
    const move = (e: PointerEvent) => {
      const d = drag.current;
      if (!d) return;
      const w = d.axis === 'y' ? d.width : d.width + (2 * (e.clientX - d.startX)) / scale;
      const h = d.axis === 'x' ? d.height : d.height + (e.clientY - d.startY) / scale;
      onDevice(responsiveWith(device, orientation, w, h));
    };
    const up = () => {
      if (!drag.current) return;
      drag.current = null;
      document.body.classList.remove('is-resizing-viewport');
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', up);
    };
  }, [device, orientation, onDevice, scale]);

  const start = (axis: 'x' | 'y' | 'xy') => (e: ReactPointerEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    drag.current = { axis, startX: e.clientX, startY: e.clientY, width: deviceWidth(device, orientation) ?? 0, height: deviceHeight(device, orientation) ?? 0 };
    document.body.classList.add('is-resizing-viewport');
  };

  return (
    <>
      <div className="studio-viewport-handle studio-viewport-handle--x" onPointerDown={start('x')} title="Drag to change the width" />
      <div className="studio-viewport-handle studio-viewport-handle--y" onPointerDown={start('y')} title="Drag to change the height" />
      <div className="studio-viewport-handle studio-viewport-handle--xy" onPointerDown={start('xy')} title="Drag to resize" />
    </>
  );
}

interface RulerProps {
  device: Device;
  orientation: Orientation;
  onDevice(device: Device): void;
  /** The viewport width in CSS px (the emulated one, or the stage's for the desktop). */
  viewportWidth: number;
}

/**
 * The strip under the toolbar: five equal segments, Phone 360 · Tablet 768 · Desktop S 1024 · Desktop M 1280 ·
 * Desktop L 1920. A click switches to the Responsive device at that width; the segment of the current width
 * is marked. Which template layout a width gets is in the toolbar's Layout label.
 */
export function PresetRuler({ device, orientation, onDevice, viewportWidth }: RulerProps) {
  const width = deviceWidth(device, orientation) ?? viewportWidth;
  const setWidth = (w: number) => {
    const r = DEVICES.find((d) => d.id === 'responsive')!;
    const h = deviceHeight(device, orientation) ?? r.height!;
    onDevice(responsiveWith(device.id === 'responsive' ? device : r, orientation, w, h));
  };
  return (
    <div className="studio-presets" role="group" aria-label="Width presets">
      {PRESETS.map((p) => (
        <button
          key={p.width}
          type="button"
          className={`studio-presets__band${width === p.width ? ' is-active' : ''}`}
          onClick={() => setWidth(p.width)}
          aria-pressed={width === p.width}
        >
          {p.title} ({p.width}px)
        </button>
      ))}
    </div>
  );
}
