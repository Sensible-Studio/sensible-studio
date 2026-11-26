import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  Menu,
  MenuTrigger,
  Text,
  ToastContainer,
  ToastQueue,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Merge from '@spectrum-icons/workflow/Merge';
import Move from '@spectrum-icons/workflow/Move';
import SelectBox from '@spectrum-icons/workflow/SelectBox';
import GlobeRemove from '@spectrum-icons/workflow/GlobeRemove';
import PublishCheck from '@spectrum-icons/workflow/PublishCheck';
import { useCallback, useEffect, useMemo, useRef, useState, type Key, type MouseEvent, type ReactElement } from 'react';
import { ContextMenu, useContextMenu, useRegisterActions, type Action } from '../shell/actions';
import { SelectionBar } from '../shell/SelectionBar';
import type { StudioClient } from '../editor/api/StudioClient';
import { PathCrumbs } from '../shell/PathCrumbs';
import { ProductBar } from '../shell/ProductBar';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { CardView, ColumnView, ListView, ViewSwitch, readView, storeView, type BrowserItem, type BrowserListing, type ViewMode } from '../sites/views';
import { parentOf, TAG_ROOT, tagsPath, tagsUrl, type TagItem, type TagListing, type TagsClient } from './api';
import { CreateTagForm, EditTagForm, MergeTagForm, MoveTagForm } from './forms';
import { ReferenceList, TagIcon, TagPreview, useTagReferences } from './views';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { HttpReplicationClient } from '../replication/api';
import { PublishDialog } from '../replication/PublishDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

const VIEW_KEY = 'studio.tags.view';
const STUB_STUDIO = { assetUrl: (p: string) => p } as unknown as StudioClient;

/** A tag as the consoles' views show it: namespaces and the root are folders, tags are leaf items. */
type TagBrowserItem = BrowserItem & { tag: TagItem };

function tagItem(t: TagItem): TagBrowserItem {
  return {
    path: t.path,
    name: t.name,
    title: t.title,
    type: t.namespace || t.root ? 'folder' : 'fragment',
    hasChildren: t.hasChildren,
    templateTitle: t.tagID,
    description: t.description,
    lastModified: t.lastModified,
    lastModifiedBy: t.lastModifiedBy,
    tag: t,
  };
}

type Form =
  | { kind: 'create'; parent: string; namespace: boolean }
  | { kind: 'edit'; item: TagItem }
  | { kind: 'move'; item: TagItem }
  | { kind: 'merge'; item: TagItem };

interface Props {
  client: TagsClient;
  initialPath: string;
  /** For the views' thumbnails; a stub is enough — tags have none. */
  studio?: StudioClient;
}

/**
 * The Tags console (`/tags.html/content/cq:tags/...`), with the consoles' Column / Card / List views and
 * breadcrumb: namespaces and tags, with Create Tag / Create Namespace, and for the selection Edit (titles per
 * language), Move, Merge and Delete. Moving, merging and deleting update the pages and media that use the tags.
 */
