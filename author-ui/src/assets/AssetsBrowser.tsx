import Annotate from "@spectrum-icons/workflow/Annotate";
import Images from "@spectrum-icons/workflow/Images";
import { ImageSetDialog } from "./imageSet";
import {
  ActionButton,
  Button,
  Checkbox,
  DialogContainer,
  FileTrigger,
  IllustratedMessage,
  Heading,
  Content,
  Item,
  Menu,
  MenuTrigger,
  Picker,
  ProgressCircle,
  SearchField,
  Text,
  ToggleButton,
  Tooltip,
  TooltipTrigger,
} from "@adobe/react-spectrum";
import Add from "@spectrum-icons/workflow/Add";
import { ContextMenu, useContextMenu, useRegisterActions, type Action } from "../shell/actions";
import ChevronDown from "@spectrum-icons/workflow/ChevronDown";
import Close from "@spectrum-icons/workflow/Close";
import Copy from "@spectrum-icons/workflow/Copy";
import Delete from "@spectrum-icons/workflow/Delete";
import Download from "@spectrum-icons/workflow/Download";
import GlobeRemove from "@spectrum-icons/workflow/GlobeRemove";
import Globe from "@spectrum-icons/workflow/Globe";
import PublishCheck from "@spectrum-icons/workflow/PublishCheck";
import { HttpReplicationClient } from "../replication/api";
import { PublishDialog } from "../replication/PublishDialog";
import Edit from "@spectrum-icons/workflow/Edit";
import FolderArchive from "@spectrum-icons/workflow/FolderArchive";
import LinkIcon from "@spectrum-icons/workflow/Link";
import LockClosed from "@spectrum-icons/workflow/LockClosed";
import LockOpen from "@spectrum-icons/workflow/LockOpen";
import Info from "@spectrum-icons/workflow/Info";
import MagicWand from "@spectrum-icons/workflow/MagicWand";
import Move from "@spectrum-icons/workflow/Move";
import Paste from "@spectrum-icons/workflow/Paste";
import RailLeft from "@spectrum-icons/workflow/RailLeft";
import Refresh from "@spectrum-icons/workflow/Refresh";
import SelectBox from "@spectrum-icons/workflow/SelectBox";
import SortOrderDown from "@spectrum-icons/workflow/SortOrderDown";
import SortOrderUp from "@spectrum-icons/workflow/SortOrderUp";
import ViewColumn from "@spectrum-icons/workflow/ViewColumn";
import ViewGrid from "@spectrum-icons/workflow/ViewGrid";
import ViewList from "@spectrum-icons/workflow/ViewList";
import WorkflowIcon from "@spectrum-icons/workflow/Workflow";
import ProjectAdd from '@spectrum-icons/workflow/ProjectAdd';
import CollectionAdd from "@spectrum-icons/workflow/CollectionAdd";
import SaveTo from "@spectrum-icons/workflow/SaveTo";
import ShareIcon from "@spectrum-icons/workflow/Share";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type DragEvent,
  type MouseEvent,
} from "react";
import {
  DAM_ROOT,
  may,
  type AssetItem,
  type DamClient,
  type DamItem,
  type Listing,
  type SearchFacet,
  type SearchQuery,
  type SortKey,
} from "./api";
import { isEditable } from "./ImageEditor";
import { StartWorkflowDialog } from "../workflow/StartWorkflowDialog";
import { languageOf, SitesTranslationProjectDialog } from '../translation/SitesTranslationProject';
import { PageLanguagesDialog } from '../translation/PageLanguagesDialog';
import { HttpPageTranslationClient } from '../translation/pages';
import {
  CreateFolderDialog,
  DeleteDialog,
  FolderPropertiesDialog,
  MoveDialog,
  folderTitles,
} from "./dialogs";
import { fragmentEditorUrl, kindOf, type Kind } from "./format";
import type { AssetsRoute } from "./route";
import {
  BulkPropertiesDialog,
  DownloadDialog,
  ExtractArchiveDialog,
} from "./bulk";
import { DownloadsButton } from "./downloads";
import { CreateLiveCopyDialog } from "./liveCopy";
import { AddToCollectionDialog, CollectionDialog } from "./collections";
import { ExportMetadataDialog, ImportMetadataDialog } from "./csv";
import { ShareLinkDialog } from "./share";
import { DEFAULT_FACETS, SearchRail } from "./search";
import { UploadPanel, useUploads } from "./uploads";
import { CardView, ColumnView, ListView, type ViewMode } from "./views";
import { MediaInspector, MEDIA_INSPECTOR_TABS, readMediaInspector, storeMediaInspector, type MediaInspectorTab } from "./Inspector";
import RailRight from "@spectrum-icons/workflow/RailRight";
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { PathCrumbs } from '../shell/PathCrumbs';
import { ScreenFrame } from '../shell/ScreenFrame';
import { SelectionBar } from '../shell/SelectionBar';
import { VOCAB } from '../shell/vocabulary';
import { DEFAULT_ASSETS_CONFIG, loadAssetsConfig, type AssetsConfigurations } from './assetsConfig';

interface Props {
  client: DamClient;
  path: string;
  /** a search below `path` instead of its listing */
  search?: SearchQuery;
  navigate(route: AssetsRoute): void;
  clipboard: string[];
  setClipboard(paths: string[]): void;
}

