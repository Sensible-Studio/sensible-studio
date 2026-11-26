import { DialogContainer, defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AnnotatedPicture, AnnotatedStage, CommentsSection, mentionAt, noteNumbers, notesAt, useComments, type Person } from './annotations';
import { markSvg, printHtml, PrintNotesDialog } from './printNotes';
import { mentionParts } from '../shell/notifications';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

// jsdom has no PointerEvent: a mouse event under that name carries what the code reads (button, position)
if (typeof globalThis.PointerEvent === 'undefined') {
  (globalThis as unknown as { PointerEvent: unknown }).PointerEvent = class extends MouseEvent {};
}

const PATH = '/content/dam/wknd/surf.jpg';

function Harness({ dam, annotating }: { dam: MemoryDam; annotating: boolean }) {
  const state = useComments(dam, PATH);
  return (
    <>
      <CommentsSection state={state} />
      <AnnotatedPicture src="/x.jpg" alt="Surf" state={state} annotating={annotating} />
    </>
  );
}

function show(dam: MemoryDam, annotating = false) {
  return render(
    <Provider theme={defaultTheme}>
      <Harness dam={dam} annotating={annotating} />
    </Provider>,
  );
}

describe('comments on a file', () => {
  it('are written with Enter, listed newest first and removed by their author', async () => {
    const dam = new MemoryDam();
    show(dam);
    expect(await screen.findByText('No comments yet.')).toBeTruthy();
    const box = screen.getByLabelText('Add a comment');
    fireEvent.change(box, { target: { value: 'Use the wider crop' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    expect(await screen.findByText('Use the wider crop')).toBeTruthy();
    fireEvent.change(box, { target: { value: 'And a darker sky' } });
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    await screen.findByText('And a darker sky');
    const items = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(items[0]).toContain('And a darker sky');
    fireEvent.click(screen.getByRole('button', { name: 'Remove comment: Use the wider crop' }));
    await waitFor(() => expect(screen.queryByText('Use the wider crop')).toBeNull());
  });

  it('can be notes drawn on the picture, numbered in both places', async () => {
    const dam = new MemoryDam();
    const { container } = show(dam, true);
    await screen.findByText('No comments yet.');
    const pic = container.querySelector('.studio-assets__annotated') as HTMLElement;
    pic.getBoundingClientRect = () => ({ left: 0, top: 0, width: 400, height: 200, right: 400, bottom: 200, x: 0, y: 0, toJSON: () => ({}) });
    fireEvent.pointerDown(pic, { clientX: 40, clientY: 20, button: 0, pointerId: 1 });
    fireEvent.pointerMove(pic, { clientX: 160, clientY: 120, pointerId: 1 });
    fireEvent.pointerUp(pic, { pointerId: 1 });
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'Logo too close to the edge' } });
    fireEvent.click(screen.getByRole('radio', { name: 'Colour #2d9d78' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add note' }));
    await screen.findByText('Logo too close to the edge');
    const saved = (await dam.comments(PATH)).comments[0];
    expect(saved.region).toEqual({ x: 0.1, y: 0.1, w: 0.3, h: 0.5 });
    expect(saved.color).toBe('#2d9d78');
    expect(container.querySelectorAll('.studio-assets__note:not(.is-draft)')).toHaveLength(1);
    expect(screen.getByText('Annotation')).toBeTruthy();
    expect(noteNumbers([saved]).get(saved.id)).toBe(1);
  });
});

describe('annotate, as AEM', () => {
  const rect = (el: HTMLElement) => {
    el.getBoundingClientRect = () => ({ left: 0, top: 0, width: 400, height: 200, right: 400, bottom: 200, x: 0, y: 0, toJSON: () => ({}) });
  };

  it('draws an arrow, an ellipse and a line by hand', async () => {
    const dam = new MemoryDam();
    const { container } = show(dam, true);
    await screen.findByText('No comments yet.');
    const pic = container.querySelector('.studio-assets__annotated') as HTMLElement;
    rect(pic);
    // an arrow from where the drag starts to where it ends
    fireEvent.click(screen.getByRole('radio', { name: 'Arrow' }));
    fireEvent.pointerDown(pic, { clientX: 320, clientY: 40, button: 0, pointerId: 1 });
    fireEvent.pointerMove(pic, { clientX: 160, clientY: 120, pointerId: 1 });
    fireEvent.pointerUp(pic, { pointerId: 1 });
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'This one' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add note' }));
    await screen.findByText('This one');
    // an ellipse
    fireEvent.click(screen.getByRole('radio', { name: 'Ellipse' }));
    fireEvent.pointerDown(pic, { clientX: 40, clientY: 20, button: 0, pointerId: 1 });
    fireEvent.pointerMove(pic, { clientX: 120, clientY: 60, pointerId: 1 });
    fireEvent.pointerUp(pic, { pointerId: 1 });
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'Round' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add note' }));
    await screen.findByText('Round');
    // a line by hand
    fireEvent.click(screen.getByRole('radio', { name: 'Draw' }));
    fireEvent.pointerDown(pic, { clientX: 40, clientY: 180, button: 0, pointerId: 1 });
    for (const [x, y] of [[80, 160], [120, 190], [160, 150], [200, 185]]) fireEvent.pointerMove(pic, { clientX: x, clientY: y, pointerId: 1 });
    fireEvent.pointerUp(pic, { pointerId: 1 });
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'Wavy' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add note' }));
    await screen.findByText('Wavy');

    const [arrow, ellipse, line] = (await dam.comments(PATH)).comments;
    expect(arrow.region).toEqual({ x: 0.4, y: 0.2, w: 0.4, h: 0.4, shape: 'arrow', points: [[0.8, 0.2], [0.4, 0.6]] });
    expect(ellipse.region).toEqual({ x: 0.1, y: 0.1, w: 0.2, h: 0.2, shape: 'ellipse' });
    expect(line.region?.shape).toBe('free');
    expect(line.region!.points!.length).toBeGreaterThanOrEqual(3);
    expect(line.region!.points![0]).toEqual([0.1, 0.9]);
    // the arrow and the line are drawn in the stage's own layer, the ellipse is a round mark
    expect(container.querySelectorAll('.studio-assets__marks polygon')).toHaveLength(1);
    expect(container.querySelectorAll('.studio-assets__marks path').length).toBeGreaterThanOrEqual(2);
    expect(container.querySelector('.studio-assets__note--ellipse')).toBeTruthy();
  });

  it('keeps the moment of a note on a video, and shows it around that moment', async () => {
    const dam = new MemoryDam();
    function Video({ time }: { time: number }) {
      const state = useComments(dam, PATH);
      return (
        <AnnotatedStage state={state} annotating time={time}>
          <div />
        </AnnotatedStage>
      );
    }
    const { container, rerender } = render(
      <Provider theme={defaultTheme}>
        <Video time={12.345} />
      </Provider>,
    );
    const stage = container.querySelector('.studio-assets__annotated') as HTMLElement;
    rect(stage);
    fireEvent.pointerDown(stage, { clientX: 40, clientY: 20, button: 0, pointerId: 1 });
    fireEvent.pointerMove(stage, { clientX: 160, clientY: 120, pointerId: 1 });
    fireEvent.pointerUp(stage, { pointerId: 1 });
    expect(screen.getByText('at 0:12')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'Blurry frame' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add note' }));
    await waitFor(() => expect(container.querySelectorAll('.studio-assets__note:not(.is-draft)')).toHaveLength(1));
    expect((await dam.comments(PATH)).comments[0].region?.time).toBe(12.35);
    // later in the video the note is not shown
    rerender(
      <Provider theme={defaultTheme}>
        <Video time={20} />
      </Provider>,
    );
    expect(container.querySelectorAll('.studio-assets__note:not(.is-draft)')).toHaveLength(0);
    expect(notesAt([{ id: 'a', text: '', author: null, created: '', mine: true, region: { x: 0, y: 0, w: 1, h: 1 } }], 99)).toHaveLength(1);
  });

  it('names people with @, and they are set apart in the text', async () => {
    const dam = new MemoryDam();
    const people = async (q: string): Promise<Person[]> =>
      [{ id: 'anna', name: 'Anna Berg', email: 'anna@example.com' }, { id: 'bob', name: 'Bob Moss' }].filter((p) => p.id.startsWith(q) || p.name.toLowerCase().includes(q));
    function Comments() {
      const state = useComments(dam, PATH);
      return <CommentsSection state={state} people={people} />;
    }
    render(
      <Provider theme={defaultTheme}>
        <Comments />
      </Provider>,
    );
    await screen.findByText('No comments yet.');
    const box = screen.getByLabelText('Add a comment') as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: 'Please check @an', selectionStart: 16 } });
    const option = await screen.findByRole('option', { name: /Anna Berg/ });
    fireEvent.mouseDown(option);
    expect(box.value).toBe('Please check @anna ');
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }));
    const mention = await screen.findByText('@anna');
    expect(mention.className).toBe('studio-assets__mentioned');
    expect(mentionAt('hi @bo', 6)).toEqual({ start: 3, query: 'bo' });
    expect(mentionAt('mail a@b', 8)).toBeNull();
    expect(mentionParts('to @anna and @bob.')).toEqual([{ text: 'to ' }, { text: '@anna', mention: 'anna' }, { text: ' and ' }, { text: '@bob', mention: 'bob' }, { text: '.' }]);
  });

  it('prints the notes chosen, numbered on the picture and listed', () => {
    const comments = [
      { id: 'c1', text: 'Crop <here>', author: 'anna', created: '2026-11-24T10:00:00Z', mine: true, color: '#d7373f', region: { x: 0.1, y: 0.1, w: 0.2, h: 0.2 } },
      { id: 'c2', text: 'Arrow', author: 'bob', created: '2026-11-24T10:01:00Z', mine: false, color: '#1473e6', region: { x: 0.4, y: 0.2, w: 0.4, h: 0.4, shape: 'arrow' as const, points: [[0.8, 0.2], [0.4, 0.6]] as Array<[number, number]> } },
      { id: 'c3', text: 'Looks good', author: 'carl', created: '2026-11-24T10:02:00Z', mine: false },
    ];
    const html = printHtml({ title: 'Surf', path: PATH, src: 'http://x/surf.jpg', width: 800, height: 400, comments, placement: 'next-page' });
    expect(html).toContain('<rect x="80.0" y="40.0" width="160.0" height="80.0"');
    expect(html).toContain('<polygon');
    expect(html).toContain('Crop &lt;here&gt;');
    expect(html).toContain('class="layout next-page"');
    expect(html).toContain('Looks good');
    expect(markSvg({ x: 0.5, y: 0.5, w: 0.2, h: 0.2, shape: 'ellipse' }, '#000', 100, 100)).toContain('<ellipse cx="60.0" cy="60.0" rx="10.0" ry="10.0"');
  });

  it('lets the reader choose what to print', async () => {
    const printed: string[] = [];
    const comments = [
      { id: 'c1', text: 'First', author: 'anna', created: '2026-11-24T10:00:00Z', mine: true, region: { x: 0.1, y: 0.1, w: 0.2, h: 0.2 } },
      { id: 'c2', text: 'Second', author: 'anna', created: '2026-11-24T10:01:00Z', mine: true, region: { x: 0.5, y: 0.5, w: 0.2, h: 0.2 } },
    ];
    const OrigImage = globalThis.Image;
    (globalThis as { Image: unknown }).Image = class {
      naturalWidth = 400;
      naturalHeight = 200;
      onload: (() => void) | null = null;
      set src(_v: string) {
        window.setTimeout(() => this.onload?.(), 0);
      }
    };
    try {
      render(
        <Provider theme={defaultTheme}>
          <DialogContainer onDismiss={() => {}}>
            <PrintNotesDialog title="Surf" path={PATH} src="/surf.jpg" comments={comments} print={async (h) => void printed.push(h)} />
          </DialogContainer>
        </Provider>,
      );
      fireEvent.click(screen.getByRole('checkbox', { name: /2\. Second/ }));
      fireEvent.click(screen.getByRole('radio', { name: 'Below it' }));
      fireEvent.click(screen.getByRole('button', { name: 'Print' }));
      await waitFor(() => expect(printed).toHaveLength(1));
      expect(printed[0]).toContain('First');
      expect(printed[0]).not.toContain('Second');
      expect(printed[0]).toContain('class="layout below"');
    } finally {
      (globalThis as { Image: unknown }).Image = OrigImage;
    }
  });
});
