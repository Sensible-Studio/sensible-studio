import { VOCAB } from '../shell/vocabulary';
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
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Inbox from '@spectrum-icons/workflow/Inbox';
import Copy from '@spectrum-icons/workflow/Copy';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import LockOpen from '@spectrum-icons/workflow/LockOpen';
import Move from '@spectrum-icons/workflow/Move';
import Paste from '@spectrum-icons/workflow/Paste';
import Properties from '@spectrum-icons/workflow/Properties';
import SelectBox from '@spectrum-icons/workflow/SelectBox';
import Sync from '@spectrum-icons/workflow/Sync';
import ViewTree from '@spectrum-icons/workflow/TreeExpandAll';
import WebPage from '@spectrum-icons/workflow/WebPage';
import Add from '@spectrum-icons/workflow/Add';
import WebPages from '@spectrum-icons/workflow/WebPages';
import WorkflowIcon from '@spectrum-icons/workflow/Workflow';
import History from '@spectrum-icons/workflow/History';
import Branch1 from '@spectrum-icons/workflow/Branch1';
import Gears from '@spectrum-icons/workflow/Gears';
import Globe from '@spectrum-icons/workflow/Globe';
import GlobeRemove from '@spectrum-icons/workflow/GlobeRemove';
import PublishCheck from '@spectrum-icons/workflow/PublishCheck';
import { HttpReplicationClient, type ReplicationClient } from '../replication/api';
import { PublishDialog } from '../replication/PublishDialog';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import ProjectAdd from '@spectrum-icons/workflow/ProjectAdd';
import { useCallback, useEffect, useMemo, useRef, useState, type Key, type MouseEvent, type ReactElement } from 'react';
import { createDefaultRegistry, type DialogResponse, type GraniteEnv, type GraniteNode, type PostEntry } from '../granite';
import { nameOf, parentOf, type StudioClient, type SiteItem, type SiteListing } from '../editor/api/StudioClient';
import { ComponentDialog, type OpenDialog } from '../editor/dialogs/ComponentDialog';
import { studioGraniteEnv } from '../editor/dialogs/graniteEnv';
import { propertiesUrl } from '../pageprops/api';
import { ProductBar } from '../shell/ProductBar';
import { PathCrumbs } from '../shell/PathCrumbs';
import { ScreenFrame } from '../shell/ScreenFrame';
import { useNavigate, useTab } from '../shell/ShellContext';
import { BulkPagePropertiesDialog, BulkVersionDialog, httpBulkPages } from './BulkPageDialogs';
import { ContextMenu, useContextMenu, useRegisterActions, type Action } from '../shell/actions';
import { SelectionBar } from '../shell/SelectionBar';
import { CreatePageWizard } from './CreatePageWizard';
import { CreateFolderDialog } from './CreateFolderDialog';
import { DeleteDialog } from './DeleteDialog';
import { MoveDialog } from './MoveDialog';
import { CardView, ColumnView, ListView, readView, storeView, ViewSwitch, type ViewMode } from './views';
import { HttpSubmissionsClient, type SubmissionCounts } from '../forms/api';
import { CreateLiveCopyWizard } from '../msm/CreateLiveCopyWizard';
import { CreateLaunchDialog } from '../launches/CreateLaunchDialog';
import { HttpLaunchesClient } from '../launches/api';
import { CsvReport, type CsvSources } from './CsvReport';
import { HttpWorkflowClient } from '../workflow/api';
import FileTxt from '@spectrum-icons/workflow/FileTxt';
import { CreateSiteWizard } from '../msm/CreateSiteWizard';
import { HttpSiteConfigClient } from '../siteconfig/api';
import { LiveCopyOverview } from '../msm/LiveCopyOverview';
import { useMsmStatus } from '../msm/LiveCopyStatus';
import { describeResult, HttpMsmClient, type MsmClient } from '../msm/MsmClient';
import { RolloutDialog } from '../msm/RolloutDialog';
import { usePageInheritance } from '../msm/usePageInheritance';
import { StartWorkflowDialog } from '../workflow/StartWorkflowDialog';
import { PageLanguagesDialog } from '../translation/PageLanguagesDialog';
import { SitesTranslationProjectDialog } from '../translation/SitesTranslationProject';
import { HttpPageTranslationClient } from '../translation/pages';
import {
  emptyCriteria,
  HttpSiteRailClient,
  isFiltering,
  readRail,
  storeRail,
  type FilterCriteria,
  type RailId,
  type SearchResults,
  type SiteRailClient,
} from './rail/api';
import { FilterResults } from './rail/FilterRail';
import { RailSelector } from './rail/RailSelector';
import { Inspector, INSPECTOR_TABS, readInspector, storeInspector, type InspectorTab } from './Inspector';
import RailRight from '@spectrum-icons/workflow/RailRight';
import { SitesRail } from './rail/SitesRail';
import { BlockIcon, PageIcon } from '../shell/pageIcons';
import FormIcon from '@spectrum-icons/workflow/Form';
import Folder from '@spectrum-icons/workflow/Folder';

export type { ViewMode } from './views';

/**
 * Roots the console browses as products of their own: the first crumb is the product, the columns
 * start below it, and the tab is named after it. Everything else is Sites, rooted at /content.
 */
export const ROOTS: Array<{ path: string; title: string }> = [
  { path: '/content/experience-fragments', title: VOCAB.blocks },
  { path: '/content/forms', title: VOCAB.forms },
];
const SITES_ROOT = { path: '/content', title: VOCAB.pages };

export function rootOf(path: string): { path: string; title: string } {
  return ROOTS.find((r) => path === r.path || path.startsWith(`${r.path}/`)) ?? SITES_ROOT;
}

interface Props {
  client: StudioClient;
  initialPath: string;
  /** MSM endpoint (default: Sensible Studio's `/bin/studio/msm.json`) */
  msm?: MsmClient;
  /** the rails' endpoint (default: `/bin/studio/siterail.json`) */
  siteRail?: SiteRailClient;
  /** publishing (default: `/bin/studio/replication.json`) */
  replication?: ReplicationClient;
}

