import {
  ActionButton,
  Button,
  Checkbox,
  DialogContainer,
  ProgressCircle,
  Text,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Cancel from '@spectrum-icons/workflow/Cancel';
import Copy from '@spectrum-icons/workflow/Copy';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Folder from '@spectrum-icons/workflow/Folder';
import Globe from '@spectrum-icons/workflow/Globe';
import GlobeRemove from '@spectrum-icons/workflow/GlobeRemove';
import Info from '@spectrum-icons/workflow/Info';
import Multiple from '@spectrum-icons/workflow/Multiple';
import Paste from '@spectrum-icons/workflow/Paste';
import SelectBox from '@spectrum-icons/workflow/SelectBox';
import { useCallback, useEffect, useMemo, useState, type MouseEvent, type ReactElement, useRef } from 'react';
import { ContextMenu, useContextMenu, useRegisterActions, type Action } from '../shell/actions';
import { SelectionBar } from '../shell/SelectionBar';
import { relativeTime } from '../editor/shell/TopBar';
import type { ReplicationClient, ReplicationStatusInfo } from '../replication/api';
import { PathCrumbs } from '../shell/PathCrumbs';
import { ProductBar } from '../shell/ProductBar';
import { ColumnView, readView, storeView, ViewSwitch, type BrowserItem, type BrowserListing, type ViewMode } from '../sites/views';
import type { StudioClient } from '../editor/api/StudioClient';
import { useNavigate, useTab } from '../shell/ShellContext';
import {
  consoleUrl,
  templateEditorUrl,
  templatePropertiesUrl,
  wizardUrl,
  type TemplateConf,
  type TemplateStatus,
  type TemplatesClient,
  type TemplateSummary,
  type TemplateUsage,
} from './api';
import { TemplatePublishDialog } from './TemplatePublishDialog';
import { BulkTemplatePropertiesDialog } from './BulkTemplateProperties';
import './templates.scss';
import { TemplateIcon } from '../shell/pageIcons';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: TemplatesClient;
  /** Publish / Unpublish and the Published column; without it they are not offered */
  replication?: ReplicationClient;
  /** `/conf/<name>`, or null for the list of configurations */
  initialConf: string | null;
  /** how a template opens (the editor; replaced in tests) */
  go?(url: string): void;
  /** For the column view's thumbnails; a stub when the console runs bare. */
  studio?: StudioClient;
}

const VIEW_KEY = 'studio.templates.view';
const ROOT = '/conf';
const STUB_STUDIO = { assetUrl: (p: string) => p } as unknown as StudioClient;
type Pending =
  | { kind: 'enable' | 'disable' | 'delete' | 'unpublish'; templates: TemplateSummary[]; usage?: Array<TemplateUsage | null> }
  | { kind: 'publish' | 'properties'; templates: TemplateSummary[] }
  | null;

const STATUS_LABEL: Record<string, string> = { draft: 'Draft', enabled: 'Enabled', disabled: 'Disabled' };

/** The column view's items: the configurations as folders, the templates as items whose "template" is their type. */
const confItem = (c: TemplateConf): BrowserItem => ({ path: c.path, name: c.name, title: c.title, type: 'folder', hasChildren: c.templates > 0 });
const templateItem = (t: TemplateSummary): BrowserItem => ({
  path: t.path,
  name: t.name,
  title: t.title,
  type: 'page',
  hasChildren: false,
  templateTitle: t.templateTypeTitle ?? undefined,
  thumbnail: t.thumbnail ?? undefined,
  lastModified: t.lastModified ?? undefined,
});

