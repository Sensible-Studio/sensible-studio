import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { Editable, EditableTree } from '../model/editables';
import type { Annotation } from './annotations';
import { AnnotationsLayer, type AnnotationMove } from './AnnotationsLayer';

const COMPONENT = '/content/p/jcr:content/root/title';
const NOTE: Annotation = { path: `${COMPONENT}/cq:annotations/1_1`, component: COMPONENT, text: 'Shorter?', color: '#3287d2', x: 10, y: 5 };

// jsdom has no PointerEvent: a MouseEvent carries the coordinates the layer reads
if (!(globalThis as { PointerEvent?: unknown }).PointerEvent) {
  (globalThis as unknown as { PointerEvent: typeof MouseEvent }).PointerEvent = MouseEvent;
}

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
});

let frame: HTMLIFrameElement | null = null;
afterEach(() => {
  cleanup();
  frame?.remove();
});

/** A content frame with one component, laid out at (20, 40), 300 × 80. */
function setUp(): { doc: Document; tree: EditableTree } {
  frame = document.createElement('iframe');
  document.body.appendChild(frame);
  const doc = frame.contentDocument!;
  const el = doc.createElement('div');
  doc.body.appendChild(el);
  el.getBoundingClientRect = () => ({ left: 20, top: 40, width: 300, height: 80, right: 320, bottom: 120, x: 20, y: 40, toJSON: () => ({}) });
  const editable = { path: COMPONENT, type: 'wknd/title', title: 'Title', el, parent: null, children: [], depth: 0, dialog: true, designDialog: false, container: false, placeholder: null, grid: null } as Editable;
  return { doc, tree: { roots: [editable], byPath: new Map([[COMPONENT, editable]]), all: [editable] } };
}

function Layer({ doc, tree, onChange }: { doc: Document; tree: EditableTree; onChange(a: Annotation, c: { text?: string }): Promise<void> }) {
  const [open, setOpen] = useState<string | null>(NOTE.path);
  return (
    <Provider theme={defaultTheme} colorScheme="light">
      <AnnotationsLayer doc={doc} tree={tree} annotations={[NOTE]} onChange={onChange} onDelete={async () => {}} open={open} onOpen={setOpen} />
    </Provider>
  );
}

describe('AnnotationsLayer', () => {
  it('takes the pointer only on its markers and notes (the page underneath stays editable)', () => {
    const { doc, tree } = setUp();
    const wheel = vi.fn();
    render(
      <div onWheel={wheel}>
        <Layer doc={doc} tree={tree} onChange={async () => {}} />
      </div>,
    );
    expect(screen.getByTestId('annotations').className).toBe('studio-annotate');
    // the wheel over a marker scrolls the page (the overlay underneath does it); inside the note it stays there
    fireEvent.wheel(screen.getByRole('button', { name: /Annotation 1/ }), { deltaY: 50 });
    expect(wheel).toHaveBeenCalledTimes(1);
    wheel.mockClear();
    fireEvent.wheel(screen.getByRole('textbox', { name: 'Annotation' }), { deltaY: 50 });
    expect(wheel).not.toHaveBeenCalled();
  });

  it('Done saves the note once and closes it', async () => {
    const { doc, tree } = setUp();
    const onChange = vi.fn(async () => {});
    render(<Layer doc={doc} tree={tree} onChange={onChange} />);
    const box = screen.getByRole('textbox', { name: 'Annotation' });
    fireEvent.change(box, { target: { value: 'Much shorter' } });
    fireEvent.blur(box);
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('textbox', { name: 'Annotation' })).toBeNull());
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith(NOTE, { text: 'Much shorter' });
    // the marker stays and opens the note again
    fireEvent.click(screen.getByRole('button', { name: /Annotation 1/ }));
    expect(screen.getByRole('textbox', { name: 'Annotation' })).toBeTruthy();
  });
});