const VIEW_KEY = 'studio.sites.view';
/** Edit on several pages opens at most this many tabs. */
const MAX_OPEN = 10;

export const sitesUrl = (path: string) => `/sites.html${path}`;

/**
 * The Sites console (`/sites.html/<path>`), laid out like AEM's: Column, List and Card views of pages
 * and folders below /content, an action bar for the selection (Edit, Properties, Lock, Copy, Move,
 * Delete), Paste, and Create Page from the templates allowed at that place.
 */
const canMakeHere = (item: { canCreate?: boolean }) => item.canCreate !== false;

const CHANGES = new Set(['lock', 'unlock', 'rollout', 'sync', 'version', 'create-page', 'create-site', 'create-livecopy', 'release', 'restore-version', 'restore-tree', 'flow', 'languages', 'translate']);
const PUBLISHES = new Set(['publish', 'publish-tree', 'unpublish']);
const REMOVES = new Set(['move', 'delete']);

/** Whether an action is one the signed-in user may take on every selected item (the server says per item; when it does not, yes). */
export function allowedFor(id: string, sel: Array<Pick<SiteItem, 'canEdit' | 'canDelete' | 'canPublish'>>): boolean {
  if (CHANGES.has(id)) return sel.every((i) => i.canEdit !== false);
  if (PUBLISHES.has(id)) return sel.every((i) => i.canPublish !== false);
  if (REMOVES.has(id)) return sel.every((i) => i.canDelete !== false);
  return true;
}

