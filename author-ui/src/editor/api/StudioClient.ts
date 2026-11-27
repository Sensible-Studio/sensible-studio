import type { ContentNode, DialogResponse, PostEntry, RepositoryItem } from '../../granite';

/** A component type as the editor shows it (Components panel, Insert dialog). */
export interface ComponentInfo {
  resourceType: string;
  path?: string;
  title: string;
  group: string;
  description?: string;
  abbreviation?: string;
  dialog?: boolean;
  container?: boolean;
  /** Property an asset path is written to when an asset is dropped on the component, e.g. `./fileReference`. */
  drop?: string;
  /** Comma-separated mime type patterns accepted by `drop`. */
  dropAccept?: string;
  /** `cq:editConfig/cq:inplaceEditing`: the component can be edited in place (text, title, ...). */
  inplace?: InplaceConfig;
  /** `cq:editConfig/cq:actions`. */
  actions?: string[];
  /** `cq:editConfig/cq:actionConfigs`: extra toolbar actions (the editor runs the handlers it knows). */
  actionConfigs?: ActionConfig[] | null;
  /** `cq:icon` (a Coral icon name such as `image`, `text`, `experience`), inherited along the super types. */
  icon?: string | null;
  /** The component's super types, nearest first (Developer mode). */
  superTypes?: Array<{ resourceType: string; path: string; title?: string }>;
}

/** One `cq:editConfig/cq:actionConfigs/*` entry. */
export interface ActionConfig {
  name: string;
  icon?: string | null;
  text?: string | null;
  handler?: string | null;
}

/**
 * The component's `cq:inplaceEditing` node: `editorType` (`text`, `title`, `plaintext`, `image`,
 * `hybrid`), `active`, and the editor configuration under `configPath` (default `inplaceEditingConfig`).
 */
export interface InplaceConfig {
  editorType?: string;
  active?: boolean | string;
  configPath?: string;
  /** For `text`: the property that holds the HTML (default `text`). */
  textPropertyName?: string;
  /** Selector of the element edited inside the component (AEM default: its first child). */
  editElementQuery?: string;
  [key: string]: unknown;
}

/** Style System choices of a component instance (`GET /bin/studio/editor.json?op=styles`). */
export interface StyleGroup {
  name: string;
  label: string;
  multiple: boolean;
  styles: Array<{ id: string; label: string; classes: string }>;
}

export interface StylesInfo {
  path: string;
  policy?: string;
  groups: StyleGroup[];
  applied: string[];
}

export interface Breakpoint {
  name: string;
  title: string;
  /** Largest viewport width (px) the breakpoint applies to. */
  width?: number;
}

export interface PageInfo {
  path: string;
  name: string;
  title?: string;
  resourceType?: string;
  template?: string;
  templateTitle?: string;
  editableTemplate?: boolean;
  breakpoints: Breakpoint[];
  lastModified?: string;
  lastModifiedBy?: string;
  locked: boolean;
  lockOwner?: string;
  canUnlock?: boolean;
  /** What the signed-in user may do with the page; absent (an older server): everything is offered. */
  canEdit?: boolean;
  canPublish?: boolean;
  language?: string;
  /** Replication status, as AEM records it on the page. */
  lastReplicated?: string;
  lastReplicatedBy?: string;
  lastReplicationAction?: string;
  /** Activated, and not changed since. */
  published?: boolean;
}

/** `GET /bin/studio/editor.json?op=page`. */
export interface EditorPageData {
  page: PageInfo;
  /** Editable container path → resource types its policy allows. */
  containers: Record<string, string[]>;
  components: Record<string, ComponentInfo>;
  /** Container path → which component an asset dropped into it becomes (policy `assetToComponentMapping`). */
  assetMappings?: Record<string, Array<{ mimeType: string; resourceType: string; dropTarget?: string }>>;
  /** Components whose policy defines Style System groups (they get a Styles action). */
  styled?: string[];
  /** Set for the structure and initial content of an editable template: the template editor's data. */
  templateEditor?: import('../template/api').TemplateInfo;
}

export interface AssetInfo {
  path: string;
  name: string;
  title: string;
  mimeType: string;
  size?: number;
  width?: number;
  height?: number;
  thumbnail?: string;
  lastModified?: string;
}