type DialogState =
  | { kind: "createFolder" }
  | { kind: "createLiveCopy" }
  | { kind: "delete"; items: DamItem[] }
  | { kind: "publish"; action: "activate" | "deactivate"; items: DamItem[] }
  | { kind: "move"; items: DamItem[] }
  | { kind: "folderProps"; folder: DamItem }
  | { kind: "download"; items: DamItem[] }
  | { kind: "bulkProps"; paths: string[] }
  | { kind: "extract"; archive: AssetItem }
  | { kind: "exportMetadata" }
  | { kind: "addToCollection"; paths: string[] }
  | { kind: "imageSet"; pictures: AssetItem[] }
  | { kind: "smartCollection" }
  | { kind: "share"; items: DamItem[] }
  | { kind: "importMetadata" }
  | null;

const SORTS: Array<{ key: SortKey; label: string }> = [
  { key: "none", label: "None" },
  { key: "name", label: "Name" },
  { key: "title", label: "Title" },
  { key: "modified", label: "Modified" },
  { key: "size", label: "Size" },
  { key: "type", label: "Type" },
];

const KIND_FILTERS: Array<{ kind: Kind; label: string }> = [
  { kind: "folder", label: "Folders" },
  { kind: "fragment", label: "Content Fragments" },
  { kind: "image", label: "Images" },
  { kind: "document", label: "Documents" },
  { kind: "pdf", label: "PDF" },
  { kind: "video", label: "Video" },
];

function stored<T extends string>(
  key: string,
  allowed: readonly T[],
  fallback: T,
): T {
  try {
    const v = window.localStorage.getItem(key) as T | null;
    return v && allowed.includes(v) ? v : fallback;
  } catch {
    return fallback;
  }
}

function store(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // private mode: per-session only
  }
}

/** Polls while assets are processing, for at most this long after the last change. */
const POLL_MS = 2500;
const POLL_FOR_MS = 3 * 60 * 1000;