/** The preview at the end of the columns: the template, its state, and a way into the editor. */
function TemplatePreview({ template, published, onOpen }: { template: TemplateSummary; published?: ReplicationStatusInfo; onOpen(): void }) {
  const rows: Array<[string, string | undefined]> = [
    ['Title', template.title],
    ['Name', template.name],
    ['Template type', template.templateTypeTitle ?? undefined],
    ['Status', STATUS_LABEL[template.status] ?? template.status],
    ['Published', published ? publishedLabel(published) : undefined],
    ['Modified', template.lastModified ? new Date(template.lastModified).toLocaleDateString(undefined, { dateStyle: 'medium' }) : undefined],
    ['Description', template.description || undefined],
    ['Path', template.path],
  ];
  return (
    <aside className="studio-preview" aria-label={`Preview of ${template.title}`}>
      <div className="studio-preview__thumb">{template.thumbnail ? <img src={template.thumbnail} alt="" /> : <TemplateIcon size="L" />}</div>
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
        <Text>Edit</Text>
      </Button>
    </aside>
  );
}

/**
 * Tools > General > Templates, as in AEM: the configurations under /conf, then the editable templates of one, with
 * Create (from a template type), Edit (the template editor), Properties, Enable / Disable, Publish / Unpublish,
 * Copy (then Paste) and Delete. Keyboard: e, p, ⌘C, ⌘V, Backspace, Escape.
 */
