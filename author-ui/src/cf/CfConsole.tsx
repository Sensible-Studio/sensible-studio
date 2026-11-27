import {
  VOCAB } from '../shell/vocabulary';
import {
  ActionButton,
  Button,
  ButtonGroup,
  Content,
  DialogContainer,
  Divider,
  Flex,
  Form,
  Heading,
  Item,
  Picker,
  SearchField,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import GlobeRemove from '@spectrum-icons/workflow/GlobeRemove';
import PublishCheck from '@spectrum-icons/workflow/PublishCheck';
import { HttpReplicationClient } from '../replication/api';
import { PublishDialog } from '../replication/PublishDialog';
import Edit from '@spectrum-icons/workflow/Edit';
import Info from '@spectrum-icons/workflow/Info';
import Copy from '@spectrum-icons/workflow/Copy';
import Move from '@spectrum-icons/workflow/Move';
import Download from '@spectrum-icons/workflow/Download';
import Globe from '@spectrum-icons/workflow/Globe';
import { useCallback, useEffect, useMemo, useState, useRef, type MouseEvent } from 'react';
import { ContextMenu, useContextMenu, useRegisterActions, type Action } from '../shell/actions';
import type { StudioClient } from '../editor/api/StudioClient';
import { PathCrumbs } from '../shell/PathCrumbs';
import { ProductBar } from '../shell/ProductBar';
import { CardView, ColumnView, ListView, readView, storeView, ViewSwitch, type BrowserItem, type BrowserListing, type ViewMode } from '../sites/views';
import { cfConsoleUrl, cfEditorUrl, cfModelsUrl, type CfClient, type CfFolder, type CfFolderRef, type CfFragment, type CfModelSummary, type CfSummary } from './api';
import { slugify } from './fields';
import { TranslateDialog } from './translation';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { RecordCard, RecordModelIcon } from '../shell/recordIcons';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { SelectionBar } from '../shell/SelectionBar';
import { DamHttpClient, type DamClient } from '../assets/api';
import { CopyDialog, MoveDialog } from '../assets/dialogs';
import { TagsEditor } from './CfEditor';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: CfClient;
  initialPath: string;
  /** For the views' thumbnails (fragments have none yet); a stub when the console runs bare. */
  studio?: StudioClient;
  /** Copy and Move go through Media's API (records are files in the DAM). */
  dam?: DamClient;
}

const ALL = '__all__';
const ROOT = '/content/dam';
const VIEW_KEY = 'studio.cf.view';

/** The trail from the root, titled by the folders already listed (a plain name until a folder is loaded). */
function trailOf(path: string, listings: Map<string, BrowserListing>): Array<{ path: string; title: string }> {
  const parts = path.split('/').filter(Boolean);
  const out: Array<{ path: string; title: string }> = [];
  for (let i = 2; i <= parts.length; i++) {
    const p = `/${parts.slice(0, i).join('/')}`;
    out.push({ path: p, title: i === 2 ? 'Content Fragments' : listings.get(p)?.item.title ?? parts[i - 1] });
  }
  return out;
}

/** The views show fragments as items shaped like Sites' pages: a `fragment` whose "template" is its model. */
const folderItem = (f: CfFolderRef): BrowserItem => ({ path: f.path, name: f.name, title: f.title, type: 'folder', hasChildren: f.hasChildren ?? true });
const fragmentItem = (f: CfSummary): BrowserItem => ({
  path: f.path,
  name: f.name,
  title: f.title,
  type: 'fragment',
  hasChildren: false,
  templateTitle: f.modelTitle ?? undefined,
  lastModified: f.modified ?? undefined,
});
function listingOf(path: string, folder: CfFolder, listings: Map<string, BrowserListing>): BrowserListing {
  return {
    item: { path, name: path.slice(path.lastIndexOf('/') + 1), title: path === ROOT ? 'Content Fragments' : folder.title, type: 'folder', hasChildren: true },
    trail: trailOf(path, listings),
    children: [...folder.folders.map(folderItem), ...folder.fragments.map(fragmentItem)],
  };
}