export function AssetsBrowser({
  client,
  path,
  search,
  navigate,
  clipboard,
  setClipboard,
}: Props) {
  const searchKey = search ? JSON.stringify(search) : "";
  const [listing, setListing] = useState<Listing | null>(null);
  // Assets Configurations: where a search starts, how far it counts, whether Share Link is offered
  const [assetsConfig, setAssetsConfig] = useState<AssetsConfigurations>(DEFAULT_ASSETS_CONFIG);
  useEffect(() => {
    let live = true;
    loadAssetsConfig().then((c) => live && setAssetsConfig(c), () => undefined);
    return () => {
      live = false;
    };
  }, []);
  const searchScope: "folder" | "all" = search?.scope ?? (assetsConfig.omnisearch.currentFolder ? "folder" : "all");
  const searchRoot = searchScope === "all" ? DAM_ROOT : path;
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<ViewMode>(() =>
    stored("studio.assets.view", ["card", "list", "column"] as const, "card"),
  );
  const [sort, setSort] = useState<SortKey>(() =>
    stored(
      "studio.assets.sort",
      ["none", "name", "title", "modified", "size", "type"] as const,
      "none",
    ),
  );
  const [order, setOrder] = useState<"asc" | "desc">(() =>
    stored("studio.assets.order", ["asc", "desc"] as const, "asc"),
  );
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [rail, setRail] = useState(!!search);
  useEffect(() => {
    if (search) setRail(true);
  }, [searchKey]); // eslint-disable-line react-hooks/exhaustive-deps
  const [filter, setFilter] = useState("");
  // the filters of the search, as Search Forms lays them out (read once, when a search shows)
  const [facets, setFacets] = useState<SearchFacet[] | null>(null);
  const searching = !!search;
  useEffect(() => {
    if (!searching || facets) return;
    let live = true;
    client.searchForm().then(
      (f) => live && setFacets(f.facets),
      () => live && setFacets(DEFAULT_FACETS),
    );
    return () => {
      live = false;
    };
  }, [client, searching, facets]);
  const [kinds, setKinds] = useState<ReadonlySet<Kind>>(new Set());
  const [dialog, setDialog] = useState<DialogState>(null);
  const [dragging, setDragging] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  /** a search by what pictures show: how many were left out as weak matches */
  const [weaker, setWeaker] = useState(0);
  /** the same search, translated first: the English phrase the pictures were compared with */
  const [searchedAs, setSearchedAs] = useState<string | null>(null);
  const [anyLanguage, setAnyLanguage] = useState(false);
  const lastToggled = useRef<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const t = window.setTimeout(() => setNotice(null), 6000);
    return () => window.clearTimeout(t);
  }, [notice]);
  const lastChange = useRef(Date.now());

  const load = useCallback(
    async (quiet = false) => {
      if (!quiet) setError(null);
      try {
        if (search) {
          // The folder itself for its title and breadcrumbs (one item is enough).
          const [r, folder] = await Promise.all([
            client.search(searchRoot, search, { sort, order }),
            client.list(path, { limit: 1 }),
          ]);
          setListing({
            ...folder,
            total: r.total,
            offset: r.offset,
            items: r.items,
          });
          setMore(r.more === true);
          setWeaker(r.weaker ?? 0);
          setSearchedAs(r.searchedAs ?? null);
          setAnyLanguage(r.anyLanguage === true);
          return;
        }
        setWeaker(0);
        setSearchedAs(null);
        setAnyLanguage(false);
        setMore(false);
        const l = await client.list(path, { sort, order });
        setListing(l);
        for (const c of l.breadcrumbs) folderTitles.set(c.path, c.title);
        for (const i of l.items)
          if (i.type === "folder") folderTitles.set(i.path, i.title);
        if (l.type === "asset")
          navigate({ kind: "details", path, properties: false });
      } catch (e) {
        if (!quiet) setError(e instanceof Error ? e.message : String(e));
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [client, path, sort, order, navigate, searchKey, searchRoot],
  );

  // the last listing stays on screen while the next loads: entering a folder is not a page reload
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setSelected(new Set());
    setBusy(true);
    void load().finally(() => setBusy(false));
  }, [load]);

  useScreenTitle(
    search ? `Search · ${listing?.title ?? VOCAB.media}` : listing?.title && path !== '/content/dam' ? `${VOCAB.media} · ${listing.title}` : VOCAB.media,
    `${search ? "Search: " : ""}${path === '/content/dam' ? VOCAB.media : listing?.title ?? VOCAB.media} | ${VOCAB.product} ${VOCAB.media}`,
  );
  const tab = useTab();
  const go = useNavigate();
  const tabActive = useRef(true);
  tabActive.current = !tab || tab.active;
  useEffect(() => {
  }, [listing]);

  const onUploaded = useCallback(
    (folder: string) => {
      lastChange.current = Date.now();
      if (folder === path) void load(true);
    },
    [path, load],
  );
  const {
    uploads,
    add: addUploads,
    dismiss,
    resolve: resolveUpload,
  } = useUploads(client, onUploaded);

  // Refresh while something is being processed, so cards switch from "Processing" to the thumbnail.
  const processing =
    listing?.items.some(
      (i) => i.type === "asset" && i.state === "processing",
    ) ?? false;
  useEffect(() => {
    if (!processing) return undefined;
    const t = window.setInterval(() => {
      if (Date.now() - lastChange.current < POLL_FOR_MS) void load(true);
    }, POLL_MS);
    return () => window.clearInterval(t);
  }, [processing, load]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!tabActive.current) return; // another tab is in front
      if (e.key === "Escape" && selected.size > 0 && dialog === null)
        setSelected(new Set());
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [selected, dialog]);

  const items = useMemo(() => {
    const all = listing?.items ?? [];
    if (search) return all; // filtered on the server
    const q = filter.trim().toLowerCase();
    return all.filter(
      (i) =>
        (!q ||
          i.title.toLowerCase().includes(q) ||
          i.name.toLowerCase().includes(q)) &&
        (kinds.size === 0 || kinds.has(kindOf(i))),
    );
  }, [listing, filter, kinds, search]);

  const selectedItems = items.filter((i) => selected.has(i.path));
  const selectedAssets = selectedItems.filter(
    (i): i is AssetItem => i.type === "asset",
  );
  const [translationProjectFor, setTranslationProjectFor] = useState<string[] | null>(null);
  const [languagesFor, setLanguagesFor] = useState<string | null>(null);
  const pageTranslation = useMemo(() => new HttpPageTranslationClient(), []);
  const [startingWorkflow, setStartingWorkflow] = useState<string[] | null>(
    null,
  );

  const toggle = (p: string, range = false) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (range && lastToggled.current) {
        const a = items.findIndex((i) => i.path === lastToggled.current);
        const b = items.findIndex((i) => i.path === p);
        if (a >= 0 && b >= 0) {
          for (let k = Math.min(a, b); k <= Math.max(a, b); k++)
            next.add(items[k].path);
          return next;
        }
      }
      if (next.has(p)) next.delete(p);
      else next.add(p);
      lastToggled.current = p;
      return next;
    });
  };

  // An asset opens in this tab, pushed on its history like a folder: Back (or the × of the details) returns
  // to this list with its scroll and selection. ⌘-click / middle-click open it in a new tab.
  const openDetails = (path: string, properties: boolean) => navigate({ kind: "details", path, properties });

  const open = (item: DamItem) => {
    if (item.type === "folder") navigate({ kind: "browse", path: item.path });
    else if (item.contentFragment) {
      if (tab) go(fragmentEditorUrl(item.path));
      else window.location.assign(fragmentEditorUrl(item.path));
    }
    else openDetails(item.path, false);
  };

  const properties = (item: DamItem) =>
    item.type === "folder"
      ? setDialog({ kind: "folderProps", folder: item })
      : openDetails(item.path, true);

  const changed = (message?: string) => {
    lastChange.current = Date.now();
    setSelected(new Set());
    if (message) setNotice(message);
    void load(true);
  };

  /** The list again, keeping the selection: after a change made in the inspector to what is selected. */
  const refreshed = () => {
    lastChange.current = Date.now();
    void load(true);
  };

  const download = (sel: DamItem[] = selectedItems) => {
    const only = sel.length === 1 ? sel[0] : null;
    if (only?.type === "asset" && only.original) {
      // One asset: the original directly, as AEM does without renditions.
      const link = document.createElement("a");
      link.href = only.original;
      link.download = only.name;
      document.body.appendChild(link);
      link.click();
      link.remove();
      return;
    }
    setDialog({ kind: "download", items: sel });
  };

  const paste = async () => {
    try {
      const copies = await client.copy(clipboard, path);
      setClipboard([]);
      changed(`${copies.length} item${copies.length > 1 ? "s" : ""} pasted`);
    } catch (e) {
      setNotice(e instanceof Error ? e.message : String(e));
    }
  };

  const run = async (fn: () => Promise<string>) => {
    try {
      changed(await fn());
    } catch (e) {
      setNotice(e instanceof Error ? e.message : String(e));
    }
  };
  const plural = (n: number, word: string) => `${n} ${word}${n > 1 ? "s" : ""}`;

  // Smart Tags: offered once the model is installed (Tools › Smart Tags).
  const [smartReady, setSmartReady] = useState(false);
  useEffect(() => {
    let live = true;
    client.smartTagsStatus().then(
      (s) =>
        live &&
        setSmartReady(
          s.model.installed === true &&
            (s.settings.tagRoots.length > 0 || s.settings.keywords),
        ),
      () => live && setSmartReady(false),
    );
    return () => {
      live = false;
    };
  }, [client]);

  const smartTag = (sel: DamItem[] = selectedItems) =>
    run(async () => {
      const n = await client.smartTag(sel.map((i) => i.path));
      return n
        ? `${plural(n, "file")} queued for Auto tags`
        : "No images to tag in the selection";
    });

  const reprocess = async (assets: AssetItem[] = selectedAssets) => {
    try {
      await client.reprocess(assets.map((a) => a.path));
      changed(
        `${assets.length} file${assets.length > 1 ? "s" : ""} queued for processing`,
      );
    } catch (e) {
      setNotice(e instanceof Error ? e.message : String(e));
    }
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const files = [...e.dataTransfer.files];
    if (files.length > 0 && !canCreate) {
      setNotice("You cannot add files to this folder");
      return;
    }
    if (files.length > 0) addUploads(path, files);
  };

  const canCreate = !search && may(listing, "canCreate");
  const replication = useMemo(() => new HttpReplicationClient(), []);

  // the inspector at the right: Properties, Timeline, References for the selection (alt+3 … alt+5, alt+` toggles)
  const [inspector, setInspectorState] = useState<MediaInspectorTab | null>(readMediaInspector);
  const setInspector = useCallback((t: MediaInspectorTab | null) => {
    setInspectorState(t);
    storeMediaInspector(t);
  }, []);
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (tab && !tab.active) return;
      const t = ev.target instanceof Element ? ev.target : null;
      if (t?.closest('[role="dialog"]') || !ev.altKey || ev.metaKey || ev.ctrlKey) return;
      if (ev.code === "Backquote") {
        ev.preventDefault();
        setInspector(inspector ? null : "properties");
        return;
      }
      const n = ev.code?.startsWith("Digit") ? Number(ev.code.slice(5)) : NaN;
      const it = MEDIA_INSPECTOR_TABS.find((x) => x.shortcut === `alt+${n}`);
      if (it) {
        ev.preventDefault();
        setInspector(it.key);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [inspector, setInspector, tab]);
  // open, the panel closes with its own X; the bar only offers to bring it back
  const inspectorButton = inspector ? null : (
    <TooltipTrigger delay={600}>
      <ActionButton isQuiet aria-label="Inspector" UNSAFE_className="studio-inspector__toggle" onPress={() => setInspector("properties")}>
        <RailRight />
        <Text>Details</Text>
      </ActionButton>
      <Tooltip>Details of the selection: properties, timeline, references (alt+`)</Tooltip>
    </TooltipTrigger>
  );

  /** The actions for a selection: the bar, the right-click menu and ⌘K read this list. */
  const actionsFor = (sel: DamItem[]): Action[] => {
    const assets = sel.filter((i): i is AssetItem => i.type === "asset");
    const all = assets.length > 0 && assets.length === sel.length;
    const canEdit = sel.every((i) => may(i, "canEdit"));
    const canDelete = sel.every((i) => may(i, "canDelete"));
    const paths = sel.map((i) => i.path);
    const out: Action[] = [];
    if (sel.length === 1) out.push({ id: "properties", label: "Properties", icon: <Info />, run: () => properties(sel[0]) });
    if (sel.length > 1 && all) out.push({ id: "properties", label: "Properties", icon: <Info />, run: () => setDialog({ kind: "bulkProps", paths }) });
    if (sel.length === 1 && canEdit && isEditable(sel[0])) out.push({ id: "edit", label: "Edit", icon: <Edit />, run: () => navigate({ kind: "edit", path: sel[0].path }) });
    if (sel.length > 0) out.push({ id: "download", label: "Download", icon: <Download />, run: () => download(sel) });
    // AEM's Annotate: the file's page, drawing notes on the picture or the video
    if (sel.length === 1 && assets.length === 1 && canEdit && ["image", "video"].includes(kindOf(assets[0])))
      out.push({ id: "annotate", label: "Annotate", icon: <Annotate />, run: () => navigate({ kind: "details", path: assets[0].path, properties: false, annotate: true }) });
    // AEM's Find Similar: the file's details with the Similar rail (pictures compared by what they show)
    if (sel.length === 1 && assets.length === 1 && ["image", "video", "pdf"].includes(kindOf(assets[0])))
      out.push({ id: "similar", label: "Find Similar", icon: <Images />, run: () => navigate({ kind: "details", path: assets[0].path, properties: false, similar: true }) });
    out.push({
      id: "copy",
      label: "Copy",
      icon: <Copy />,
      run: () => {
        setClipboard(paths);
        setSelected(new Set());
        setNotice(`${sel.length} item${sel.length > 1 ? "s" : ""} copied. Open a folder and paste.`);
      },
    });
    out.push({ id: "publish", label: "Publish", icon: <PublishCheck />, run: () => setDialog({ kind: "publish", action: "activate", items: sel }) });
    out.push({ id: "unpublish", label: "Unpublish", icon: <GlobeRemove />, run: () => setDialog({ kind: "publish", action: "deactivate", items: sel }) });
    if (assetsConfig.mayShare !== false) out.push({ id: "share", label: "Share Link", icon: <ShareIcon />, run: () => setDialog({ kind: "share", items: sel }) });
    out.push({ id: "collection", label: "Add to Collection", icon: <CollectionAdd />, run: () => setDialog({ kind: "addToCollection", paths }) });
    if (canDelete) out.push({ id: "move", label: "Move", icon: <Move />, run: () => setDialog({ kind: "move", items: sel }) });
    if (all && assets.every((a) => languageOf(a.path))) out.push({ id: "translate", label: "Translation Project", icon: <ProjectAdd />, run: () => setTranslationProjectFor(assets.map((a) => a.path)) });
    // one file in a language folder: its copies in the other languages, translated (title, description) on request
    if (all && assets.length === 1 && !assets[0].contentFragment && languageOf(assets[0].path))
      out.push({ id: "languages", label: "Language Copies", icon: <Globe />, run: () => setLanguagesFor(assets[0].path) });
    if (all) out.push({ id: "flow", label: "Start Flow", icon: <WorkflowIcon />, run: () => setStartingWorkflow(assets.map((a) => a.path)) });
    if (all && canEdit && assets.every((a) => !a.checkedOutBy)) {
      out.push({
        id: "checkout",
        label: "Checkout",
        icon: <LockClosed />,
        run: () =>
          run(async () => {
            await client.checkout(assets.map((a) => a.path));
            return `${plural(assets.length, "file")} checked out`;
          }),
      });
    }
    if (all && canEdit && assets.every((a) => a.checkedOutBy)) {
      out.push({
        id: "checkin",
        label: "Check In",
        icon: <LockOpen />,
        run: () =>
          run(async () => {
            await client.checkin(assets.map((a) => a.path));
            return `${plural(assets.length, "file")} checked in`;
          }),
      });
    }
    // pictures and videos (not sets, not records) can be kept together as an image set
    if (all && canEdit && assets.length > 0 && assets.every((a) => kindOf(a) === "image" || kindOf(a) === "video")) {
      out.push({ id: "imageset", label: "Image Set", icon: <Images />, run: () => setDialog({ kind: "imageSet", pictures: assets }) });
    }
    if (all && canEdit && assets.length > 1) {
      out.push({
        id: "relate",
        label: "Relate",
        icon: <LinkIcon />,
        run: () =>
          run(async () => {
            const added = await client.relate(assets.map((a) => a.path));
            return added ? `${plural(assets.length, "file")} related` : "These files were already related";
          }),
      });
    }
    if (sel.length === 1 && assets.length === 1 && kindOf(assets[0]) === "archive") out.push({ id: "extract", label: "Extract Archive", icon: <FolderArchive />, run: () => setDialog({ kind: "extract", archive: assets[0] }) });
    if (smartReady && canEdit) out.push({ id: "smarttag", label: "Auto tags", icon: <MagicWand />, run: () => smartTag(sel) });
    if (assets.length > 0 && canEdit) out.push({ id: "reprocess", label: "Reprocess files", icon: <Refresh />, run: () => reprocess(assets) });
    if (canDelete) out.push({ id: "delete", label: "Delete", icon: <Delete />, separator: true, run: () => setDialog({ kind: "delete", items: sel }) });
    return out;
  };
  const actions = selectedItems.length ? actionsFor(selectedItems) : [];
  useRegisterActions(tab?.id, selectedItems.length ? { subject: selectedItems.length === 1 ? selectedItems[0].title : `${selectedItems.length} items`, actions } : null);
  const ctx = useContextMenu();
  const onItemMenu = (item: DamItem, e: MouseEvent) => {
    const sel = selected.has(item.path) ? selectedItems : [item];
    if (!selected.has(item.path)) setSelected(new Set([item.path]));
    ctx.open(e, actionsFor(sel));
  };

  // the root's own title is the repository's ("Assets" in an imported DAM); the console names it
  const crumbs = (listing?.breadcrumbs ?? [{ path: DAM_ROOT, title: VOCAB.media }]).map((c) => (c.path === DAM_ROOT ? { ...c, title: VOCAB.media } : c));
  const title = listing?.title ?? "";

  return (
    <div className="studio-assets__browser">
      {translationProjectFor && <SitesTranslationProjectDialog paths={translationProjectFor} onClose={() => setTranslationProjectFor(null)} />}
      {languagesFor && (
        <PageLanguagesDialog client={pageTranslation} path={languagesFor} file onClose={() => setLanguagesFor(null)} onChanged={refreshed} />
      )}
      {startingWorkflow && (
        <StartWorkflowDialog
          payloads={startingWorkflow}
          onClose={() => setStartingWorkflow(null)}
        />
      )}
      {selected.size > 0 ? (
        <SelectionBar
          count={selected.size}
          actions={actions}
          leading={["properties", "edit", "download", "share", "similar", "checkout", "checkin", "publish"]}
          onClear={() => setSelected(new Set())}
          start={
          <TooltipTrigger>
            <ToggleButton
              isQuiet
              isSelected={rail}
              onChange={setRail}
              aria-label="Filters"
            >
              <RailLeft />
            </ToggleButton>
            <Tooltip>Filters</Tooltip>
          </TooltipTrigger>
          }
          end={inspectorButton}
        />
      ) : (
      <ScreenFrame
        navigation={
          <TooltipTrigger>
            <ToggleButton isQuiet isSelected={rail} onChange={setRail} aria-label="Filters">
              <RailLeft />
            </ToggleButton>
            <Tooltip>Filters</Tooltip>
          </TooltipTrigger>
        }
        identity={
          <>
          <PathCrumbs crumbs={crumbs} onGo={(p) => navigate({ kind: "browse", path: p })} />
          </>
        }
        actions={
          <>
          <SearchField
            aria-label="Search"
            placeholder={`Search in ${path === DAM_ROOT ? VOCAB.media : title || VOCAB.media}`}
            isQuiet
            width="size-2400"
            key={`q:${search?.fulltext ?? ""}`}
            defaultValue={search?.fulltext ?? ""}
            onSubmit={(v) =>
              navigate({
                kind: "browse",
                path,
                search: { ...search, fulltext: v.trim() || undefined },
              })
            }
            onClear={() => {
              if (search)
                navigate({
                  kind: "browse",
                  path,
                  search: { ...search, fulltext: undefined },
                });
            }}
          />
          {search && (
            <ActionButton
              isQuiet
              onPress={() => setDialog({ kind: "smartCollection" })}
            >
              <SaveTo />
              <Text>Save as Smart Collection</Text>
            </ActionButton>
          )}
          {search && (
            <ActionButton
              isQuiet
              onPress={() => navigate({ kind: "browse", path })}
            >
              <Close />
              <Text>Close Search</Text>
            </ActionButton>
          )}
          {view !== "list" && (
            <ActionButton
              isQuiet
              onPress={() => setSelected(new Set(items.map((i) => i.path)))}
              isDisabled={items.length === 0}
            >
              <SelectBox />
              <Text>Select All</Text>
            </ActionButton>
          )}
          <span className="studio-assets__count">
            {items.length} of {listing?.total ?? 0}
            {more ? "+" : ""}
          </span>
          {search?.look && anyLanguage && !searchedAs && (
            <span className="studio-assets__count" title="Read in its own language by the multilingual model">
              · any language
            </span>
          )}
          {search?.look && searchedAs && (
            <span className="studio-assets__count" title="The description in English, as the pictures were compared with it">
              as “{searchedAs}”
            </span>
          )}
          {search?.look && !search.weak && weaker > 0 && items.length > 0 && (
            <button
              type="button"
              className="studio-assets__weaker"
              onClick={() => navigate({ kind: "browse", path, search: { ...search, weak: true } })}
            >
              Show {weaker} weak {weaker === 1 ? "match" : "matches"}
            </button>
          )}
          <Picker
            label="Sort by"
            labelPosition="side"
            isQuiet
            selectedKey={sort}
            onSelectionChange={(k) => {
              setSort(k as SortKey);
              store("studio.assets.sort", String(k));
            }}
            items={SORTS}
            width="size-1600"
          >
            {(s) => <Item key={s.key}>{s.label}</Item>}
          </Picker>
          <TooltipTrigger>
            <ActionButton
              isQuiet
              aria-label={order === "asc" ? "Ascending" : "Descending"}
              isDisabled={sort === "none"}
              onPress={() => {
                const o = order === "asc" ? "desc" : "asc";
                setOrder(o);
                store("studio.assets.order", o);
              }}
            >
              {order === "asc" ? <SortOrderDown /> : <SortOrderUp />}
            </ActionButton>
            <Tooltip>{order === "asc" ? "Ascending" : "Descending"}</Tooltip>
          </TooltipTrigger>
          {clipboard.length > 0 && canCreate && (
            <ActionButton onPress={paste}>
              <Paste />
              <Text>Paste ({clipboard.length})</Text>
            </ActionButton>
          )}
          </>
        }
        tools={
          <>
          <DownloadsButton />
          <MenuTrigger>
            <ActionButton isQuiet aria-label="Metadata">
              <Text>Metadata</Text>
              <ChevronDown size="S" />
            </ActionButton>
            <Menu
              onAction={(k) =>
                setDialog({
                  kind: k === "export" ? "exportMetadata" : "importMetadata",
                })
              }
            >
              <Item key="export">Export Metadata (CSV)</Item>
              <Item key="import">Import Metadata (CSV)</Item>
            </Menu>
          </MenuTrigger>
          <MenuTrigger>
            <ActionButton isQuiet aria-label="View">
              {view === "card" ? (
                <ViewGrid />
              ) : view === "list" ? (
                <ViewList />
              ) : (
                <ViewColumn />
              )}
              <ChevronDown size="S" />
            </ActionButton>
            <Menu
              selectionMode="single"
              selectedKeys={[view]}
              onAction={(k) => {
                setView(k as ViewMode);
                store("studio.assets.view", String(k));
              }}
            >
              <Item key="card" textValue="Card View">
                <ViewGrid />
                <Text>Card View</Text>
              </Item>
              <Item key="list" textValue="List View">
                <ViewList />
                <Text>List View</Text>
              </Item>
              <Item key="column" textValue="Column View">
                <ViewColumn />
                <Text>Column View</Text>
              </Item>
            </Menu>
          </MenuTrigger>
          </>
        }
        primary={canCreate ? <CreateMenu onFolder={() => setDialog({ kind: "createFolder" })} onLiveCopy={() => setDialog({ kind: "createLiveCopy" })} onFiles={(files) => addUploads(path, files)} /> : undefined}
      />
      )}

      <div className="studio-assets__body">
        {rail && search && (
          <aside className="studio-assets__rail" aria-label="Filters">
            <h2>Filters</h2>
            <SearchRail
              facets={facets ?? DEFAULT_FACETS}
              query={search}
              scopeTitle={title || "Media"}
              scope={path === DAM_ROOT ? undefined : searchScope}
              onChange={(q) => navigate({ kind: "browse", path, search: q })}
            />
          </aside>
        )}
        {rail && !search && (
          <aside className="studio-assets__rail" aria-label="Filters">
            <h2>Filters</h2>
            <SearchField
              label="Name or title"
              value={filter}
              onChange={setFilter}
              width="100%"
            />
            <fieldset>
              <legend>Type</legend>
              {KIND_FILTERS.map((k) => (
                <Checkbox
                  key={k.kind}
                  isSelected={kinds.has(k.kind)}
                  onChange={(on) =>
                    setKinds((prev) => {
                      const next = new Set(prev);
                      if (on) next.add(k.kind);
                      else next.delete(k.kind);
                      return next;
                    })
                  }
                >
                  {k.label}
                </Checkbox>
              ))}
            </fieldset>
          </aside>
        )}
        <main
          className={`studio-assets__content${dragging ? " is-dragging" : ""}${busy && listing ? " is-loading" : ""}${view === "column" && !search && inspector && selectedItems.length > 0 ? " is-hugging" : ""}`}
          aria-busy={busy}
          onDragOver={(e) => {
            if ([...e.dataTransfer.types].includes("Files")) {
              e.preventDefault();
              setDragging(true);
            }
          }}
          onDragLeave={(e) => {
            if (e.currentTarget === e.target) setDragging(false);
          }}
          onDrop={onDrop}
        >
          {error ? (
            <IllustratedMessage>
              <Heading>Cannot open {path}</Heading>
              <Content>{error}</Content>
            </IllustratedMessage>
          ) : listing === null ? (
            <div className="studio-assets__loading">
              <ProgressCircle isIndeterminate aria-label="Loading" />
            </div>
          ) : items.length === 0 && (view !== "column" || search) ? (
            <IllustratedMessage>
              <Heading>
                {search
                  ? "No results"
                  : listing.items.length === 0
                    ? "This folder is empty"
                    : "Nothing matches the filters"}
              </Heading>
              <Content>
                {search?.look
                  ? weaker > 0
                    ? "No picture fits this description well. Describe it another way (in English)."
                    : "No picture below this folder could be compared with the description."
                  : search
                  ? "Nothing below this folder matches. Try other words or fewer filters."
                  : listing.items.length === 0
                    ? canCreate
                      ? "Drop files here or use Create to add files and folders."
                      : "You can look here, but not add files."
                    : "Change or clear the filters."}
              </Content>
              {search?.look && !search.weak && weaker > 0 && (
                <Button
                  variant="secondary"
                  onPress={() => navigate({ kind: "browse", path, search: { ...search, weak: true } })}
                >
                  Show the {weaker} weak {weaker === 1 ? "match" : "matches"}
                </Button>
              )}
            </IllustratedMessage>
          ) : view === "card" || (search && view === "column") ? (
            <CardView
              items={items}
              selected={selected}
              onToggle={toggle}
              onContextMenu={onItemMenu}
              onSelectOnly={(p) => setSelected(new Set(p))}
              onOpen={open}
              onProperties={properties}
            />
          ) : view === "list" ? (
            <ListView
              items={items}
              selected={selected}
              onToggle={toggle}
              onContextMenu={onItemMenu}
              onSelectOnly={(p) => setSelected(new Set(p))}
              onOpen={open}
              onProperties={properties}
            />
          ) : (
            <ColumnView
              client={client}
              path={path}
              ancestors={crumbs.map((c) => c.path)}
              items={items}
              selected={selected}
              onToggle={toggle}
              onSelectOnly={(p) => setSelected(new Set(p))}
              onOpen={open}
              onProperties={properties}
              onContextMenu={onItemMenu}
              onNavigate={(p) => navigate({ kind: "browse", path: p })}
            />
          )}
          {dragging && (
            <div className="studio-assets__dropzone">
              Drop files to upload to {title}
            </div>
          )}
        </main>
        {/* details are of what is ticked: with nothing selected there is no panel and no button for it */}
        {inspector && selectedItems.length > 0 && (
          <MediaInspector
            tab={inspector}
            onTab={setInspector}
            onClose={() => setInspector(null)}
            client={client}
            location={listing ? { path: listing.path, title: title || VOCAB.media } : null}
            selection={selectedItems}
            onChanged={refreshed}
            onOpen={open}
            onProperties={properties}
            onOpenPath={(p) =>
              // files have an extension, folders (nearly always) none
              /\.[a-z0-9]{2,5}$/i.test(p.substring(p.lastIndexOf("/") + 1)) ? openDetails(p, false) : navigate({ kind: "browse", path: p })
            }
          />
        )}
      </div>

      <UploadPanel
        uploads={uploads}
        onDismiss={dismiss}
        onResolve={resolveUpload}
      />
      {notice && (
        <div className="studio-assets__toast" role="status">
          <span>{notice}</span>
          <ActionButton
            isQuiet
            aria-label="Dismiss"
            onPress={() => setNotice(null)}
          >
            <Close size="S" />
          </ActionButton>
        </div>
      )}

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog?.kind === "createLiveCopy" && (
          <CreateLiveCopyDialog client={client} parent={path} onDone={() => changed()} close={() => setDialog(null)} />
        )}
        {dialog?.kind === "createFolder" && (
          <CreateFolderDialog
            client={client}
            parent={path}
            onDone={() => changed()}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "publish" && (
          <PublishDialog
            client={replication}
            paths={dialog.items.map((i) => i.path)}
            title={dialog.items.length === 1 ? dialog.items[0].title : `${dialog.items.length} items`}
            action={dialog.action}
            offerDeep={dialog.items.some((i) => i.type === "folder")}
            noun="Item"
            below="files and folders"
            onDone={() => {
              setDialog(null);
              changed(`${dialog.items.length} item${dialog.items.length > 1 ? "s" : ""} ${dialog.action === "activate" ? "published" : "unpublished"}`);
            }}
          />
        )}
        {dialog?.kind === "delete" && (
          <DeleteDialog
            client={client}
            items={dialog.items}
            onDone={() =>
              changed(
                `${dialog.items.length} item${dialog.items.length > 1 ? "s" : ""} deleted`,
              )
            }
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "move" && (
          <MoveDialog
            client={client}
            items={dialog.items}
            start={path}
            onDone={(paths, adjusted) =>
              changed(
                `${paths.length} item${paths.length > 1 ? "s" : ""} moved${adjusted ? `, ${adjusted} reference${adjusted > 1 ? "s" : ""} adjusted` : ""}`,
              )
            }
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "download" && (
          <DownloadDialog items={dialog.items} close={() => setDialog(null)} />
        )}
        {dialog?.kind === "imageSet" && (
          <ImageSetDialog
            client={client}
            folder={path}
            pictures={dialog.pictures}
            sets={items.filter((i): i is AssetItem => i.type === "asset" && !!i.imageSet)}
            onDone={(m) => changed(m)}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "addToCollection" && (
          <AddToCollectionDialog
            client={client}
            paths={dialog.paths}
            onDone={(m) => changed(m)}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "share" && (
          <ShareLinkDialog
            client={client}
            items={dialog.items}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "smartCollection" && search && (
          <CollectionDialog
            heading="Save as Smart Collection"
            onSave={async (t, description, isPublic) => {
              const c = await client.createCollection(
                t,
                description,
                isPublic,
                { scope: path, query: search },
              );
              setNotice(`Smart collection ${c.title} saved`);
            }}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "exportMetadata" && (
          <ExportMetadataDialog
            path={path}
            title={title || "Media"}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "importMetadata" && (
          <ImportMetadataDialog
            client={client}
            onDone={() => {
              lastChange.current = Date.now();
              void load(true);
            }}
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "extract" && (
          <ExtractArchiveDialog
            client={client}
            archive={dialog.archive}
            onDone={(r) =>
              changed(
                `${plural(r.created.length, "file")} extracted${r.skipped.length ? `, ${r.skipped.length} skipped` : ""}${r.into !== path ? ` into ${r.into.slice(r.into.lastIndexOf("/") + 1)}` : ""}`,
              )
            }
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "bulkProps" && (
          <BulkPropertiesDialog
            client={client}
            paths={dialog.paths}
            onDone={(n) =>
              changed(`Properties of ${n} file${n > 1 ? "s" : ""} saved`)
            }
            close={() => setDialog(null)}
          />
        )}
        {dialog?.kind === "folderProps" && (
          <FolderPropertiesDialog
            client={client}
            folder={dialog.folder}
            onDone={() => changed()}
            close={() => setDialog(null)}
          />
        )}
      </DialogContainer>
      {ctx.menu && <ContextMenu at={ctx.menu.at} actions={ctx.menu.actions} onClose={ctx.close} />}
    </div>
  );
}

/** Create ▾ — Folder, Files (file picker; files can also be dropped on the page). */
function CreateMenu({
  onFolder,
  onLiveCopy,
  onFiles,
}: {
  onFolder(): void;
  onLiveCopy(): void;
  onFiles(files: File[]): void;
}) {
  const fileTrigger = useRef<HTMLDivElement>(null);
  return (
    <>
      <MenuTrigger>
        <ActionButton UNSAFE_className="studio-assets__create">
          <Add />
          <Text>Create</Text>
        </ActionButton>
        <Menu
          onAction={(k) => {
            if (k === "folder") onFolder();
            else if (k === "livecopy") onLiveCopy();
            else fileTrigger.current?.querySelector("button")?.click();
          }}
        >
          <Item key="folder">Folder</Item>
          <Item key="files">Files</Item>
          <Item key="livecopy">Linked copy</Item>
        </Menu>
      </MenuTrigger>
      <div ref={fileTrigger} hidden>
        <FileTrigger
          allowsMultiple
          onSelect={(list) => list && onFiles([...list])}
        >
          <button type="button">Choose files</button>
        </FileTrigger>
      </div>
    </>
  );
}
