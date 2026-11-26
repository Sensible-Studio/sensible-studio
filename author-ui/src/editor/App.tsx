import { Content, DialogContainer, Divider, Heading, ToastContainer, ToastQueue } from '@adobe/react-spectrum';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createDefaultRegistry, type ContentNode, type DialogResponse, type GraniteEnv, type GraniteNode, type PostEntry } from '../granite';
import { nameOf, type AssetInfo, type ComponentInfo, type EditorPageData, type Placement, type StudioClient } from './api/StudioClient';
import { ComponentDialog, type OpenDialog } from './dialogs/ComponentDialog';
import { propertiesUrl } from '../pageprops/api';
import { studioGraniteEnv } from './dialogs/graniteEnv';
import { InsertComponentDialog } from './dialogs/InsertComponentDialog';
import { StylesButton } from './dialogs/StylesPopover';
import { PanelSelectButton } from './dialogs/PanelSelect';
import { activeIndex as activePanel, navigate as navigatePanel, panelContainerOf, revealInPanels } from './model/panelContainers';
import { ConvertToXfDialog } from './dialogs/ConvertToXfDialog';
import { RolloutDialog } from '../msm/RolloutDialog';
import { PublishDialog } from '../replication/PublishDialog';
import { HttpReplicationClient } from '../replication/api';
import { EMPTY_HISTORY, push as pushHistory, redo as redoHistory, scopeFor, undo as undoHistory, parentOf as parentPath, type HistoryState } from './history/history';
import { hasInplaceEditor, inplaceEditing, inplaceTarget } from './inline/inplace';
import { imageEditEntries, ImageEditorDialog, imageEditorFeatures, readImageEdits, type ImageEdits } from './inline/ImageEditorDialog';
import { InlineToolbar } from './inline/InlineToolbar';
import { END_EVENT, useInlineEdit, type InlineSession } from './inline/useInlineEdit';
import {
  accepts,
  ancestors,
  breakpointFor,
  columnState,
  containerOf,
  EMPTY_TREE,
  gridColumns,
  scanEditables,
  type ColumnState,
  type Editable,
  type EditableTree,
} from './model/editables';
import { EditToolbar, type ToolbarAction } from './overlay/EditToolbar';
import { Overlay, type DropPayload } from './overlay/Overlay';
import { boxOf, hitTest, type DropTarget, type Target } from './overlay/geometry';
import { AssetsPanel } from './panels/AssetsPanel';
import { ComponentsPanel } from './panels/ComponentsPanel';
import { ContentTreePanel } from './panels/ContentTreePanel';
import { DeveloperPanel } from './panels/DeveloperPanel';
import { DEVICES, deviceHeight, deviceWidth, effectiveScale, EmulatorBar, PresetRuler, ViewportHandles, type Device, type Orientation, type Zoom } from './shell/EmulatorBar';
import { SidePanel, type PanelId } from './shell/SidePanel';
import { TopBar, type EditorMode, type PageMenuAction, type TemplateMode } from './shell/TopBar';
import { templateEditorUrl } from '../templates/api';
import { TimewarpBar } from './timewarp/TimewarpBar';
import { HttpTargetingClient, type TargetInfo } from '../targeting/api';
import { TargetingPanel } from '../targeting/TargetingPanel';
import { ContextHubBar } from '../targeting/ContextHubBar';
import { AnnotationsLayer, type AnnotationMove } from './annotations/AnnotationsLayer';
import { annotationName, arrowEntries, arrowName, collectAnnotations, createEntries, formatPoints, SKETCHES, type Annotation, type Arrow, type Point } from './annotations/annotations';
import { LiveCopyStatus, useMsmStatus } from '../msm/LiveCopyStatus';
import { describeResult, HttpMsmClient, type MsmClient } from '../msm/MsmClient';
import { usePageInheritance } from '../msm/usePageInheritance';
import { StartWorkflowDialog } from '../workflow/StartWorkflowDialog';
import { HttpWorkflowClient, type InstanceSummary } from '../workflow/api';
import { StatusBar, type PageStatus } from './shell/StatusBar';
import { HttpLaunchesClient, formatDate, type LaunchInfo } from '../launches/api';
import { useNavigate, useTab } from '../shell/ShellContext';
import { CreateLaunchDialog } from '../launches/CreateLaunchDialog';
import { PromoteLaunchDialog } from '../launches/PromoteLaunchDialog';
import { HttpTemplateClient, templatePart, type PolicyTarget, type TemplateClient } from './template/api';
import { PolicyDialog } from './template/PolicyDialog';
import { AllowedComponentsPanel, TemplateBanner } from './template/TemplateParts';
import './template/template.scss';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';

interface Props {
  client: StudioClient;
  initialPage: string;
  /** MSM endpoint (default: Sensible Studio's `/bin/studio/msm.json`) */
  msm?: MsmClient;
  /** Template editor endpoint (default: Sensible Studio's `/bin/studio/templates.json`) */
  templates?: TemplateClient;
}

/** A selection survives re-renders of the page by path; the Editable objects are rebuilt each time. */
type SelectionKey = { kind: 'editable' | 'placeholder'; path: string };

interface ClipboardItem {
  path: string;
  type: string;
  title: string;
}

interface Clipboard {
  mode: 'copy' | 'cut';
  items: ClipboardItem[];
}

/**
 * The page editor: the page renders in a same-origin frame with `wcmmode=edit`; an overlay over it
 * outlines components, shows their toolbar and takes drops from the side panel. Every change goes to
 * Sling (POST servlet) and the frame re-renders, so what the author sees is what the site renders.
 */
/** Core's Experience Fragment edit handler (v1, v2). */
/** localStorage: whether the notes are shown over the page in Edit mode (`false` hides them). */
const ANNOTATIONS_SHOWN = 'studio.editor.annotations';

/** The resource type of an experience fragment itself (the page that holds its variations). */
const XF_HOLDER = 'cq/experience-fragments/components/experiencefragment';

/** The variation the editor opens for an experience fragment: its master, else its first one. `node` is `<fragment>.2.json`. */
export function xfVariation(path: string, node: unknown): string | null {
  if (!node || typeof node !== 'object') return null;
  const pages = Object.entries(node as Record<string, unknown>).filter(
    (e): e is [string, { 'jcr:content'?: Record<string, unknown> }] => !!e[1] && typeof e[1] === 'object' && (e[1] as Record<string, unknown>)['jcr:primaryType'] === 'cq:Page',
  );
  const master = pages.find(([, p]) => p['jcr:content']?.['cq:xfMasterVariation'] === true || p['jcr:content']?.['cq:xfMasterVariation'] === 'true');
  const pick = master ?? pages[0];
  return pick ? `${path}/${pick[0]}` : null;
}

const XF_EDIT_HANDLER = /^CQ\.CoreComponents\.experiencefragment\.v\d+\.actions\.edit$/;

/** The content node at `path` in the page's content tree (`root` is the tree's path), if loaded. */
function contentAt(tree: ContentNode | null, root: string, path: string): ContentNode | null {
  if (!tree || (path !== root && !path.startsWith(`${root}/`))) return null;
  let node: ContentNode | undefined = tree;
  for (const seg of path === root ? [] : path.slice(root.length + 1).split('/')) {
    const next: unknown = node?.[seg];
    node = next && typeof next === 'object' && !Array.isArray(next) ? (next as ContentNode) : undefined;
  }
  return node ?? null;
}

/**
 * What AEM's authoring page styles (`cq.authoring.page`, loaded into the page in edit mode) do to the page: an empty
 * component's `cq-placeholder` and a container's new section take room, so they can be seen, selected and dropped on.
 */
function addAuthoringStyles(d: Document): void {
  if (d.getElementById('studio-authoring-page')) return;
  const style = d.createElement('style');
  style.id = 'studio-authoring-page';
  style.textContent = [
    // containers get room above and below their content (AEM's `cq-Editable-dom--container`)
    '[data-studio-container="true"], [data-studio-container="true"].aem-GridColumn { padding-top: 1.25rem; padding-bottom: 1.25rem; }',
    '.cq-placeholder, .new { min-height: 2.875rem; }',
    '.new { margin: 1.25rem 0 1.5625rem; }',
    '.cq-placeholder.cq-marker-start { margin: 0.625rem 0 0.3125rem; }',
    '.cq-placeholder.cq-marker-end { margin: 0.3125rem 0 0.625rem; }',
    '.parsys > .cq-placeholder.cq-marker-start { margin: 0; }',
    'img.cq-placeholder { width: 100%; }',
  ].join('\n');
  // first in <head>, as AEM loads its authoring styles before the site's: the site's CSS keeps the last word
  // (WKND's grid clears `.aem-Grid-newComponent` and removes its margins)
  const head = d.head ?? d.documentElement;
  head.insertBefore(style, head.firstChild);
}

interface PanelMemory {
  children: string[];
  active: string | null;
  activeIndex: number;
}

/** Which panel each panel container of the page shows, and its panels, before the page re-renders. */
function rememberPanels(tree: EditableTree): Map<string, PanelMemory> {
  const out = new Map<string, PanelMemory>();
  for (const e of tree.all) {
    const pc = e.children.length ? panelContainerOf(e.el) : null;
    if (!pc) continue;
    const i = activePanel(pc);
    out.set(e.path, { children: e.children.map((c) => c.path), active: e.children[i]?.path ?? null, activeIndex: i });
  }
  return out;
}

/**
 * After a re-render, as Core's panel container hooks do after a change: a panel just added is shown, else the panel
 * shown before (wherever it moved), else, when it was removed, the one before it.
 */
function restorePanels(tree: EditableTree, memory: Map<string, PanelMemory>): void {
  for (const [path, m] of memory) {
    const e = tree.byPath.get(path);
    const pc = e ? panelContainerOf(e.el) : null;
    if (!e || !pc) continue;
    const paths = e.children.map((c) => c.path);
    const added = paths.findIndex((p) => !m.children.includes(p));
    const target = added >= 0 ? added : m.active && paths.includes(m.active) ? paths.indexOf(m.active) : Math.max(0, m.activeIndex - 1);
    if (target !== activePanel(pc)) navigatePanel(pc, target);
  }
}

