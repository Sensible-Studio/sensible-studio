import {
  ActionButton,
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  DialogContainer,
  Divider,
  Heading,
  IllustratedMessage,
  ProgressCircle,
  Text,
  TextArea,
  TextField,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Close from '@spectrum-icons/workflow/Close';
import Delete from '@spectrum-icons/workflow/Delete';
import Download from '@spectrum-icons/workflow/Download';
import Info from '@spectrum-icons/workflow/Info';
import Remove from '@spectrum-icons/workflow/Remove';
import SmartCollection from '@spectrum-icons/workflow/Collection';
import { useCallback, useEffect, useState } from 'react';
import type { DamClient, DamCollection, DamItem, SearchQuery } from './api';
import { DownloadDialog } from './bulk';
import { fragmentEditorUrl } from './format';
import type { AssetsRoute } from './route';
import { CardView } from './views';
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { routeUrl } from './route';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

function describeQuery(q?: SearchQuery): string {
  if (!q) return '';
  const parts = [q.fulltext ? `“${q.fulltext}”` : '', ...(q.types ?? []), ...(q.tags ?? []), q.modified ? `modified in ${q.modified}` : '', q.size ?? '', q.orientation ?? '', ...Object.entries(q.meta ?? {}).map(([p, v]) => `${p}: ${v.join(' or ')}`), ...Object.entries(q.metaText ?? {}).map(([p, t]) => `${p} contains “${t}”`)];
  return parts.filter(Boolean).join(', ') || 'everything';
}

/** `/mnt/overlay/dam/gui/content/collections.html`: the collections the user can see. */
export function CollectionsPage({ client, navigate }: { client: DamClient; navigate(route: AssetsRoute): void }) {
  const [list, setList] = useState<DamCollection[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<{ kind: 'create' } | { kind: 'edit' | 'delete'; collection: DamCollection } | null>(null);

  const load = useCallback(async () => {
    try {
      setList(await client.collections());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client]);

  useScreenTitle('Collections', 'Collections | Sensible Studio Media');
  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="studio-assets__profiles">
      <ToolBar
        url={routeUrl({ kind: 'collections' })}
        primary={
          <Button variant="accent" onPress={() => setDialog({ kind: 'create' })}>
            <Add />
            Create
          </Button>
        }
      />
      {error ? (
        <ErrorState title="Collections could not be loaded">{error}</ErrorState>
      ) : list === null ? (
        <LoadingState label="Loading collections" />
      ) : list?.length === 0 ? (
        <EmptyState title="No collections">Create one here, or select files and use Add to Collection. A search can be saved as a smart collection.</EmptyState>
      ) : (
        <div className="studio-assets__cards studio-assets__collections" role="grid" aria-label="Collections">
          {list?.map((c) => (
            <div key={c.path} role="gridcell" aria-label={c.title} className="studio-assets__card studio-assets__card--collection">
              <button type="button" className="studio-assets__collection-open" onClick={() => navigate({ kind: 'collection', path: c.path })}>
                <div className="studio-assets__card-media">
                  {!c.smart && (c.previews ?? []).length > 0 ? (
                    <div className={`studio-assets__collage studio-assets__collage--${(c.previews ?? []).length}`} aria-hidden>
                      {(c.previews ?? []).map((p) => (
                        <img key={p} src={p} alt="" loading="lazy" draggable={false} />
                      ))}
                    </div>
                  ) : (
                    <div className="studio-assets__collage studio-assets__collage--empty" aria-hidden>
                      <SmartCollection size="XL" />
                    </div>
                  )}
                </div>
                <div className="studio-assets__card-body">
                  <div className="studio-assets__card-title">{c.title}</div>
                  <div className="studio-assets__card-type">{c.smart ? 'SMART COLLECTION' : `COLLECTION · ${plural(c.count ?? 0, 'item').toUpperCase()}`}</div>
                  <div className="studio-assets__card-meta">
                    <span>{c.public ? 'Public' : 'Private'}</span>
                    {c.owner && <span>{c.owner}</span>}
                  </div>
                  {c.smart && <div className="studio-assets__card-meta">{describeQuery(c.query)}</div>}
                </div>
              </button>
              <div className="studio-assets__collection-actions">
                <ActionButton isQuiet aria-label={`Properties of ${c.title}`} onPress={() => setDialog({ kind: 'edit', collection: c })}>
                  <Info size="S" />
                </ActionButton>
                <ActionButton isQuiet aria-label={`Delete ${c.title}`} onPress={() => setDialog({ kind: 'delete', collection: c })}>
                  <Delete size="S" />
                </ActionButton>
              </div>
            </div>
          ))}
        </div>
      )}
      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog?.kind === 'create' && (
          <CollectionDialog
            onSave={async (title, description, isPublic) => {
              const c = await client.createCollection(title, description, isPublic);
              void load();
              navigate({ kind: 'collection', path: c.path });
            }}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === 'edit' && (
          <CollectionDialog
            collection={dialog.collection}
            onSave={async (title, description, isPublic) => {
              await client.updateCollection(dialog.collection.path, { title, description, public: isPublic });
              void load();
            }}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === 'delete' && (
          <StudioConfirmDialog
            title="Delete collection"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteCollection(dialog.collection.path);
              } catch (e) {
                setError(e instanceof Error ? e.message : String(e));
              }
              void load();
            }}
            onCancel={() => setDialog(null)}
          >
            The collection <strong>{dialog.collection.title}</strong> goes; its assets stay where they are.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

/** Title, description, public/private: for creating and for the properties of a collection. */
export function CollectionDialog({
  collection,
  heading,
  onSave,
  close,
}: {
  collection?: DamCollection;
  heading?: string;
  onSave(title: string, description: string, isPublic: boolean): Promise<void>;
  close(): void;
}) {
  const [title, setTitle] = useState(collection?.title ?? '');
  const [description, setDescription] = useState(collection?.description ?? '');
  const [isPublic, setPublic] = useState(collection?.public ?? false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <StudioDialog size="S">
      <Heading>{heading ?? (collection ? 'Collection Properties' : 'Create Collection')}</Heading>
      <Divider />
      <Content>
        <TextField label="Title" value={title} onChange={setTitle} width="100%" autoFocus isRequired />
        <TextArea label="Description" value={description} onChange={setDescription} width="100%" />
        <Checkbox isSelected={isPublic} onChange={setPublic}>
          Public collection
        </Checkbox>
        <Text UNSAFE_className="studio-assets__muted">{isPublic ? 'Everyone who can see the files sees it.' : 'Only you see it.'}</Text>
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={busy || !title.trim()}
          onPress={async () => {
            setBusy(true);
            setError(null);
            try {
              await onSave(title.trim(), description, isPublic);
              close();
            } catch (e) {
              setError(e instanceof Error ? e.message : String(e));
            } finally {
              setBusy(false);
            }
          }}
        >
          {collection ? 'Save' : 'Create'}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/** One collection: its items in cards, with Remove from Collection and Download. */
export function CollectionPage({ client, path, navigate }: { client: DamClient; path: string; navigate(route: AssetsRoute): void }) {
  const [data, setData] = useState<{ collection: DamCollection; total: number; items: DamItem[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [downloading, setDownloading] = useState<DamItem[] | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await client.collection(path));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client, path]);

  useEffect(() => {
    void load();
  }, [load]);
  useScreenTitle(data ? `Collection · ${data.collection.title}` : null, data ? `${data.collection.title} | Sensible Studio Collections` : null);
  useEffect(() => {
  }, [data]);

  const toggle = (p: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });
  const open = (item: DamItem) => {
    if (item.type === 'folder') navigate({ kind: 'browse', path: item.path });
    else if (item.contentFragment) window.location.assign(fragmentEditorUrl(item.path));
    else navigate({ kind: 'details', path: item.path, properties: false });
  };
  const items = data?.items ?? [];
  const chosen = items.filter((i) => selected.has(i.path));
  const c = data?.collection;

  return (
    <div className="studio-assets__browser">
      {selected.size > 0 && (
        <div className="studio-assets__selectionbar" role="toolbar" aria-label="Selection actions">
          <ActionButton isQuiet onPress={() => setDownloading(chosen)}>
            <Download />
            <Text>Download</Text>
          </ActionButton>
          {c && !c.smart && (
            <ActionButton
              isQuiet
              onPress={async () => {
                try {
                  const n = await client.removeFromCollection(path, chosen.map((i) => i.path));
                  setSelected(new Set());
                  setNotice(`${plural(n, 'item')} removed from the collection`);
                  void load();
                } catch (e) {
                  setNotice(e instanceof Error ? e.message : String(e));
                }
              }}
            >
              <Remove />
              <Text>Remove from Collection</Text>
            </ActionButton>
          )}
          <span className="studio-assets__spacer" />
          <span className="studio-assets__selcount">{selected.size} selected</span>
          <ActionButton isQuiet aria-label="Clear selection" onPress={() => setSelected(new Set())}>
            <Close />
          </ActionButton>
        </div>
      )}
      <div className="studio-assets__actionbar">
        <div className="studio-assets__actionbar-left">
          <ActionButton isQuiet onPress={() => navigate({ kind: 'collections' })}>
            <Text>Collections</Text>
          </ActionButton>
          <span aria-hidden>›</span>
          <strong className="studio-assets__collection-title">{c?.title ?? ''}</strong>
        </div>
        <div className="studio-assets__actionbar-right">
          {c && (
            <span className="studio-assets__count">
              {c.smart ? `Smart · ${describeQuery(c.query)} · ` : ''}
              {plural(data?.total ?? 0, 'item')}
            </span>
          )}
        </div>
      </div>
      <div className="studio-assets__body">
        <main className="studio-assets__content">
          {error ? (
            <IllustratedMessage>
              <Heading>Cannot open the collection</Heading>
              <Content>{error}</Content>
            </IllustratedMessage>
          ) : !data ? (
            <div className="studio-assets__loading">
              <ProgressCircle isIndeterminate aria-label="Loading" />
            </div>
          ) : items.length === 0 ? (
            <IllustratedMessage>
              <Heading>{c?.smart ? 'Nothing matches the search now' : 'This collection is empty'}</Heading>
              <Content>{c?.smart ? 'Files show up here when they match.' : 'Select files in a folder and use Add to Collection.'}</Content>
            </IllustratedMessage>
          ) : (
            <CardView items={items} selected={selected} onToggle={toggle} onSelectOnly={(p) => setSelected(new Set(p))} onOpen={open} onProperties={open} />
          )}
          {c?.description && <p className="studio-assets__muted studio-assets__collection-desc">{c.description}</p>}
        </main>
      </div>
      {notice && (
        <div className="studio-assets__toast" role="status">
          <span>{notice}</span>
          <ActionButton isQuiet aria-label="Dismiss" onPress={() => setNotice(null)}>
            <Close size="S" />
          </ActionButton>
        </div>
      )}
      <DialogContainer onDismiss={() => setDownloading(null)}>
        {downloading && <DownloadDialog items={downloading} close={() => setDownloading(null)} />}
      </DialogContainer>
    </div>
  );
}

/** Add to Collection: pick existing (plain) collections, or create a new one with the items. */
export function AddToCollectionDialog({ client, paths, onDone, close }: { client: DamClient; paths: string[]; onDone(message: string): void; close(): void }) {
  const [list, setList] = useState<DamCollection[] | null>(null);
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    client.collections().then(
      (l) => setList(l.filter((c) => !c.smart)),
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);

  if (creating) {
    return (
      <CollectionDialog
        heading="Create Collection"
        onSave={async (title, description, isPublic) => {
          const c = await client.createCollection(title, description, isPublic);
          await client.addToCollection(c.path, paths);
          onDone(`${plural(paths.length, 'item')} added to ${c.title}`);
        }}
        close={close}
      />
    );
  }
  return (
    <StudioDialog size="S">
      <Heading>Add to Collection</Heading>
      <Divider />
      <Content>
        {list === null && !error && <ProgressCircle size="S" isIndeterminate aria-label="Loading" />}
        {list?.length === 0 && <p className="studio-assets__muted">No collections yet.</p>}
        <div className="studio-assets__collection-picks">
          {list?.map((c) => (
            <Checkbox
              key={c.path}
              isSelected={chosen.has(c.path)}
              onChange={(on) =>
                setChosen((prev) => {
                  const next = new Set(prev);
                  if (on) next.add(c.path);
                  else next.delete(c.path);
                  return next;
                })
              }
            >
              {c.title} ({c.count ?? 0})
            </Checkbox>
          ))}
        </div>
        <ActionButton onPress={() => setCreating(true)}>
          <Add />
          <Text>New Collection</Text>
        </ActionButton>
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={busy || chosen.size === 0}
          onPress={async () => {
            setBusy(true);
            setError(null);
            try {
              for (const c of chosen) await client.addToCollection(c, paths);
              const names = (list ?? []).filter((c) => chosen.has(c.path)).map((c) => c.title);
              onDone(`${plural(paths.length, 'item')} added to ${names.join(', ')}`);
              close();
            } catch (e) {
              setError(e instanceof Error ? e.message : String(e));
            } finally {
              setBusy(false);
            }
          }}
        >
          Add
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