describe('annotation markers', () => {
  it('show the first words of the note, with … when it goes on', async () => {
    const { excerpt } = await import('./AnnotationsLayer');
    expect(excerpt('Shorter?')).toEqual({ text: 'Shorter?', cut: false });
    expect(excerpt('Make the title shorter and punchier please')).toEqual({ text: 'Make the title shorter', cut: true });
    expect(excerpt('  ')).toEqual({ text: '', cut: false });
    expect(excerpt('Supercalifragilisticexpialidocious-and-then-some')).toEqual({ text: 'Supercalifragilisticexpialid', cut: true });
  });

  it('show the whole note on hover and open it on click', () => {
    const { doc, tree } = setUp();
    const long: Annotation = { ...NOTE, text: 'Make the title shorter and punchier please' };
    function Closed() {
      const [open, setOpen] = useState<string | null>(null);
      return (
        <Provider theme={defaultTheme} colorScheme="light">
          <AnnotationsLayer doc={doc} tree={tree} annotations={[long]} onChange={async () => {}} onDelete={async () => {}} open={open} onOpen={setOpen} />
        </Provider>
      );
    }
    render(<Closed />);
    const marker = screen.getByRole('button', { name: /Annotation 1/ });
    expect(marker.textContent).toBe('Make the title shorter…');
    fireEvent.mouseEnter(marker);
    expect(screen.getByRole('tooltip').textContent).toContain('Make the title shorter and punchier please');
    fireEvent.mouseLeave(marker);
    expect(screen.queryByRole('tooltip')).toBeNull();
    fireEvent.click(marker);
    expect(screen.getByRole('textbox', { name: 'Annotation' })).toBeTruthy();
  });
});

const OTHER = '/content/p/jcr:content/root/text';

/** Two components: the title at (20, 40) 300 × 80 and a text at (20, 200) 300 × 80. */
function twoComponents(): { doc: Document; tree: EditableTree } {
  const { doc, tree } = setUp();
  const el = doc.createElement('div');
  doc.body.appendChild(el);
  el.getBoundingClientRect = () => ({ left: 20, top: 200, width: 300, height: 80, right: 320, bottom: 280, x: 20, y: 200, toJSON: () => ({}) });
  const other = { ...tree.roots[0], path: OTHER, type: 'wknd/text', title: 'Text', el } as Editable;
  return { doc, tree: { roots: [...tree.roots, other], byPath: new Map([...tree.byPath, [OTHER, other]]), all: [...tree.all, other] } };
}

function Moving(props: {
  doc: Document;
  tree: EditableTree;
  annotations: Annotation[];
  onMove?(a: Annotation, to: AnnotationMove): Promise<void>;
  onAddArrow?(a: Annotation, points: { x: number; y: number }[]): Promise<void>;
  onDeleteArrow?(arrow: { path: string }): Promise<void>;
  onOpen?(path: string | null): void;
  open?: string | null;
}) {
  return (
    <Provider theme={defaultTheme} colorScheme="light">
      <AnnotationsLayer
        doc={props.doc}
        tree={props.tree}
        annotations={props.annotations}
        onChange={async () => {}}
        onDelete={async () => {}}
        onMove={props.onMove ?? (async () => {})}
        onAddArrow={props.onAddArrow ?? (async () => {})}
        onDeleteArrow={props.onDeleteArrow ?? (async () => {})}
        open={props.open ?? null}
        onOpen={props.onOpen ?? (() => {})}
      />
    </Provider>
  );
}