export type AssetType = 'images' | 'documents' | 'videos' | 'all';

export interface AssetQuery {
  path?: string;
  type?: AssetType;
  q?: string;
  offset?: number;
  limit?: number;
}

export interface AssetPage {
  total: number;
  offset: number;
  assets: AssetInfo[];
}

/** `GET /bin/studio/editor.json?op=pages`: pages below a root, depth first. */
export interface PageListItem {
  path: string;
  title: string;
  /** 1 = direct child of the root */
  level: number;
  template?: string;
  resourceType?: string;
  lastModified?: string;
  /** false for pages that only group others (an experience fragment and its variations) */
  editable?: boolean;
}

/** A page or folder in the Sites console. */
export interface SiteItem {
  /** Whether the signed-in user may add a page or a folder below it; absent (an older server): offered. */
  canCreate?: boolean;
  path: string;
  name: string;
  title: string;
  type: 'page' | 'folder';
  hasChildren: boolean;
  template?: string;
  templateTitle?: string;
  resourceType?: string;
  lastModified?: string;
  lastModifiedBy?: string;
  created?: string;
  language?: string;
  locked?: boolean;
  lockOwner?: string;
  hideInNav?: boolean;
  description?: string;
  /** on the publish instances ('published'), changed since ('modified'), taken down ('unpublished'); absent: never published */
  publication?: 'published' | 'modified' | 'unpublished';
  lastPublished?: string;
  /** the preview tier (Publish to Preview), in the same words; absent: never sent to Preview */
  preview?: 'published' | 'modified' | 'unpublished';
  lastPreviewed?: string;
  /** DAM asset or template thumbnail path */
  thumbnail?: string;
  /** MSM: the page is rolled out from a blueprint (`cancelled`: its live copy is suspended) */
  liveCopy?: 'inherited' | 'cancelled';
  /** MSM: the page is the root of a live copy */
  liveCopyRoot?: boolean;
}

export interface SiteListing {
  item: SiteItem;
  trail: Array<{ path: string; title: string }>;
  children: SiteItem[];
}

/** A property somewhere in the repository that points at a page (or below it). */
export interface ReferenceInfo {
  /** resource holding the property */
  path: string;
  property: string;
  /** page containing that resource, if any */
  page?: string;
  pageTitle?: string;
  value: string;
}

export interface TemplateInfo {
  path: string;
  title: string;
  description?: string;
  thumbnail?: string;
}

/** Where a new or moved component goes: into `container`, before the sibling named `before` (or last). */
export interface Placement {
  container: string;
  before?: string | null;
}

/** Column settings of one component at one layout breakpoint (`cq:responsive/<breakpoint>`). */
export interface ResponsiveSetting {
  width?: number;
  offset?: number;
  behavior?: 'hide' | 'newline' | null;
}

/** An experience fragment template (`op=xfTemplates`). */
export interface XfTemplate {
  path: string;
  title: string;
  variantType?: string;
}

/** A node as it was at some moment, for undo: its content (null: it did not exist) and the sibling it preceded. */
export interface NodeSnapshot {
  content: ContentNode | null;
  /** Name of the next sibling (null: it was the last child). */
  next: string | null;
}

