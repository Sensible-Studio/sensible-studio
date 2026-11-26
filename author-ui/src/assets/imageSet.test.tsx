import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AssetDetails, AssetItem, DamClient } from './api';
import { kindOf, typeLabel } from './format';
import { ImageSetDialog, ImageSetStage, PicturePickerDialog, moved } from './imageSet';

afterEach(cleanup);

// jsdom has no PointerEvent: a mouse event under that name carries what the code reads (button, position)
if (typeof globalThis.PointerEvent === 'undefined') {
  (globalThis as unknown as { PointerEvent: unknown }).PointerEvent = class extends MouseEvent {};
}

const picture = (name: string): AssetItem => ({ type: 'asset', path: `/content/dam/wknd/${name}.jpg`, name: `${name}.jpg`, title: name, mimeType: 'image/jpeg' }) as AssetItem;
const set = (members: AssetItem[], extra: Partial<AssetDetails> = {}): AssetDetails =>
  ({ type: 'asset', path: '/content/dam/wknd/trip', name: 'trip', title: 'Trip', mimeType: 'image/vnd.studio.set', imageSet: true, setMembers: members, metadata: {}, renditions: [], failed: [], breadcrumbs: [], ...extra }) as AssetDetails;

function fakeClient(over: Partial<DamClient> = {}) {
  return { setMembers: vi.fn(async () => undefined), createSet: vi.fn(async () => '/content/dam/wknd/bali'), asset: vi.fn(), ...over } as unknown as DamClient;
}
const wrap = (ui: JSX.Element) => render(<Provider theme={defaultTheme}>{ui}</Provider>);