const STUB_STUDIO = { assetUrl: (p: string) => p } as unknown as StudioClient;

/** The preview at the end of the columns: what the fragment is, and a way into it. */
function CfPreview({ fragment, onOpen }: { fragment: CfSummary; onOpen(): void }) {
  const rows: Array<[string, string | undefined]> = [
    ['Title', fragment.title],
    ['Name', fragment.name],
    ['Model', fragment.modelTitle ?? undefined],
    ['Modified', fragment.modified ? new Date(fragment.modified).toLocaleDateString(undefined, { dateStyle: 'medium' }) : undefined],
    ['Description', fragment.description || undefined],
    ['Path', fragment.path],
  ];
  return (
    <aside className="studio-preview" aria-label={`Preview of ${fragment.title}`}>
      <div className="studio-preview__thumb studio-preview__thumb--record">
        <RecordCard title={fragment.title} model={fragment.modelTitle} description={fragment.description} />
      </div>
      <dl className="studio-preview__meta">
        {rows
          .filter(([, v]) => v)
          .map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
      </dl>
      <Button variant="primary" onPress={onOpen} alignSelf="start">
        <Edit />
        <Text>Open</Text>
      </Button>
    </aside>
  );
}

/**
 * The Content Fragments console (`/cf.html/content/dam/...`): DAM folders and the fragments in them, search
 * across subfolders (by text and model), create and delete. A fragment opens in the fragment editor.
 */
export function CfConsole({ client, initialPath, studio = STUB_STUDIO, dam: damProp }: Props) {
  const dam = useMemo(() => damProp ?? new DamHttpClient(), [damProp]);
  const [path, setPath] = useState(initialPath);
  // In the shell the folder is the tab's place: Back and Forward change it, entering a folder pushes it.
  const tab = useTab();
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const go = useNavigate();
  const tabPath = tab?.place.kind === 'cf' ? tab.place.path : undefined;
  useEffect(() => {
    if (tabPath !== undefined && tabPath !== path) {
      setPath(tabPath);
      setSelected(new Set());
      setQuery('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabPath]);
  const [listings, setListings] = useState<Map<string, BrowserListing>>(new Map());
  /** The folders as the API gave them (the fragments' details), by path. */
  const [folders, setFolders] = useState<Map<string, CfFolder>>(new Map());
  const [failure, setFailure] = useState<string | null>(null);
  const [view, setViewState] = useState<ViewMode>(() => readView(VIEW_KEY));
  const setView = (v: ViewMode) => {
    setViewState(v);
    storeView(VIEW_KEY, v);
  };
  const [query, setQuery] = useState('');
  const [modelFilter, setModelFilter] = useState<string>(ALL);
  const [results, setResults] = useState<CfSummary[] | null>(null);
  const [models, setModels] = useState<CfModelSummary[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<null | 'create' | 'delete' | 'translate' | 'publish' | 'unpublish' | 'properties' | 'copy' | 'move'>(null);
  const replication = useMemo(() => new HttpReplicationClient(), []);
  const [user, setUser] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  const searching = query.trim() !== '' || modelFilter !== ALL;

  const folderTitle = listings.get(path)?.item.title ?? path.slice(path.lastIndexOf('/') + 1);
  useScreenTitle(path === ROOT ? VOCAB.records : `${VOCAB.records} · ${folderTitle}`, `${folderTitle} | ${VOCAB.product} ${VOCAB.records}`);
  useEffect(() => {
    client.models().then(setModels, () => setModels([]));
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  // the current folder, and the folders above it for the columns (cached until something changes)
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const folder = await client.folder(path);
        const above = trailOf(path, new Map()).filter((t) => t.path !== path);
        const others = await Promise.all(above.map((t) => (version === 0 && listings.has(t.path) ? null : client.folder(t.path).catch(() => null))));
        if (cancelled) return;
        setFolders((prev) => {
          const next = version > 0 ? new Map<string, CfFolder>() : new Map(prev);
          above.forEach((t, i) => {
            const o = others[i];
            if (o) next.set(t.path, o);
          });
          next.set(path, folder);
          return next;
        });
        setListings((prev) => {
          const next = version > 0 ? new Map<string, BrowserListing>() : new Map(prev);
          above.forEach((t, i) => {
            const o = others[i];
            if (o) next.set(t.path, listingOf(t.path, o, next));
          });
          next.set(path, listingOf(path, folder, next));
          // titles in the trails come from the folders above: refresh them now that all are known
          for (const [p, l] of next) next.set(p, { ...l, trail: trailOf(p, next) });
          return next;
        });
        setFailure(null);
      } catch (e) {
        if (!cancelled) setFailure(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, path, version]);

  useEffect(() => {
    if (!searching) {
      setResults(null);
      return;
    }
    let cancelled = false;
    const t = window.setTimeout(() => {
      client
        .search(path, query, modelFilter === ALL ? null : modelFilter, 0, 500)
        .then((r) => !cancelled && setResults(r.fragments))
        .catch(() => !cancelled && setResults([]));
    }, 200);
    return () => {
      cancelled = true;
      window.clearTimeout(t);
    };
  }, [client, path, query, modelFilter, searching, version]);

  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      const m = window.location.pathname.match(/^\/cf\.html(\/content\/dam.*)?$/);
      setPath(m?.[1] ? decodeURI(m[1]) : '/content/dam');
      setSelected(new Set());
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const navigate = useCallback(
    (p: string) => {
      if (p === path) return;
      if (tabRef.current) tabRef.current.setPlace({ kind: 'cf', path: p });
      else window.history.pushState(null, '', cfConsoleUrl(p));
      setPath(p);
      setSelected(new Set());
      setQuery('');
    },
    [path],
  );

  // the last folder stays on screen while the next loads (no spinner between folders)
  const loaded = listings.get(path) ?? null;
  const shownRef = useRef<BrowserListing | null>(null);
  if (loaded) shownRef.current = loaded;
  const current = loaded ?? shownRef.current;
  const trail = useMemo(() => trailOf(path, listings), [path, listings]);
  /** The fragments on screen, by path: the folder's, or the search results. */
  const fragments = useMemo(() => {
    const list = searching ? results ?? [] : folders.get(path)?.fragments ?? [];
    return new Map(list.map((f) => [f.path, f]));
  }, [searching, results, folders, path]);
  const items: BrowserItem[] = useMemo(() => (searching ? (results ?? []).map(fragmentItem) : current?.children ?? []), [searching, results, current]);
  const selectedFragments = [...selected].map((p) => fragments.get(p)).filter((f): f is CfSummary => !!f);
  const loading = searching ? results === null : current === null && !failure;
  const busy = !searching && loaded === null && current !== null;
  const toggle = (item: BrowserItem) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(item.path)) next.delete(item.path);
      else next.add(item.path);
      return next;
    });
  const open = (item: BrowserItem) => (item.type === 'folder' ? navigate(item.path) : go(cfEditorUrl(item.path)));

  /** A record as a JSON file (its fields in every variation, title, description, tags, model): what AEM's Download gives a developer. */
  const downloadRecords = async (sel: CfSummary[]) => {
    try {
      for (const f of sel) {
        const full = await client.fragment(f.path);
        const blob = new Blob([JSON.stringify(full, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `${f.name}.json`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };
  const asItems = (sel: CfSummary[]) => sel.map((f) => ({ type: 'asset' as const, path: f.path, name: f.name, title: f.title }));

  /** The actions for selected records: the bar, the right-click menu and ⌘K read this list. */
  const actionsFor = (sel: CfSummary[]): Action[] => {
    const out: Action[] = [];
    if (sel.length === 1) out.push({ id: 'open', label: 'Open', icon: <Edit />, run: () => go(cfEditorUrl(sel[0].path)) });
    // AEM's Properties, Copy, Move and Download of a content fragment
    if (sel.length === 1) out.push({ id: 'properties', label: 'Properties', icon: <Info />, run: () => setDialog('properties') });
    out.push({ id: 'download', label: 'Download', icon: <Download />, run: () => void downloadRecords(sel) });
    out.push({ id: 'copy', label: 'Copy', icon: <Copy />, run: () => setDialog('copy') });
    out.push({ id: 'move', label: 'Move', icon: <Move />, run: () => setDialog('move') });
    out.push({ id: 'publish', label: 'Publish', icon: <PublishCheck />, run: () => setDialog('publish') });
    out.push({ id: 'unpublish', label: 'Unpublish', icon: <GlobeRemove />, run: () => setDialog('unpublish') });
    out.push({ id: 'translate', label: 'Translate', icon: <Globe />, run: () => setDialog('translate') });
    out.push({ id: 'delete', label: 'Delete', icon: <Delete />, separator: true, run: () => setDialog('delete') });
    // not what the person may not do with these
    const no = new Set<string>();
    if (sel.some((f) => f.canEdit === false)) ['move', 'translate'].forEach((id) => no.add(id));
    if (sel.some((f) => f.canPublish === false)) ['publish', 'unpublish'].forEach((id) => no.add(id));
    if (sel.some((f) => f.canDelete === false)) ['move', 'delete'].forEach((id) => no.add(id));
    if (folders.get(path)?.canCreate === false) no.add('copy');
    return out.filter((x) => !no.has(x.id));
  };
  useRegisterActions(tab?.id, selectedFragments.length ? { subject: selectedFragments.length === 1 ? selectedFragments[0].title : `${selectedFragments.length} content fragments`, actions: actionsFor(selectedFragments) } : null);
  const ctx = useContextMenu();
  const onItemMenu = (item: BrowserItem, e: MouseEvent) => {
    const f = fragments.get(item.path);
    if (!f) return; // folders have no actions of their own yet
    const sel = selected.has(f.path) ? selectedFragments : [f];
    if (!selected.has(f.path)) setSelected(new Set([f.path]));
    ctx.open(e, actionsFor(sel));
  };
  const labels = { kind: 'Model', empty: searching ? 'No content fragments found. Try another search or model.' : 'No content fragments here. Create one, or open a subfolder.' };

  return (
    <div className="studio-cfc">
      {!tab && <ProductBar user={user} />}
      {selectedFragments.length > 0 ? (
        <SelectionBar count={selectedFragments.length} actions={actionsFor(selectedFragments)} leading={['open', 'properties', 'download', 'publish', 'copy', 'move']} onClear={() => setSelected(new Set())} />
      ) : (
      <ScreenFrame
        identity={<PathCrumbs crumbs={trail} onGo={navigate} />}
        tools={<Flex gap="size-100" alignItems="center" wrap>
          <SearchField aria-label="Search content fragments" placeholder="Search here" value={query} onChange={setQuery} width="size-2400" />
          <Picker
            aria-label="Model"
            width="size-2000"
            selectedKey={modelFilter}
            onSelectionChange={(k) => setModelFilter(String(k))}
            items={[{ path: ALL, title: 'All models' }, ...models]}
          >
            {(m) => <Item key={m.path}>{m.title}</Item>}
          </Picker>
          <ActionButton isQuiet onPress={() => go(cfModelsUrl())}>
            <RecordModelIcon />
            <Text>{VOCAB.models}</Text>
          </ActionButton>
          <ViewSwitch view={view} onChange={setView} />
        </Flex>}
        primary={<Button variant="accent" onPress={() => setDialog('create')} isDisabled={!models.some((m) => m.enabled) || folders.get(path)?.canCreate === false}><Add /><Text>Create content fragment</Text></Button>}
      />
      )}

      <div className={`studio-cfc__content${busy ? ' is-loading' : ''}`} aria-busy={busy}>
        {failure ? (
          <ErrorState title={`Could not list ${path}`}>{failure}</ErrorState>
        ) : loading ? (
          <LoadingState />
        ) : view === 'column' && !searching && current ? (
          <ColumnView
            client={studio}
            listings={listings}
            trail={trail}
            current={current}
            selected={[...selected]}
            onOpen={open}
            onToggle={toggle}
            onContextMenu={onItemMenu}
            labels={labels}
            renderPreview={(item) => {
              const f = fragments.get(item.path);
              return f ? <CfPreview fragment={f} onOpen={() => go(cfEditorUrl(f.path))} /> : null;
            }}
          />
        ) : view === 'card' ? (
          <CardView client={studio} items={items} selected={[...selected]} onOpen={open} onToggle={toggle} onContextMenu={onItemMenu} labels={labels} />
        ) : (
          <ListView client={studio} items={items} selected={[...selected]} onOpen={open} onToggle={toggle} onContextMenu={onItemMenu} labels={labels} />
        )}
      </div>

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog === 'translate' && (
          <TranslateDialog client={client} paths={selectedFragments.map((f) => f.path)} onDone={() => setVersion((v) => v + 1)} />
        )}
        {dialog === 'create' && (
          <CreateFragmentDialog
            folder={path}
            models={models.filter((m) => m.enabled)}
            onCreate={async (model, title, name, description) => {
              const created = await client.create(path, title, model, name, description);
              go(cfEditorUrl(created));
            }}
          />
        )}
        {(dialog === 'publish' || dialog === 'unpublish') && (
          <PublishDialog
            client={replication}
            paths={selectedFragments.map((f) => f.path)}
            title={selectedFragments.length === 1 ? selectedFragments[0].title : `${selectedFragments.length} content fragments`}
            action={dialog === 'publish' ? 'activate' : 'deactivate'}
            noun="Content Fragment"
            onDone={() => {
              setDialog(null);
              setSelected(new Set());
              setVersion((v) => v + 1);
            }}
          />
        )}
        {dialog === 'properties' && selectedFragments.length === 1 && (
          <RecordPropertiesDialog
            client={client}
            path={selectedFragments[0].path}
            onSaved={() => setVersion((v) => v + 1)}
          />
        )}
        {dialog === 'copy' && (
          <CopyDialog
            client={dam}
            items={asItems(selectedFragments)}
            start={path}
            close={() => setDialog(null)}
            onDone={(paths) => {
              setDialog(null);
              setSelected(new Set());
              setVersion((v) => v + 1);
              ToastQueue.positive(`${paths.length} content fragment${paths.length > 1 ? 's' : ''} copied`, { timeout: 3000 });
            }}
          />
        )}
        {dialog === 'move' && (
          <MoveDialog
            client={dam}
            items={asItems(selectedFragments)}
            start={path}
            close={() => setDialog(null)}
            onDone={(paths, adjusted) => {
              setDialog(null);
              setSelected(new Set());
              setVersion((v) => v + 1);
              ToastQueue.positive(`${paths.length} content fragment${paths.length > 1 ? 's' : ''} moved${adjusted ? `, ${adjusted} reference${adjusted > 1 ? 's' : ''} adjusted` : ''}`, { timeout: 4000 });
            }}
          />
        )}
        {dialog === 'delete' && (
          <StudioConfirmDialog
            title="Delete content fragments"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              let failed = 0;
              for (const f of selectedFragments) {
                try {
                  await client.remove(f.path);
                } catch {
                  failed++;
                }
              }
              setSelected(new Set());
              setVersion((v) => v + 1);
              if (failed) ToastQueue.negative(`${failed} content fragment${failed > 1 ? 's' : ''} could not be deleted`, { timeout: 6000 });
              else ToastQueue.positive(`Deleted ${selectedFragments.length} content fragment${selectedFragments.length > 1 ? 's' : ''}`, { timeout: 2500 });
            }}
          >
            Delete {selectedFragments.length === 1 ? `“${selectedFragments[0].title}”` : `${selectedFragments.length} fragments`}? This cannot be undone.
            Pages that reference {selectedFragments.length === 1 ? 'it' : 'them'} will show nothing in {selectedFragments.length === 1 ? 'its' : 'their'} place.
            {' '}If published, {selectedFragments.length === 1 ? 'it is' : 'they are'} taken off the live site first.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      {ctx.menu && <ContextMenu at={ctx.menu.at} actions={ctx.menu.actions} onClose={ctx.close} />}
      <ToastContainer />
    </div>
  );
}

function CreateFragmentDialog({
  folder,
  models,
  onCreate,
}: {
  folder: string;
  models: CfModelSummary[];
  onCreate(model: string, title: string, name: string, description: string): Promise<void>;
}) {
  const dialog = useDialogContainer();
  const [model, setModel] = useState<string | null>(models[0]?.path ?? null);
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [nameTouched, setNameTouched] = useState(false);
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const effectiveName = nameTouched ? name : slugify(title);
  const valid = !!model && title.trim() !== '' && /^[a-z0-9][a-z0-9_\-.]*$/i.test(effectiveName);

  const create = async () => {
    if (!valid || !model) return;
    setBusy(true);
    setError(null);
    try {
      await onCreate(model, title.trim(), effectiveName, description.trim());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>Create content fragment</Heading>
      <Divider />
      <Content>
        <Form onSubmit={(e) => (e.preventDefault(), void create())} validationBehavior="native">
          <Picker label="Model" isRequired selectedKey={model} onSelectionChange={(k) => setModel(String(k))} items={models}>
            {(m) => (
              <Item key={m.path} textValue={m.title}>
                <Text>{m.title}</Text>
                {m.description ? <Text slot="description">{m.description}</Text> : null}
              </Item>
            )}
          </Picker>
          <TextField label="Title" isRequired autoFocus value={title} onChange={setTitle} />
          <TextField
            label="Name"
            description={`Created as ${folder}/${effectiveName || '…'}`}
            value={effectiveName}
            onChange={(v) => {
              setNameTouched(true);
              setName(v);
            }}
            validationState={effectiveName && !/^[a-z0-9][a-z0-9_\-.]*$/i.test(effectiveName) ? 'invalid' : undefined}
            errorMessage="Letters, digits, '-', '_' and '.' only"
          />
          <TextArea label="Description" value={description} onChange={setDescription} />
        </Form>
        {error && <Text UNSAFE_className="studio-cf-error">{error}</Text>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void create()} isDisabled={!valid} isPending={busy}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/** True below 640px: the console then shows only the title column. */

/** AEM's Properties of a content fragment: title, description and tags, saved without opening the editor. */
function RecordPropertiesDialog({ client, path, onSaved }: { client: CfClient; path: string; onSaved(): void }) {
  const dialog = useDialogContainer();
  const [fragment, setFragment] = useState<CfFragment | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [tags, setTags] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    client.fragment(path).then(
      (f) => {
        setFragment(f);
        setTitle(f.title);
        setDescription(f.description ?? '');
        setTags([...(f.tags ?? [])]);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client, path]);
  const save = async () => {
    if (!fragment || !title.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await client.save({ path, variation: 'master', values: {}, title: title.trim(), description, tags });
      onSaved();
      ToastQueue.positive('Properties saved', { timeout: 2500 });
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="M">
      <Heading>Properties</Heading>
      <Divider />
      <Content>
        {!fragment && !error ? (
          <LoadingState />
        ) : (
          <Form aria-label="Content Fragment properties" onSubmit={(e) => { e.preventDefault(); void save(); }}>
            <TextField label="Title" isRequired value={title} onChange={setTitle} width="100%" autoFocus />
            <TextArea label="Description" value={description} onChange={setDescription} width="100%" />
            <TagsEditor tags={tags} onChange={setTags} />
          </Form>
        )}
        {fragment && <p className="studio-cf__muted">{fragment.path}</p>}
        {error && <p className="studio-cf__error" role="alert">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={busy}>Cancel</Button>
        <Button variant="accent" onPress={() => void save()} isPending={busy} isDisabled={!fragment || !title.trim()}>Save</Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