describe('moving annotations and drawing arrows', () => {
  const ARROW = { path: `${NOTE.path}/sketches/arrow_1`, points: [{ x: 20, y: 10 }, { x: 120, y: 10 }] };

  it('a marker dragged by its grip moves inside its component', async () => {
    const { doc, tree } = twoComponents();
    const onMove = vi.fn(async () => {});
    render(<Moving doc={doc} tree={tree} annotations={[NOTE]} onMove={onMove} />);
    // the marker is at (30, 45): the title's corner plus x 10, y 5
    const grip = screen.getByRole('button', { name: 'Move annotation' });
    fireEvent.pointerDown(grip, { clientX: 35, clientY: 50, button: 0, pointerId: 1 });
    fireEvent.pointerMove(grip, { clientX: 135, clientY: 60, pointerId: 1 });
    fireEvent.pointerUp(grip, { clientX: 135, clientY: 60, pointerId: 1 });
    await waitFor(() => expect(onMove).toHaveBeenCalledTimes(1));
    expect(onMove).toHaveBeenCalledWith(NOTE, { component: COMPONENT, x: 110, y: 15, arrows: [] });
  });

  it('dropped on another component, the note goes there and its arrows keep their tip', async () => {
    const { doc, tree } = twoComponents();
    const onMove = vi.fn(async () => {});
    render(<Moving doc={doc} tree={tree} annotations={[{ ...NOTE, arrows: [ARROW] }]} onMove={onMove} />);
    const grip = screen.getByRole('button', { name: 'Move annotation' });
    fireEvent.pointerDown(grip, { clientX: 35, clientY: 50, button: 0, pointerId: 1 });
    fireEvent.pointerUp(grip, { clientX: 55, clientY: 230, pointerId: 1 });
    await waitFor(() => expect(onMove).toHaveBeenCalledTimes(1));
    const [, to] = onMove.mock.calls[0] as unknown as [Annotation, AnnotationMove];
    expect(to).toMatchObject({ component: OTHER, x: 30, y: 25 });
    // the start followed the marker (moved by 20, 180), the tip stayed at (140, 50) in the page: (120, -150) in the text
    expect(to.arrows[0].points[0]).toEqual({ x: 20 + 20, y: 10 + 180 - 160 });
    expect(to.arrows[0].points[1]).toEqual({ x: 120, y: -150 });
  });

  it('a click on the grip is not a move', () => {
    const { doc, tree } = twoComponents();
    const onMove = vi.fn(async () => {});
    render(<Moving doc={doc} tree={tree} annotations={[NOTE]} onMove={onMove} />);
    const grip = screen.getByRole('button', { name: 'Move annotation' });
    fireEvent.pointerDown(grip, { clientX: 35, clientY: 50, button: 0, pointerId: 1 });
    fireEvent.pointerUp(grip, { clientX: 36, clientY: 51, pointerId: 1 });
    expect(onMove).not.toHaveBeenCalled();
  });

  it('draws an arrow from the marker, in the frame of its component', async () => {
    const { doc, tree } = twoComponents();
    const onAddArrow = vi.fn(async () => {});
    render(<Moving doc={doc} tree={tree} annotations={[NOTE]} onAddArrow={onAddArrow} />);
    const handle = screen.getByRole('button', { name: 'Draw an arrow' });
    fireEvent.pointerDown(handle, { clientX: 60, clientY: 50, button: 0, pointerId: 1 });
    fireEvent.pointerMove(handle, { clientX: 100, clientY: 20, pointerId: 1 });
    fireEvent.pointerMove(handle, { clientX: 160, clientY: 30, pointerId: 1 });
    fireEvent.pointerUp(handle, { clientX: 200, clientY: 150, pointerId: 1 });
    await waitFor(() => expect(onAddArrow).toHaveBeenCalledTimes(1));
    const [, points] = onAddArrow.mock.calls[0] as unknown as [Annotation, { x: number; y: number }[]];
    expect(points[0]).toEqual({ x: 40, y: 10 });
    expect(points[points.length - 1]).toEqual({ x: 180, y: 110 });
    // a slip is not an arrow
    onAddArrow.mockClear();
    fireEvent.pointerDown(handle, { clientX: 60, clientY: 50, button: 0, pointerId: 1 });
    fireEvent.pointerUp(handle, { clientX: 66, clientY: 52, pointerId: 1 });
    expect(onAddArrow).not.toHaveBeenCalled();
  });

  it('shows the arrows; a click on one offers to delete it', async () => {
    const { doc, tree } = twoComponents();
    const onDeleteArrow = vi.fn(async () => {});
    const { container } = render(<Moving doc={doc} tree={tree} annotations={[{ ...NOTE, arrows: [ARROW] }]} onDeleteArrow={onDeleteArrow} />);
    expect(container.querySelectorAll('.studio-annotate__stroke')).toHaveLength(1);
    expect(container.querySelector('.studio-annotate__head')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Delete arrow' })).toBeNull();
    fireEvent.pointerDown(container.querySelector('.studio-annotate__hit')!);
    fireEvent.click(screen.getByRole('button', { name: 'Delete arrow' }));
    await waitFor(() => expect(onDeleteArrow).toHaveBeenCalledWith(ARROW));
  });

  it('over the page in Edit mode, a press elsewhere closes the note', () => {
    const { doc, tree } = twoComponents();
    const onOpen = vi.fn();
    render(<Moving doc={doc} tree={tree} annotations={[NOTE]} open={NOTE.path} onOpen={onOpen} />);
    expect(screen.getByRole('textbox', { name: 'Annotation' })).toBeTruthy();
    fireEvent.pointerDown(screen.getByRole('textbox', { name: 'Annotation' }));
    expect(onOpen).not.toHaveBeenCalled();
    fireEvent.pointerDown(document.body);
    expect(onOpen).toHaveBeenCalledWith(null);
  });
});