export function TemplatesConsole({ client, replication, initialConf, go: goProp, studio = STUB_STUDIO }: Props) {
  const [conf, setConf] = useState<string | null>(initialConf);
  // In the shell the configuration shown is the tab's place; Back and Forward change it.
  const tab = useTab();
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const navigateShell = useNavigate();
  const go = goProp ?? navigateShell;
  const tabConf = tab?.place.kind === 'templates' && tab.place.route.screen === 'console' ? tab.place.route.conf : undefined;
  useEffect(() => {
    if (tabConf !== undefined && tabConf !== conf) {
      setConf(tabConf);
      setSelected([]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabConf]);
  const [confs, setConfs] = useState<TemplateConf[] | null>(null);
  const [templates, setTemplates] = useState<TemplateSummary[] | null>(null);
  const [published, setPublished] = useState<Record<string, ReplicationStatusInfo>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [clipboard, setClipboard] = useState<TemplateSummary[]>([]);
  const [view, setView] = useState<ViewMode>(() => readView(VIEW_KEY));
  const [user, setUser] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending>(null);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  useEffect(() => {
    let live = true;
    setFailure(null);
    client.confs().then(
      (c) => live && setConfs(c),
      (e: unknown) => live && setFailure(message(e)),
    );
    return () => {
      live = false;
    };
  }, [client, version]);

  useEffect(() => {
    let live = true;
    if (!conf) {
      setTemplates(null);
      return;
    }
    client.templates(conf).then(
      (t) => {
        if (!live) return;
        setTemplates([...t].sort((a, b) => a.title.localeCompare(b.title)));
        if (replication && t.length) {
          replication.status(t.map((x) => x.path)).then(
            (s) => live && setPublished(Object.fromEntries(s.map((x) => [x.path, x]))),
            () => undefined,
          );
        }
      },
      (e: unknown) => live && setFailure(message(e)),
    );
    return () => {
      live = false;
    };
  }, [client, replication, conf, version]);

  const confTitle = conf ? (confs?.find((c) => c.path === conf)?.title ?? conf.split('/').pop()!) : null;

  useEffect(() => {
    if (tabRef.current) tabRef.current.setTitle(confTitle ? `Templates · ${confTitle}` : 'Templates');
    else document.title = `${confTitle ? `${confTitle} | ` : ''}Templates | Sensible Studio`;
  }, [confTitle]);

  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      const m = decodeURI(window.location.pathname).match(/templates\.html(\/conf\/.+?)\/?$/);
      setConf(m ? m[1] : null);
      setSelected([]);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const navigate = useCallback((c: string | null) => {
    if (tabRef.current) tabRef.current.setPlace({ kind: 'templates', route: { screen: 'console', conf: c } });
    else window.history.pushState(null, '', consoleUrl(c));
    setConf(c);
    setSelected([]);
  }, []);

  const changeView = (v: ViewMode) => {
    setView(v);
    storeView(VIEW_KEY, v);
  };

  const items = useMemo(() => templates ?? [], [templates]);
  /** The two levels of the column view: the configurations, then the templates of the open one. */
  const columnListings = useMemo(() => {
    const m = new Map<string, BrowserListing>();
    const root = { path: ROOT, title: 'Templates' };
    m.set(ROOT, { item: { path: ROOT, name: 'conf', title: 'Templates', type: 'folder', hasChildren: true }, trail: [root], children: (confs ?? []).map(confItem) });
    if (conf) {
      const c = confs?.find((x) => x.path === conf);
      m.set(conf, {
        item: { path: conf, name: conf.split('/').pop()!, title: c?.title ?? confTitle ?? conf, type: 'folder', hasChildren: true },
        trail: [root, { path: conf, title: c?.title ?? confTitle ?? conf }],
        children: items.map(templateItem),
      });
    }
    return m;
  }, [confs, conf, confTitle, items]);
  const selection = items.filter((t) => selected.includes(t.path));
  const single = selection.length === 1 ? selection[0] : null;
  const canPaste = !!conf && clipboard.length > 0 && clipboard.every((t) => t.path.startsWith(`${conf}/`));
  const isPublished = (t: TemplateSummary) => !!published[t.path]?.activated;

  const refresh = () => setVersion((v) => v + 1);

  const paste = async () => {
    if (!canPaste) return;
    try {
      const made: TemplateSummary[] = [];
      for (const t of clipboard) made.push(await client.copy(t.path));
      ToastQueue.positive(made.length === 1 ? `Pasted “${made[0].title}”` : `${made.length} templates pasted`, { timeout: 2500 });
      setSelected(made.map((t) => t.path));
      refresh();
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    }
  };

  const askDelete = async (list: TemplateSummary[]) => {
    setPending({ kind: 'delete', templates: list });
    const usage = await Promise.all(list.map((t) => client.usage(t.path).catch(() => null)));
    setPending((p) => (p?.kind === 'delete' ? { ...p, usage } : p));
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (tabRef.current && !tabRef.current.active) return;
      if (pending || (e.target instanceof HTMLElement && e.target.closest('input, textarea, [contenteditable="true"]'))) return;
      const mod = e.metaKey || e.ctrlKey;
      if (e.key === 'Escape') setSelected([]);
      else if (single && !mod && e.key === 'e') go(templateEditorUrl(single.path));
      else if (single && !mod && e.key === 'p') go(templatePropertiesUrl(single.path));
      else if (selection.length > 1 && !mod && e.key === 'p') setPending({ kind: 'properties', templates: selection });
      else if (mod && e.key === 'c' && selection.length) {
        setClipboard(selection);
        ToastQueue.info(selection.length === 1 ? `“${selection[0].title}” copied` : `${selection.length} templates copied`, { timeout: 2000 });
      } else if (mod && e.key === 'v' && canPaste) void paste();
      else if ((e.key === 'Backspace' || e.key === 'Delete') && selection.length) {
        e.preventDefault();
        void askDelete(selection);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const toggle = (t: TemplateSummary) => setSelected((s) => (s.includes(t.path) ? s.filter((p) => p !== t.path) : [...s, t.path]));

  const setStatus = async (status: TemplateStatus, list: TemplateSummary[]) => {
    setBusy(true);
    try {
      for (const t of list) await client.setStatus(t.path, status);
      ToastQueue.positive(`${list.length === 1 ? `“${list[0].title}”` : `${list.length} templates`} ${status === 'enabled' ? 'enabled' : 'disabled'}`, {
        timeout: 2500,
      });
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    } finally {
      setBusy(false);
      setPending(null);
      refresh();
    }
  };

  const unpublish = async (list: TemplateSummary[]) => {
    if (!replication) return;
    setBusy(true);
    try {
      await replication.deactivate(
        list.map((t) => t.path),
        { deep: true },
      );
      ToastQueue.positive(list.length === 1 ? `“${list[0].title}” unpublished` : `${list.length} templates unpublished`, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    } finally {
      setBusy(false);
      setPending(null);
      refresh();
    }
  };

  const remove = async (list: TemplateSummary[]) => {
    setBusy(true);
    const refused: string[] = [];
    for (const t of list) {
      try {
        await client.remove(t.path);
      } catch (e) {
        refused.push(message(e));
      }
    }
    setBusy(false);
    setPending(null);
    setSelected([]);
    if (refused.length) ToastQueue.negative(refused.join(' '), { timeout: 10000 });
    else ToastQueue.positive(list.length === 1 ? 'Template deleted' : `${list.length} templates deleted`, { timeout: 2500 });
    refresh();
  };

  const used = pending && 'usage' in pending ? (pending.usage?.reduce((n, u) => n + (u?.count ?? 0), 0) ?? 0) : 0;

  /** The actions for a selection: the bar, the right-click menu and ⌘K read this list. */
  const actionsFor = (sel: TemplateSummary[]): Action[] => {
    const one = sel.length === 1 ? sel[0] : null;
    const out: Action[] = [];
    if (one) {
      out.push({ id: 'edit', label: 'Edit', icon: <Edit />, shortcut: 'e', run: () => go(templateEditorUrl(one.path)) });
      out.push({ id: 'properties', label: 'Properties', icon: <Info />, shortcut: 'p', run: () => go(templatePropertiesUrl(one.path)) });
    }
    // several templates, as in AEM: Properties edits their titles and a description they can share
    if (sel.length > 1) out.push({ id: 'properties', label: 'Properties', icon: <Info />, shortcut: 'p', run: () => setPending({ kind: 'properties', templates: sel }) });
    if (sel.some((t) => t.status !== 'enabled')) out.push({ id: 'enable', label: 'Enable', icon: <Multiple />, run: () => setPending({ kind: 'enable', templates: sel.filter((t) => t.status !== 'enabled') }) });
    if (sel.some((t) => t.status === 'enabled')) out.push({ id: 'disable', label: 'Disable', icon: <Cancel />, run: () => setPending({ kind: 'disable', templates: sel.filter((t) => t.status === 'enabled') }) });
    if (replication) out.push({ id: 'publish', label: 'Publish', icon: <Globe />, run: () => setPending({ kind: 'publish', templates: sel }) });
    if (replication && sel.some(isPublished)) out.push({ id: 'unpublish', label: 'Unpublish', icon: <GlobeRemove />, run: () => setPending({ kind: 'unpublish', templates: sel.filter(isPublished) }) });
    out.push({
      id: 'copy',
      label: 'Copy',
      icon: <Copy />,
      shortcut: '⌘C',
      separator: true,
      run: () => {
        setClipboard(sel);
        ToastQueue.info(sel.length === 1 ? `“${sel[0].title}” copied` : `${sel.length} templates copied`, { timeout: 2000 });
      },
    });
    out.push({ id: 'delete', label: 'Delete', icon: <Delete />, shortcut: '⌫', run: () => void askDelete(sel) });
    return out;
  };
  const actions = selection.length ? actionsFor(selection) : [];
  useRegisterActions(tab?.id, selection.length ? { subject: single ? single.title : `${selection.length} templates`, actions } : null);
  const ctx = useContextMenu();
  const onItemMenu = (item: { path: string }, e: MouseEvent) => {
    const t = items.find((x) => x.path === item.path);
    if (!t) return;
    const sel = selected.includes(t.path) ? selection : [t];
    if (!selected.includes(t.path)) setSelected([t.path]);
    ctx.open(e, actionsFor(sel));
  };

  return (
    <div className="studio-sites studio-tpls">
      {!tab && <ProductBar user={user} />}

      {selection.length > 0 ? (
        <SelectionBar count={selection.length} actions={actions} onClear={() => setSelected([])} />
      ) : (
        <ScreenFrame
          identity={<PathCrumbs crumbs={[{ path: ROOT, title: 'Templates' }, ...(conf ? [{ path: conf, title: confTitle ?? conf }] : [])]} onGo={(p) => navigate(p === ROOT ? null : p)} />}
          actions={<>
            {canPaste && <BarButton icon={<Paste />} label="Paste" shortcut="⌘V" onPress={() => void paste()} />}
            {conf && (
              <ActionButton isQuiet onPress={() => setSelected(items.map((t) => t.path))} isDisabled={!items.length}>
                <SelectBox />
                <Text>Select All</Text>
              </ActionButton>
            )}
            <ViewSwitch view={view} onChange={changeView} />
          </>}
          primary={<Button variant="accent" isDisabled={!conf} onPress={() => conf && go(wizardUrl(conf))}>Create template</Button>}
        />
      )}

      <main className="studio-sites__content">
        {failure ? (
          <ErrorState title="Could not load the templates">{failure}</ErrorState>
        ) : !confs || (conf && !templates) ? (
          <LoadingState />
        ) : view === 'column' ? (
          <ColumnView
            client={studio}
            listings={columnListings}
            trail={columnListings.get(conf ?? ROOT)!.trail}
            current={columnListings.get(conf ?? ROOT)!}
            selected={selected}
            onOpen={(i) => (i.type === 'folder' ? navigate(i.path) : go(templateEditorUrl(i.path)))}
            onToggle={(i) => {
              const t = items.find((x) => x.path === i.path);
              if (t) toggle(t);
            }}
            onContextMenu={onItemMenu}
            labels={{ kind: 'Template type', empty: 'This configuration has no templates yet.' }}
            renderIcon={(i) => (i.type === 'folder' ? <Folder size="S" /> : <TemplateIcon size="S" />)}
            renderPreview={(i) => {
              const t = items.find((x) => x.path === i.path);
              return t ? <TemplatePreview template={t} published={replication ? published[t.path] : undefined} onOpen={() => go(templateEditorUrl(t.path))} /> : null;
            }}
          />
        ) : !conf ? (
          <ConfList confs={confs} view={view} onOpen={(c) => navigate(c.path)} />
        ) : view === 'list' ? (
          <TemplateList items={items} published={replication ? published : null} selected={selected} onOpen={(t) => go(templateEditorUrl(t.path))} onToggle={toggle} />
        ) : (
          <TemplateCards items={items} published={replication ? published : null} selected={selected} onOpen={(t) => go(templateEditorUrl(t.path))} onToggle={toggle} />
        )}
      </main>

      <DialogContainer onDismiss={() => !busy && setPending(null)}>
        {pending?.kind === 'properties' && (
          <BulkTemplatePropertiesDialog
            client={client}
            templates={pending.templates}
            close={() => setPending(null)}
            onDone={(n) => {
              ToastQueue.positive(`Properties of ${n} template${n === 1 ? '' : 's'} saved`, { timeout: 4000 });
              refresh();
            }}
          />
        )}
        {pending?.kind === 'publish' && replication && (
          <TemplatePublishDialog client={client} replication={replication} templates={pending.templates} onDone={refresh} />
        )}
        {pending?.kind === 'unpublish' && (
          <StudioConfirmDialog
            title="Unpublish"
            variant="destructive"
            primaryActionLabel="Unpublish"
            cancelLabel="Cancel"
            isPrimaryActionDisabled={busy}
            onPrimaryAction={() => void unpublish(pending.templates)}
          >
            {pending.templates.length === 1 ? `Unpublish “${pending.templates[0].title}”?` : `Unpublish ${pending.templates.length} templates?`} Pages on
            publish that use {pending.templates.length === 1 ? 'it' : 'them'} will no longer render their structure.
          </StudioConfirmDialog>
        )}
        {pending?.kind === 'enable' && (
          <StudioConfirmDialog
            title="Enable"
            variant="confirmation"
            primaryActionLabel="Enable"
            cancelLabel="Cancel"
            isPrimaryActionDisabled={busy}
            onPrimaryAction={() => void setStatus('enabled', pending.templates)}
          >
            {pending.templates.length === 1 ? `Enable “${pending.templates[0].title}”?` : `Enable ${pending.templates.length} templates?`} Authors can then
            create pages from {pending.templates.length === 1 ? 'it' : 'them'} where the site allows.
          </StudioConfirmDialog>
        )}
        {pending?.kind === 'disable' && (
          <StudioConfirmDialog
            title="Disable"
            variant="warning"
            primaryActionLabel="Disable"
            cancelLabel="Cancel"
            isPrimaryActionDisabled={busy}
            onPrimaryAction={() => void setStatus('disabled', pending.templates)}
          >
            {pending.templates.length === 1 ? `Disable “${pending.templates[0].title}”?` : `Disable ${pending.templates.length} templates?`} No new pages
            can be created from {pending.templates.length === 1 ? 'it' : 'them'}; existing pages keep their structure.
          </StudioConfirmDialog>
        )}
        {pending?.kind === 'delete' && (
          <StudioConfirmDialog
            title="Delete"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            isPrimaryActionDisabled={busy || !pending.usage || used > 0}
            onPrimaryAction={() => void remove(pending.templates)}
          >
            {!pending.usage ? (
              <ProgressCircle size="S" isIndeterminate aria-label="Checking which pages use the templates" />
            ) : used > 0 ? (
              <>
                {pending.templates.length === 1 ? `“${pending.templates[0].title}” is` : 'These templates are'} used by {used} page{used === 1 ? '' : 's'} and
                cannot be deleted. Disable {pending.templates.length === 1 ? 'it' : 'them'} instead.
                <ul className="studio-tpls__usage">
                  {pending.usage
                    .flatMap((u) => u?.pages ?? [])
                    .slice(0, 8)
                    .map((p) => (
                      <li key={p}>{p}</li>
                    ))}
                </ul>
              </>
            ) : (
              <>
                {pending.templates.length === 1 ? `Delete “${pending.templates[0].title}”?` : `Delete ${pending.templates.length} templates?`} No page uses{' '}
                {pending.templates.length === 1 ? 'it' : 'them'}. This cannot be undone.
              </>
            )}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      {ctx.menu && <ContextMenu at={ctx.menu.at} actions={ctx.menu.actions} onClose={ctx.close} />}
      <ToastContainer />
    </div>
  );
}

// ------------------------------------------------------------------------------------------ configurations

function ConfList({ confs, view, onOpen }: { confs: TemplateConf[]; view: ViewMode; onOpen(c: TemplateConf): void }) {
  if (!confs.length) return <p className="studio-sites__empty">There is no configuration under /conf yet.</p>;
  if (view === 'list') {
    return (
      <div className="studio-listview">
        <table className="studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Path</th>
              <th>Templates</th>
            </tr>
          </thead>
          <tbody>
            {confs.map((c) => (
              <tr key={c.path}>
                <td>
                  <button type="button" className="studio-listview__title" onClick={() => onOpen(c)}>
                    <span className="studio-listview__thumb">
                      <Folder size="S" />
                    </span>
                    {c.title}
                  </button>
                </td>
                <td className="studio-listview__muted">{c.path}</td>
                <td className="studio-listview__muted">{c.templates}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  return (
    <ul className="studio-cards">
      {confs.map((c) => (
        <li key={c.path} className="studio-card">
          <button type="button" className="studio-card__open" onClick={() => onOpen(c)} title={c.path}>
            <span className="studio-card__thumb studio-tpls__folder">
              <Folder size="XL" />
            </span>
            <span className="studio-card__body">
              <span className="studio-card__type">Folder</span>
              <span className="studio-card__title">{c.title}</span>
              <span className="studio-card__meta">
                {c.templates} template{c.templates === 1 ? '' : 's'}
              </span>
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

// ------------------------------------------------------------------------------------------ templates

interface ItemsProps {
  items: TemplateSummary[];
  /** replication status by path; null: no Published column */
  published: Record<string, ReplicationStatusInfo> | null;
  selected: string[];
  onOpen(t: TemplateSummary): void;
  onToggle(t: TemplateSummary): void;
}

/** AEM's Published column: "Not published", or when (and whether it changed since). */
export function publishedLabel(s: ReplicationStatusInfo | undefined): string {
  if (!s || !s.lastReplicated || !s.lastReplicationAction) return 'Not published';
  const when = relativeTime(new Date(s.lastReplicated));
  if (s.lastReplicationAction === 'Deactivate') return `Unpublished ${when}`;
  if (s.pending) return 'Publication pending';
  return s.published ? `Published ${when}` : `Modified since published ${when}`;
}

function TemplateCards({ items, published, selected, onOpen, onToggle }: ItemsProps) {
  if (!items.length) return <p className="studio-sites__empty">This configuration has no templates yet. Create one from a template type.</p>;
  return (
    <ul className="studio-cards">
      {items.map((t) => {
        const isSelected = selected.includes(t.path);
        return (
          <li key={t.path} className={`studio-card${isSelected ? ' is-selected' : ''}`}>
            <button type="button" className="studio-card__open" onClick={() => onOpen(t)} title={t.path}>
              <span className="studio-card__thumb">{t.thumbnail ? <img src={t.thumbnail} alt="" loading="lazy" /> : <TemplateIcon size="XL" />}</span>
              <span className="studio-card__body">
                <span className="studio-card__type">{t.templateTypeTitle ?? 'Template'}</span>
                <span className="studio-card__title">{t.title}</span>
                <span className="studio-card__meta">
                  <span className={`studio-tpls__state studio-tpls__state--${t.status}`}>{STATUS_LABEL[t.status] ?? t.status}</span>
                  {when(t.lastModified) && ` · ${when(t.lastModified)}`}
                </span>
                {published && <span className="studio-card__meta">{publishedLabel(published[t.path])}</span>}
              </span>
            </button>
            <span className="studio-card__check">
              <Checkbox aria-label={`Select ${t.title}`} isSelected={isSelected} onChange={() => onToggle(t)} isEmphasized />
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function TemplateList({ items, published, selected, onOpen, onToggle }: ItemsProps) {
  if (!items.length) return <p className="studio-sites__empty">This configuration has no templates yet. Create one from a template type.</p>;
  return (
    <div className="studio-listview studio-tpls__list">
      <table className="studio-table">
        <thead>
          <tr>
            <th aria-label="Select" />
            <th>Title</th>
            <th>Template Type</th>
            <th>Status</th>
            <th>Modified</th>
            {published && <th>Published</th>}
          </tr>
        </thead>
        <tbody>
          {items.map((t) => {
            const isSelected = selected.includes(t.path);
            return (
              <tr key={t.path} className={isSelected ? 'is-selected' : undefined}>
                <td className="studio-listview__check">
                  <Checkbox aria-label={`Select ${t.title}`} isSelected={isSelected} onChange={() => onToggle(t)} isEmphasized />
                </td>
                <td>
                  <button type="button" className="studio-listview__title" onClick={() => onOpen(t)}>
                    <span className="studio-listview__thumb">{t.thumbnail ? <img src={t.thumbnail} alt="" loading="lazy" /> : <TemplateIcon size="S" />}</span>
                    {t.title}
                  </button>
                </td>
                <td className="studio-listview__muted">{t.templateTypeTitle ?? ''}</td>
                <td>{STATUS_LABEL[t.status] ?? t.status}</td>
                <td className="studio-listview__muted">
                  <span className="studio-tpls__when">{when(t.lastModified)}</span>
                  {t.lastModifiedBy && <span className="studio-tpls__who">{t.lastModifiedBy}</span>}
                </td>
                {published && <td className="studio-listview__muted">{publishedLabel(published[t.path])}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function BarButton({ icon, label, shortcut, onPress }: { icon: ReactElement; label: string; shortcut?: string; onPress(): void }) {
  return (
    <TooltipTrigger delay={600}>
      <ActionButton isQuiet onPress={onPress}>
        {icon}
        <Text>
          {label}
          {shortcut && <span className="studio-actionbar__hint"> ({shortcut})</span>}
        </Text>
      </ActionButton>
      <Tooltip>{shortcut ? `${label} (${shortcut})` : label}</Tooltip>
    </TooltipTrigger>
  );
}

export function when(iso?: string | null) {
  return iso ? relativeTime(new Date(iso)) : '';
}

export function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