export function SitesConsole({ client, initialPath, msm: msmClient, siteRail: siteRailClient, replication: replicationClient }: Props) {
  const registry = useMemo(() => createDefaultRegistry(), []);
  const msm = useMemo(() => msmClient ?? new HttpMsmClient(), [msmClient]);
  const [path, setPath] = useState(initialPath);
  // In the shell the console's place is the tab's: Back and Forward move it, and its own moves are pushed
  // on the tab. On its own it keeps the URL in step itself, as before.
  const tab = useTab();
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const go = useNavigate();
  const tabPath = tab?.place.kind === 'sites' ? tab.place.path : null;
  useEffect(() => {
    if (tabPath !== null && tabPath !== path) {
      setPath(tabPath);
      setSelected([]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabPath]);
  const moveTo = (p: string) => {
    if (tab) tab.setPlace({ kind: 'sites', path: p });
    else window.history.pushState(null, '', sitesUrl(p));
    setPath(p);
  };
  const [listings, setListings] = useState<Map<string, SiteListing>>(new Map());
  const [error, setError] = useState<string | null>(null);
  const [view, setViewState] = useState<ViewMode>(() => readView(VIEW_KEY));
  const [selected, setSelected] = useState<string[]>([]);
  const [clipboard, setClipboard] = useState<SiteItem[] | null>(null);
  const [creating, setCreating] = useState<string | null>(null);
  // what Create makes there: a page, an experience fragment, or a variation of the fragment one is in
  const [creatingKind, setCreatingKind] = useState<'page' | 'xf' | 'variation'>('page');
  const [creatingFolder, setCreatingFolder] = useState<string | null>(null);
  const [startingWorkflow, setStartingWorkflow] = useState<string[] | null>(null);
  const [moving, setMoving] = useState<SiteItem | null>(null);
  /** with several selected: the others, which go along to the same place */
  const [movingMore, setMovingMore] = useState<SiteItem[]>([]);
  // Publish / Unpublish: AEM's dialog, for the selected pages
  const [publishing, setPublishing] = useState<{ action: 'activate' | 'deactivate'; items: SiteItem[]; deep?: boolean } | null>(null);
  const replication = useMemo(() => replicationClient ?? new HttpReplicationClient(), [replicationClient]);
  const [deleting, setDeleting] = useState<SiteItem[] | null>(null);
  const [properties, setProperties] = useState<OpenDialog | null>(null);
  const [creatingLiveCopy, setCreatingLiveCopy] = useState<string | null>(null);
  // Create ▾ Release and Restore ▾ on a selected page (AEM's Create Launch / Restore Version / Restore Tree)
  const [creatingRelease, setCreatingRelease] = useState<SiteItem[] | null>(null);
  // several pages selected: Properties and Create ▾ Version for all of them
  const [bulkProperties, setBulkProperties] = useState<SiteItem[] | null>(null);
  const [bulkVersion, setBulkVersion] = useState<SiteItem[] | null>(null);
  const bulkPages = useMemo(() => httpBulkPages((path, entries) => client.post(path, entries)), [client]);
  const [timelineRequest, setTimelineRequest] = useState<{ dialog: 'version' | 'restoreVersion' | 'restoreTree'; nonce: number } | null>(null);
  const launches = useMemo(() => new HttpLaunchesClient(), []);
  // Create ▾ CSV Report: the parent whose pages it lists
  const [csvReportOf, setCsvReportOf] = useState<string | null>(null);
  const csvSources: CsvSources = useMemo(
    () => ({
      runningFlows: async () => {
        const list = await new HttpWorkflowClient().instances('RUNNING');
        return Object.fromEntries(list.filter((i) => i.payload).map((i) => [i.payload!, i.modelTitle ?? i.title ?? i.modelId]));
      },
      translations: async (under: string) => {
        const list = await new HttpPageTranslationClient().queue({ under });
        return Object.fromEntries(list.map((i) => [i.path, i.state]));
      },
    }),
    [],
  );
  /** The title of where something is created: a selected page (Create ▾ on a selection) or the folder you are in. */
  const titleAt = (p: string) => selection.find((i) => i.path === p)?.title ?? (current?.item.path === p ? current.item.title : undefined) ?? p;
  const [creatingSite, setCreatingSite] = useState<string | null>(null);
  const [rolloutOf, setRolloutOf] = useState<string | null>(null);
  const [overviewOf, setOverviewOf] = useState<string | null>(null);
  const [languageCopiesOf, setLanguageCopiesOf] = useState<string[] | null>(null);
  const [translationProjectFor, setTranslationProjectFor] = useState<string[] | null>(null);
  const pageTranslation = useMemo(() => new HttpPageTranslationClient(), []);
  const [user, setUser] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  // the left rail (Content Only, Content Tree, Timeline, References, Site, Filter) and the filter
  const siteRail = useMemo(() => siteRailClient ?? new HttpSiteRailClient(), [siteRailClient]);
  const [rail, setRailState] = useState<RailId>(readRail);
  const [criteria, setCriteria] = useState<FilterCriteria>(() => emptyCriteria(initialPath));
  const [results, setResults] = useState<SearchResults | null>(null);
  const filtering = rail === 'filter' && isFiltering(criteria);
  const setRail = useCallback((r: RailId) => {
    setRailState(r);
    storeRail(r);
  }, []);
  // the inspector at the right: Properties, Timeline, References, Site for the selection (alt+3 … alt+6, alt+` closes)
  const [inspector, setInspectorState] = useState<InspectorTab | null>(readInspector);
  const setInspector = useCallback((t: InspectorTab | null) => {
    setInspectorState(t);
    storeInspector(t);
  }, []);
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (tabRef.current && !tabRef.current.active) return;
      const t = ev.target instanceof Element ? ev.target : null;
      if (t?.closest('[role="dialog"]') || !ev.altKey || ev.metaKey || ev.ctrlKey) return;
      if (ev.code === 'Backquote') {
        ev.preventDefault();
        setInspector(inspector ? null : 'properties');
        return;
      }
      const n = ev.code?.startsWith('Digit') ? Number(ev.code.slice(5)) : NaN;
      const tab = INSPECTOR_TABS.find((x) => x.shortcut === `alt+${n}`);
      if (tab) {
        ev.preventDefault();
        setInspector(tab.key);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [inspector, setInspector]);

  // What is on screen while the next place loads: the last listing, not a spinner — entering a folder
  // must not look like a page reload. The spinner shows only before anything has loaded at all.
  const loaded = listings.get(path) ?? null;
  const shownRef = useRef<SiteListing | null>(null);
  if (loaded) shownRef.current = loaded;
  const current = loaded ?? shownRef.current;
  const loading = !loaded;
  const root = rootOf(path);

  // the Forms console: how many submissions each form has (and how many unread), as a mark after its title
  const [counts, setCounts] = useState<SubmissionCounts>({});
  const submissions = useMemo(() => new HttpSubmissionsClient(), []);
  useEffect(() => {
    if (root.path !== '/content/forms') return;
    let live = true;
    submissions.counts(path).then((c) => live && setCounts(c), () => live && setCounts({}));
    return () => {
      live = false;
    };
  }, [submissions, root.path, path, version]);
  // in Blocks a page is a block, in Forms a form: the same views, the product's own icon
  const renderIcon = useCallback(
    (item: SiteItem) =>
      item.type === 'folder' ? <Folder size="S" /> : root.path === '/content/experience-fragments' ? <BlockIcon size="S" /> : root.path === '/content/forms' ? <FormIcon size="S" /> : <PageIcon size="S" />,
    [root.path],
  );
  const renderBadge = useCallback(
    (item: SiteItem) => {
      const c = counts[item.path];
      return (
        <>
          <PublicationDot item={item} />
          {c && c.total ? (
            <span className={`studio-submissions-badge${c.unseen ? ' is-new' : ''}`} title={`${c.total} submission${c.total === 1 ? '' : 's'}${c.unseen ? `, ${c.unseen} new` : ''}`}>
              {c.unseen ? `${c.unseen} new` : c.total}
            </span>
          ) : null}
        </>
      );
    },
    [counts],
  );
  // the trail from the root: the product's own crumb first, nothing above it
  const trail = useMemo(() => {
    const full = current?.trail ?? [];
    const i = full.findIndex((t) => t.path === root.path);
    const below = i >= 0 ? full.slice(i + 1) : full.filter((t) => t.path !== SITES_ROOT.path);
    return [{ path: root.path, title: root.title }, ...below];
  }, [current, root.path, root.title]);

  // ------------------------------------------------------------------------------------------ loading

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const listing = await client.siteChildren(path);
        // the levels above: from the cache, unless everything is being reloaded
        const others = await Promise.all(
          listing.trail.filter((t) => t.path !== path).map((t) => (version === 0 && listings.has(t.path) ? null : client.siteChildren(t.path).catch(() => null))),
        );
        if (cancelled) return;
        setListings((prev) => {
          const next = version > 0 ? new Map<string, SiteListing>() : new Map(prev);
          next.set(path, listing);
          for (const o of others) if (o) next.set(o.item.path, o);
          return next;
        });
        setError(null);
        const r = rootOf(path);
        if (tabRef.current) tabRef.current.setTitle(listing.item.path === r.path ? r.title : `${r.title} · ${listing.item.title}`);
        else document.title = `${listing.item.title} | Sensible Studio ${r.title}`;
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, path, version]);

  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  useEffect(() => {
    if (tab) return; // the shell follows the browser's history for its tabs
    const onPop = () => {
      const m = window.location.pathname.match(/^\/sites\.html(\/content.*)?$/);
      setPath(m?.[1] ? decodeURI(m[1]) : '/content');
      setSelected([]);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  // without filters set, the filter searches below wherever the console is
  useEffect(() => {
    setCriteria((c) => (isFiltering(c) || c.path === path ? c : { ...c, path }));
  }, [path]);

  const navigate = (p: string) => {
    if (p === path) return;
    moveTo(p);
    setSelected([]);
  };

  const setView = (v: ViewMode) => {
    setViewState(v);
    storeView(VIEW_KEY, v);
  };

  // ------------------------------------------------------------------------------------------ selection

  const itemsByPath = useMemo(() => {
    const m = new Map<string, SiteItem>();
    for (const r of results?.items ?? []) m.set(r.path, r);
    for (const l of listings.values()) {
      m.set(l.item.path, l.item);
      for (const c of l.children) m.set(c.path, c);
    }
    return m;
  }, [listings, results]);

  const selection = selected.map((p) => itemsByPath.get(p)).filter((i): i is SiteItem => !!i);
  const single = selection.length === 1 ? selection[0] : null;
  // MSM actions of the selected page: Rollout for a blueprint, Synchronize for a live copy page
  const singleMsm = useMsmStatus(msm, single?.type === 'page' ? single.path : null);
  const isBlueprint = !!singleMsm.status?.isSource;
  const canSync = !!singleMsm.status?.relationship?.inherited && singleMsm.status.relationship.status !== 'suspended';
  const pageInheritance = usePageInheritance(msm, properties?.variant === 'page' ? properties.path : null);

  const toggle = (item: SiteItem) => {
    const parent = parentOf(item.path);
    if (parent !== path && !filtering) {
      // selecting in an outer column moves there, like AEM
      moveTo(parent);
      setSelected([item.path]);
      return;
    }
    setSelected((s) => (s.includes(item.path) ? s.filter((p) => p !== item.path) : [...s, item.path]));
  };

  const selectAll = () => setSelected((filtering ? (results?.items ?? []) : (current?.children ?? [])).map((c) => c.path));

  // ------------------------------------------------------------------------------------------ actions

  const run = async (work: () => Promise<unknown>, success?: string) => {
    try {
      await work();
      if (success) ToastQueue.positive(success, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      refresh();
    }
  };

  const edit = (item: SiteItem) => {
    go({ kind: 'editor', path: item.path });
  };

  const openProperties = async (item: SiteItem) => {
    if (!item.resourceType) return;
    // like AEM: the page properties are their own screen (Cancel / Save & Close come back here)
    if (item.type === 'page') {
      go(propertiesUrl(item.path));
      return;
    }
    const contentPath = `${item.path}/jcr:content`;
    try {
      const [response, content] = await Promise.all([client.getDialog(item.resourceType, 'cq:dialog', contentPath), client.getContent(contentPath)]);
      if (!response.root) {
        ToastQueue.info(`${item.title} has no page properties dialog. ${response.warnings.join(' ')}`.trim(), { timeout: 6000 });
        return;
      }
      setProperties({ title: item.title, path: contentPath, dialog: response as DialogResponse & { root: GraniteNode }, content, variant: 'page' });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
  };

  const saveProperties = async (entries: PostEntry[]) => {
    if (!properties) return;
    await client.post(properties.path, entries);
    ToastQueue.positive(`${properties.title} saved`, { timeout: 3000 });
    setProperties(null);
    refresh();
  };

  const paste = () => {
    const items = clipboard;
    if (!items) return;
    void run(async () => {
      for (const it of items) {
        if (client.copyPage) await client.copyPage(it.path, path);
        else await client.copyResource(it.path, { container: path, before: null });
      }
    }, items.length === 1 ? `${items[0].title} pasted` : `${items.length} items pasted`);
  };

  const lock = (item: SiteItem, locked: boolean) =>
    void run(() => client.setPageLock(item.path, locked), locked ? `${item.title} locked` : `${item.title} unlocked`);

  const remove = (items: SiteItem[]) => {
    setDeleting(null);
    void run(async () => {
      // a version of every page first, so Restore Version can bring them back
      await siteRail.deletePages(items.map((it) => it.path));
      setSelected([]);
    }, items.length === 1 ? `${items[0].title} deleted` : `${items.length} items deleted`);
  };

  const onAction = (action: string, sel: SiteItem[] = selection) => {
    const single = sel.length === 1 ? sel[0] : null;
    switch (action) {
      case 'edit':
        if (single?.type === 'page') edit(single);
        else if (sel.length > 1 && sel.every((i) => i.type === 'page')) {
          // the first in this tab's place would lose the selection's console: each page gets its own tab
          for (const p of sel.slice(0, MAX_OPEN)) go({ kind: 'editor', path: p.path }, { newTab: true });
          if (sel.length > MAX_OPEN) ToastQueue.info(`The first ${MAX_OPEN} pages were opened`, { timeout: 4000 });
        }
        break;
      case 'properties':
        if (single?.type === 'page') void openProperties(single);
        else if (sel.length > 1 && sel.every((i) => i.type === 'page')) setBulkProperties(sel);
        break;
      case 'submissions':
        if (single?.type === 'page') go({ kind: 'form-submissions', form: single.path });
        break;
      case 'lock':
      case 'unlock':
        if (single?.type === 'page') lock(single, action === 'lock');
        else if (sel.length > 1 && sel.every((i) => i.type === 'page')) {
          const want = action === 'lock';
          const todo = sel.filter((i) => !!i.locked !== want);
          void run(async () => {
            for (const it of todo) await client.setPageLock(it.path, want);
          }, `${todo.length} page${todo.length === 1 ? '' : 's'} ${want ? 'locked' : 'unlocked'}`);
        }
        break;
      case 'languages':
        if (sel.length > 0 && sel.every((i) => i.type === 'page')) setLanguageCopiesOf(sel.map((i) => i.path));
        break;
      case 'siteconfig':
        if (single) go({ kind: 'siteconfig', path: single.path });
        break;
      case 'publish-tree':
        if (sel.length) setPublishing({ action: 'activate', items: sel.filter((i) => i.type === 'page'), deep: true });
        break;
      case 'publish':
      case 'unpublish':
        if (sel.length) setPublishing({ action: action === 'publish' ? 'activate' : 'deactivate', items: sel.filter((i) => i.type === 'page') });
        break;
      case 'translate':
        setTranslationProjectFor(sel.map((i) => i.path));
        break;
      case 'flow':
        setStartingWorkflow(sel.map((i) => i.path));
        break;
      case 'release':
        if (sel.length > 0 && sel.every((i) => i.type === 'page')) setCreatingRelease(sel);
        break;
      case 'csv':
        if (single?.type === 'page') setCsvReportOf(single.path);
        break;
      case 'create-page':
        if (single?.type === 'page') setCreating(single.path);
        break;
      case 'create-site':
        if (single?.type === 'page') setCreatingSite(single.path);
        break;
      case 'create-livecopy':
        if (single?.type === 'page') setCreatingLiveCopy(single.path);
        break;
      case 'version':
        if (single?.type === 'page') {
          setInspector('timeline');
          setTimelineRequest({ dialog: 'version', nonce: Date.now() });
        } else if (sel.length > 1 && sel.every((i) => i.type === 'page')) setBulkVersion(sel);
        break;
      case 'restore-version':
      case 'restore-tree':
        if (single?.type === 'page') {
          setInspector('timeline');
          setTimelineRequest({ dialog: action === 'restore-version' ? 'restoreVersion' : 'restoreTree', nonce: Date.now() });
        }
        break;
      case 'copy':
        setClipboard(sel);
        setSelected([]);
        ToastQueue.info(`${sel.length === 1 ? sel[0].title : `${sel.length} items`} copied: open the destination and Paste`, { timeout: 4000 });
        break;
      case 'move':
        if (single) {
          setMovingMore([]);
          setMoving(single);
        } else if (sel.length > 1) {
          setMovingMore(sel.slice(1));
          setMoving(sel[0]);
        }
        break;
      case 'delete':
        if (sel.length) setDeleting(sel);
        break;
      case 'rollout':
        if (single && isBlueprint) setRolloutOf(single.path);
        break;
      case 'overview':
        if (single && isBlueprint) setOverviewOf(single.path);
        break;
      case 'sync':
        if (single && canSync) {
          const it = single;
          void run(async () => {
            const result = await msm.sync(it.path, { deep: false });
            ToastQueue.positive(`${it.title}: ${describeResult(result)}`, { timeout: 5000 });
          });
        }
        break;
    }
  };

  /**
   * The actions for a selection — the bar, the right-click menu and ⌘K all read this list. MSM's
   * Sync / Overview depend on the status of the single selected page, known only for the current selection.
   */
  const actionsFor = (sel: SiteItem[]): Action[] => {
    const one = sel.length === 1 ? sel[0] : null;
    const page = one?.type === 'page' ? one : null;
    const pages = sel.length > 0 && sel.every((i) => i.type === 'page');
    const current = one !== null && one.path === single?.path;
    const a = (id: string, label: string, icon: ReactElement, extra: Partial<Action> = {}): Action => ({ id, label, icon, run: () => onAction(id, sel), ...extra });
    const out: Action[] = [];
    if (page) out.push(a('edit', 'Edit', <Edit />, { shortcut: 'e' }), a('properties', 'Properties', <Properties />, { shortcut: 'p' }));
    // several pages, as in AEM: Edit opens each in a tab of its own, Properties edits what they can share
    if (pages && sel.length > 1) out.push(a('edit', 'Edit', <Edit />, { shortcut: 'e' }), a('properties', 'Properties', <Properties />, { shortcut: 'p' }));
    if (page && root.path === '/content/forms') out.push(a('submissions', 'Submissions', <Inbox />, { shortcut: 's' }));
    if (page) out.push(page.locked ? a('unlock', 'Unlock', <LockOpen />) : a('lock', 'Lock', <LockClosed />));
    // several: Lock while any of them is open, Unlock once all are locked
    if (pages && sel.length > 1) out.push(sel.every((i) => i.locked) ? a('unlock', 'Unlock', <LockOpen />) : a('lock', 'Lock', <LockClosed />));
    if (current && isBlueprint) out.push(a('rollout', 'Sync changes', <Sync />), a('overview', 'Linked copy Overview', <ViewTree />));
    if (current && canSync) out.push(a('sync', 'Synchronize', <Sync />));
    if (pages) out.push(a('publish', 'Publish', <PublishCheck />));
    if (pages && sel.some((i) => i.hasChildren)) out.push(a('publish-tree', 'Publish with the pages below', <PublishCheck />));
    if (pages) out.push(a('unpublish', 'Unpublish', <GlobeRemove />, { danger: true }));
    if (pages) out.push(a('languages', 'Language versions', <Globe />));
    if (page) out.push(a('siteconfig', 'Site configuration', <Gears />));
    // AEM's Create ▾ on a selection, in its order: Workflow, Version, Page, Site, Live Copy, Launch, Language Copy;
    // a page, a site or a linked copy is created below the selected page
    if (pages) out.push(a('flow', 'Start Flow', <WorkflowIcon />, { group: 'Create', groupIcon: <Add /> }));
    if (page) {
      out.push(
        a('version', 'Version', <History />, { group: 'Create' }),
        a('create-page', root.path === '/content/forms' ? 'Form' : 'Page', <WebPage />, { group: 'Create' }),
      );
      if (root.path !== '/content/forms') out.push(a('create-site', 'Site', <WebPages />, { group: 'Create' }), a('create-livecopy', 'Linked copy', <ViewTree />, { group: 'Create' }));
      out.push(a('release', 'Release', <Branch1 />, { group: 'Create' }));
    }
    if (pages && sel.length > 1) out.push(a('version', 'Version', <History />, { group: 'Create' }), a('release', 'Release', <Branch1 />, { group: 'Create' }));
    if (pages) out.push(a('translate', 'Translation Project', <ProjectAdd />, { group: 'Create' }));
    if (page) out.push(a('csv', 'CSV Report', <FileTxt />, { group: 'Create' }));
    // AEM's Restore ▾: a version of this page, or deleted pages below it
    if (page) out.push(a('restore-version', 'Restore Version', <History />, { group: 'Restore', groupIcon: <History /> }), a('restore-tree', 'Restore Tree', <History />, { group: 'Restore' }));
    out.push(a('copy', 'Copy', <Copy />, { shortcut: '⌘C', separator: true }));
    out.push(a('move', 'Move', <Move />, { shortcut: 'm' }));
    out.push(a('delete', 'Delete', <Delete />, { shortcut: '⌫', danger: true }));
    // what the person may not do with these is not offered: a reader had every action, each ending in a refusal
    return out.filter((x) => allowedFor(x.id, sel));
  };
  const actions = selection.length ? actionsFor(selection) : [];
  useRegisterActions(tab?.id, selection.length ? { subject: single ? single.title : `${selection.length} items`, actions } : null);

  // right-click on an item: it becomes the selection (unless it is part of it), and its actions open at the pointer
  const ctx = useContextMenu();
  const onItemMenu = (item: SiteItem, e: MouseEvent) => {
    const sel = selected.includes(item.path) ? selection : [item];
    if (!selected.includes(item.path)) setSelected([item.path]);
    ctx.open(e, actionsFor(sel));
  };

  // keyboard shortcuts as in AEM: e, p, m, backspace, mod+c, mod+v, escape
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (tabRef.current && !tabRef.current.active) return; // another tab is in front
      if (creating || moving || deleting || properties || bulkProperties || bulkVersion || creatingLiveCopy || creatingSite || rolloutOf || overviewOf || creatingRelease || csvReportOf) return;
      const t = ev.target as HTMLElement | null;
      // shortcuts work while a checkbox has focus, not while typing
      if (t?.closest('input:not([type="checkbox"]):not([type="radio"]), textarea, select, [contenteditable="true"], [role="dialog"], [role="menu"]')) return;
      const mod = ev.metaKey || ev.ctrlKey;
      // the key must not also land in the field of the dialog it opens
      if (!mod && selection.length && ['e', 'p', 'm'].includes(ev.key)) ev.preventDefault();
      if (ev.key === 'Escape') setSelected([]);
      else if (mod && ev.key === 'v' && clipboard) paste();
      else if (!selection.length) return;
      else if (mod && ev.key === 'c') onAction('copy');
      else if (!mod && ev.key === 'e') onAction('edit');
      else if (!mod && ev.key === 'p') onAction('properties');
      else if (!mod && ev.key === 's' && root.path === '/content/forms') onAction('submissions');
      else if (!mod && ev.key === 'm') onAction('move');
      else if (ev.key === 'Backspace' || ev.key === 'Delete') {
        ev.preventDefault();
        onAction('delete');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const env: GraniteEnv = useMemo(() => studioGraniteEnv(client, properties?.path), [client, properties?.path]);

  // ------------------------------------------------------------------------------------------ render

  // an installation with nothing in it: say where a first site comes from, not only that there is none
  const emptyLabels = current?.item.path === '/content' ? { empty: canMakeHere(current.item) ? 'No site yet. Create \u203a Site makes the first one.' : 'No site yet.' } : undefined;
  const canCreate = (current?.item.path === '/content' || current?.item.type === 'page' || current?.item.type === 'folder') && current?.item.canCreate !== false;
  // Create ▾ follows the place: experience fragments are made as fragments (with their master variation), and
  // inside one what is made is a variation; folders go into folders
  const inXf = root.path === '/content/experience-fragments';
  const inFragment = inXf && current?.item.resourceType === 'cq/experience-fragments/components/experiencefragment';
  const inFolder = !current || current.item.type === 'folder';
  const createItems: Array<{ key: string; label: string; description: string }> = [
    ...(inXf
      ? inFragment
        ? [{ key: 'variation', label: 'Variant', description: 'Another version of this fragment, for a channel or an offer' }]
        : [{ key: 'xf', label: 'Experience Fragment', description: 'A reusable piece of a page, with its master variant' }]
      : [{ key: 'page', label: root.path === '/content/forms' ? 'Form' : 'Page', description: 'From a template allowed here' }]),
    ...(inFolder && !inFragment ? [{ key: 'folder', label: 'Folder', description: inXf ? 'To keep experience fragments in' : 'To keep pages in' }] : []),
    ...(inXf ? [] : [{ key: 'site', label: 'Site', description: 'A new one, or languages and chapters from a source' }]),
    { key: 'livecopy', label: 'Linked copy', description: inXf ? 'Fragments that follow a source' : 'Pages that follow a source' },
    { key: 'csv', label: 'CSV Report', description: inXf ? 'What is here as a spreadsheet' : 'The pages here as a spreadsheet' },
  ];

  return (
    <div className="studio-sites">
      {!tab && <ProductBar user={user} />}

      {selection.length > 0 ? (
        <SelectionBar
          count={selection.length}
          actions={actions}
          leading={['flow', 'edit', 'properties', 'lock', 'unlock', 'copy', 'move', 'publish', 'restore-version', 'delete']}
          onClear={() => setSelected([])}
          start={<RailSelector rail={rail} onChange={setRail} />}
          end={<InspectorButton on={!!inspector} onPress={() => setInspector(inspector ? null : 'properties')} />}
        />
      ) : (
        <ScreenFrame
          navigation={<RailSelector rail={rail} onChange={setRail} />}
          identity={<PathCrumbs crumbs={trail} onGo={navigate} />}
          actions={
            <>
            {clipboard && (
              <ActionButton isQuiet onPress={paste}>
                <Paste />
                <Text>Paste</Text>
              </ActionButton>
            )}
            <ActionButton isQuiet onPress={selectAll} isDisabled={!current?.children.length}>
              <SelectBox />
              <Text>Select All</Text>
            </ActionButton>
            </>
          }
          tools={
            <>
              <ViewSwitch view={view} onChange={setView} />
            </>
          }
          primary={
            <MenuTrigger>
              <Button variant="accent" isDisabled={!canCreate}>
                Create
              </Button>
              <Menu
                onAction={(k: Key) => {
                  if (k === 'page' || k === 'xf' || k === 'variation') {
                    setCreatingKind(k);
                    setCreating(path);
                  } else if (k === 'folder') setCreatingFolder(path);
                  else if (k === 'livecopy') setCreatingLiveCopy(path);
                  else if (k === 'csv') setCsvReportOf(path);
                  else if (k === 'site') setCreatingSite(path);
                }}
                disabledKeys={client.createFolder ? [] : ['folder']}
              >
                {createItems.map((c) => (
                  <Item key={c.key} textValue={c.label}>
                    <Text>{c.label}</Text>
                    <Text slot="description">{c.description}</Text>
                  </Item>
                ))}
              </Menu>
            </MenuTrigger>
          }
        />
      )}

      <div className="studio-sites__body">
        <SitesRail
          rail={rail}
          client={client}
          siteRail={siteRail}
          path={path}
          version={version}
          onNavigate={navigate}
          onChanged={refresh}
          criteria={criteria}
          onCriteria={setCriteria}
          onResults={setResults}
        />
        <main className={`studio-sites__content${loading && current ? ' is-loading' : ''}${view === 'column' && !filtering && inspector && selection.length > 0 ? ' is-hugging' : ''}`} aria-busy={loading}>
          {filtering ? (
            <FilterResults client={client} results={results} selected={selected} onOpen={(i) => (i.type === 'page' ? edit(i) : navigate(i.path))} onToggle={toggle} />
          ) : error ? (
            <ErrorState title={`Could not open ${path}`}>{error}</ErrorState>
          ) : !current ? (
            <LoadingState />
          ) : view === 'column' ? (
            <ColumnView labels={emptyLabels} client={client} msm={msm} onChanged={refresh} listings={listings} trail={trail} current={current} selected={selected} onOpen={(i) => navigate(i.path)} onToggle={toggle} onContextMenu={onItemMenu} renderBadge={renderBadge} renderIcon={renderIcon} renderPreview={inspector && selection.length > 0 ? () => null : undefined} />
          ) : view === 'list' ? (
            <ListView labels={emptyLabels} client={client} items={current.children} selected={selected} onOpen={(i) => (i.hasChildren ? navigate(i.path) : i.type === 'page' ? edit(i) : navigate(i.path))} onToggle={toggle} onContextMenu={onItemMenu} renderBadge={renderBadge} renderIcon={renderIcon} />
          ) : (
            <CardView labels={emptyLabels} client={client} items={current.children} selected={selected} onOpen={(i) => (i.hasChildren ? navigate(i.path) : i.type === 'page' ? edit(i) : navigate(i.path))} onToggle={toggle} onContextMenu={onItemMenu} renderBadge={renderBadge} renderIcon={renderIcon} />
          )}
        </main>
        {/* details are of what is ticked: with nothing selected there is no panel and no button for it */}
        {inspector && selection.length > 0 && (
          <Inspector
            tab={inspector}
            onTab={setInspector}
            onClose={() => setInspector(null)}
            client={client}
            siteRail={siteRail}
            msm={msm}
            location={current?.item ?? null}
            selection={selection}
            onNavigate={navigate}
            onChanged={refresh}
            onEdit={edit}
            onProperties={(i) => void openProperties(i)}
            onPublish={(i) => setPublishing({ action: 'activate', items: [i] })}
            timelineRequest={timelineRequest}
          />
        )}
      </div>

      {csvReportOf && <CsvReport client={client} parent={csvReportOf} sources={csvSources} onClose={() => setCsvReportOf(null)} />}

      <DialogContainer onDismiss={() => setCreatingFolder(null)}>
        {creatingFolder && client.createFolder && (
          <CreateFolderDialog
            parent={creatingFolder}
            create={(title, name) => client.createFolder!(creatingFolder, title, name)}
            onClose={() => setCreatingFolder(null)}
            onCreated={(folder) => {
              setCreatingFolder(null);
              refresh();
              setSelected([folder.path]);
              ToastQueue.positive(`Folder ${folder.title} created`, { timeout: 4000 });
            }}
          />
        )}
      </DialogContainer>
      {creating && (
        <CreatePageWizard
          client={client}
          parent={creating}
          parentTitle={titleAt(creating)}
          kind={creatingKind}
          onClose={() => setCreating(null)}
          onCreated={(page) => {
            setCreating(null);
            refresh();
            setSelected([page.path]);
            ToastQueue.positive(`${creatingKind === 'xf' ? 'Experience fragment' : creatingKind === 'variation' ? 'Variant' : 'Page'} ${page.title} created`, {
              timeout: 6000,
              actionLabel: 'Open',
              onAction: () => edit(page),
              shouldCloseOnAction: true,
            });
          }}
        />
      )}

      {properties && (
        <ComponentDialog
          key={properties.path}
          {...properties}
          registry={registry}
          env={env}
          inheritance={pageInheritance ?? null}
          inheritancePending={pageInheritance === undefined}
          onSubmit={saveProperties}
          onClose={() => setProperties(null)}
        />
      )}

      <DialogContainer onDismiss={() => setPublishing(null)}>
        {publishing && (
          <PublishDialog
            client={replication}
            paths={publishing.items.map((i) => i.path)}
            title={publishing.items.length === 1 ? publishing.items[0].title : `${publishing.items.length} pages`}
            action={publishing.action}
            initialDeep={publishing.deep}
            offerDeep
            onDone={() => {
              setPublishing(null);
              setSelected([]);
              refresh();
            }}
          />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setMoving(null)}>
        {moving && (
          <MoveDialog
            client={client}
            item={moving}
            more={movingMore}
            onMove={(dest, name, adjust) => {
              const it = moving;
              const others = movingMore;
              setMoving(null);
              if (others.length > 0) {
                // several: each to the same place under its own name; what was live follows, as for one
                void run(async () => {
                  let adjusted = 0;
                  const failed: string[] = [];
                  for (const one of [it, ...others]) {
                    const moved = await client.movePage(one.path, dest, nameOf(one.path), adjust);
                    adjusted += moved.adjusted ?? 0;
                    if (moved.republishError) failed.push(one.title);
                  }
                  setSelected([]);
                  if (dest !== path) navigate(dest);
                  ToastQueue.positive(`${others.length + 1} items moved${adjusted ? `, ${adjusted} reference${adjusted === 1 ? '' : 's'} adjusted` : ''}`, { timeout: 5000 });
                  if (failed.length) ToastQueue.negative(`${failed.join(', ')}: live, and could not be published at the new address`, { timeout: 12000 });
                });
                return;
              }
              void run(async () => {
                const moved = await client.movePage(it.path, dest, name, adjust);
                setSelected([]);
                if (parentOf(moved.path) !== path) navigate(parentOf(moved.path));
                ToastQueue.positive(
                  `${it.title} moved${moved.adjusted ? `, ${moved.adjusted} reference${moved.adjusted === 1 ? '' : 's'} adjusted` : ''}${moved.republished?.length ? `; published again at its new address${moved.republished.length > 1 ? ` (${moved.republished.length} pages)` : ''}` : ''}`,
                  { timeout: 5000 },
                );
                // the move stands; the live site still has the old address until someone publishes
                if (moved.republishError) ToastQueue.negative(`${it.title} was live and could not be published at its new address: ${moved.republishError}`, { timeout: 12000 });
              });
            }}
          />
        )}
      </DialogContainer>

      {creatingSite && (
        <CreateSiteWizard
          msm={msm}
          parent={creatingSite}
          parentTitle={titleAt(creatingSite)}
          user={user}
          onClose={() => setCreatingSite(null)}
          onCreated={(created, result) => {
            setCreatingSite(null);
            refresh();
            if (created) setSelected([created]);
            ToastQueue.positive(`Site created: ${describeResult(result)}`, { timeout: 6000 });
          }}
          // a site is a page right below Pages: only there can one start from nothing
          blank={
            creatingSite === '/content'
              ? {
                  templates: () => client.pageTemplates('/content'),
                  create: async (title, name, template) => {
                    const page = await client.createPage('/content', template, title, name);
                    // its own configuration, for the data layer and the like; the site stands without it
                    await new HttpSiteConfigClient().createContext(page.path).catch(() => undefined);
                    return page.path;
                  },
                }
              : undefined
          }
          onBlankCreated={(created) => {
            setCreatingSite(null);
            refresh();
            setSelected([created]);
            ToastQueue.positive('Site created', { timeout: 6000, actionLabel: 'Open', onAction: () => go({ kind: 'editor', path: created }), shouldCloseOnAction: true });
          }}
        />
      )}

      <DialogContainer onDismiss={() => setBulkProperties(null)}>
        {bulkProperties && (
          <BulkPagePropertiesDialog
            client={bulkPages}
            pages={bulkProperties}
            user={user}
            close={() => setBulkProperties(null)}
            onDone={(n) => {
              ToastQueue.positive(`Properties of ${n} pages saved`, { timeout: 4000 });
              refresh();
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setBulkVersion(null)}>
        {bulkVersion && (
          <BulkVersionDialog
            pages={bulkVersion}
            createVersion={(path, label, comment) => client.createVersion(path, label, comment)}
            close={() => setBulkVersion(null)}
            onDone={(n) => ToastQueue.positive(`A version of ${n} pages created`, { timeout: 4000 })}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setCreatingRelease(null)}>
        {creatingRelease && (
          <CreateLaunchDialog
            client={launches}
            msm={msm}
            source={creatingRelease[0].path}
            sourceTitle={creatingRelease[0].title}
            more={creatingRelease.slice(1).map((i) => i.path)}
            onCreated={(l) => {
              setCreatingRelease(null);
              ToastQueue.positive(`Release ${l.title} created`, { timeout: 6000, shouldCloseOnAction: true, actionLabel: 'Open', onAction: () => go({ kind: 'editor', path: l.sourceInLaunch }) });
            }}
          />
        )}
      </DialogContainer>

      {creatingLiveCopy && (
        <CreateLiveCopyWizard
          client={client}
          msm={msm}
          parent={creatingLiveCopy}
          parentTitle={titleAt(creatingLiveCopy)}
          onClose={() => setCreatingLiveCopy(null)}
          onCreated={(created, result) => {
            setCreatingLiveCopy(null);
            refresh();
            setSelected([created]);
            ToastQueue.positive(`Linked copy created: ${describeResult(result)}`, {
              timeout: 6000,
              actionLabel: 'Open',
              onAction: () => go({ kind: 'editor', path: created }),
              shouldCloseOnAction: true,
            });
          }}
        />
      )}

      <DialogContainer onDismiss={() => setRolloutOf(null)}>
        {rolloutOf && singleMsm.status?.liveCopies && (
          <RolloutDialog
            msm={msm}
            path={rolloutOf}
            liveCopies={singleMsm.status.liveCopies}
            onDone={() => {
              singleMsm.reload();
              refresh();
            }}
          />
        )}
      </DialogContainer>

      {overviewOf && (
        <LiveCopyOverview
          msm={msm}
          path={overviewOf}
          onClose={() => setOverviewOf(null)}
          onChanged={() => {
            singleMsm.reload();
            refresh();
          }}
        />
      )}

      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && <DeleteDialog client={client} items={deleting} onDelete={() => remove(deleting)} />}
      </DialogContainer>

      {languageCopiesOf && (
        <PageLanguagesDialog client={pageTranslation} path={languageCopiesOf[0]} more={languageCopiesOf.slice(1)} onClose={() => setLanguageCopiesOf(null)} onChanged={refresh} />
      )}
      {translationProjectFor && <SitesTranslationProjectDialog paths={translationProjectFor} onClose={() => setTranslationProjectFor(null)} />}
      {startingWorkflow && <StartWorkflowDialog payloads={startingWorkflow} onClose={() => setStartingWorkflow(null)} />}
      {ctx.menu && <ContextMenu at={ctx.menu.at} actions={ctx.menu.actions} onClose={ctx.close} />}
      <ToastContainer placement="bottom" />
    </div>
  );
}

/** Where a page stands with publishing: a green dot published, orange changed since, grey taken down; nothing when never published. */
export function PublicationDot({ item }: { item: SiteItem }) {
  if (!item.publication) return null;
  const label = { published: 'Published', modified: 'Modified since published', unpublished: 'Unpublished' }[item.publication];
  const when = item.lastPublished ? ` ${new Date(item.lastPublished).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}` : '';
  return <span className={`studio-pubdot studio-pubdot--${item.publication}`} role="img" aria-label={label} title={`${label}${when}`} />;
}

function InspectorButton({ on, onPress }: { on: boolean; onPress(): void }) {
  // open, the panel closes with its own X; the bar only offers to bring it back
  if (on) return null;
  return (
    <TooltipTrigger delay={600}>
      <ActionButton isQuiet aria-label="Inspector" UNSAFE_className="studio-inspector__toggle" onPress={onPress}>
        <RailRight />
        <Text>Details</Text>
      </ActionButton>
      <Tooltip>Details of the selection: properties, timeline, references (alt+`)</Tooltip>
    </TooltipTrigger>
  );
}
