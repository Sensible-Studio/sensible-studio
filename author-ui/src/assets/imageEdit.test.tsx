import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { assetsRoute, routeUrl } from './route';
import { ImageEditor, isEditable } from './ImageEditor';
import {
  addCorner,
  areaForward,
  areaInverse,
  clipPolygon,
  defaultPolygon,
  dragArea,
  dragCorner,
  dragBox,
  finalAreas,
  forward,
  initialCrop,
  inverse,
  isIdentity,
  parseImageMap,
  polygonArea,
  removeCorner,
  sizeAfter,
  type EditOperation,
  type MapArea,
} from './imageEdit';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

beforeAll(() => {
  // jsdom has no canvas: the preview is only drawn, never read, so a stub is enough.
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    drawImage() {},
    translate() {},
    rotate() {},
    scale() {},
  } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/png;base64,AA==');
  if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
    (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
      escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
    };
  }
});

const disabled = (el: HTMLElement) => (el as HTMLButtonElement).disabled;
const close = (a: number, b: number) => Math.abs(a - b) < 1e-9;

describe('editor geometry', () => {
  it('turns and flips points like studio-dam turns pixels', () => {
    // Clockwise: top-left goes to top-right.
    expect(forward({ x: 0, y: 0 }, [{ op: 'rotate', degrees: 90 }])).toEqual({ x: 1, y: 0 });
    expect(forward({ x: 0.2, y: 0.3 }, [{ op: 'rotate', degrees: 270 }])).toEqual({ x: 0.3, y: 0.8 });
    expect(forward({ x: 0.2, y: 0.3 }, [{ op: 'flip', axis: 'horizontal' }])).toEqual({ x: 0.8, y: 0.3 });
    const ops: EditOperation[] = [
      { op: 'crop', x: 0.1, y: 0.2, width: 0.5, height: 0.6 },
      { op: 'rotate', degrees: 90 },
      { op: 'flip', axis: 'vertical' },
    ];
    const p = { x: 0.33, y: 0.47 };
    const back = inverse(forward(p, ops), ops);
    expect(close(back.x, p.x) && close(back.y, p.y)).toBe(true);
  });

  it('tracks the pixel size', () => {
    expect(sizeAfter({ width: 1600, height: 886 }, [{ op: 'rotate', degrees: 90 }])).toEqual({ width: 886, height: 1600 });
    expect(sizeAfter({ width: 1600, height: 886 }, [{ op: 'crop', x: 0, y: 0, width: 0.5, height: 0.5 }])).toEqual({ width: 800, height: 443 });
  });

  it('knows when nothing changed', () => {
    const turn: EditOperation = { op: 'rotate', degrees: 90 };
    expect(isIdentity([turn, turn, turn, turn])).toBe(true);
    expect(isIdentity([{ op: 'flip', axis: 'vertical' }, { op: 'flip', axis: 'vertical' }])).toBe(true);
    expect(isIdentity([turn])).toBe(false);
  });

  it('starts ratio crops at the ratio in pixels', () => {
    const size = { width: 1600, height: 900 };
    const box = initialCrop(size, 1);
    expect(close((box.width * size.width) / (box.height * size.height), 1)).toBe(true);
    expect(box.x + box.width / 2).toBeCloseTo(0.5);
    const dragged = dragBox(box, 'se', 0.05, 0.3, size, 1);
    expect(close((dragged.width * size.width) / (dragged.height * size.height), 1)).toBe(true);
    expect(dragged.x + dragged.width).toBeLessThanOrEqual(1);
    expect(dragged.y + dragged.height).toBeLessThanOrEqual(1);
  });

  it('moves map areas with the image and clips them on save', () => {
    const source = { width: 1000, height: 500 };
    const ops: EditOperation[] = [{ op: 'crop', x: 0.5, y: 0, width: 0.5, height: 1 }];
    const area: MapArea = { shape: 'rect', coords: [0.4, 0.2, 0.7, 0.6], href: '/content/x', target: '', alt: 'X' };
    const shown = areaForward(area, source, ops);
    expect(shown.coords.map((c) => Math.round(c * 100) / 100)).toEqual([-0.2, 0.2, 0.4, 0.6]);
    expect(areaInverse(shown, source, ops).coords.map((c) => Math.round(c * 100) / 100)).toEqual([0.4, 0.2, 0.7, 0.6]);
    const saved = finalAreas([area, { ...area, coords: [0, 0, 0.3, 0.3] }], source, ops);
    expect(saved).toHaveLength(1);
    expect(saved[0].coords.map((c) => Math.round(c * 100) / 100)).toEqual([0, 0.2, 0.4, 0.6]);
  });

  it('reads the Core Components map notation', () => {
    const map = parseImageMap('[rect(10,20,300,400)"/content/a"|"_blank"|"A"|(0.01,0.02,0.3,0.4)][circle(5,5,10)"#b"|""|"B"|(0.5,0.5,0.1)][rect(1,2,3,4)"x"|""|""]');
    expect(map).toEqual([
      { shape: 'rect', coords: [0.01, 0.02, 0.3, 0.4], href: '/content/a', target: '_blank', alt: 'A' },
      { shape: 'circle', coords: [0.5, 0.5, 0.1], href: '#b', target: '', alt: 'B' },
    ]);
  });

  it('reads polygons and keeps them through turns and crops', () => {
    const [poly] = parseImageMap('[poly(10,10,90,10,50,80)"/content/wknd"|""|"Tent"|(0.1,0.1,0.9,0.1,0.5,0.8)]');
    expect(poly).toEqual({ shape: 'poly', coords: [0.1, 0.1, 0.9, 0.1, 0.5, 0.8], href: '/content/wknd', target: '', alt: 'Tent' });
    expect(parseImageMap('[poly(1,1,2,2)""|""|""|(0.1,0.1,0.2,0.2)]')).toEqual([]); // two corners are no polygon
    const size = { width: 400, height: 200 };
    const ops: EditOperation[] = [{ op: 'rotate', degrees: 90 }, { op: 'flip', axis: 'horizontal' }];
    const back = areaInverse(areaForward(poly, size, ops), size, ops);
    back.coords.forEach((c, i) => expect(c).toBeCloseTo(poly.coords[i], 6));
  });

  it('cuts a polygon to what a crop leaves of it', () => {
    // a square over the right half of the image; the crop keeps the left 75%
    const square: MapArea = { shape: 'poly', coords: [0.5, 0.25, 1, 0.25, 1, 0.75, 0.5, 0.75], href: '', target: '', alt: '' };
    const [cut] = finalAreas([square], { width: 400, height: 400 }, [{ op: 'crop', x: 0, y: 0, width: 0.75, height: 1 }]);
    expect(cut.shape).toBe('poly');
    expect(polygonArea(cut.coords)).toBeCloseTo((1 / 3) * 0.5, 4);
    expect(Math.max(...cut.coords)).toBeLessThanOrEqual(1);
    // nothing of it is left by a crop of the left half
    expect(finalAreas([square], { width: 400, height: 400 }, [{ op: 'crop', x: 0, y: 0, width: 0.4, height: 1 }])).toEqual([]);
    expect(clipPolygon([-0.5, 0.5, 0.5, -0.5, 1.5, 0.5, 0.5, 1.5]).length).toBe(8); // a diamond around the image: the image itself
  });

  it('shapes a polygon by its corners', () => {
    const a: MapArea = { shape: 'poly', coords: [0.2, 0.2, 0.8, 0.2, 0.5, 0.8], href: '', target: '', alt: '' };
    expect(addCorner(a, 0).coords).toEqual([0.2, 0.2, 0.5, 0.2, 0.8, 0.2, 0.5, 0.8]);
    expect(addCorner(a, 2).coords.slice(6)).toEqual([0.35, 0.5]);
    expect(removeCorner(addCorner(a, 0), 1).coords).toEqual(a.coords);
    expect(removeCorner(a, 0)).toBe(a); // three corners stay
    expect(dragCorner(a, 2, 0.9, 0.1).coords.slice(4)).toEqual([1, 0.9]);
    // moved as a whole it stops at the edge without losing its shape
    const moved = dragArea(a, 'move', 0.9, 0, { width: 100, height: 100 });
    expect(moved.coords[2]).toBeCloseTo(1);
    expect(moved.coords[2] - moved.coords[0]).toBeCloseTo(0.6);
    expect(defaultPolygon({ width: 400, height: 200 }).length).toBe(10);
  });

  it('routes the AEM editor URL', () => {
    const url = '/mnt/overlay/dam/gui/content/assets/assetedit.html/content/dam/wknd/surf.jpg';
    expect(assetsRoute({ pathname: url, hash: '' })).toEqual({ kind: 'edit', path: '/content/dam/wknd/surf.jpg' });
    expect(routeUrl({ kind: 'edit', path: '/content/dam/wknd/surf.jpg' })).toBe(url);
  });

  it('edits only finished JPEG, PNG and WebP images', () => {
    expect(isEditable({ type: 'asset', mimeType: 'image/jpeg', state: 'processed' })).toBe(true);
    expect(isEditable({ type: 'asset', mimeType: 'image/jpeg', state: 'processing' })).toBe(false);
    expect(isEditable({ type: 'asset', mimeType: 'image/gif' })).toBe(false);
    expect(isEditable({ type: 'asset', mimeType: 'application/pdf' })).toBe(false);
    expect(isEditable({ type: 'folder' })).toBe(false);
  });
});

