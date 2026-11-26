import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { ActionButton, Button, ButtonGroup, Content, Dialog, DialogContainer, Divider, Heading, Picker, Item, TextField } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Folder from '@spectrum-icons/workflow/Folder';
import ChevronLeft from '@spectrum-icons/workflow/ChevronLeft';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Close from '@spectrum-icons/workflow/Close';
import type { AssetDetails, AssetItem, Crumb, DamClient, DamItem } from './api';
import { kindOf, nameOf } from './format';

/** `list` with the item at `from` moved to `to`. */
export function moved<T>(list: T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length || from === to) return list;
  const out = list.slice();
  const [item] = out.splice(from, 1);
  out.splice(to, 0, item);
  return out;
}

/**
 * The page of an image set: its pictures in their order. Each can be moved one place or taken
 * out (the picture itself stays in Media); a change is saved at once. Pictures are added from
 * the folder view: select them, then "Image Set".
 */
export function ImageSetStage({
  client,
  set,
  canEdit,
  onOpen,
  onChanged,
  onError,
}: {
  client: DamClient;
  set: AssetDetails;
  canEdit: boolean;
  onOpen(path: string): void;
  onChanged(): void;
  onError(message: string): void;
}) {
  const [pictures, setPictures] = useState<AssetItem[]>(set.setMembers ?? []);
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [kind, setKind] = useState<'gallery' | 'spin'>(set.setKind ?? 'gallery');
  const hasVideo = pictures.some((p) => kindOf(p) === 'video');
  /** while a picture is dragged: where it comes from and the place it would take */
  const [drag, setDrag] = useState<{ from: number; over: number } | null>(null);
  /** set by a drag that just ended, so the click that follows it does not open the picture */
  const dragged = useRef(false);

  const save = async (next: AssetItem[], shown: 'gallery' | 'spin' = kind) => {
    const before = pictures;
    const kindBefore = kind;
    setPictures(next);
    setKind(shown);
    setBusy(true);
    try {
      await client.setMembers(set.path, next.map((p) => p.path), shown === kindBefore ? undefined : shown);
      onChanged();
    } catch (e) {
      setPictures(before);
      setKind(kindBefore);
      onError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  // Dragging is followed with pointer events (mouse, pen and finger alike): a picture is picked up once
  // the pointer has moved a little, the place under the pointer is where it would go, and letting go
  // puts it there. The arrows do the same for the keyboard.
  const startDrag = (e: ReactPointerEvent<HTMLLIElement>, from: number) => {
    if (e.button > 0 || (e.target as HTMLElement).closest('.studio-assets__set-tools')) return; // the main button, or a finger
    const start = { x: e.clientX, y: e.clientY };
    let over = from;
    let active = false;
    const move = (ev: PointerEvent) => {
      if (!active && Math.hypot(ev.clientX - start.x, ev.clientY - start.y) < 6) return;
      active = true;
      ev.preventDefault();
      const under = typeof document.elementFromPoint === 'function' ? document.elementFromPoint(ev.clientX, ev.clientY) : null;
      const item = under?.closest('[data-set-index]');
      over = item ? Number(item.getAttribute('data-set-index')) : from;
      setDrag({ from, over });
    };
    const end = (ev: PointerEvent) => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
      setDrag(null);
      if (!active) return;
      dragged.current = true;
      window.setTimeout(() => (dragged.current = false), 0);
      if (ev.type === 'pointerup' && over !== from) void save(moved(pictures, from, over));
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  };

  return (
    <div className="studio-assets__set">
      <p className="studio-assets__muted">
        {pictures.length} {pictures.length === 1 ? 'item' : 'items'},{' '}
        {kind === 'spin' ? 'shown as one object that visitors turn by dragging: each is the next angle.' : 'shown together in this order.'}
        {set.setMissing ? ` ${set.setMissing} more ${set.setMissing === 1 ? 'is' : 'are'} named but no longer in Media, or not yours to see.` : ''}
        {canEdit && pictures.length > 1 ? ' Drag a picture to another place, or use the arrows.' : ''}
      </p>
      {canEdit && (
        <div className="studio-assets__set-bar">
          <ActionButton isDisabled={busy} onPress={() => setAdding(true)}>
            <Add />
            <span>{kind === 'spin' ? 'Add pictures' : 'Add pictures or videos'}</span>
          </ActionButton>
          <Picker
            aria-label="Shown as"
            selectedKey={kind}
            isDisabled={busy}
            disabledKeys={hasVideo ? ['spin'] : []}
            onSelectionChange={(k) => {
              if (k !== kind) void save(pictures, k as 'gallery' | 'spin');
            }}
          >
            <Item key="gallery">Shown as a gallery</Item>
            <Item key="spin">{hasVideo ? 'Shown as a spin (pictures only)' : 'Shown as a spin'}</Item>
          </Picker>
        </div>
      )}
      <ol className="studio-assets__set-list" aria-label="Pictures of the set">
        {pictures.map((p, i) => (
          <li
            key={p.path}
            className={`studio-assets__set-item${drag?.from === i ? ' is-dragged' : ''}${drag && drag.over === i && drag.from !== i ? (drag.from < i ? ' is-after' : ' is-before') : ''}`}
            data-set-index={i}
            onPointerDown={canEdit && !busy && pictures.length > 1 ? (e) => startDrag(e, i) : undefined}
          >
            {/* not a <button>: a picture inside one cannot be dragged in every browser */}
            <div
              className="studio-assets__set-open"
              role="button"
              tabIndex={0}
              aria-label={`Open ${p.title}`}
              title={`Open ${p.title}`}
              onClick={() => {
                if (!dragged.current) onOpen(p.path);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onOpen(p.path);
                }
              }}
            >
              {p.web || p.thumbnail ? <img src={p.thumbnail ?? p.web ?? undefined} alt="" loading="lazy" draggable={false} /> : <span>{nameOf(p.path)}</span>}
            </div>
            <div className="studio-assets__set-caption">
              <span className="studio-assets__set-index">{i + 1}</span>
              <span className="studio-assets__set-title" title={p.path}>
                {p.title}
              </span>
            </div>
            {canEdit && (
              <div className="studio-assets__set-tools">
                <ActionButton isQuiet isDisabled={busy || i === 0} aria-label={`Move ${p.title} earlier`} onPress={() => void save(moved(pictures, i, i - 1))}>
                  <ChevronLeft />
                </ActionButton>
                <ActionButton
                  isQuiet
                  isDisabled={busy || i === pictures.length - 1}
                  aria-label={`Move ${p.title} later`}
                  onPress={() => void save(moved(pictures, i, i + 1))}
                >
                  <ChevronRight />
                </ActionButton>
                <ActionButton
                  isQuiet
                  isDisabled={busy || pictures.length === 1}
                  aria-label={`Take ${p.title} out of the set`}
                  onPress={() => void save(pictures.filter((x) => x.path !== p.path))}
                >
                  <Close />
                </ActionButton>
              </div>
            )}
          </li>
        ))}
      </ol>
      <DialogContainer onDismiss={() => setAdding(false)}>
        {adding && (
          <PicturePickerDialog
            client={client}
            start={set.path.substring(0, set.path.lastIndexOf('/'))}
            taken={pictures.map((p) => p.path)}
            videos={kind !== 'spin'}
            onPick={(more) => void save([...pictures, ...more])}
            close={() => setAdding(false)}
          />
        )}
      </DialogContainer>
    </div>
  );
}

/**
 * Pictures chosen from Media for a set: folders are walked, pictures ticked (in any number of
 * folders); those already in the set are shown as taken.
 */
export function PicturePickerDialog({
  client,
  start,
  taken,
  videos = false,
  single,
  onPick,
  close,
}: {
  client: DamClient;
  start: string;
  taken: string[];
  /** also offer videos (a gallery holds them; a spin does not) */
  videos?: boolean;
  /** one picture only, with this title and button */
  single?: { title: string; action: string };
  onPick(pictures: AssetItem[]): void;
  close(): void;
}) {
  const [folder, setFolder] = useState(start);
  const [crumbs, setCrumbs] = useState<Crumb[]>([]);
  const [items, setItems] = useState<DamItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<AssetItem[]>([]);

  useEffect(() => {
    let live = true;
    setItems(null);
    setError(null);
    client.list(folder, { limit: 500 }).then(
      (l) => {
        if (!live) return;
        setCrumbs(l.breadcrumbs);
        setItems(l.items.filter((i) => i.type === 'folder' || kindOf(i) === 'image' || (videos && kindOf(i) === 'video')));
      },
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, folder, videos]);

  const toggle = (p: AssetItem) =>
    setChosen((c) => (c.some((x) => x.path === p.path) ? c.filter((x) => x.path !== p.path) : single ? [p] : [...c, p]));

  return (
    <Dialog size="L">
      <Heading>{single ? single.title : videos ? 'Add pictures or videos' : 'Add pictures'}</Heading>
      <Divider />
      <Content>
        <nav className="studio-assets__setpick-crumbs" aria-label="Folder">
          {crumbs.map((c, i) => (
            <span key={c.path}>
              {i > 0 && <span aria-hidden> › </span>}
              {c.path === folder ? <strong>{c.title}</strong> : (
                <button type="button" className="studio-assets__linkbtn" onClick={() => setFolder(c.path)}>
                  {c.title}
                </button>
              )}
            </span>
          ))}
        </nav>
        {error && (
          <p className="studio-assets__dialog-error" role="alert">
            {error}
          </p>
        )}
        {items === null && !error && <p className="studio-assets__muted">Loading…</p>}
        {items !== null && items.length === 0 && <p className="studio-assets__muted">No pictures or folders here.</p>}
        {items !== null && items.length > 0 && (
          <ul className="studio-assets__setpick-list" aria-label="Pictures and folders">
            {items.map((item) => {
              if (item.type === 'folder') {
                return (
                  <li key={item.path}>
                    <button type="button" className="studio-assets__setpick-folder" onClick={() => setFolder(item.path)}>
                      <Folder size="S" /> {item.title}
                    </button>
                  </li>
                );
              }
              const inSet = taken.includes(item.path);
              const on = chosen.some((x) => x.path === item.path);
              return (
                <li key={item.path}>
                  <label className={`studio-assets__setpick-picture${on ? ' is-on' : ''}${inSet ? ' is-taken' : ''}`} title={inSet ? 'Already in the set' : item.title}>
                    <input type="checkbox" checked={on || inSet} disabled={inSet} onChange={() => toggle(item)} aria-label={item.title} />
                    {item.thumbnail ? <img src={item.thumbnail} alt="" loading="lazy" /> : <span className="studio-assets__setpick-noimg" />}
                    <span className="studio-assets__setpick-title">{item.title}</span>
                  </label>
                </li>
              );
            })}
          </ul>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={chosen.length === 0}
          onPress={() => {
            onPick(chosen);
            close();
          }}
        >
          {single ? single.action : chosen.length === 0 ? 'Add' : videos ? `Add ${chosen.length}` : `Add ${chosen.length} ${chosen.length === 1 ? 'picture' : 'pictures'}`}
        </Button>
      </ButtonGroup>
    </Dialog>
  );
}

/**
 * "Image Set" for the selected pictures: a new set of them (in the order they are listed), or
 * their addition to a set of the same folder.
 */
export function ImageSetDialog({
  client,
  folder,
  pictures,
  sets,
  onDone,
  close,
}: {
  client: DamClient;
  folder: string;
  pictures: AssetItem[];
  /** the image sets of this folder, to add to */
  sets: AssetItem[];
  onDone(message: string, path: string): void;
  close(): void;
}) {
  const [target, setTarget] = useState('new');
  const [title, setTitle] = useState('');
  const [kind, setKind] = useState<'gallery' | 'spin'>('gallery');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const n = pictures.length;
  const hasVideo = pictures.some((p) => kindOf(p) === 'video');

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      if (target === 'new') {
        const path = await client.createSet(folder, title.trim(), pictures.map((p) => p.path), kind === 'spin' ? 'spin' : undefined);
        onDone(`${kind === 'spin' ? 'Spin set' : 'Image set'} “${title.trim()}” created with ${n} ${n === 1 ? 'item' : 'items'}`, path);
      } else {
        const existing = await client.asset(target);
        const have = (existing.setMembers ?? []).map((m) => m.path);
        const add = pictures.map((p) => p.path).filter((p) => !have.includes(p));
        if (add.length > 0) await client.setMembers(target, [...have, ...add]);
        onDone(add.length ? `${add.length} ${add.length === 1 ? 'picture' : 'pictures'} added to “${existing.title}”` : `They were already in “${existing.title}”`, target);
      }
      close();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <Dialog size="S">
      <Heading>Image Set</Heading>
      <Divider />
      <Content>
        <p className="studio-assets__muted">
          An image set keeps {n === 1 ? 'this file' : `these ${n} files`} together, in an order, as one file that a page can show as a gallery, or as an
          object visitors turn. The files stay where they are.
        </p>
        <form
          className="studio-assets__set-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (target !== 'new' || title.trim()) void submit();
          }}
        >
          {sets.length > 0 && (
            <Picker label="Put them in" selectedKey={target} onSelectionChange={(k) => setTarget(String(k))} width="100%">
              {[<Item key="new">A new image set</Item>, ...sets.map((s) => <Item key={s.path}>{s.title}</Item>)]}
            </Picker>
          )}
          {target === 'new' && <TextField label="Title" value={title} onChange={setTitle} autoFocus isRequired width="100%" />}
          {target === 'new' && (
            <Picker
              label="Shown as"
              selectedKey={kind}
              onSelectionChange={(k) => setKind(k as 'gallery' | 'spin')}
              disabledKeys={hasVideo ? ['spin'] : []}
              description={
                kind === 'spin'
                  ? 'Pictures of one thing from successive angles, in order: visitors turn it by dragging.'
                  : 'One picture or video at a time, picked by thumbnails.'
              }
              width="100%"
            >
              <Item key="gallery">A gallery</Item>
              <Item key="spin">{hasVideo ? 'A spin (pictures only)' : 'A spin'}</Item>
            </Picker>
          )}
        </form>
        {error && (
          <p className="studio-assets__dialog-error" role="alert">
            {error}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close}>
          Cancel
        </Button>
        <Button variant="accent" isDisabled={busy || (target === 'new' && !title.trim())} onPress={() => void submit()}>
          {target === 'new' ? 'Create' : 'Add'}
        </Button>
      </ButtonGroup>
    </Dialog>
  );
}