export function TagsConsole({ client, initialPath, studio = STUB_STUDIO }: Props) {
  const tab = useTab();
  const [path, setPath] = useState(initialPath);
  const [listings, setListings] = useState<Map<string, TagListing>>(new Map());
  const [error, setError] = useState<string | null>(null);
  const [view, setViewState] = useState<ViewMode>(() => readView(VIEW_KEY));
  const [selected, setSelected] = useState<string[]>([]);
  const [form, setForm] = useState<Form | null>(null);
  const [deleting, setDeleting] = useState<TagItem[] | null>(null);
  const [publishing, setPublishing] = useState<{ action: 'activate' | 'deactivate'; items: TagItem[] } | null>(null);
  const replication = useMemo(() => new HttpReplicationClient(), []);
  const [user, setUser] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  // the last folder stays on screen while the next loads (no spinner between folders)
  const loaded = listings.get(path) ?? null;
  const shownRef = useRef<TagListing | null>(null);
  if (loaded) shownRef.current = loaded;
  const current = loaded ?? shownRef.current;
  const busy = loaded === null && current !== null;
  const trail = useMemo(() => (current?.trail ?? [{ path: TAG_ROOT, title: VOCAB.tags }]).map((t) => (t.path === TAG_ROOT ? { ...t, title: VOCAB.tags } : t)), [current]);
  useScreenTitle(current ? (path === TAG_ROOT ? VOCAB.tags : `${VOCAB.tags} · ${current.item.title}`) : null, current ? `${current.item.title} | ${VOCAB.product} ${VOCAB.tags}` : null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const listing = await client.children(path);
        const others = await Promise.all(listing.trail.filter((t) => t.path !== path).map((t) => client.children(t.path).catch(() => null)));
        if (cancelled) return;
        setListings((prev) => {
          const next = version > 0 ? new Map<string, TagListing>() : new Map(prev);
          next.set(path, listing);
          for (const o of others) if (o) next.set(o.item.path, o);
          return next;
        });
        setError(null);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [client, path, version]);

  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  const tabPath = tab?.place.kind === 'tags' ? tab.place.path : undefined;
  useEffect(() => {
    if (tabPath !== undefined && tabPath !== path) {
      setPath(tabPath);
      setSelected([]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabPath]);
  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      setPath(tagsPath(window.location.pathname) ?? TAG_ROOT);
      setSelected([]);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  const navigate = (p: string) => {
    if (p === path) return;
    if (tab) tab.setPlace({ kind: 'tags', path: p });
    else window.history.pushState(null, '', tagsUrl(p));
    setPath(p);
    setSelected([]);
  };

  const setView = (v: ViewMode) => {
    setViewState(v);
    storeView(VIEW_KEY, v);
  };

  // the tree the views read: every loaded level, its tags as items
  const browserListings = useMemo(() => {
    const m = new Map<string, BrowserListing<TagBrowserItem>>();
    for (const l of listings.values()) m.set(l.item.path, { item: tagItem(l.item), trail: l.trail, children: l.children.map(tagItem) });
    return m;
  }, [listings]);
  const browserCurrent = current ? browserListings.get(current.item.path) ?? { item: tagItem(current.item), trail: current.trail, children: current.children.map(tagItem) } : null;
  const items = browserCurrent?.children ?? [];
  const labels = { kind: 'Tag ID', folder: 'Namespace', empty: current?.item.root ? 'There are no tags yet. Create a namespace to start.' : 'No tags here yet.' };
  const renderIcon = (item: TagBrowserItem) => <TagIcon item={item.tag} />;
  const openItem = (item: TagBrowserItem) => (item.hasChildren || item.type === 'folder' ? navigate(item.path) : setForm({ kind: 'edit', item: item.tag }));
  const toggleItem = (item: TagBrowserItem) => toggle(item.tag);

  const itemsByPath = useMemo(() => {
    const m = new Map<string, TagItem>();
    for (const l of listings.values()) {
      m.set(l.item.path, l.item);
      for (const c of l.children) m.set(c.path, c);
    }
    return m;
  }, [listings]);

  const selection = selected.map((p) => itemsByPath.get(p)).filter((i): i is TagItem => !!i);
  const single = selection.length === 1 ? selection[0] : null;

  const toggle = (item: TagItem) => {
    const parent = parentOf(item.path);
    if (parent !== path) {
      // selecting in an outer column moves there, like AEM
      if (tab) tab.setPlace({ kind: 'tags', path: parent });
      else window.history.pushState(null, '', tagsUrl(parent));
      setPath(parent);
      setSelected([item.path]);
      return;
    }
    setSelected((s) => (s.includes(item.path) ? s.filter((p) => p !== item.path) : [...s, item.path]));
  };

  const closeForm = () => setForm(null);

  const remove = (items: TagItem[]) => {
    setDeleting(null);
    void (async () => {
      try {
        const result = await client.remove(items.map((i) => i.path));
        setSelected([]);
        ToastQueue.positive(
          `${items.length === 1 ? items[0].title : `${items.length} tags`} deleted${result.adjusted ? `, removed from ${result.adjusted} item${result.adjusted === 1 ? '' : 's'}` : ''}`,
          { timeout: 4000 },
        );
      } catch (e) {
        ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
      }
      refresh();
    })();
  };

  const onAction = (action: string, sel: TagItem[] = selection) => {
    const single = sel.length === 1 ? sel[0] : null;
    if (action === 'create' && single) setForm({ kind: 'create', parent: single.path, namespace: false });
    else if (action === 'edit' && single) setForm({ kind: 'edit', item: single });
    else if (action === 'move' && single) setForm({ kind: 'move', item: single });
    else if (action === 'merge' && single && !single.namespace) setForm({ kind: 'merge', item: single });
    else if (action === 'delete' && sel.length) setDeleting(sel);
    else if ((action === 'publish' || action === 'unpublish') && sel.length) setPublishing({ action: action === 'publish' ? 'activate' : 'deactivate', items: sel });
  };

  /** The actions for a selection: the bar, the right-click menu and ⌘K read this list. */
  const actionsFor = (sel: TagItem[]): Action[] => {
    const one = sel.length === 1 ? sel[0] : null;
    const a = (id: string, label: string, icon: ReactElement, extra: Partial<Action> = {}): Action => ({ id, label, icon, run: () => onAction(id, sel), ...extra });
    const out: Action[] = [];
    if (one) out.push(a('create', 'Create Tag', <Add />), a('edit', 'Edit', <Edit />, { shortcut: 'p' }), a('move', 'Move', <Move />, { shortcut: 'm' }));
    if (one && !one.namespace) out.push(a('merge', 'Merge', <Merge />));
    // AEM's Publish and Unpublish of tags, for one or many: a tag goes with the tags below it
    out.push(a('publish', 'Publish', <PublishCheck />), a('unpublish', 'Unpublish', <GlobeRemove />, { danger: true }));
    out.push(a('delete', 'Delete', <Delete />, { shortcut: '⌫', separator: true }));
    return out;
  };
  const actions = selection.length ? actionsFor(selection) : [];
  useRegisterActions(tab?.id, selection.length ? { subject: single ? single.title : `${selection.length} tags`, actions } : null);
  const ctx = useContextMenu();
  const onItemMenu = (item: TagBrowserItem, e: MouseEvent) => {
    const t = item.tag;
    const sel = selected.includes(t.path) ? selection : [t];
    if (!selected.includes(t.path)) setSelected([t.path]);
    ctx.open(e, actionsFor(sel));
  };

  // keyboard shortcuts as in the other consoles: p (edit), m (move), backspace (delete), escape
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (tab && !tab.active) return; // another tab is in front
      if (form || deleting || publishing) return;
      const t = ev.target as HTMLElement | null;
      if (t?.closest('input:not([type="checkbox"]), textarea, select, [contenteditable="true"], [role="dialog"], [role="menu"]')) return;
      if (ev.metaKey || ev.ctrlKey || ev.altKey) return;
      if (ev.key === 'Escape') setSelected([]);
      else if (!selection.length) return;
      else if (ev.key === 'p' || ev.key === 'm') {
        ev.preventDefault();
        onAction(ev.key === 'p' ? 'edit' : 'move');
      } else if (ev.key === 'Backspace' || ev.key === 'Delete') {
        ev.preventDefault();
        onAction('delete');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  // ------------------------------------------------------------------------------------------ forms

  if (form) {
    return (
      <div className="studio-sites studio-tags">
        {!tab && <ProductBar user={user} />}
        {form.kind === 'create' ? (
          <CreateTagForm
            client={client}
            parent={form.parent}
            namespace={form.namespace}
            onCancel={closeForm}
            onCreated={(item) => {
              refresh();
              const parent = parentOf(item.path);
              if (parent !== path) navigate(parent);
              setSelected([item.path]);
              setForm({ kind: 'edit', item });
              ToastQueue.positive(`${item.namespace ? 'Namespace' : 'Tag'} ${item.title} created`, { timeout: 3000 });
            }}
          />
        ) : form.kind === 'edit' ? (
          <EditTagForm
            client={client}
            path={form.item.path}
            onCancel={closeForm}
            onSaved={(t) => {
              setForm({ kind: 'edit', item: t });
              refresh();
              ToastQueue.positive(`${t.title} saved`, { timeout: 3000 });
            }}
          />
        ) : form.kind === 'move' ? (
          <MoveTagForm
            client={client}
            item={form.item}
            onCancel={closeForm}
            onDone={(r) => {
              const it = form.item;
              closeForm();
              setSelected([]);
              refresh();
              navigate(parentOf(r.path));
              setSelected([r.path]);
              ToastQueue.positive(`${it.title} moved${r.adjusted ? `, ${r.adjusted} item${r.adjusted === 1 ? '' : 's'} updated` : ''}`, { timeout: 4000 });
            }}
          />
        ) : (
          <MergeTagForm
            client={client}
            item={form.item}
            onCancel={closeForm}
            onDone={(r) => {
              const it = form.item;
              closeForm();
              setSelected([]);
              refresh();
              ToastQueue.positive(`${it.title} merged${r.adjusted ? `, ${r.adjusted} item${r.adjusted === 1 ? '' : 's'} updated` : ''}`, { timeout: 4000 });
            }}
          />
        )}
        <ToastContainer placement="bottom" />
      </div>
    );
  }

  // ------------------------------------------------------------------------------------------ console

  return (
    <div className="studio-sites studio-tags">
      {!tab && <ProductBar user={user} />}

      {selection.length > 0 ? (
        <SelectionBar count={selection.length} actions={actions} onClear={() => setSelected([])} />
      ) : (
        <ScreenFrame
          identity={<PathCrumbs crumbs={trail} onGo={navigate} />}
          actions={<>
            <ActionButton isQuiet onPress={() => setSelected((current?.children ?? []).map((c) => c.path))} isDisabled={!current?.children.length}>
              <SelectBox />
              <Text>Select All</Text>
            </ActionButton>
            <ViewSwitch view={view} onChange={setView} />
          </>}
          primary={<MenuTrigger>
              <Button variant="accent">Create</Button>
              <Menu onAction={(k: Key) => setForm({ kind: 'create', parent: path, namespace: k === 'namespace' })}>
                <Item key="tag" textValue="Create Tag">
                  <Add />
                  <Text>Create Tag</Text>
                </Item>
                <Item key="namespace" textValue="Create Namespace">
                  <Add />
                  <Text>Create Namespace</Text>
                </Item>
              </Menu>
            </MenuTrigger>}
        />
      )}

      <main className={`studio-sites__content${busy ? ' is-loading' : ''}`} aria-busy={busy}>
        {error ? (
          <ErrorState title={`Could not open ${path}`}>{error}</ErrorState>
        ) : !current || !browserCurrent ? (
          <LoadingState />
        ) : view === 'column' ? (
          current.children.length === 0 && current.item.root ? (
            <p className="studio-sites__empty">{labels.empty}</p>
          ) : (
            <ColumnView
              client={studio}
              listings={browserListings}
              trail={trail}
              current={browserCurrent}
              selected={selected}
              onOpen={openItem}
              onToggle={toggleItem}
              onContextMenu={onItemMenu}
              labels={labels}
              renderIcon={renderIcon}
              renderPreview={(item) => <TagPreview client={client} item={item.tag} />}
            />
          )
        ) : view === 'card' ? (
          <CardView client={studio} items={items} selected={selected} onOpen={openItem} onToggle={toggleItem} onContextMenu={onItemMenu} labels={labels} renderIcon={renderIcon} />
        ) : (
          <ListView client={studio} items={items} selected={selected} onOpen={openItem} onToggle={toggleItem} onContextMenu={onItemMenu} labels={labels} renderIcon={renderIcon} />
        )}
      </main>
      {ctx.menu && <ContextMenu at={ctx.menu.at} actions={ctx.menu.actions} onClose={ctx.close} />}

      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && <DeleteTagsDialog client={client} items={deleting} onDelete={() => remove(deleting)} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setPublishing(null)}>
        {publishing && (
          <PublishDialog
            client={replication}
            paths={publishing.items.map((t) => t.path)}
            title={publishing.items.length === 1 ? publishing.items[0].title : `${publishing.items.length} tags`}
            action={publishing.action}
            noun="Tag"
            uses={false}
            onDone={() => {
              setPublishing(null);
              setSelected([]);
            }}
          />
        )}
      </DialogContainer>

      <ToastContainer placement="bottom" />
    </div>
  );
}

/** Delete confirmation listing the pages and assets that use the tags; they lose the tags. */
function DeleteTagsDialog({ client, items, onDelete }: { client: TagsClient; items: TagItem[]; onDelete(): void }) {
  const { refs, error } = useTagReferences(
    client,
    items.map((i) => i.path),
  );
  const used = (refs?.length ?? 0) > 0;
  const withChildren = items.some((i) => i.hasChildren);
  return (
    <StudioConfirmDialog
      title={used ? 'Delete tags in use' : 'Delete'}
      variant="destructive"
      primaryActionLabel={used ? 'Force Delete' : 'Delete'}
      cancelLabel="Cancel"
      isPrimaryActionDisabled={!refs && !error}
      onPrimaryAction={onDelete}
    >
      {items.length === 1 ? (
        <>
          You are going to delete <strong>{items[0].title}</strong> ({items[0].tagID})
          {withChildren && ' and all the tags below it'}.
        </>
      ) : (
        <>
          You are going to delete {items.length} tags: <strong>{items.map((d) => d.title).join(', ')}</strong>
          {withChildren && ', with the tags below them'}.
        </>
      )}
      <div className="studio-delete__refs">
        {used && <Text>These pages and files use them and will lose the tags:</Text>}
        <ReferenceList refs={refs} error={error} empty="No page or file uses them." />
      </div>
    </StudioConfirmDialog>
  );
}