export function App({ client, initialPage, msm: msmClient, templates: templatesClient }: Props) {
  const registry = useMemo(() => createDefaultRegistry(), []);
  const msm = useMemo(() => msmClient ?? new HttpMsmClient(), [msmClient]);
  const templates = useMemo(() => templatesClient ?? new HttpTemplateClient(), [templatesClient]);
  const [pagePath, setPagePath] = useState(initialPage);
  // In the shell the editor's page is the tab's place: Back and Forward change it, and a page the editor
  // moves to itself (a link followed in Preview, a launch, the template's initial content) is pushed.
  const tab = useTab();
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const go = useNavigate();
  // loading must not start again because the navigation function is a new one on every render of the shell
  const goRef = useRef(go);
  goRef.current = go;
  const tabPage = tab?.place.kind === 'editor' ? tab.place.path : null;
  useEffect(() => {
    if (tabPage !== null && tabPage !== pagePath) setPagePath(tabPage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabPage]);
  const [data, setData] = useState<EditorPageData | null>(null);
  const [content, setContent] = useState<ContentNode | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mode, setMode] = useState<EditorMode>('edit');
  const [openAnnotation, setOpenAnnotation] = useState<string | null>(null);
  // the notes are shown over the page in Edit mode too (the eye in the top bar hides them; remembered per browser)
  const [annotationsShown, setAnnotationsShown] = useState(() => {
    try {
      return window.localStorage.getItem(ANNOTATIONS_SHOWN) !== 'false';
    } catch {
      return true;
    }
  });
  const showAnnotations = (shown: boolean) => {
    setAnnotationsShown(shown);
    try {
      window.localStorage.setItem(ANNOTATIONS_SHOWN, String(shown));
    } catch {
      // private window: the choice lasts as long as the page
    }
  };
  // Timewarp: the moment the page is shown for (null: now)
  const [timewarp, setTimewarp] = useState<number | null>(null);
  // Developer mode: script errors the page's window reported
  const [frameErrors, setFrameErrors] = useState<string[]>([]);
  const [sidePanelOpen, setSidePanelOpen] = useState(true);
  const [panel, setPanel] = useState<PanelId>('components');
  const [emulatorOpen, setEmulatorOpen] = useState(false);
  const [device, setDevice] = useState<Device>(DEVICES[0]);
  const [orientation, setOrientation] = useState<Orientation>('portrait');
  const [zoom, setZoom] = useState<Zoom>('fit');
  const [stageWidth, setStageWidth] = useState(1200);
  const [doc, setDoc] = useState<Document | null>(null);
  const [tree, setTree] = useState<EditableTree>(EMPTY_TREE);
  const treeRef = useRef(tree);
  treeRef.current = tree;
  /** The panels the panel containers showed before the page re-rendered (see rememberPanels). */
  const panelsRef = useRef<Map<string, PanelMemory>>(new Map());
  const [selectionKey, setSelectionKey] = useState<SelectionKey | null>(null);
  // `?mode=personalization&select=<component path>` (the Activities console's link): applied once the page's editables are known
  const deepLink = useRef<URLSearchParams | null>(new URLSearchParams(window.location.search));
  useEffect(() => {
    const q = deepLink.current;
    if (!q || tree.all.length === 0) return;
    deepLink.current = null;
    const m = q.get('mode');
    if (m === 'targeting' || m === 'layout' || m === 'developer' || m === 'preview') setMode(m);
    const sel = q.get('select');
    if (sel && tree.all.some((e) => e.path === sel)) setSelectionKey({ kind: 'editable', path: sel });
  }, [tree]);
  // Group selection (AEM's Group action / Cmd-click): more selected components besides the primary one
  const [groupKeys, setGroupKeys] = useState<string[]>([]);
  const [groupMode, setGroupMode] = useState(false);
  const [history, setHistory] = useState<HistoryState>(EMPTY_HISTORY);
  const [clipboard, setClipboard] = useState<Clipboard | null>(null);
  const [dialog, setDialog] = useState<OpenDialog | null>(null);
  const [insertAt, setInsertAt] = useState<Placement | null>(null);
  const [xfConvert, setXfConvert] = useState<Editable[] | null>(null);
  const [rolloutOf, setRolloutOf] = useState<Editable | null>(null);
  const [publishing, setPublishing] = useState<'activate' | 'deactivate' | null>(null);
  // a Publish Later / Unpublish Later waiting for its time (shown in the page menu)
  const [scheduledPublication, setScheduledPublication] = useState<{ action: string; when: string } | null>(null);
  // Launches: the launch this page is in, or the launches made from it
  const launches = useMemo(() => new HttpLaunchesClient(), []);
  const [launchInfo, setLaunchInfo] = useState<LaunchInfo | null>(null);
  const [creatingLaunch, setCreatingLaunch] = useState(false);
  const [promotingLaunch, setPromotingLaunch] = useState(false);
  const replication = useMemo(() => new HttpReplicationClient(), []);
  // Targeting: the targeted component the selection is in, the experience of each target the page shows
  const targeting = useMemo(() => new HttpTargetingClient(), []);
  const [targetInfo, setTargetInfo] = useState<TargetInfo | null>(null);
  const [experienceOf, setExperienceOf] = useState<Record<string, string>>({});
  // ContextHub in Preview
  const [contextHubOpen, setContextHubOpen] = useState(false);
  const [imageEdit, setImageEdit] = useState<{ editable: Editable; src: string; initial: ImageEdits } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<Editable | null>(null);
  const [inline, setInline] = useState<(InlineSession & { path: string; title: string }) | null>(null);
  const [startingWorkflow, setStartingWorkflow] = useState(false);
  // the status bar, like AEM's: workflows the page is in, a lock by someone else, live copy
  const workflows = useMemo(() => new HttpWorkflowClient(), []);
  const [pageWorkflows, setPageWorkflows] = useState<InstanceSummary[]>([]);
  const [currentUser, setCurrentUser] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [msmOpen, setMsmOpen] = useState(false);
  const [policyTarget, setPolicyTarget] = useState<PolicyTarget | null>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef(0);

  const contentRoot = `${pagePath}/jcr:content`;
  // the template editor: the structure or initial content of an editable template
  const part = templatePart(pagePath);
  const template = part ? (data?.templateEditor ?? null) : null;
  const structureMode = part?.part === 'structure' && mode === 'edit';
  /** A structure component authors may edit on pages (`editable=true`). */
  const isUnlocked = useCallback((e: Editable) => !!template?.unlocked[e.path], [template]);
  /**
   * Initial Content, like AEM: only what the structure unlocks is edited; the initial content's own copy of a locked
   * structure component (its root, usually) is not.
   */
  const lockedInInitial = useCallback(
    (e: Editable) => {
      if (!template || part?.part !== 'initial') return false;
      const structurePath = template.structure + e.path.slice(template.initial.length);
      return template.unlocked[structurePath] === false;
    },
    [template, part],
  );
  // MSM: the page as a live copy or blueprint (page menu), and its inherited page properties
  const pageMsm = useMsmStatus(msm, pagePath);
  const pageInheritance = usePageInheritance(msm, dialog?.variant === 'page' ? dialog.path : null);
  const frameMode = mode === 'preview' || mode === 'timewarp' ? 'disabled' : 'edit';
  const annotations = useMemo(() => collectAnnotations(content, contentRoot), [content, contentRoot]);
  const experienceParams = Object.entries(experienceOf)
    .filter(([, name]) => name && name !== 'default')
    .map(([path, name]) => `&studio.experience=${encodeURIComponent(`${path}=${name}`)}`)
    .join('');
  const frameUrl = client.pageUrl(pagePath, frameMode) + (mode === 'timewarp' && timewarp !== null ? `&timewarp=${timewarp}` : '') + (frameMode === 'edit' ? experienceParams : '');
  const frameUrlRef = useRef(frameUrl);
  frameUrlRef.current = frameUrl;
  const frameModeRef = useRef(frameMode);
  frameModeRef.current = frameMode;
  const emulatedWidth = emulatorOpen ? deviceWidth(device, orientation) : null;
  const emulatedHeight = emulatorOpen ? deviceHeight(device, orientation) : null;
  const viewportWidth = emulatedWidth ?? stageWidth;
  /** How the emulated viewport is drawn: scaled down to fit the stage, or at the chosen zoom. */
  const scale = emulatedWidth ? effectiveScale(zoom, emulatedWidth, stageWidth) : 1;
  const breakpoint = breakpointFor(viewportWidth, data?.page.breakpoints ?? []);

  // ------------------------------------------------------------------------------------------ loading

  const loadData = useCallback(async () => {
    try {
      const [d, c] = await Promise.all([client.getPageData(pagePath), client.getContent(contentRoot).catch(() => null)]);
      // an experience fragment itself is only the holder of its variations and has nothing to render: the editor
      // opens its master variation, as AEM does
      if (d.page.resourceType === XF_HOLDER) {
        const variation = xfVariation(pagePath, await fetch(`${encodeURI(pagePath)}.2.json`, { credentials: 'same-origin', cache: 'no-store' }).then((r) => (r.ok ? r.json() : null), () => null));
        if (variation) return goRef.current({ kind: 'editor', path: variation }, { replace: true });
      }
      setData(d);
      setContent(c);
      setLoadError(null);
    } catch (e) {
      setData(null);
      setLoadError(e instanceof Error ? e.message : String(e));
    }
  }, [client, pagePath, contentRoot]);

  useEffect(() => {
    setSelectionKey(null);
    setGroupKeys([]);
    setGroupMode(false);
    setHistory(EMPTY_HISTORY);
    setTree(EMPTY_TREE);
    void loadData();
    const t = tabRef.current;
    if (t) {
      if (!(t.place.kind === 'editor' && t.place.path === pagePath)) t.setPlace({ kind: 'editor', path: pagePath });
    } else window.history.replaceState(null, '', `/editor.html${pagePath}.html${window.location.search}`);
  }, [loadData, pagePath]);

  useEffect(() => {
    const title = template?.title ?? data?.page.title ?? nameOf(pagePath);
    if (tabRef.current) tabRef.current.setTitle(title);
    else document.title = `${title} · Sensible Studio`;
  }, [data, pagePath, template]);

  useEffect(() => {
    client.currentUser().then(setCurrentUser, () => setCurrentUser(null));
  }, [client]);

  useEffect(() => {
    launches.info(pagePath).then(setLaunchInfo, () => setLaunchInfo(null));
  }, [launches, pagePath, data]);

  useEffect(() => {
    replication.status([pagePath]).then(
      (s) => setScheduledPublication(s[0]?.scheduled ? { action: s[0].scheduledAction ?? 'Activate', when: s[0].scheduled } : null),
      () => setScheduledPublication(null),
    );
  }, [replication, pagePath, data, publishing]);

  const loadWorkflows = useCallback(() => {
    workflows.instances('RUNNING,SUSPENDED').then(
      (all) => setPageWorkflows(all.filter((w) => w.payload === pagePath || w.payload?.startsWith(`${pagePath}/jcr:content`))),
      () => setPageWorkflows([]),
    );
  }, [workflows, pagePath]);
  useEffect(() => {
    loadWorkflows();
  }, [loadWorkflows, startingWorkflow]);

  const pageStatuses: PageStatus[] = useMemo(() => {
    const out: PageStatus[] = [];
    for (const w of pageWorkflows) {
      const step = w.current[0];
      const mine = !!step?.assignee && (step.assignee === currentUser || step.assignee === 'administrators');
      out.push({
        id: `wf-${w.id}`,
        variant: 'info',
        title: `This page is in the flow "${w.title || w.modelTitle || w.name}"`,
        text: step ? `${w.status === 'SUSPENDED' ? 'suspended at' : 'at'} the step "${step.title ?? step.name}"${step.assignee ? `, assigned to ${step.assignee}` : ''}.` : undefined,
        actions: [{ label: mine ? 'Open in Inbox' : 'View flow', onPress: () => go(mine ? '/workflow/inbox.html' : '/workflow/instances.html', { newTab: true }) }],
      });
    }
    if (data?.page.locked && data.page.lockOwner && data.page.lockOwner !== currentUser) {
      out.push({ id: 'lock', variant: 'warning', title: `This page is locked by ${data.page.lockOwner}.`, text: 'It cannot be edited until it is unlocked.' });
    }
    if (launchInfo?.launch) {
      const l = launchInfo.launch;
      out.push({
        id: 'launch',
        variant: 'info',
        title: `This page is part of the release “${l.title}”${l.liveDate ? `, live ${formatDate(l.liveDate)}` : ''}.`,
        text: launchInfo.sourcePath ? `Source: ${launchInfo.sourcePath}.` : undefined,
        actions: [
          { label: 'Go live', onPress: () => setPromotingLaunch(true) },
          ...(launchInfo.sourcePath && l.liveDate
            ? [{ label: 'Preview on the site at the live date', onPress: () => window.open(`${launchInfo.sourcePath}.html?wcmmode=disabled&timewarp=${Date.parse(l.liveDate!)}`, '_blank') }]
            : []),
          ...(launchInfo.sourcePath ? [{ label: 'Open source page', onPress: () => go({ kind: 'editor', path: launchInfo.sourcePath! }) }] : []),
        ],
      });
    }
    if (pageMsm.status?.inLiveCopy && pageMsm.status.relationship) {
      const rel = pageMsm.status.relationship;
      out.push({
        id: 'livecopy',
        variant: 'info',
        title: rel.cancelled || rel.status === 'suspended' ? 'This page is a linked copy whose inheritance is suspended.' : 'This page is a linked copy.',
        text: `Blueprint: ${rel.sourcePath}${rel.lastRolledOut ? `, synced ${relativeTimeOf(rel.lastRolledOut)}` : ''}.`,
        actions: [{ label: 'Linked copy Status', onPress: () => setMsmOpen(true) }],
      });
    }
    return out;
  }, [pageWorkflows, currentUser, data, pageMsm.status, launchInfo]);

  useEffect(() => {
    const el = stageRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setStageWidth(el.clientWidth));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  /**
   * Re-renders only what a change touched, like AEM's `REFRESH_SELF` / `REFRESH_PARENT` listeners: the page is
   * rendered again on the server (one request, no scripts, styles or images) and the element at `scope` is swapped
   * for its fresh rendering, so the rest of the page, its scroll position, loaded images and the state of its
   * components stay. Falls back to a full reload when the scope is not an element of the page (page properties, a
   * container that did not exist yet) or the swap cannot be done. Panels of Tabs/Carousel/Accordion inside the
   * scope are restored as after a full reload.
   */
  const refresh = useCallback(
    async (scope: string | null) => {
      const frame = frameRef.current;
      const d = frame?.contentDocument;
      if (!d?.body || !scope || frameModeRef.current !== 'edit') {
        reloadFrame();
        return;
      }
      const selector = `[data-studio-path="${CSS.escape(scope)}"]`;
      const old = d.querySelector(selector);
      if (!old) {
        reloadFrame();
        return;
      }
      try {
        const res = await fetch(frameUrlRef.current, { credentials: 'same-origin', cache: 'no-store' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const fresh = new DOMParser().parseFromString(await res.text(), 'text/html');
        const next = fresh.querySelector(selector);
        if (!next || !old.isConnected) throw new Error('scope not rendered');
        const memory = rememberPanels(treeRef.current);
        old.replaceWith(d.importNode(next, true));
        const scanned = scanEditables(d);
        setTree(scanned);
        restorePanels(scanned, memory);
      } catch {
        reloadFrame();
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  /** Re-renders the whole page in place, keeping the scroll position and the panel each panel container shows. */
  const reloadFrame = useCallback(() => {
    const win = frameRef.current?.contentWindow;
    if (!win) return;
    scrollRef.current = win.scrollY;
    panelsRef.current = rememberPanels(treeRef.current);
    win.location.reload();
  }, []);

  const onFrameLoad = () => {
    const frame = frameRef.current;
    const d = frame?.contentDocument ?? null;
    const win = frame?.contentWindow;
    if (!d || !win) return;
    if (mode === 'preview') {
      // following a link in Preview changes the page the editor is on
      const m = win.location.pathname.match(/^(\/content\/.+?)\.html$/);
      if (m && m[1] !== pagePath) {
        setPagePath(m[1]);
        return;
      }
    }
    if (scrollRef.current) {
      win.scrollTo(0, scrollRef.current);
      scrollRef.current = 0;
    }
    if (frameMode === 'edit') addAuthoringStyles(d);
    setDoc(d);
    const scanned = scanEditables(d);
    setTree(scanned);
    restorePanels(scanned, panelsRef.current);
    panelsRef.current = new Map();
    setFrameErrors([]);
    win.addEventListener('error', (ev) => setFrameErrors((prev) => [...prev, `${ev.message} (${ev.filename?.split('/').pop() ?? ''}:${ev.lineno})`].slice(-50)));
    win.addEventListener('unhandledrejection', (ev) => setFrameErrors((prev) => [...prev, `Unhandled promise rejection: ${String((ev as PromiseRejectionEvent).reason)}`].slice(-50)));
  };

  // components that render late (client-side) or reflow: rescan when the DOM structure changes
  useEffect(() => {
    if (!doc?.body || mode === 'preview') return;
    let timer = 0;
    const mo = new MutationObserver((records) => {
      if (!records.some((r) => r.type === 'childList')) return;
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setTree(scanEditables(doc)), 250);
    });
    mo.observe(doc.body, { subtree: true, childList: true });
    return () => {
      mo.disconnect();
      window.clearTimeout(timer);
    };
  }, [doc, mode]);

  // ------------------------------------------------------------------------------------------ selection

  const selection: Target | null = useMemo(() => {
    if (!selectionKey) return null;
    const e = tree.byPath.get(selectionKey.path);
    if (!e) return null;
    if (selectionKey.kind === 'placeholder') return e.placeholder ? { kind: 'placeholder', container: e } : null;
    return { kind: 'editable', editable: e };
  }, [selectionKey, tree]);

  const group: Editable[] = useMemo(
    () => groupKeys.map((p) => tree.byPath.get(p)).filter((e): e is Editable => !!e && e.path !== selectionKey?.path),
    [groupKeys, tree, selectionKey],
  );
  /** The primary selection and the group: everything a group action applies to. */
  const selected: Editable[] = useMemo(() => (selection?.kind === 'editable' ? [selection.editable, ...group] : group), [selection, group]);

  // Targeting mode: the targeted component the selection is in (its experiences fill the side panel)
  useEffect(() => {
    if (mode !== 'targeting') {
      setTargetInfo(null);
      setExperienceOf({});
      return;
    }
    let t: Editable | null = selection?.kind === 'editable' ? selection.editable : null;
    while (t && t.type !== 'cq/personalization/components/target') t = t.parent;
    if (!t) {
      setTargetInfo(null);
      return;
    }
    const path = t.path;
    let live = true;
    targeting.target(path).then(
      (info) => live && setTargetInfo(info.targeted ? info : null),
      () => live && setTargetInfo(null),
    );
    return () => {
      live = false;
    };
  }, [mode, selection, targeting, tree]);

  const select = (t: Target | null, toggle = false) => {
    // in the structure, the slot of an unlocked container is the container (it shows its allowed components)
    if (t?.kind === 'placeholder' && structureMode && isUnlocked(t.container)) t = { kind: 'editable', editable: t.container };
    if (t?.kind === 'placeholder' && lockedInInitial(t.container)) t = null;
    if (toggle || (groupMode && t?.kind === 'editable')) {
      if (t?.kind !== 'editable') return;
      const path = t.editable.path;
      const current = selectionKey?.kind === 'editable' ? selectionKey.path : null;
      if (path === current) {
        // deselecting the primary one: the latest of the group takes its place
        const rest = groupKeys.filter((p) => p !== path);
        setSelectionKey(rest.length ? { kind: 'editable', path: rest[rest.length - 1] } : null);
        setGroupKeys(rest.slice(0, -1));
      } else if (groupKeys.includes(path)) {
        setGroupKeys(groupKeys.filter((p) => p !== path));
      } else {
        setGroupKeys(current ? [...groupKeys.filter((p) => p !== current), current] : groupKeys);
        setSelectionKey({ kind: 'editable', path });
      }
      return;
    }
    setGroupKeys([]);
    setGroupMode(false);
    setSelectionKey(t ? { kind: t.kind, path: t.kind === 'editable' ? t.editable.path : t.container.path } : null);
  };

  const selectPath = (path: string) => {
    const e = tree.byPath.get(path);
    if (!e) return;
    setSelectionKey({ kind: 'editable', path });
    revealInPanels(e.el);
    e.el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  };

  // A selected component inside a panel container (Tabs, Carousel, Accordion) is shown, like AEM's content tree
  // does: the panels holding it open, so what the content tree, Parent, or an insert selected can be seen and edited.
  // Keyed on the selection itself (and a re-rendered page), not on every rescan, so that showing another panel of
  // the same container by hand is not undone.
  useEffect(() => {
    if (!selectionKey || mode === 'preview' || mode === 'timewarp') return;
    const e = tree.byPath.get(selectionKey.path);
    if (e) revealInPanels(selectionKey.kind === 'placeholder' && e.placeholder ? e.placeholder : e.el);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectionKey, doc]);

  // ------------------------------------------------------------------------------------------ components and policies

  const infoOf = useCallback(
    (type: string): ComponentInfo | undefined => data?.components[type],
    [data],
  );
  const allowedIn = useCallback((container: Editable): string[] => data?.containers[container.path] ?? [], [data]);

  const pageComponents = useMemo(() => {
    if (!data) return [];
    const types = new Set(Object.values(data.containers).flat());
    return [...types].map((t) => data.components[t]).filter((c): c is ComponentInfo => !!c);
  }, [data]);

  /** An allowed component of `container` that takes an asset of this mime type (e.g. Image for image/*). */
  const componentForAsset = (container: Editable, asset: AssetInfo): ComponentInfo | undefined => {
    const allowedTypes = allowedIn(container);
    // the container policy's asset-to-component mapping decides, like in AEM (image/* → Image)
    for (const m of data?.assetMappings?.[container.path] ?? []) {
      const c = infoOf(m.resourceType);
      // …as long as that component takes the file itself: a mapping of image/* must not hand an Image component a
      // kind of image it does not accept (an image set, which only some components can show)
      if (c?.drop && allowedTypes.includes(m.resourceType) && accepts(m.mimeType.replace(/\*/g, '.*'), asset.mimeType) && accepts(c.dropAccept, asset.mimeType))
        return c;
    }
    const candidates = allowedTypes.map((t) => infoOf(t)).filter((c): c is ComponentInfo => !!c?.drop && accepts(c.dropAccept, asset.mimeType));
    // without a mapping prefer a component named after the media type (…/image for images)
    const media = asset.mimeType.split('/')[0];
    return candidates.find((c) => nameOf(c.resourceType) === media) ?? candidates[0];
  };

  const allowed = (container: Editable, payload: DropPayload): boolean => {
    // the structure of an unlocked container is its policy: its content is added in Initial Content and on pages
    if (structureMode && isUnlocked(container)) return false;
    if (lockedInInitial(container)) return false;
    switch (payload.kind) {
      case 'component':
        return allowedIn(container).includes(payload.component.resourceType);
      case 'move':
        // an inherited component stays where the blueprint has it
        return payload.editable.msm !== 'inherited' && allowedIn(container).includes(payload.editable.type);
      case 'asset':
        // `container` is either a component with a drop target or a real container
        if (container.drop && !container.container) return accepts(container.dropAccept, payload.asset.mimeType);
        return container.container && !!componentForAsset(container, payload.asset);
    }
  };

  // ------------------------------------------------------------------------------------------ annotations

  /**
   * The Annotations button: a new note, open for typing, on the selected component (at its visible top left corner)
   * or, with nothing selected, on the component in the middle of the view. The marker can then be dragged anywhere.
   */
  const createAnnotation = async () => {
    const win = doc?.defaultView;
    if (!win) return;
    let target: Editable | null = selection?.kind === 'editable' ? selection.editable : null;
    let at: { x: number; y: number } | null = null;
    if (target) {
      const b = boxOf(target.el);
      if (b) at = { x: Math.max(b.left, 0) + 16, y: Math.max(b.top, 0) + 16 };
    } else {
      const p = { x: win.innerWidth / 2, y: win.innerHeight / 3 };
      const hit = hitTest(tree, p.x, p.y);
      if (hit?.kind === 'editable') {
        target = hit.editable;
        at = p;
      }
    }
    const box = target ? boxOf(target.el) : null;
    if (!target || !at || !box) {
      ToastQueue.info('Select a component to annotate', { timeout: 4000 });
      return;
    }
    // several notes on one component: each new one a little lower, not on top of the last
    const n = annotations.filter((a) => a.component === target.path).length;
    const x = Math.round(at.x - box.left);
    const y = Math.round(Math.min(at.y - box.top + n * 30, Math.max(0, box.height - 24)));
    const path = `${target.path}/cq:annotations/${annotationName(n)}`;
    try {
      await client.post(path, createEntries(x, y));
      showAnnotations(true);
      await loadData();
      setOpenAnnotation(path);
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const changeAnnotation = async (a: Annotation, changes: { text?: string; color?: string }) => {
    const entries: PostEntry[] = [];
    if (changes.text !== undefined) entries.push({ name: 'text', value: changes.text });
    if (changes.color !== undefined) entries.push({ name: 'color', value: changes.color });
    if (!entries.length) return;
    try {
      await client.post(a.path, entries);
      await loadData();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  /** A marker dragged to another spot, maybe onto another component: the note moves there, its arrows follow. */
  const moveAnnotation = async (a: Annotation, to: AnnotationMove) => {
    try {
      let path = a.path;
      if (to.component !== a.component) {
        const container = `${to.component}/cq:annotations`;
        await client.post(container, [{ name: 'jcr:primaryType', value: 'nt:unstructured' }]);
        path = await client.moveResource(a.path, { container });
        // the last note of its component: no empty cq:annotations left behind
        if (!annotations.some((o) => o.component === a.component && o.path !== a.path)) await client.deleteResource(`${a.component}/cq:annotations`);
      }
      await client.post(path, [
        { name: 'x', value: String(to.x) },
        { name: 'x@TypeHint', value: 'Long' },
        { name: 'y', value: String(to.y) },
        { name: 'y@TypeHint', value: 'Long' },
      ]);
      for (const { arrow, points } of to.arrows) {
        await client.post(path + arrow.path.slice(a.path.length), [{ name: 'points', value: formatPoints(points) }]);
      }
      if (openAnnotation === a.path) setOpenAnnotation(path);
      await loadData();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const addArrow = async (a: Annotation, points: Point[]) => {
    try {
      await client.post(`${a.path}/${SKETCHES}/${arrowName()}`, arrowEntries(points));
      await loadData();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const deleteArrow = async (arrow: Arrow) => {
    try {
      await client.deleteResource(arrow.path);
      await loadData();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const deleteAnnotation = async (a: Annotation) => {
    try {
      await client.deleteResource(a.path);
      setOpenAnnotation(null);
      await loadData();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  // ------------------------------------------------------------------------------------------ changes

  /**
   * Runs a change, then re-renders the page. With `track`, the node at `track.scope` is snapshotted
   * before and after, so the change can be undone and redone.
   */
  const run = async (work: () => Promise<string | void>, after?: (result: string | void) => void, track?: { label: string; scope: string | null }) => {
    setBusy(true);
    try {
      const before = track?.scope ? await client.snapshot(track.scope) : null;
      const result = await work();
      if (track?.scope && before) {
        const afterSnap = await client.snapshot(track.scope);
        setHistory((h) => pushHistory(h, { label: track.label, scope: track.scope!, before, after: afterSnap }));
      }
      await refresh(track?.scope ?? null);
      void loadData();
      pageMsm.reload();
      after?.(result);
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(false);
    }
  };

  const travel = async (direction: 'undo' | 'redo') => {
    const stack = direction === 'undo' ? history.undo : history.redo;
    if (!stack.length || busy) return;
    setBusy(true);
    try {
      const r = await (direction === 'undo' ? undoHistory(client, history) : redoHistory(client, history));
      if (r) {
        setHistory(r.state);
        setSelectionKey(null);
        setGroupKeys([]);
        await refresh(r.entry.scope);
        void loadData();
        ToastQueue.info(`${direction === 'undo' ? 'Undo' : 'Redo'}: ${r.entry.label}`, { timeout: 2500 });
      }
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(false);
    }
  };
  const undo = () => travel('undo');
  const redo = () => travel('redo');

  // ------------------------------------------------------------------------------------------ in-place editing

  /** Starts editing a title or text in the page itself (AEM's Edit action), when the component allows it. */
  const startInline = (e: Editable): boolean => {
    const info = infoOf(e.type);
    if (e.msm === 'inherited') return false;
    if ((info?.inplace?.editorType ?? '').toLowerCase() === 'image' && hasInplaceEditor(info)) {
      void openImageEditor(e);
      return true;
    }
    const editing = inplaceEditing(info);
    if (!editing) return false;
    const el = inplaceTarget(e, editing, infoOf(e.type));
    if (!el) return false;
    setSelectionKey({ kind: 'editable', path: e.path });
    setGroupKeys([]);
    setInline({ el, editing, path: e.path, title: e.title });
    return true;
  };

  /** AEM's image editor: crop, rotate and flip, stored on the component for the image servlet. */
  const openImageEditor = async (e: Editable) => {
    setBusy(true);
    try {
      const c = (await client.getContent(e.path)) as Record<string, unknown>;
      const ref = typeof c.fileReference === 'string' ? c.fileReference : null;
      if (!ref) {
        ToastQueue.info(`${e.title} has no image yet: configure it first`, { timeout: 4000 });
        return;
      }
      setImageEdit({ editable: e, src: `${client.assetUrl(ref)}/_jcr_content/renditions/cq5dam.web.1280.1280.jpeg`, initial: readImageEdits(c) });
    } catch (err) {
      ToastQueue.negative(err instanceof Error ? err.message : String(err), { timeout: 6000 });
    } finally {
      setBusy(false);
    }
  };

  useInlineEdit({
    session: inline,
    onEnd: (end) => {
      const session = inline;
      setInline(null);
      if (!session || end.reason === 'cancel' || !end.changed) return;
      const entries: PostEntry[] = [{ name: `./${session.editing.property}`, value: end.value }];
      if (session.editing.kind === 'text') entries.push({ name: './textIsRich', value: 'true' });
      void run(
        () => client.post(session.path, entries),
        () => ToastQueue.positive(`${session.title} saved`, { timeout: 3000 }),
        { label: `edit ${session.title}`, scope: session.path },
      );
    },
  });

  const openDialog = async (path: string, resourceType: string, title: string, variant: OpenDialog['variant']) => {
    setBusy(true);
    try {
      const [response, c] = await Promise.all([client.getDialog(resourceType, 'cq:dialog', path), client.getContent(path)]);
      if (!response.root) {
        ToastQueue.info(`${title} has no dialog. ${response.warnings.join(' ')}`.trim(), { timeout: 6000 });
        return;
      }
      setDialog({ title, path, dialog: response as DialogResponse & { root: GraniteNode }, content: c, variant });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(false);
    }
  };

  const saveDialog = async (entries: PostEntry[]) => {
    if (!dialog) return;
    const { title, path, variant } = dialog;
    // page properties are the jcr:content node itself: not restored wholesale, so not in the history
    const scope = variant === 'component' ? scopeFor([path]) : null;
    const before = scope ? await client.snapshot(scope) : null;
    await client.post(path, entries);
    if (scope && before) {
      const after = await client.snapshot(scope);
      setHistory((h) => pushHistory(h, { label: `configure ${title}`, scope, before, after }));
    }
    setDialog(null);
    await refresh(scope);
    void loadData();
    ToastQueue.positive(`${title} saved`, { timeout: 3000 });
  };

  /**
   * An unlocked container of the template the page has no content for yet renders at the page's path but does not
   * exist: create it (with the template's type) before the first component goes in.
   */
  const ensureContainer = async (path: string) => {
    const e = tree.byPath.get(path);
    if (!e || !e.type) return;
    try {
      await client.getContent(path);
    } catch {
      await client.post(path, [
        { name: './jcr:primaryType', value: 'nt:unstructured' },
        { name: './sling:resourceType', value: e.type },
      ]);
    }
  };

  const insert = (component: ComponentInfo, at: Placement, properties?: PostEntry[]) =>
    run(
      async () => {
        await ensureContainer(at.container);
        return client.createComponent(component.resourceType, at, properties);
      },
      (path) => {
        if (path) setSelectionKey({ kind: 'editable', path });
        ToastQueue.positive(`${component.title} added`, { timeout: 3000 });
      },
      { label: `insert ${component.title}`, scope: scopeFor([at.container]) },
    );

  const paste = (at: Placement) => {
    const clip = clipboard;
    if (!clip) return;
    const label = clip.items.length === 1 ? clip.items[0].title : `${clip.items.length} components`;
    void run(
      async () => {
        await ensureContainer(at.container);
        // pasting each item before the same sibling keeps their order
        let last: string | undefined;
        for (const item of clip.items) last = await (clip.mode === 'copy' ? client.copyResource(item.path, at) : client.moveResource(item.path, at));
        return last;
      },
      (path) => {
        if (clip.mode === 'cut') setClipboard(null);
        setGroupKeys([]);
        if (path) setSelectionKey({ kind: 'editable', path });
      },
      { label: `paste ${label}`, scope: scopeFor([at.container, ...clip.items.map((i) => parentPath(i.path))]) },
    );
  };

  /** Deletes the components; with a group, all of them (AEM's group delete). */
  const remove = (items: Editable[]) => {
    setConfirmDelete(null);
    const label = items.length === 1 ? items[0].title : `${items.length} components`;
    void run(
      async () => {
        for (const e of items) await client.deleteResource(e.path);
      },
      () => {
        setSelectionKey(null);
        setGroupKeys([]);
        ToastQueue.positive(`${label} deleted`, { timeout: 3000 });
      },
      { label: `delete ${label}`, scope: scopeFor(items.map((e) => parentPath(e.path))) },
    );
  };

  const onDrop = (payload: DropPayload, target: DropTarget) => {
    if (payload.kind === 'component') {
      void insert(payload.component, target.placement);
    } else if (payload.kind === 'move') {
      void run(
        async () => {
          await ensureContainer(target.placement.container);
          return client.moveResource(payload.editable.path, target.placement);
        },
        (path) => path && setSelectionKey({ kind: 'editable', path }),
        { label: `move ${payload.editable.title}`, scope: scopeFor([parentPath(payload.editable.path), target.placement.container]) },
      );
    } else if (target.onto?.drop) {
      const onto = target.onto;
      void run(
        () => client.post(onto.path, [{ name: onto.drop!, value: payload.asset.path }]),
        () => setSelectionKey({ kind: 'editable', path: onto.path }),
        { label: `drop file on ${onto.title}`, scope: scopeFor([onto.path]) },
      );
    } else {
      const container = tree.byPath.get(target.placement.container);
      const component = container && componentForAsset(container, payload.asset);
      if (component?.drop) void insert(component, target.placement, [{ name: component.drop, value: payload.asset.path }]);
    }
  };

  const onColumnChange = (e: Editable, state: ColumnState) => {
    void run(() => client.setResponsive(e.path, breakpoint, { width: state.width, offset: state.offset }), undefined, {
      label: `resize ${e.title}`,
      scope: scopeFor([e.path]),
    });
  };

  /** Placement "before this component" for toolbar Insert/Paste on a component. */
  const before = (e: Editable): Placement | null => {
    const c = containerOf(e);
    return c ? { container: c.path, before: nameOf(e.path) } : null;
  };

  const toolbarActions = (t: Target): ToolbarAction[] => {
    if (t.kind === 'placeholder') {
      if (lockedInInitial(t.container)) return [];
      return clipboard && canPasteInto(t.container) ? ['insert', 'paste'] : ['insert'];
    }
    const e = t.editable;
    const c = containerOf(e);
    if (mode === 'layout') {
      const out: ToolbarAction[] = [];
      if (e.parent) out.push('parent');
      if (c?.grid) out.push('newline', 'hide');
      if (e.container && e.grid) out.push('unhide', 'reset');
      return out;
    }
    if (lockedInInitial(e)) return e.parent ? ['parent'] : [];
    if (mode === 'targeting') {
      // Targeting, like AEM: a component gets targeted; inside a target, the experience is edited as usual
      const t: ToolbarAction[] = [];
      if (isTarget(e)) t.push('untarget');
      else if (targetAncestor(e)) {
        if (hasInplaceEditor(infoOf(e.type))) t.push('edit');
        if (e.dialog) t.push('configure');
        if (data?.styled?.includes(e.path)) t.push('styles');
      } else if (c && e.msm !== 'inherited') t.push('target');
      if (e.parent) t.push('parent');
      return t;
    }
    if (structureMode) {
      // the template structure, like AEM: an unlocked component is configured by authors on their pages
      const open = isUnlocked(e);
      const s: ToolbarAction[] = [];
      if (!open && opensXf(e)) s.push('editXf');
      if (!open && e.dialog) s.push('configure');
      if (!open && data?.styled?.includes(e.path)) s.push('styles');
      if (!open && hasPanels(e)) s.push('panelSelect');
      if (!open) s.push('copy');
      if (c && !open) s.push('cut');
      if (c) s.push('delete');
      if (c && !open && clipboard && canPasteInto(c) && !clipboard.items.some((i) => i.path === e.path)) s.push('paste');
      if (c) s.push('insert');
      s.push('policy', open ? 'lockStructure' : 'unlockStructure');
      if (e.parent) s.push('parent');
      if (c?.grid) s.push('layout');
      return s;
    }
    const out: ToolbarAction[] = [];
    if (group.length > 0 || groupMode) {
      // a group: what AEM offers for several components at once
      const all = [e, ...group];
      out.push('copy');
      if (all.every((x) => containerOf(x) && x.msm !== 'inherited')) out.push('cut', 'delete');
      out.push('group');
      return out;
    }
    // MSM, like AEM: an inherited component is read-only until its inheritance is cancelled
    const inherited = e.msm === 'inherited';
    if (inherited) out.push('cancelInheritance');
    if (e.msm === 'cancelled') out.push('reenableInheritance');
    if (!inherited && hasInplaceEditor(infoOf(e.type))) out.push('edit');
    if (opensXf(e)) out.push('editXf');
    if (e.dialog && !inherited) out.push('configure');
    if (!inherited && data?.styled?.includes(e.path)) out.push('styles');
    if (hasPanels(e)) out.push('panelSelect');
    out.push('copy');
    if (c && !inherited) out.push('cut', 'delete');
    if (c && clipboard && canPasteInto(c) && !clipboard.items.some((i) => i.path === e.path)) out.push('paste');
    if (c) out.push('insert');
    if (c) out.push('group');
    // MSM, like AEM: on a blueprint page every component can be rolled out on its own
    if (pageMsm.status?.isSource && pageMsm.status.liveCopies?.length) out.push('rollout');
    // AEM's order from here: Parent, Convert to block variant, Layout
    if (e.parent) out.push('parent');
    if (c && !isXf(e)) out.push('xfconvert');
    if (c?.grid) out.push('layout');
    return out;
  };

  /** Core's panel selector condition: a panel container (Tabs, Accordion, Carousel) with at least one panel. */
  const hasPanels = (e: Editable) => e.children.length > 0 && !!panelContainerOf(e.el);

  /** The panels of a container for the panel selector: the component of each, and its panel title. */
  const panelEntries = (e: Editable) =>
    e.children.map((c) => {
      const node = contentAt(content, contentRoot, c.path);
      const sub = node?.['cq:panelTitle'] ?? node?.['jcr:title'];
      return { name: nameOf(c.path), title: c.title, subtitle: typeof sub === 'string' && sub ? sub : undefined };
    });

  /** Core's Block "Edit" (`cq:actionConfigs/editInNewTab`): the component opens its record's variation. */
  const opensXf = (e: Editable) => !!infoOf(e.type)?.actionConfigs?.some((a) => XF_EDIT_HANDLER.test(a.handler ?? ''));

  /** As AEM's handler: the record's variation, in the editor, in a new tab (this tab when pop-ups are blocked). */
  const editXf = async (e: Editable) => {
    const open = (variation: string) => {
      go({ kind: 'editor', path: variation.replace(/\/$/, '') }, { newTab: true });
    };
    // the page's content is loaded: read the variation now, while the click still allows a new tab
    const known = contentAt(content, contentRoot, e.path)?.fragmentVariationPath;
    if (typeof known === 'string' && known) {
      open(known);
      return;
    }
    try {
      const c = (await client.getContent(e.path)) as Record<string, unknown>;
      if (typeof c.fragmentVariationPath === 'string' && c.fragmentVariationPath) open(c.fragmentVariationPath);
      else ToastQueue.info("This experience fragment component doesn't have an associated variant", { timeout: 5000 });
    } catch (err) {
      ToastQueue.negative(err instanceof Error ? err.message : String(err), { timeout: 6000 });
    }
  };

  const canPasteInto = (c: Editable) => !!clipboard && clipboard.items.every((i) => allowedIn(c).includes(i.type));

  const TARGET_TYPE = 'cq/personalization/components/target';
  const isTarget = (e: Editable) => e.type === TARGET_TYPE;
  /** The targeted component an editable is (or sits inside). */
  const targetAncestor = (e: Editable): Editable | null => {
    for (let x: Editable | null = e; x; x = x.parent) if (isTarget(x)) return x;
    return null;
  };

  /** An Experience Fragment component the container allows (the project's, over Core's). */
  const isXf = (e: Editable) => /experiencefragment/.test(e.type) || !!infoOf(e.type)?.superTypes?.some((s) => /experiencefragment/.test(s.resourceType));
  const isXfType = (t: string) => /\/experiencefragment$/.test(t) || !!infoOf(t)?.superTypes?.some((s) => /components\/experiencefragment\/v\d+\/experiencefragment$/.test(s.resourceType));
  /** The container's own Block component, else the project's (any policy), else Core's. */
  const xfComponentFor = (c: Editable): string =>
    allowedIn(c).find(isXfType) ?? Object.keys(data?.components ?? {}).find(isXfType) ?? 'core/wcm/components/experiencefragment/v2/experiencefragment';

  const onToolbar = (t: Target, action: ToolbarAction) => {
    if (t.kind === 'placeholder') {
      const at = { container: t.container.path, before: null };
      if (action === 'insert') setInsertAt(at);
      if (action === 'paste') paste(at);
      return;
    }
    const e = t.editable;
    const grid = containerOf(e)?.grid ?? null;
    const col = columnState(e.el, breakpoint, gridColumns(grid));
    switch (action) {
      case 'edit':
        if (!startInline(e)) void openDialog(e.path, e.type, e.title, 'component');
        break;
      case 'configure':
        void openDialog(e.path, e.type, e.title, 'component');
        break;
      case 'styles':
      case 'panelSelect':
        break; // rendered as their own popovers
      case 'copy':
      case 'cut': {
        const items = selected.length ? selected : [e];
        setClipboard({ mode: action, items: items.map((x) => ({ path: x.path, type: x.type, title: x.title })) });
        const what = items.length === 1 ? items[0].title : `${items.length} components`;
        ToastQueue.info(`${what} ${action === 'copy' ? 'copied' : 'cut'}: paste before a component or into a container`, { timeout: 3000 });
        break;
      }
      case 'delete':
        setConfirmDelete(e);
        break;
      case 'editXf':
        void editXf(e);
        break;
      case 'xfconvert':
        setXfConvert(selected.length ? selected : [e]);
        break;
      case 'rollout':
        setRolloutOf(e);
        break;
      case 'target':
        void run(
          async () => {
            setTargetInfo(await targeting.targetComponent(e.path));
          },
          () => ToastQueue.info(`${e.title} is personalized: add an experience per audience in the side panel`, { timeout: 4000 }),
          { label: `target ${e.title}`, scope: scopeFor([e.path]) },
        );
        break;
      case 'untarget':
        void run(
          async () => {
            await targeting.untarget(e.path, experienceOf[e.path]);
            setTargetInfo(null);
            setExperienceOf((m) => {
              const n = { ...m };
              delete n[e.path];
              return n;
            });
          },
          undefined,
          { label: `remove personalization of ${e.title}`, scope: scopeFor([e.path]) },
        );
        break;
      case 'group':
        if (group.length > 0 || groupMode) {
          setGroupKeys([]);
          setGroupMode(false);
        } else {
          setGroupMode(true);
        }
        break;
      case 'paste': {
        const at = before(e);
        if (at) paste(at);
        break;
      }
      case 'insert':
        setInsertAt(before(e));
        break;
      case 'parent':
        if (e.parent) setSelectionKey({ kind: 'editable', path: e.parent.path });
        break;
      case 'layout':
        setMode('layout');
        break;
      case 'newline':
        void run(() => client.setResponsive(e.path, breakpoint, { width: col.width, behavior: col.newline ? null : 'newline' }), undefined, {
          label: `float ${e.title}`,
          scope: scopeFor([e.path]),
        });
        break;
      case 'hide':
        void run(() => client.setResponsive(e.path, breakpoint, { width: col.width, behavior: 'hide' }), () => setSelectionKey(null), {
          label: `hide ${e.title}`,
          scope: scopeFor([e.path]),
        });
        break;
      case 'unhide': {
        const hidden = e.children.filter((ch) => columnState(ch.el, breakpoint).hidden || !ch.el.offsetParent);
        void run(
          async () => {
            for (const ch of hidden) await client.setResponsive(ch.path, breakpoint, { behavior: null });
          },
          undefined,
          { label: 'show hidden components', scope: scopeFor([e.path]) },
        );
        break;
      }
      case 'reset':
        void run(() => client.resetResponsive(e.children.map((ch) => ch.path), breakpoint), undefined, {
          label: `revert layout of ${e.title}`,
          scope: scopeFor([e.path]),
        });
        break;
      case 'cancelInheritance':
        void run(
          async () => void (await msm.inheritance(e.path, 'cancel', { deep: true })),
          () => ToastQueue.info(`${e.title}: inheritance cancelled, the component can be edited`, { timeout: 4000 }),
        );
        break;
      case 'policy':
        setPolicyTarget({ path: e.path, type: e.type, title: e.title });
        break;
      case 'lockStructure':
      case 'unlockStructure':
        void run(
          () => templates.setLocked(e.path, action === 'lockStructure'),
          () =>
            ToastQueue.info(
              action === 'lockStructure'
                ? `${e.title} is locked: pages show it as the template has it`
                : `${e.title} is unlocked: authors edit it on their pages`,
              { timeout: 4000 },
            ),
        );
        break;
      case 'reenableInheritance':
        void run(
          async () => void (await msm.inheritance(e.path, 'reenable')),
          () => ToastQueue.positive(`${e.title}: inheritance re-enabled and synchronized with the source`, { timeout: 4000 }),
        );
        break;
    }
  };

  const activeToolbar = (t: Target): ToolbarAction[] => {
    const out: ToolbarAction[] = [];
    if (t.kind === 'editable' && mode === 'layout' && columnState(t.editable.el, breakpoint).newline) out.push('newline');
    if (group.length > 0 || groupMode) out.push('group');
    return out;
  };

  // ------------------------------------------------------------------------------------------ page menu and keys

  const onPageAction = (action: PageMenuAction, argument?: string) => {
    const page = data?.page;
    switch (action) {
      case 'properties':
        // like AEM: the page properties are their own screen; Cancel / Save & Close come back here
        if (page?.resourceType) go(propertiesUrl(pagePath));
        break;
      case 'editTemplate':
        // AEM opens the template's structure in the template editor, in a new tab (this one when pop-ups are blocked)
        if (page?.template) {
          go(templateEditorUrl(page.template), { newTab: true });
        }
        break;
      case 'lock':
      case 'unlock':
        void client
          .setPageLock(pagePath, action === 'lock')
          .then(() => loadData())
          .then(() => ToastQueue.positive(action === 'lock' ? 'Page locked' : 'Page unlocked', { timeout: 3000 }))
          .catch((e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }));
        break;
      case 'published':
        window.open(client.pageUrl(pagePath, 'disabled'), '_blank', 'noopener');
        break;
      case 'publish':
        setPublishing('activate');
        break;
      case 'unpublish':
        setPublishing('deactivate');
        break;
      case 'reload':
        reloadFrame();
        void loadData();
        break;
      case 'start':
        go({ kind: 'sites', path: pagePath.slice(0, pagePath.lastIndexOf('/')) });
        break;
      case 'workflow':
        setStartingWorkflow(true);
        break;
      case 'createLaunch':
        setCreatingLaunch(true);
        break;
      case 'promoteLaunch':
        setPromotingLaunch(true);
        break;
      case 'openLaunch': {
        const l = launchInfo?.launchesOfPage.find((x) => x.path === argument);
        if (l?.pageInLaunch) go({ kind: 'editor', path: l.pageInLaunch });
        break;
      }
      case 'openLaunchSource':
        if (launchInfo?.sourcePath) go({ kind: 'editor', path: launchInfo.sourcePath });
        break;
      case 'msm':
        setMsmOpen(true);
        break;
      case 'initialProperties':
        if (template) go(propertiesUrl(template.initial));
        break;
      case 'pagePolicy':
        if (template && data?.page.resourceType) {
          setPolicyTarget({ path: `${template.structure}/jcr:content`, type: data.page.resourceType, title: 'Page' });
        }
        break;
      case 'enableTemplate':
      case 'disableTemplate':
        if (template) {
          const status = action === 'enableTemplate' ? 'enabled' : 'disabled';
          void run(
            async () => void (await templates.setStatus(template.path, status)),
            () => ToastQueue.positive(status === 'enabled' ? 'Template enabled: pages can be created with it' : 'Template disabled', { timeout: 4000 }),
          );
        }
        break;
      case 'sync':
        void run(async () => {
          const result = await msm.sync(pagePath, { deep: false });
          ToastQueue.positive(`Synchronized: ${describeResult(result)}`, { timeout: 5000 });
        });
        break;
    }
  };

  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (tabRef.current && !tabRef.current.active) return; // another tab is in front
      if (dialog || insertAt || confirmDelete || msmOpen || inline || policyTarget || imageEdit || xfConvert || rolloutOf || publishing || mode === 'preview') return;
      const t = ev.target as HTMLElement | null;
      if (t && (t.closest('input, textarea, select, [contenteditable="true"]') || t.closest('[role="dialog"]'))) return;
      const sel = selection;
      const mod = ev.metaKey || ev.ctrlKey;
      if (mod && ev.key.toLowerCase() === 'z') {
        ev.preventDefault();
        void (ev.shiftKey ? redo() : undo());
        return;
      }
      if (mod && ev.key.toLowerCase() === 'y') {
        ev.preventDefault();
        void redo();
        return;
      }
      if (ev.key === 'Escape') {
        setSelectionKey(null);
        setGroupKeys([]);
        setGroupMode(false);
      }
      if (!sel) return;
      if (sel.kind === 'editable' && (ev.key === 'Delete' || ev.key === 'Backspace') && selected.every((x) => containerOf(x) && x.msm !== 'inherited')) {
        ev.preventDefault();
        setConfirmDelete(sel.editable);
      } else if (mod && (ev.key === 'c' || ev.key === 'x') && sel.kind === 'editable') {
        onToolbar(sel, ev.key === 'c' ? 'copy' : 'cut');
      } else if (mod && ev.key === 'v' && toolbarActions(sel).includes('paste')) {
        onToolbar(sel, 'paste');
      } else if (ev.key === 'Enter' && sel.kind === 'editable' && sel.editable.dialog && sel.editable.msm !== 'inherited' && mode === 'edit') {
        onToolbar(sel, 'configure');
      } else if (ev.key.toLowerCase() === 'i' && mode === 'edit' && toolbarActions(sel).includes('insert')) {
        ev.preventDefault();
        onToolbar(sel, 'insert');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const env: GraniteEnv = useMemo(() => studioGraniteEnv(client, dialog?.path, data), [client, dialog?.path, data]);

  const insertComponents = insertAt
    ? (data?.containers[insertAt.container] ?? []).map((t) => infoOf(t)).filter((c): c is ComponentInfo => !!c)
    : [];

  // ------------------------------------------------------------------------------------------ render

  return (
    <div className={`studio-app studio-app--${mode}${busy ? ' is-busy' : ''}`}>
      <TopBar
        page={data?.page ?? null}
        pagePath={pagePath}
        onClose={() => {
          const folder = { kind: 'sites' as const, path: pagePath.slice(0, pagePath.lastIndexOf('/')) };
          const t = tabRef.current;
          if (!t) go(folder);
          else if (t.canBack) t.leave(folder);
          else t.close();
        }}
        mode={mode}
        annotations={annotations.length}
        annotationsShown={annotationsShown}
        onToggleAnnotations={() => showAnnotations(!annotationsShown)}
        onAnnotate={() => void createAnnotation()}
        onMode={(m) => {
          if (m === 'preview') setSelectionKey(null);
          setMode(m);
        }}
        sidePanelOpen={sidePanelOpen}
        onToggleSidePanel={() => setSidePanelOpen((v) => !v)}
        emulatorOpen={emulatorOpen}
        onToggleEmulator={() => setEmulatorOpen((v) => !v)}
        scheduledPublication={scheduledPublication}
        launch={launchInfo?.launch ? { title: launchInfo.launch.title, source: launchInfo.sourcePath ?? undefined } : null}
        launchesOfPage={launchInfo?.launchesOfPage.map((l) => ({ path: l.path, title: l.title })) ?? []}
        contextHubOpen={contextHubOpen}
        onToggleContextHub={() => setContextHubOpen((v) => !v)}
        onPageAction={onPageAction}
        canUndo={history.undo.length > 0 && !busy}
        canRedo={history.redo.length > 0 && !busy}
        isBusy={busy}
        onUndo={() => void undo()}
        onRedo={() => void redo()}
        template={template && part ? { title: template.title, part: part.part, status: template.status, lastModified: template.lastModified, lastModifiedBy: template.lastModifiedBy } : undefined}
        onTemplateMode={(m: TemplateMode) => {
          if (!template) return;
          setSelectionKey(null);
          const target = m === 'initial' ? template.initial : template.structure;
          if (target !== pagePath) setPagePath(target);
          setMode(m === 'layout' ? 'layout' : 'edit');
        }}
        msm={
          pageMsm.status?.inLiveCopy
            ? { label: 'Linked copy Status', sync: !!pageMsm.status.relationship?.inherited && pageMsm.status.relationship.status !== 'suspended' }
            : pageMsm.status?.isSource
              ? { label: 'Source: Sync changes and Linked copies' }
              : undefined
        }
      />

      {template && <TemplateBanner template={template} />}
      {!template && <StatusBar statuses={pageStatuses} />}


      <div className="studio-workspace">
        {mode === 'developer' && (
          <aside className="studio-sidepanel studio-sidepanel--developer" aria-label="Developer">
            <section className="studio-sidepanel__panel">
              <h2 className="studio-sidepanel__title">Developer</h2>
              <div className="studio-sidepanel__body">
                <DeveloperPanel
                  tree={tree}
                  content={content}
                  contentRoot={contentRoot}
                  selected={selection?.kind === 'editable' ? selection.editable : null}
                  onSelect={selectPath}
                  infoOf={infoOf}
                  allowedIn={allowedIn}
                  errors={frameErrors}
                />
              </div>
            </section>
          </aside>
        )}
        {mode === 'targeting' && (
          <aside className="studio-sidepanel studio-sidepanel--targeting" aria-label="Personalize">
            <section className="studio-sidepanel__panel">
              <h2 className="studio-sidepanel__title">Personalize</h2>
              <div className="studio-sidepanel__body">
                <TargetingPanel
                  client={targeting}
                  pagePath={pagePath}
                  target={targetInfo}
                  experience={targetInfo ? (experienceOf[targetInfo.path] ?? 'default') : 'default'}
                  onExperience={(name) => {
                    if (!targetInfo) return;
                    setExperienceOf((m) => ({ ...m, [targetInfo.path]: name }));
                    setSelectionKey({ kind: 'editable', path: targetInfo.path });
                  }}
                  onChanged={(info) => {
                    setTargetInfo(info.targeted ? info : null);
                    if (!info.targeted) setExperienceOf((m) => {
                      const n = { ...m };
                      delete n[info.path];
                      return n;
                    });
                    void loadData();
                    reloadFrame();
                  }}
                />
              </div>
            </section>
          </aside>
        )}
        {sidePanelOpen && mode !== 'preview' && mode !== 'timewarp' && mode !== 'developer' && mode !== 'targeting' && (
          <SidePanel active={panel} onActive={setPanel}>
            {panel === 'assets' && <AssetsPanel client={client} />}
            {panel === 'components' && <ComponentsPanel components={pageComponents} />}
            {panel === 'tree' && (
              <ContentTreePanel
                tree={tree}
                content={content}
                contentRoot={contentRoot}
                selected={selection?.kind === 'editable' ? selection.editable.path : null}
                onSelect={selectPath}
              />
            )}
          </SidePanel>
        )}

        <main className={`studio-stage${emulatorOpen ? ' has-emulator' : ''}`} ref={stageRef}>
          {mode !== 'preview' && mode !== 'timewarp' && selection?.kind === 'editable' && (
            <nav className="studio-selection-path" aria-label="Selected component path">
              <span className="studio-selection-path__label">Selected</span>
              {[...ancestors(selection.editable)].reverse().map((item) => (
                <button key={item.path} type="button" onClick={() => selectPath(item.path)}>{item.title}</button>
              ))}
              <span aria-hidden="true">/</span>
              <strong>{selection.editable.title}</strong>
            </nav>
          )}
          {mode === 'preview' && contextHubOpen && (
            <ContextHubBar
              client={targeting}
              pagePath={pagePath}
              frame={() => frameRef.current?.contentWindow ?? null}
              onClose={() => setContextHubOpen(false)}
              onDevice={(size) => {
                // the Device Emulator module: the page takes the device's size, through the editor's own emulator
                if (!size) return setEmulatorOpen(false);
                const responsive = DEVICES.find((d) => d.id === 'responsive');
                if (!responsive) return;
                setOrientation('portrait');
                setDevice({ ...responsive, width: size.width, height: size.height });
                setEmulatorOpen(true);
              }}
            />
          )}
          {mode === 'timewarp' && (
            <TimewarpBar msm={msm} pagePath={pagePath} time={timewarp} onTime={setTimewarp} onCreateVersion={(l, c) => client.createVersion(pagePath, l, c)} launches={launchInfo?.launchesOfPage ?? []} />
          )}
          {emulatorOpen && (
            <EmulatorBar
              device={device}
              orientation={orientation}
              onDevice={setDevice}
              onOrientation={setOrientation}
              viewportWidth={viewportWidth}
              breakpoints={data?.page.breakpoints ?? []}
              breakpoint={breakpoint}
              showBreakpoints
              zoom={zoom}
              onZoom={setZoom}
              scale={scale}
            />
          )}
          {emulatorOpen && (
            <PresetRuler device={device} orientation={orientation} onDevice={setDevice} viewportWidth={viewportWidth} />
          )}
          {loadError && (
            <div className="studio-stage__error" role="alert">
              <strong>Could not open {pagePath}</strong>
              <span>{loadError}</span>
            </div>
          )}
          <div
            className={`studio-stage__scaler${emulatedWidth ? ' is-emulated' : ''}`}
            style={emulatedWidth && emulatedHeight ? { width: emulatedWidth * scale, height: emulatedHeight * scale } : undefined}
          >
          <div
            className={`studio-stage__viewport${emulatedWidth ? ' is-emulated' : ''}`}
            style={emulatedWidth && emulatedHeight ? { width: emulatedWidth, height: emulatedHeight, transform: scale === 1 ? undefined : `scale(${scale})` } : undefined}
          >
            {emulatedWidth && device.id === 'responsive' && <ViewportHandles device={device} orientation={orientation} onDevice={setDevice} scale={scale} />}
            <iframe
              key={`${frameMode}|${mode === 'timewarp' ? timewarp : ''}|${frameMode === 'edit' ? experienceParams : ''}`}
              ref={frameRef}
              className="studio-frame"
              title="Page content"
              src={frameUrl}
              onLoad={onFrameLoad}
            />
            {mode !== 'preview' && mode !== 'timewarp' && (
              <Overlay
                scale={scale}
                mode={mode === 'developer' || mode === 'targeting' ? 'edit' : mode}
                doc={doc}
                tree={tree}
                selection={selection}
                group={group}
                onSelect={select}
                onConfigure={(e) => {
                  if (mode === 'developer') return;
                  if (mode === 'targeting' && !targetAncestor(e)) return;
                  if (e.msm === 'inherited') {
                    ToastQueue.info(`${e.title} is inherited from the source: cancel its inheritance to edit it`, { timeout: 4000 });
                  } else if (!startInline(e)) {
                    void openDialog(e.path, e.type, e.title, 'component');
                  }
                }}
                inline={!!inline}
                toolbar={(t) =>
                  mode === 'developer' ? null : inline && t.kind === 'editable' && t.editable.path === inline.path ? (
                    <InlineToolbar
                      el={inline.el}
                      editing={inline.editing}
                      onSave={() => inline.el.ownerDocument.defaultView?.dispatchEvent(new CustomEvent(END_EVENT, { detail: 'save' }))}
                      onCancel={() => inline.el.ownerDocument.defaultView?.dispatchEvent(new CustomEvent(END_EVENT, { detail: 'cancel' }))}
                    />
                  ) : (
                    <EditToolbar
                      actions={toolbarActions(t)}
                      active={activeToolbar(t)}
                      onAction={(a) => onToolbar(t, a)}
                      render={
                        t.kind === 'editable'
                          ? {
                              panelSelect: () => {
                                const pc = panelContainerOf(t.editable.el);
                                return (
                                  <PanelSelectButton
                                    panels={panelEntries(t.editable)}
                                    active={() => (pc ? activePanel(pc) : 0)}
                                    onNavigate={(i) => {
                                      // the page may have re-rendered since the toolbar opened
                                      const now = tree.byPath.get(t.editable.path);
                                      const c = now ? panelContainerOf(now.el) : pc;
                                      if (c) navigatePanel(c, i);
                                    }}
                                    onReorder={
                                      t.editable.msm === 'inherited'
                                        ? undefined
                                        : (names) =>
                                            run(() => client.post(t.editable.path, [{ name: 'itemOrder', value: names.join(',') }]), undefined, {
                                              label: `reorder ${t.editable.title}`,
                                              scope: scopeFor([t.editable.path]),
                                            })
                                    }
                                  />
                                );
                              },
                              styles: () => (
                                <StylesButton
                                  client={client}
                                  path={t.editable.path}
                                  onSave={(entries) =>
                                    run(() => client.post(t.editable.path, entries), undefined, {
                                      label: `style ${t.editable.title}`,
                                      scope: scopeFor([t.editable.path]),
                                    })
                                  }
                                />
                              ),
                            }
                          : undefined
                      }
                    />
                  )
                }
                allowed={mode === 'developer' || mode === 'targeting' ? () => false : allowed}
                onInsertInto={mode === 'edit' && !structureMode ? (c) => setInsertAt({ container: c.path, before: null }) : undefined}
                onDrop={onDrop}
                breakpoint={breakpoint}
                onColumnChange={onColumnChange}
                unlocked={part?.part === 'structure' ? isUnlocked : undefined}
                hidePlaceholder={part?.part === 'initial' ? lockedInInitial : undefined}
                containerPanel={
                  structureMode
                    ? (c) =>
                        isUnlocked(c) ? (
                          <AllowedComponentsPanel
                            components={allowedIn(c).map((t) => infoOf(t)).filter((x): x is ComponentInfo => !!x)}
                            onPolicy={(comp) => setPolicyTarget({ path: c.path, type: comp.resourceType, container: c.path, title: comp.title })}
                          />
                        ) : null
                    : undefined
                }
              >
                {mode === 'edit' && !template && annotationsShown && !inline && annotations.length > 0 && (
                  <AnnotationsLayer
                    scale={scale}
                    doc={doc}
                    tree={tree}
                    annotations={annotations}
                    onChange={changeAnnotation}
                    onDelete={deleteAnnotation}
                    onMove={moveAnnotation}
                    onAddArrow={addArrow}
                    onDeleteArrow={deleteArrow}
                    open={openAnnotation}
                    onOpen={setOpenAnnotation}
                  />
                )}
              </Overlay>
            )}
          </div>
          </div>
        </main>
      </div>

      {dialog && (
        <ComponentDialog
          key={dialog.path}
          {...dialog}
          registry={registry}
          env={env}
          inheritance={dialog.variant === 'page' ? (pageInheritance ?? null) : null}
          inheritancePending={dialog.variant === 'page' && pageInheritance === undefined}
          onSubmit={saveDialog}
          onClose={() => setDialog(null)}
        />
      )}

      <DialogContainer onDismiss={() => setInsertAt(null)}>
        {insertAt && (
          <InsertComponentDialog
            components={insertComponents}
            onPick={(c) => {
              const at = insertAt;
              setInsertAt(null);
              void insert(c, at);
            }}
          />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setImageEdit(null)}>
        {imageEdit && (
          <ImageEditorDialog
            title={imageEdit.editable.title}
            src={imageEdit.src}
            features={imageEditorFeatures(infoOf(imageEdit.editable.type)?.inplace)}
            initial={imageEdit.initial}
            onSave={(edits, image) => {
              const e = imageEdit.editable;
              return run(() => client.post(e.path, imageEditEntries(edits, image)), () => ToastQueue.positive(`${e.title} saved`, { timeout: 3000 }), {
                label: `edit image of ${e.title}`,
                scope: scopeFor([e.path]),
              });
            }}
          />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setCreatingLaunch(false)}>
        {creatingLaunch && (
          <CreateLaunchDialog
            client={launches}
            msm={msm}
            source={pagePath}
            sourceTitle={data?.page.title ?? nameOf(pagePath)}
            onCreated={(l) => {
              go({ kind: 'editor', path: l.sourceInLaunch });
            }}
          />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setPromotingLaunch(false)}>
        {promotingLaunch && launchInfo?.launch && (
          <PromoteLaunchDialog
            client={launches}
            launch={launchInfo.launch}
            page={pagePath}
            onDone={(deleted) => {
              if (deleted && launchInfo.sourcePath) go({ kind: 'editor', path: launchInfo.sourcePath });
              else launches.info(pagePath).then(setLaunchInfo, () => undefined);
            }}
          />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setPublishing(null)}>
        {publishing && (
          <PublishDialog client={replication} paths={[pagePath]} title={data?.page.title ?? nameOf(pagePath)} action={publishing} offerDeep onDone={() => void loadData()} />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setRolloutOf(null)}>
        {rolloutOf && pageMsm.status?.liveCopies && (
          <RolloutDialog
            msm={msm}
            path={rolloutOf.path}
            liveCopies={pageMsm.status.liveCopies}
            component={{ title: rolloutOf.title }}
            onDone={() => {
              pageMsm.reload();
              setRolloutOf(null);
            }}
          />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setXfConvert(null)}>
        {xfConvert && (
          <ConvertToXfDialog
            client={client}
            paths={xfConvert.map((x) => x.path)}
            title={xfConvert.length === 1 ? xfConvert[0].title : `${nameOf(pagePath)} fragment`}
            pagePath={pagePath}
            onConvert={async (o) => {
              const c = containerOf(xfConvert[0]);
              if (!c) throw new Error('The component is not in a container');
              const type = xfComponentFor(c);
              const items = xfConvert;
              await run(
                async () => {
                  const r = await client.convertToXf(items.map((x) => x.path), { ...o, resourceType: type });
                  return r.component;
                },
                (path) => {
                  setGroupKeys([]);
                  if (path) setSelectionKey({ kind: 'editable', path });
                  ToastQueue.positive(`Converted into an experience fragment: ${o.title}`, { timeout: 5000 });
                },
                { label: `convert ${items.length === 1 ? items[0].title : `${items.length} components`} to an experience fragment`, scope: scopeFor([c.path]) },
              );
            }}
          />
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setMsmOpen(false)}>
        {msmOpen && (
          <StudioDialog size="M" isDismissable>
            <Heading>{pageMsm.status?.inLiveCopy ? 'Linked copy' : 'Source'}</Heading>
            <Divider />
            <Content>
              <LiveCopyStatus
                msm={msm}
                path={pagePath}
                onChanged={() => {
                  pageMsm.reload();
                  reloadFrame();
                  void loadData();
                }}
              />
            </Content>
          </StudioDialog>
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setConfirmDelete(null)}>
        {confirmDelete && (
          <StudioConfirmDialog
            title="Delete"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={() => remove(selected.length > 1 ? selected : [confirmDelete])}
          >
            {selected.length > 1 ? (
              <>
                You are going to delete the following <strong>{selected.length}</strong> components: {selected.map((x) => x.title).join(', ')}.
              </>
            ) : (
              <>
                You are going to delete the following component: <strong>{confirmDelete.title}</strong>
                {confirmDelete.children.length > 0 && ` (with the ${confirmDelete.children.length} components inside it)`}.
              </>
            )}
          </StudioConfirmDialog>
        )}
      </DialogContainer>

      {policyTarget && (
        <PolicyDialog
          key={`${policyTarget.path}|${policyTarget.type}|${policyTarget.container ?? ''}`}
          client={client}
          templates={templates}
          target={policyTarget}
          registry={registry}
          env={env}
          onClose={() => setPolicyTarget(null)}
          onSaved={(title) => {
            setPolicyTarget(null);
            reloadFrame();
            void loadData();
            ToastQueue.positive(`Configuration "${title}" saved`, { timeout: 3000 });
          }}
        />
      )}

      {startingWorkflow && <StartWorkflowDialog payloads={[pagePath]} onClose={() => setStartingWorkflow(false)} />}
      <ToastContainer placement="bottom" />
    </div>
  );
}


function relativeTimeOf(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const m = Math.round(ms / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  return d === 1 ? 'yesterday' : `${d} days ago`;
}