describe('image sets', () => {
  it('is a kind of its own, whatever its type says', () => {
    expect(kindOf(set([]))).toBe('set');
    expect(typeLabel(set([]))).toBe('IMAGE SET');
    expect(moved(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c']);
    expect(moved(['a', 'b', 'c'], 2, 3)).toEqual(['a', 'b', 'c']);
  });

  it('moves a picture and takes one out, saving the new order each time', async () => {
    const client = fakeClient();
    const onChanged = vi.fn();
    wrap(<ImageSetStage client={client} set={set([picture('one'), picture('two'), picture('three')], { setMissing: 1 })} canEdit onOpen={vi.fn()} onChanged={onChanged} onError={vi.fn()} />);
    expect(screen.getByText(/3 items, shown together in this order\. 1 more is named/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Move one earlier' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Move three earlier' }));
    await waitFor(() => expect(client.setMembers).toHaveBeenCalledWith('/content/dam/wknd/trip', ['/content/dam/wknd/one.jpg', '/content/dam/wknd/three.jpg', '/content/dam/wknd/two.jpg'], undefined));
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Take one out of the set' }));
    await waitFor(() => expect(client.setMembers).toHaveBeenLastCalledWith('/content/dam/wknd/trip', ['/content/dam/wknd/three.jpg', '/content/dam/wknd/two.jpg'], undefined));
  });

  it('shows no tools to who may not change the set, and puts a picture back when saving fails', async () => {
    const { unmount } = wrap(<ImageSetStage client={fakeClient()} set={set([picture('one'), picture('two')])} canEdit={false} onOpen={vi.fn()} onChanged={vi.fn()} onError={vi.fn()} />);
    expect(screen.queryByRole('button', { name: 'Move two earlier' })).toBeNull();
    unmount();
    const onError = vi.fn();
    const client = fakeClient({ setMembers: vi.fn(async () => { throw new Error('checked out by somebody else'); }) });
    wrap(<ImageSetStage client={client} set={set([picture('one'), picture('two')])} canEdit onOpen={vi.fn()} onChanged={vi.fn()} onError={onError} />);
    fireEvent.click(screen.getByRole('button', { name: 'Take two out of the set' }));
    await waitFor(() => expect(onError).toHaveBeenCalledWith('checked out by somebody else'));
    expect(screen.getByRole('button', { name: 'Take two out of the set' })).toBeTruthy();
  });

  it('creates a set of the selected pictures', async () => {
    const client = fakeClient();
    const onDone = vi.fn();
    wrap(<ImageSetDialog client={client} folder="/content/dam/wknd" pictures={[picture('one'), picture('two')]} sets={[]} onDone={onDone} close={vi.fn()} />);
    expect((screen.getByRole('button', { name: 'Create' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByRole('textbox', { name: /Title/ }), { target: { value: 'Bali' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(client.createSet).toHaveBeenCalledWith('/content/dam/wknd', 'Bali', ['/content/dam/wknd/one.jpg', '/content/dam/wknd/two.jpg'], undefined));
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('Image set “Bali” created with 2 items', '/content/dam/wknd/bali'));
  });

  it('moves a picture by dragging it onto another, and a drag does not open it', async () => {
    const client = fakeClient();
    const onOpen = vi.fn();
    wrap(<ImageSetStage client={client} set={set([picture('one'), picture('two'), picture('three')])} canEdit onOpen={onOpen} onChanged={vi.fn()} onError={vi.fn()} />);
    const items = screen.getAllByRole('listitem');
    const under = vi.fn(() => items[2] as Element);
    (document as unknown as { elementFromPoint: unknown }).elementFromPoint = under;
    fireEvent.pointerDown(items[0], { button: 0, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(window, { clientX: 12, clientY: 10 }); // not yet a drag
    expect(items[0].className).not.toContain('is-dragged');
    fireEvent.pointerMove(window, { clientX: 300, clientY: 10 });
    expect(items[0].className).toContain('is-dragged');
    expect(items[2].className).toContain('is-after');
    fireEvent.pointerUp(window, { clientX: 300, clientY: 10 });
    // the browser follows the release with a click on what is under the pointer
    fireEvent.click(screen.getAllByRole('button', { name: /^Open / })[0]);
    expect(onOpen).not.toHaveBeenCalled();
    await waitFor(() => expect(client.setMembers).toHaveBeenCalledWith('/content/dam/wknd/trip', ['/content/dam/wknd/two.jpg', '/content/dam/wknd/three.jpg', '/content/dam/wknd/one.jpg'], undefined));
  });

  it('adds pictures chosen from Media, also from another folder', async () => {
    const folder = { type: 'folder', path: '/content/dam/wknd/more', name: 'more', title: 'More' };
    const list = vi.fn(async (path: string) => ({
      path,
      title: path,
      type: 'folder',
      breadcrumbs: [{ path: '/content/dam/wknd', title: 'WKND' }, ...(path.endsWith('/more') ? [{ path, title: 'More' }] : [])],
      total: 0,
      offset: 0,
      items: path.endsWith('/more') ? [{ ...picture('four'), path: '/content/dam/wknd/more/four.jpg' }] : [folder, picture('one'), picture('two'), { ...picture('notes'), mimeType: 'application/pdf' }],
    }));
    const onPick = vi.fn();
    wrap(<PicturePickerDialog client={fakeClient({ list } as unknown as Partial<DamClient>)} start="/content/dam/wknd" taken={['/content/dam/wknd/one.jpg']} onPick={onPick} close={vi.fn()} />);
    expect(((await screen.findByRole('checkbox', { name: 'one' })) as HTMLInputElement).disabled).toBe(true); // already in the set
    expect(screen.queryByRole('checkbox', { name: 'notes' })).toBeNull(); // not a picture
    fireEvent.click(screen.getByRole('checkbox', { name: 'two' }));
    fireEvent.click(screen.getByRole('button', { name: /More/ }));
    fireEvent.click(await screen.findByRole('checkbox', { name: 'four' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add 2 pictures' }));
    expect(onPick.mock.calls[0][0].map((p: AssetItem) => p.path)).toEqual(['/content/dam/wknd/two.jpg', '/content/dam/wknd/more/four.jpg']);
  });

  it('says a spin set is one, and a set with a video cannot be shown as a spin', () => {
    expect(typeLabel(set([], { setKind: 'spin' }))).toBe('SPIN SET');
    const video = { ...picture('clip'), path: '/content/dam/wknd/clip.mp4', mimeType: 'video/mp4' } as AssetItem;
    wrap(<ImageSetStage client={fakeClient()} set={set([picture('one'), video])} canEdit onOpen={vi.fn()} onChanged={vi.fn()} onError={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Add pictures or videos/ })).toBeTruthy();
    expect(screen.getByText(/2 items, shown together in this order/)).toBeTruthy();
    cleanup();
    wrap(<ImageSetStage client={fakeClient()} set={set([picture('one'), picture('two')], { setKind: 'spin' })} canEdit onOpen={vi.fn()} onChanged={vi.fn()} onError={vi.fn()} />);
    expect(screen.getByText(/turn by dragging/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /^Add pictures$/ })).toBeTruthy();
  });
});