/** Everything the editor needs from the server. */
export interface StudioClient {
  /** The node at `path` with everything below it, and its position among its siblings. */
  snapshot(path: string): Promise<NodeSnapshot>;
  /** Puts the node at `path` back to a snapshot: replaced in content and position, or removed. */
  restore(path: string, snapshot: NodeSnapshot): Promise<void>;
  /** The merged dialog; with `contentPath` also the instance's content policy (read as `cqDesign`). */
  getDialog(resourceType: string, dialogName?: 'cq:dialog' | 'cq:design_dialog', contentPath?: string): Promise<DialogResponse>;
  /** Full content subtree of a resource (`.infinity.json`). */
  getContent(path: string): Promise<ContentNode>;
  /** Sling POST to `path`. */
  post(path: string, entries: PostEntry[]): Promise<void>;
  getPageData(pagePath: string): Promise<EditorPageData>;
  /** Style System groups of a component's policy and the styles applied to it. */
  getStyles(path: string): Promise<StylesInfo>;
  searchAssets(query: AssetQuery): Promise<AssetPage>;
  /** Pages below `root` (`xf`: only experience fragment variations). */
  listPages(root: string, depth: number, xf?: boolean): Promise<PageListItem[]>;
  /** Sites console: an item, its trail from /content and its child pages and folders. */
  siteChildren(path: string): Promise<SiteListing>;
  /** Templates allowed for a new page below `parent`. */
  pageTemplates(parent: string): Promise<TemplateInfo[]>;
  createPage(parent: string, template: string, title: string, name?: string): Promise<SiteItem>;
  /** An experience fragment: the fragment and its master variation from the template (`master`: the variation's path). */
  createExperienceFragment?(parent: string, template: string, title: string, name?: string): Promise<SiteItem & { master?: string }>;
  /** A copy of a page (with the pages below it) under `parent`; the copy does not carry the original's publication state. */
  copyPage?(path: string, parent: string): Promise<SiteItem>;
  /** A folder to keep pages or experience fragments in. */
  createFolder?(parent: string, title: string, name?: string): Promise<SiteItem>;
  /** Properties outside `path` that reference it (or anything below it). */
  references(path: string): Promise<ReferenceInfo[]>;
  /**
   * Moves and/or renames a page or folder; rewrites references to it in `adjustPages` and inside
   * the moved subtree. Returns its new path and how many properties were rewritten.
   */
  /** `republished`: the pages that were live and were published again at the new path; `republishError`: why they were not */
  movePage(path: string, destParent: string, name: string, adjustPages?: string[]): Promise<{ path: string; adjusted: number; republished?: string[]; republishError?: string }>;
  /** The signed-in user (`anonymous` when nobody is). */
  currentUser(): Promise<string>;
  /** Creates a component; returns the new resource path. */
  createComponent(resourceType: string, at: Placement, properties?: PostEntry[]): Promise<string>;
  deleteResource(path: string): Promise<void>;
  /** Moves a component (also within the same container); returns its new path. */
  moveResource(path: string, to: Placement): Promise<string>;
  /** Copies a component; returns the path of the copy. */
  copyResource(path: string, to: Placement): Promise<string>;
  setResponsive(path: string, breakpoint: string, setting: ResponsiveSetting): Promise<void>;
  /** Removes the layout settings of `paths` at `breakpoint`. */
  resetResponsive(paths: string[], breakpoint: string): Promise<void>;
  setPageLock(pagePath: string, locked: boolean): Promise<PageInfo>;
  /** Records the page as published or unpublished (`deep`: with its children). */
  setPublished(pagePath: string, published: boolean, deep?: boolean): Promise<PageInfo>;
  /** Experience fragment templates (their initial content is a variation). */
  xfTemplates(): Promise<XfTemplate[]>;
  /** Moves components into a new experience fragment and puts an Experience Fragment component in their place. */
  convertToXf(paths: string[], options: { parent: string; title: string; template: string; resourceType: string }): Promise<{ fragment: string; variation: string; component: string }>;
  /** Saves a version of the page (Timewarp and the Sites timeline list them). */
  createVersion(pagePath: string, label?: string, comment?: string): Promise<{ id: string; name: string; created?: string }>;
  listChildren(path: string): Promise<RepositoryItem[]>;
  /** URL that renders the page for the content frame. */
  pageUrl(pagePath: string, mode: 'edit' | 'disabled'): string;
  assetUrl(path: string): string;
  /** URL of the 319px thumbnail rendition of a DAM asset. */
  assetThumbnail(path: string): string;
  /** `dc:title` / `dc:description` of a DAM asset, null when it cannot be read. */
  assetInfo(path: string): Promise<{ path: string; title?: string; description?: string; mimeType?: string } | null>;
  /** The featured image (`cq:featuredimage`) of a page, null when it has none. */
  pageImage(pagePath: string): Promise<{ fileReference: string; alt?: string } | null>;
}

export class StudioHttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export const nameOf = (path: string) => path.slice(path.lastIndexOf('/') + 1);
export const parentOf = (path: string) => path.slice(0, path.lastIndexOf('/'));