describe('image editor', () => {
  function setup() {
    const dam = new MemoryDam().folder('/content/dam/wknd', 'WKND').asset('/content/dam/wknd/surf.jpg', { width: 1000, height: 500 }, { 'dc:title': 'Surf' });
    const navigate = vi.fn();
    const fakeImage = { naturalWidth: 1000, naturalHeight: 500, width: 1000, height: 500 } as HTMLImageElement;
    render(
      <Provider theme={defaultTheme}>
        <ImageEditor client={dam} path="/content/dam/wknd/surf.jpg" navigate={navigate} loadImage={async () => fakeImage} />
      </Provider>,
    );
    return { dam, navigate };
  }

  it('applies turns and flips on Finish, then goes back', async () => {
    const { dam, navigate } = setup();
    const finish = await screen.findByRole('button', { name: 'Finish' });
    expect(disabled(finish)).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Rotate Right' }));
    fireEvent.click(screen.getByRole('button', { name: 'Flip Horizontally' }));
    expect(disabled(screen.getByRole('button', { name: 'Finish' }))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(dam.edits).toHaveLength(1));
    expect(dam.edits[0]).toEqual({
      path: '/content/dam/wknd/surf.jpg',
      operations: [
        { op: 'rotate', degrees: 90 },
        { op: 'flip', axis: 'horizontal' },
      ],
      areas: undefined,
    });
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ kind: 'details', path: '/content/dam/wknd/surf.jpg', properties: false }));
  });

  it('undoes and redoes; four turns save nothing', async () => {
    const { dam, navigate } = setup();
    await screen.findByRole('button', { name: 'Finish' });
    fireEvent.click(screen.getByRole('button', { name: 'Rotate Left' }));
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(disabled(screen.getByRole('button', { name: 'Finish' }))).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Redo' }));
    for (let i = 0; i < 3; i++) fireEvent.click(screen.getByRole('button', { name: 'Rotate Left' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(dam.edits).toHaveLength(0);
  });

  it('crops at the confirmed box', async () => {
    const { dam } = setup();
    fireEvent.click(await screen.findByRole('button', { name: 'Start Crop' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Square' }));
    const box = screen.getByRole('slider', { name: 'Crop selection' });
    fireEvent.keyDown(box, { key: 'ArrowLeft', shiftKey: true });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(dam.edits).toHaveLength(1));
    const [crop] = dam.edits[0].operations;
    expect(crop.op).toBe('crop');
    if (crop.op === 'crop') {
      // Square in pixels: 0.8 of the 500px height, on the 1000px-wide image, moved 10% left.
      expect(crop.height).toBeCloseTo(0.8);
      expect(crop.width).toBeCloseTo(0.4);
      expect(crop.x).toBeCloseTo(0.2);
    }
  });

  it('adds a linked map area', async () => {
    const { dam } = setup();
    fireEvent.click(await screen.findByRole('button', { name: 'Release Map' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add Rectangular Map' }));
    const bar = screen.getByRole('group', { name: 'Map area' });
    fireEvent.change(within(bar).getByRole('textbox', { name: 'Path' }), { target: { value: 'javascript:alert(1)' } });
    expect(disabled(within(bar).getByRole('button', { name: 'Confirm' }))).toBe(true);
    fireEvent.change(within(bar).getByRole('textbox', { name: 'Path' }), { target: { value: '/content/wknd/us/en' } });
    fireEvent.change(within(bar).getByRole('textbox', { name: 'Alt Text' }), { target: { value: 'Home' } });
    fireEvent.click(within(bar).getByRole('button', { name: 'Confirm' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(dam.edits).toHaveLength(1));
    expect(dam.edits[0].operations).toEqual([]);
    expect(dam.edits[0].areas).toEqual([{ shape: 'rect', coords: [0.2, 0.2, 0.8, 0.8], href: '/content/wknd/us/en', target: '', alt: 'Home' }]);
  });

  it('adds a polygonal map area and removes a corner of it', async () => {
    const { dam } = setup();
    fireEvent.click(await screen.findByRole('button', { name: 'Release Map' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add Polygonal Map' }));
    expect(screen.getAllByRole('button', { name: /^Corner \d of 5$/ })).toHaveLength(5);
    fireEvent.doubleClick(screen.getByRole('button', { name: 'Corner 2 of 5' }));
    expect(screen.getAllByRole('button', { name: /^Corner \d of 4$/ })).toHaveLength(4);
    const bar = screen.getByRole('group', { name: 'Map area' });
    fireEvent.change(within(bar).getByRole('textbox', { name: 'Path' }), { target: { value: '/content/wknd/us/en' } });
    fireEvent.click(within(bar).getByRole('button', { name: 'Confirm' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(dam.edits).toHaveLength(1));
    const [area] = dam.edits[0].areas ?? [];
    expect(area.shape).toBe('poly');
    expect(area.coords).toHaveLength(8);
    expect(area.href).toBe('/content/wknd/us/en');
  });
});
