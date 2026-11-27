import type { EditOperation, MapArea } from "./imageEdit";

/**
 * What the Assets screens need from Sensible Studio (`/bin/studio/dam.json`, `/bin/studio/dam/upload`). Kept as an
 * interface so the screens can be tested against an in-memory implementation.
 */

export interface Crumb {
  path: string;
  title: string;
}

export interface FolderItem {
  type: "folder";
  path: string;
  name: string;
  title: string;
  /** processing profile set on this folder (not inherited ones) */
  processingProfile?: string | null;
  /** metadata schema set on this folder (not inherited ones) */
  metadataSchema?: string | null;
  /** metadata profile set on this folder (not inherited ones): default values for the files uploaded here */
  metadataProfile?: string | null;
  /** folder metadata schema set on this folder (not inherited ones): the fields of the folder's own Properties */
  folderMetadataSchema?: string | null;
  /** asset chosen as the folder's card thumbnail (instead of the collage) */
  thumbnailAsset?: string | null;
  /** Smart Tags for uploads here: set on this folder ('on' / 'off'), or inherited when absent */
  smartTagging?: "on" | "off" | null;
  /** image profile (Smart Crop) set on this folder (not inherited ones) */
  imageProfile?: string | null;
  modified?: string | null;
  /** Up to three thumbnail URLs of assets inside, for the folder card collage. */
  previews?: string[];
  /** in a live copy: its root, following its source, or suspended (absent elsewhere and for local items) */
  liveCopy?: LiveCopyStatus;
  /** false when the user may not add files or folders here (absent: allowed) */
  canCreate?: boolean;
  /** false when the user may not change its properties (absent: allowed) */
  canEdit?: boolean;
  /** false when the user may not delete or move it (absent: allowed) */
  canDelete?: boolean;
  canPublish?: boolean;
}

/** `op=similar`: the pictures most like one asset. */
export interface SimilarResult {
  path: string;
  /** best first; `score` is 0..1 */
  items: Array<AssetItem & { score: number }>;
  compared: number;
  /** pictures without an embedding yet: they could not be compared */
  notIndexed: number;
  /** the asset itself has no picture */
  noImage?: boolean;
}

export interface AssetItem {
  /** in a live copy: following its source, or suspended (absent elsewhere and for local files) */
  liveCopy?: LiveCopyStatus;
  type: "asset";
  path: string;
  name: string;
  title: string;
  mimeType?: string | null;
  size?: number | null;
  width?: number | null;
  height?: number | null;
  modified?: string | null;
  created?: string | null;
  /** `dam:assetState`: processing, processed, failed (or absent for imported content). */
  state?: string | null;
  /** in a search by what pictures show: how well the picture fits the description, 0..1 */
  score?: number;
  thumbnail?: string | null;
  /** The web rendition (cq5dam.web.*), the best preview. */
  web?: string | null;
  original?: string | null;
  /** A content fragment (opens in the fragment editor, has no image renditions). */
  contentFragment?: boolean;
  /** An image set: a file that names several pictures, in an order, to be shown together. */
  imageSet?: boolean;
  /** of a set: shown as a gallery (pictures and videos, picked by thumbnails) or as a spin (pictures turned by dragging) */
  setKind?: "gallery" | "spin";
  /** of a set: how many pictures it holds (those this reader may see) */
  members?: number;
  description?: string | null;
  /** user who has the asset checked out (`cq:drivelock`) */
  checkedOutBy?: string | null;
  /** the metadata has a license (adobe_dam:restrictions or xmpRights:WebStatement): with DRM on, downloaded after agreeing */
  licensed?: boolean;
  /** when the asset stops being usable (`prism:expirationDate`), and whether that is past */
  expires?: string | null;
  expired?: boolean;
  /** length in seconds, for videos and audio */
  duration?: number | null;
  /** the frame a video's thumbnails are made from (`cq5dam.poster.jpg`), when one was chosen */
  poster?: string | null;
  /** a video as browsers play it (made by ffmpeg on the server when the original is not that) */
  preview?: string | null;
  /** false when the user may not change it: metadata, edits, check out (absent: allowed) */
  canEdit?: boolean;
  /** false when the user may not delete or move it (absent: allowed) */
  canDelete?: boolean;
  canPublish?: boolean;
}

export type DamItem = FolderItem | AssetItem;

export type LiveCopyStatus = "root" | "inherited" | "suspended";

/** How a gallery looks, saved under a name (AEM's viewer presets for image sets). */
export interface GalleryPreset {
  name: string;
  title: string;
  thumbs: "below" | "left" | "right" | "dots" | "none";
  arrows: boolean;
  /** "" = each picture's own shape */
  ratio: "" | "16:9" | "3:2" | "4:3" | "1:1";
  zoom: boolean;
}

/** Where a folder or file stands in Live Copy (AEM's MSM for assets). */
export interface LiveCopyInfo {
  /** inside a live copy: its root, the source of this item, and how it follows it */
  root?: string;
  source?: string;
  sourceExists?: boolean;
  status?: LiveCopyStatus | "local";
  lastRolledOut?: string | null;
  lastRolledOutBy?: string | null;
  /** fields changed in the live copy, which no longer follow the source */
  cancelledProperties?: string[];
  /** the source's changes come at once, without a rollout */
  pushOnModify?: boolean;
  /** the live copies that follow this item (it is their source, or in it) */
  liveCopies: Array<{ root: string; path: string; title: string; status: LiveCopyStatus | "local" | "missing" }>;
}

/** What a rollout or synchronization did. */
export interface RolloutResult {
  added: number;
  updated: number;
  removed: number;
  skipped: string[];
}

export interface Listing {
  path: string;
  title: string;
  type: "folder" | "asset";
  breadcrumbs: Crumb[];
  total: number;
  offset: number;
  items: DamItem[];
  /** false while the media service is down: uploads wait as "Processing" until it is back */
  processingAvailable?: boolean;
  /** what the user may do in this folder (absent: allowed; the server checks each action anyway) */
  canCreate?: boolean;
  canEdit?: boolean;
  canDelete?: boolean;
  canPublish?: boolean;
}

/** Whether `item` allows `what` (only an explicit false denies). */
export const may = (
  item:
    | { canCreate?: boolean; canEdit?: boolean; canDelete?: boolean; canPublish?: boolean }
    | null
    | undefined,
  what: "canCreate" | "canEdit" | "canDelete" | "canPublish",
) => item?.[what] !== false;

export type SortKey = "none" | "name" | "title" | "modified" | "size" | "type";

export interface ListOptions {
  sort?: SortKey;
  order?: "asc" | "desc";
  offset?: number;
  limit?: number;
}

export interface Rendition {
  name: string;
  mimeType?: string | null;
  size?: number | null;
  width?: number | null;
  height?: number | null;
  url: string;
}

export interface AssetDetails extends AssetItem {
  metadata: Record<string, unknown>;
  renditions: Rendition[];
  failed: Array<{ name: string; reason?: string | null }>;
  processingError?: string | null;
  breadcrumbs: Crumb[];
  /** Image map areas in Core Components notation (`jcr:content/imageMap`). */
  imageMap?: string | null;
  /** assets related to this one (Relate) */
  related?: RelatedAsset[];
  /** of an image set: its pictures, in order */
  setMembers?: AssetItem[];
  /** of an image set: pictures it names that are gone, or that this reader may not see */
  setMissing?: number;
  /** of a video: false when the server has no ffmpeg (no poster, thumbnails or browser copy) */
  videoTools?: boolean;
  /** the folder's metadata schema: the Properties form */
  schema?: MetadataSchema | null;
  /** Dynamic Media smart crops (when the folder has an image profile) */
  smartCrops?: SmartCrop[];
  /** what the image model found: taxonomy tags (with confidence 0..1) and plain keywords */
  smartTags?: SmartTags | null;
  /** color tags: the colors the picture is mostly made of (null until they were found) */
  colors?: Array<{ name: string; share: number }> | null;
}

export interface SmartTags {
  tags: Array<{ id: string; title: string; confidence?: number | null }>;
  keywords: string[];
  tagged?: string | null;
}

export interface SmartTagsSettings {
  enabled: boolean;
  /** tag ids whose sub-tags the model may choose from, e.g. wknd-shared:activity */
  tagRoots: string[];
  /** also plain keywords (beach, dog, ...) */
  keywords: boolean;
  max: number;
  minConfidence: number;
}

/** A tag Auto tags learned from the pictures that have it (Smart Tag training). */
export interface LearnedTag {
  tag: string;
  title: string;
  /** pictures it was learned from */
  examples: number;
  /** share of those pictures it finds again, each compared with the others */
  recall: number;
  /** pictures without the tag it would be given to */
  others: number;
  threshold: number;
  trained?: string;
  trainedBy?: string;
}

export interface SmartTagTraining {
  models: LearnedTag[];
  minExamples: number;
  goodExamples: number;
  /** for the tags asked about: pictures with the tag, and how many of them were looked at (can teach it) */
  ready?: Array<{ tag: string; title: string; tagged: number; examples: number }>;
}

/** One tag of a training: learned, or why not (`error`). */
export type TrainingResult = { tag: string; title: string; examples: number; error?: string } & Partial<LearnedTag>;

export interface SmartTagsStatus {
  settings: SmartTagsSettings;
  model: {
    model?: string;
    repository?: string;
    revision?: string;
    license?: string;
    installed?: boolean;
    loaded?: boolean;
    files?: Array<{ name: string; size: number; present: boolean }>;
    error?: string;
  };
  install: {
    state: "idle" | "downloading" | "done" | "failed";
    file?: string | null;
    bytes: number;
    total: number;
    error?: string | null;
  };
  /** the optional multilingual model: pictures found by a description in any language */
  searchModel?: SmartTagsStatus["model"];
  searchInstall?: SmartTagsStatus["install"];
  admin: boolean;
  /** with a folder: whether a folder inside it that inherits gets Smart Tags */
  effective?: boolean;
}

export type SchemaFieldType =
  "text" | "textarea" | "multi" | "number" | "date" | "checkbox" | "select";

export interface SchemaField {
  label: string;
  /** metadata property, e.g. dc:title */
  property: string;
  type: SchemaFieldType;
  required?: boolean;
  description?: string;
  options?: Array<{ value: string; text: string }>;
}

export interface SchemaTab {
  title: string;
  fields: SchemaField[];
}

export interface MetadataSchema {
  path: string;
  name: string;
  title: string;
  tabs: SchemaTab[];
  /** A metadata profile's default values, by property. */
  values?: Record<string, FieldValue>;
}

/**
 * The other forms of Tools > Assets, made like metadata schemas: folder metadata schemas (the fields of a
 * folder's Properties) and metadata profiles (default values for the files uploaded to a folder).
 */
export type FormKind = "folderSchema" | "profile";

/** A value for {@link DamClient.saveFields}: null removes the property. */
export type FieldValue =
  string | number | boolean | string[] | { date: string } | null;

export interface RelatedAsset {
  path: string;
  name: string;
  title: string;
  mimeType?: string | null;
  thumbnail?: string | null;
}

export interface ExtractResult {
  created: string[];
  folders: string[];
  /** entries not extracted: existing names, unsupported types, unsafe paths */
  skipped: string[];
  /** the folder extracted into */
  into: string;
}

export interface EditResult {
  edited: boolean;
  width: number;
  height: number;
}

export interface FolderNode {
  path: string;
  name: string;
  title: string;
  hasChildren: boolean;
}

/** Editable Basic metadata. Arrays are multi-valued properties. */
export interface BasicMetadata {
  "dc:title"?: string;
  "dc:description"?: string;
  "dc:language"?: string;
  "dc:rights"?: string;
  "dc:subject"?: string[];
  "dc:creator"?: string[];
  "dc:contributor"?: string[];
  /** tag IDs (`ns:a/b`) */
  "cq:tags"?: string[];
  /** the day after which the file is expired (`yyyy-MM-dd`); empty clears it */
  "prism:expirationDate"?: string;
}

/** Multi-valued fields; in bulk editing they can be appended to instead of replaced. */
export const MULTI_FIELDS = [
  "dc:subject",
  "dc:creator",
  "dc:contributor",
  "cq:tags",
] as const;
export type MultiField = (typeof MULTI_FIELDS)[number];

/** A JCR version of an asset (the Timeline). */
export interface AssetVersion {
  name: string;
  created: string;
  labels: string[];
  comment?: string | null;
  createdBy?: string | null;
}

/** A rendition of a processing profile: `<name>.<format>`, fitted inside width x height. */
export interface ProfileRendition {
  name: string;
  /** images: the box; videos (mp4, webm): only `height` counts, the most lines kept */
  width: number;
  height: number;
  format: "jpeg" | "png" | "webp" | "mp4" | "webm";
  quality: number;
  /** pictures only: the watermark is drawn on it */
  watermark?: boolean;
}

export type WatermarkPosition = "center" | "top-left" | "top-right" | "bottom-left" | "bottom-right" | "tile";

/** The watermark drawn on the renditions that ask for it: a picture from Media. */
export interface WatermarkSetting {
  image: string | null;
  position: WatermarkPosition;
  /** 1–100 */
  opacity: number;
  /** 5–100, % of the rendition's width */
  size: number;
  /** the picture is there and can be drawn */
  ready?: boolean;
  thumbnail?: string | null;
}

export interface ProcessingProfile {
  path: string;
  name: string;
  title: string;
  renditions: ProfileRendition[];
}

/** Dynamic Media: an image preset, named parameters of the public `/is/image` URL. */
export interface ImagePreset {
  name: string;
  title: string;
  description?: string | null;
  /** wid, hei, fmt, qlt, fit, op_usm, bgc, ... as strings */
  params: Record<string, string>;
  /** the parameters as a query string: `wid=1600&fmt=webp` */
  query: string;
  /** for an asset: its URL with this preset */
  url?: string;
}

/** Dynamic Media Smart Crop: an image profile lists the aspect ratios a folder's images get crops for. */
export interface CropRatio {
  name: string;
  width: number;
  height: number;
}

export interface ImageProfile {
  path: string;
  name: string;
  title: string;
  ratios: CropRatio[];
}

/** One smart crop of an asset: fractions of the image, and whether an author set it. */
export interface SmartCrop {
  name: string;
  left: number;
  top: number;
  width: number;
  height: number;
  imageWidth: number;
  imageHeight: number;
  ratioWidth: number;
  ratioHeight: number;
  manual: boolean;
  url?: string | null;
}

export interface ImagePresets {
  presets: ImagePreset[];
  /** the asset's public URL without parameters (when asked for an asset) */
  url?: string;
  /** the request parameters the URL accepts */
  parameters: string[];
  admin: boolean;
}

/** A collection: assets and folders referenced (not copied), or a saved search (smart). */
export interface DamCollection {
  type: "collection";
  path: string;
  name: string;
  title: string;
  description?: string | null;
  owner?: string | null;
  public: boolean;
  modified?: string | null;
  smart?: boolean;
  /** smart collections: where and what they search */
  scope?: string;
  query?: SearchQuery;
  /** plain collections */
  count?: number;
  previews?: string[];
}

/** A Share Link as listed for its owner (the token is only known when it is made). */
export interface ShareLink {
  id: string;
  paths: string[];
  expires: string;
  created?: string;
  renditions?: boolean;
}

/** What to do when an uploaded file has the name of an existing asset. */
export type UploadConflict = "rename" | "replace" | "version";

/** A property somewhere in the repository that names an asset or folder (or something below it). */
/** Where a note sits on the picture, in fractions of its width and height. */
export interface CommentRegion {
  /** the box the mark fits in, in fractions of the picture */
  x: number;
  y: number;
  w: number;
  h: number;
  /** rect (absent), ellipse, arrow (from its first point to its last) or free (a line drawn by hand) */
  shape?: NoteShape;
  /** arrow and free: the points, in fractions */
  points?: Array<[number, number]>;
  /** on a video: the second the note is about */
  time?: number;
}

export type NoteShape = "rect" | "ellipse" | "arrow" | "free";

/** A comment on a file, or a note on its picture when it has a region. */
export interface AssetComment {
  id: string;
  text: string;
  author: string | null;
  created: string;
  /** written by the person looking (they may remove it) */
  mine: boolean;
  region?: CommentRegion;
  color?: string;
}

export interface DamReference {
  /** resource holding the property */
  path: string;
  property: string;
  /** page containing that resource, if any */
  page?: string;
  pageTitle?: string;
  value: string;
}

export interface MoveResult {
  paths: string[];
  /** properties rewritten in the adjusted pages */
  adjusted: number;
}

/** Filters of the Assets search (DamSearch on the server); empty fields do not filter. */
export interface SearchQuery {
  fulltext?: string;
  /** kinds as in format.ts: image, video, audio, pdf, document, archive, fragment, file */
  types?: string[];
  /** tag IDs; an asset matches with the tag or one below it */
  tags?: string[];
  /** last modified within: 1d, 7d, 30d, 365d */
  modified?: string;
  /** small (< 1 MB), medium (1 to 10 MB), large (> 10 MB) */
  size?: "small" | "medium" | "large";
  orientation?: "landscape" | "portrait" | "square";
  /** a metadata property has one of the values (the options filters of the search form); properties are ANDed */
  meta?: Record<string, string[]>;
  /** a metadata property contains the text, any case (the text filters of the search form) */
  metaText?: Record<string, string>;
  /**
   * Read the text as a description of what a picture shows ("a red tent on a mountain") instead
   * of words to find in titles and tags; the other filters do not apply then.
   */
  look?: boolean;
  /** with `look`: also the pictures that fit the description only weakly (left out otherwise) */
  weak?: boolean;
  /** where the search runs: the folder and below, or all of Media (default: Assets Omnisearch's setting) */
  scope?: "folder" | "all";
  /**
   * with `look`: the description is not English; the instance's translation provider puts it
   * into English first (the picture model reads only English)
   */
  translate?: boolean;
}

/** The filters a search form can show: the five built in, then filters on a metadata property. */
export type SearchFacetType = "types" | "tags" | "modified" | "size" | "orientation" | "colors" | "options" | "text";

/** One filter of the Media search rail (Search Forms; DamSearchForms on the server). */
export interface SearchFacet {
  type: SearchFacetType;
  label: string;
  description?: string;
  /** options and text: the metadata property, such as dc:creator */
  property?: string;
  /** tags: the tag the picker starts at, such as wknd: */
  root?: string;
  /** options: the values to tick */
  options?: Array<{ value: string; label: string }>;
}

export interface SearchForm {
  facets: SearchFacet[];
  /** false: the default form (nothing saved) */
  custom: boolean;
  modified?: string;
  modifiedBy?: string;
}

export interface MetadataValues {
  property: string;
  /** the most used first */
  values: Array<{ value: string; count: number }>;
  /** there are more values than were listed */
  more: boolean;
  /** not every file was read (a large folder) */
  truncated: boolean;
}

export interface SearchResult {
  path: string;
  total: number;
  /** more than `total` (Assets Omnisearch counts only up to its limit) */
  more?: boolean;
  offset: number;
  /** the walk stopped at its limit: there may be more */
  truncated?: boolean;
  /** a search by what pictures show: the pictures that could not be compared yet */
  notIndexed?: number;
  /** a search by what pictures show: the pictures left out because they fit only weakly */
  weaker?: number;
  /** a search by what pictures show, translated first: the English phrase that was searched */
  searchedAs?: string;
  /** a search by what pictures show, read in its own language by the multilingual model */
  anyLanguage?: boolean;
  items: DamItem[];
}

const MB = 1024 * 1024;
export const SIZE_RANGES: Record<
  NonNullable<SearchQuery["size"]>,
  [number, number]
> = {
  small: [-1, MB - 1],
  medium: [MB, 10 * MB],
  large: [10 * MB + 1, -1],
};

export interface DamClient {
  list(path: string, options?: ListOptions): Promise<Listing>;
  /** Assets (and folders, by name or title) below `scope` that match. Relevance order unless sorted. */
  search(
    scope: string,
    query: SearchQuery,
    options?: ListOptions,
  ): Promise<SearchResult>;
  asset(path: string): Promise<AssetDetails>;
  folders(path: string): Promise<FolderNode[]>;
  createFolder(parent: string, title: string, name?: string): Promise<string>;
  remove(paths: string[]): Promise<void>;
  /**
   * Moves into `dest`; `name` renames (one item only). References in `adjust` (pages or the
   * resources holding them) are rewritten to the new paths.
   */
  move(
    paths: string[],
    dest: string,
    name?: string,
    adjust?: string[],
  ): Promise<MoveResult>;
  /** Everything that references `path` or anything below it (the Sites reference search). */
  references(path: string): Promise<DamReference[]>;
  /** comments and notes on the picture (Timeline, Annotate) */
  comments(path: string): Promise<{ comments: AssetComment[]; canComment: boolean }>;
  addComment(path: string, text: string, region?: CommentRegion, color?: string): Promise<AssetComment>;
  deleteComment(path: string, id: string): Promise<void>;
  copy(paths: string[], dest: string): Promise<string[]>;
  reprocess(paths: string[]): Promise<void>;
  /** Saves the given fields; multi-valued ones in `append` are added to the existing values. */
  saveMetadata(
    path: string,
    fields: BasicMetadata,
    append?: readonly MultiField[],
  ): Promise<void>;
  /** Versions of an asset, newest first. */
  versions(path: string): Promise<AssetVersion[]>;
  createVersion(
    path: string,
    label?: string,
    comment?: string,
  ): Promise<AssetVersion>;
  /** Restores `version`; the current state is saved as a version first (returned). */
  restoreVersion(path: string, version: string): Promise<AssetVersion>;
  setFolderTitle(path: string, title: string): Promise<void>;
  /** The folder card shows this asset's thumbnail; `null` goes back to the collage. */
  setFolderThumbnail(path: string, asset: string | null): Promise<void>;
  /**
   * Image editor save: `operations` replace the original (renditions follow), `areas` (fractions
   * of the final image) replace the image map; `[]` removes it, `undefined` leaves it alone.
   */
  edit(
    path: string,
    operations: EditOperation[],
    areas?: MapArea[],
  ): Promise<EditResult>;
  /** Metadata schemas; with a folder or asset, also the one that applies there. */
  schemas(
    path?: string,
  ): Promise<{ schemas: MetadataSchema[]; effective?: string | null }>;
  saveSchema(
    name: string,
    title: string,
    tabs: SchemaTab[],
  ): Promise<MetadataSchema>;
  deleteSchema(name: string): Promise<void>;
  setFolderSchema(folder: string, schema: string | null): Promise<void>;
  /** Folder metadata schemas or metadata profiles; with a folder or asset, the one that applies there. */
  forms(
    kind: FormKind,
    path?: string,
  ): Promise<{ forms: MetadataSchema[]; effective?: string | null }>;
  /** Search Forms: the filters the Media search shows (the default form when none was saved). */
  searchForm(): Promise<SearchForm>;
  saveSearchForm(facets: SearchFacet[]): Promise<SearchForm>;
  /** Back to the default form. */
  resetSearchForm(): Promise<SearchForm>;
  /** The values a metadata property has on the files below a folder, the most used first. */
  metadataValues(path: string, property: string): Promise<MetadataValues>;
  saveForm(
    kind: FormKind,
    name: string,
    title: string,
    tabs: SchemaTab[],
    values?: Record<string, FieldValue>,
  ): Promise<MetadataSchema>;
  deleteForm(kind: FormKind, name: string): Promise<void>;
  /** Sets the folder's form of that kind; `null` removes it (the parent's applies again). */
  setFolderForm(kind: FormKind, folder: string, form: string | null): Promise<void>;
  /** Fills the empty properties of the files in a folder (and subfolders) from their metadata profile. */
  applyProfile(
    folder: string,
  ): Promise<{ files: number; changed: number; truncated: boolean }>;
  /** A folder's metadata schema (own or inherited) and the folder's values for its fields. */
  folderMetadata(
    folder: string,
  ): Promise<{ schema: MetadataSchema | null; values: Record<string, unknown> }>;
  saveFolderMetadata(
    folder: string,
    fields: Record<string, FieldValue>,
  ): Promise<void>;
  /** Typed metadata from a schema form. */
  saveFields(path: string, fields: Record<string, FieldValue>): Promise<void>;
  /** Processing profiles; with a folder, also the one that applies there (own or inherited). */
  profiles(
    folder?: string,
  ): Promise<{ profiles: ProcessingProfile[]; effective?: string | null; watermark?: WatermarkSetting; admin?: boolean }>;
  /** Gallery presets (Image Presets page). */
  galleryPresets(): Promise<{ presets: GalleryPreset[]; admin?: boolean }>;
  /** creates (no name) or changes one; published at once */
  saveGalleryPreset(preset: Omit<GalleryPreset, "name"> & { name?: string }): Promise<GalleryPreset>;
  deleteGalleryPreset(name: string): Promise<void>;
  /** Live Copy of Media folders. */
  liveCopy(path: string): Promise<LiveCopyInfo>;
  createLiveCopy(source: string, parent: string, title: string, name?: string, push?: boolean): Promise<RolloutResult & { path: string }>;
  /** the source's changes are pushed at once (or not) */
  liveCopyPush(path: string, push: boolean): Promise<void>;
  /** a live copy (or part of it) takes the source's changes; with reset, what was changed there goes too */
  liveCopySync(path: string, reset?: boolean): Promise<RolloutResult>;
  /** from the source: every live copy following it takes its changes */
  rollout(path: string): Promise<RolloutResult>;
  liveCopySuspend(path: string, suspend: boolean): Promise<void>;
  liveCopyDetach(path: string): Promise<void>;
  /** a field changed in the live copy follows the source again */
  liveCopyInherit(path: string, field: string): Promise<void>;
  /** Administrators: the watermark; an empty image removes it. */
  saveWatermark(setting: WatermarkSetting): Promise<WatermarkSetting>;
  saveProfile(
    name: string,
    title: string,
    renditions: ProfileRendition[],
  ): Promise<ProcessingProfile>;
  deleteProfile(name: string): Promise<void>;
  /** Dynamic Media image presets; with an asset, the URL of each for it. */
  imagePresets(asset?: string): Promise<ImagePresets>;
  saveImagePreset(
    name: string,
    title: string,
    description: string,
    params: Record<string, string>,
  ): Promise<ImagePreset>;
  deleteImagePreset(name: string): Promise<void>;
  /** Smart Crop image profiles; with a folder, the one that applies there (own or inherited). */
  imageProfiles(
    folder?: string,
  ): Promise<{
    profiles: ImageProfile[];
    effective?: string | null;
    admin: boolean;
  }>;
  saveImageProfile(
    name: string,
    title: string,
    ratios: CropRatio[],
  ): Promise<ImageProfile>;
  deleteImageProfile(name: string): Promise<void>;
  /** Sets the folder's image profile (null removes it) and queues its images; returns how many. */
  setFolderImageProfile(
    folder: string,
    profile: string | null,
  ): Promise<number>;
  /** queues smart crops for assets (folders: everything below); returns how many */
  smartCropRun(paths: string[]): Promise<number>;
  /** the pictures most like this one, best first */
  similar(path: string, limit?: number): Promise<SimilarResult>;
  /** queues the pictures that have not been looked at yet (all of Media, or below `paths`); returns how many */
  similarIndex(paths?: string[]): Promise<number>;
  /** an author's crop (fractions; the height follows the ratio) */
  setSmartCrop(
    path: string,
    name: string,
    left: number,
    top: number,
    width: number,
  ): Promise<SmartCrop>;
  /** back to the computed crops */
  resetSmartCrops(path: string): Promise<SmartCrop[]>;
  /** Sets the folder's profile; `null` removes it (the parent's applies again). */
  setFolderProfile(folder: string, profile: string | null): Promise<void>;
  /**
   * Share Link: anyone with the returned URL can download the items until `days` have passed (or, with
   * `options.amount` and `options.unit`, that long); `options.originals` false gives only the renditions.
   */
  createShareLink(
    paths: string[],
    days: number,
    renditions: boolean,
    options?: { originals?: boolean; amount?: number; unit?: string },
  ): Promise<{ url: string; id: string; expires: string }>;
  /** The user's own links (optionally only those that share `path`). */
  shareLinks(path?: string): Promise<ShareLink[]>;
  revokeShareLink(id: string): Promise<void>;
  collections(): Promise<DamCollection[]>;
  collection(
    path: string,
  ): Promise<{ collection: DamCollection; total: number; items: DamItem[] }>;
  /** With `smart`, the collection is that search below `smart.scope`. */
  createCollection(
    title: string,
    description: string,
    isPublic: boolean,
    smart?: { scope: string; query: SearchQuery },
  ): Promise<DamCollection>;
  updateCollection(
    path: string,
    fields: { title?: string; description?: string; public?: boolean },
  ): Promise<DamCollection>;
  deleteCollection(path: string): Promise<void>;
  /** Returns how many items were added (or removed). */
  addToCollection(collection: string, paths: string[]): Promise<number>;
  removeFromCollection(collection: string, paths: string[]): Promise<number>;
  /** Metadata Import: a CSV with `assetPath` first, then `name {{Type[: multi]}}` columns. */
  importMetadata(csv: File): Promise<ImportResult>;
  /** Smart Tags: model and settings, the model download, tagging assets again, and an author's corrections. */
  smartTagsStatus(folder?: string): Promise<SmartTagsStatus>;
  /** a folder's own Smart Tags choice; null inherits */
  setFolderSmartTagging(
    folder: string,
    value: "on" | "off" | null,
  ): Promise<void>;
  saveSmartTagsSettings(
    settings: SmartTagsSettings,
  ): Promise<SmartTagsSettings>;
  installSmartTagsModel(): Promise<void>;
  /** downloads the multilingual model (picture search in any language) into the image service */
  installSearchModel(): Promise<void>;
  /** queues assets (folders: everything below) for tagging; returns how many */
  smartTag(paths: string[]): Promise<number>;
  /** Smart Tag training: the tags learned; with `tags`, how many pictures could teach each. */
  smartTagTraining(tags?: string[]): Promise<SmartTagTraining>;
  /** Learns the tags from the pictures that have them (admin). */
  trainSmartTags(tags: string[]): Promise<TrainingResult[]>;
  forgetSmartTag(tag: string): Promise<void>;
  /**
   * Color tags for the files named or below the folders named: those without, or all `again`. At most 2000 files
   * a call; `more` says there are more to do.
   */
  colorTag(paths: string[], again?: boolean): Promise<{ tagged: number; skipped: number; more: boolean }>;
  /** applies a smart tag as a tag (`apply`), or removes it for good */
  changeSmartTag(path: string, tag: string, apply: boolean): Promise<SmartTags>;
  /** Asset Reports: what can be made, the user's reports, a page of one, create, delete. */
  reportCatalog(): Promise<ReportCatalog>;
  reports(): Promise<ReportInfo[]>;
  report(id: string, offset?: number, limit?: number): Promise<ReportPage>;
  createReport(request: ReportRequest): Promise<ReportInfo>;
  deleteReports(ids: string[]): Promise<number>;
  /**
   * The frame (JPEG or PNG) a video's thumbnails are made from; null removes it. The asset is
   * processed again.
   */
  setPoster(path: string, image: Blob | null): Promise<void>;
  /** Checkout locks assets for everyone else; Check In releases them. */
  checkout(paths: string[]): Promise<void>;
  checkin(paths: string[]): Promise<void>;
  /** Relates every asset with every other one; returns the new links. */
  relate(paths: string[]): Promise<number>;
  /** makes an image set of these pictures (in this order) in `parent`; returns its path */
  createSet(parent: string, title: string, members: string[], kind?: "gallery" | "spin"): Promise<string>;
  /** replaces the pictures of an image set, in this order; with `kind`, also how the set is shown */
  setMembers(path: string, members: string[], kind?: "gallery" | "spin"): Promise<void>;
  unrelate(path: string, others: string[]): Promise<void>;
  /** Unpacks a ZIP asset next to it, or into a new folder named after it. */
  extractArchive(
    path: string,
    options: {
      into: "here" | "folder";
      conflict: "skip" | "replace" | "rename";
    },
  ): Promise<ExtractResult>;
  /** Assets anywhere in the DAM with the same content as `file` (by SHA-1), for the upload's duplicate check. */
  findDuplicates(file: File): Promise<string[]>;
  /**
   * Uploads one file into `folder`; `onProgress` gets 0..1. Returns the asset path. When an asset
   * of that name exists and `conflict` is not given, rejects with a {@link DamError} of status 409.
   */
  upload(
    folder: string,
    file: File,
    onProgress?: (fraction: number) => void,
    conflict?: UploadConflict,
  ): Promise<string>;
  currentUser(): Promise<string>;
}

export class DamError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export const DAM_ROOT = "/content/dam";
/** Files larger than this are uploaded without the duplicate check. */
export const MAX_HASHED = 512 * 1024 * 1024;

/** ZIP of assets and folders (originals; with `renditions` also the renditions). */
export function downloadUrl(
  paths: string[],
  options: { renditions?: boolean; separate?: boolean; agree?: string[] } = {},
  base = "",
): string {
  const q = new URLSearchParams();
  for (const p of paths) q.append("path", p);
  if (options.renditions) q.set("renditions", "true");
  if (options.separate) q.set("separate", "true");
  // DRM: the licensed files whose license was agreed to (the others are left out)
  for (const a of options.agree ?? []) q.append("agree", a);
  return `${base}/bin/studio/dam/download.zip?${q}`;
}

/** Metadata Export: CSV of the folder's assets (all metadata properties unless `properties`). */
export function metadataCsvUrl(
  path: string,
  recursive: boolean,
  properties: string[] = [],
  base = "",
): string {
  const q = new URLSearchParams({ path });
  if (recursive) q.set("recursive", "true");
  for (const p of properties) q.append("property", p);
  return `${base}/bin/studio/dam/metadata.csv?${q}`;
}

/** The CSV of an Asset Report. */
export function reportCsvUrl(id: string, base = ""): string {
  return `${base}/bin/studio/dam/reports.csv?${new URLSearchParams({ id })}`;
}

export interface ReportType {
  /** AEM's report ids: assetadditionreport, assetmodificationreport, ... */
  id: string;
  title: string;
  description: string;
  /** one row per asset, with columns to choose */
  columns: boolean;
  /** has a date range */
  dates: boolean;
  /** has a folder */
  folder: boolean;
}

export interface ReportCatalog {
  types: ReportType[];
  columns: Array<{ id: string; title: string; default: boolean }>;
}

export interface ReportRequest {
  type: string;
  title: string;
  description?: string;
  path?: string;
  /** yyyy-mm-dd */
  from?: string;
  to?: string;
  columns?: string[];
  /** metadata properties as extra columns, e.g. dc:creator */
  properties?: string[];
  excludeFragments?: boolean;
  includeRenditions?: boolean;
  excludeSubfolders?: boolean;
}

export interface ReportInfo {
  id: string;
  title: string;
  description?: string | null;
  type: string;
  typeTitle: string;
  path?: string | null;
  from?: string | null;
  to?: string | null;
  owner: string;
  created: string;
  status: string;
  rows: number;
  truncated: boolean;
  summary: Record<string, number>;
}

export interface ReportPage extends ReportInfo {
  header: string[];
  offset: number;
  items: string[][];
}

export interface ImportResult {
  updated: number;
  skipped: Array<{ line: number; path: string; reason: string }>;
}

/** `ns:a/b` <-> `/content/cq:tags/ns/a/b` (`ns:` is the namespace itself). */
export const TAGS_ROOT = "/content/cq:tags";
export function tagIdOf(path: string): string | null {
  if (!path.startsWith(`${TAGS_ROOT}/`)) return null;
  const [ns, ...rest] = path.slice(TAGS_ROOT.length + 1).split("/");
  return `${ns}:${rest.join("/")}`;
}
export function tagPathOf(id: string): string {
  const i = id.indexOf(":");
  const ns = i < 0 ? "default" : id.slice(0, i);
  const rest = i < 0 ? id : id.slice(i + 1);
  return rest ? `${TAGS_ROOT}/${ns}/${rest}` : `${TAGS_ROOT}/${ns}`;
}
const API = "/bin/studio/dam.json";
const CSRF = { "X-Studio-CSRF": "1" };

async function errorOf(res: Response, what: string): Promise<DamError> {
  let message = `${what}: HTTP ${res.status}`;
  try {
    const body = (await res.json()) as { error?: string };
    if (body.error) message = body.error;
  } catch {
    // not JSON
  }
  return new DamError(res.status, message);
}

/** Same-origin client: on Sensible Studio itself, or through the Vite dev proxy. */
export class DamHttpClient implements DamClient {
  constructor(private readonly base = "") {}

  private async get<T>(
    params: Record<string, string> | URLSearchParams,
    what: string,
  ): Promise<T> {
    const res = await fetch(
      `${this.base}${API}?${new URLSearchParams(params)}`,
      { credentials: "same-origin" },
    );
    if (!res.ok) throw await errorOf(res, what);
    return (await res.json()) as T;
  }

  private async post<T>(form: URLSearchParams, what: string): Promise<T> {
    // Sling reads form parameters as ISO-8859-1 unless told otherwise: without this a title with "×" or "ă"
    // was stored as "Ã\u0097" / "Ä\u0083" (the Content-Type charset is not enough)
    form.set("_charset_", "utf-8");
    const res = await fetch(`${this.base}${API}`, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        ...CSRF,
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
      },
      body: form,
    });
    if (!res.ok) throw await errorOf(res, what);
    return (await res.json()) as T;
  }

  list(path: string, options: ListOptions = {}): Promise<Listing> {
    const p: Record<string, string> = { op: "list", path };
    if (options.sort && options.sort !== "none") p.sort = options.sort;
    if (options.order) p.order = options.order;
    if (options.offset) p.offset = String(options.offset);
    p.limit = String(options.limit ?? 500);
    return this.get<Listing>(p, `List ${path}`);
  }

  search(
    scope: string,
    query: SearchQuery,
    options: ListOptions = {},
  ): Promise<SearchResult> {
    if (query.look && query.fulltext?.trim()) {
      return this.get<SearchResult>(
        {
          op: "pictures",
          path: scope,
          q: query.fulltext.trim().slice(0, 200),
          limit: String(Math.min(options.limit ?? 60, 200)),
          ...(query.weak ? { min: "0" } : {}),
          ...(query.translate ? { translate: "true" } : {}),
        },
        `Pictures of ${query.fulltext.trim()}`,
      );
    }
    const p = new URLSearchParams({ op: "search", path: scope });
    if (query.fulltext?.trim()) p.set("fulltext", query.fulltext.trim());
    for (const t of query.types ?? []) p.append("type", t);
    for (const t of query.tags ?? []) p.append("tag", t);
    if (query.modified) p.set("modified", query.modified);
    if (query.size) {
      const [min, max] = SIZE_RANGES[query.size];
      if (min >= 0) p.set("minSize", String(min));
      if (max >= 0) p.set("maxSize", String(max));
    }
    if (query.orientation) p.set("orientation", query.orientation);
    for (const [prop, values] of Object.entries(query.meta ?? {}))
      for (const v of values) p.append("meta", `${prop}=${v}`);
    for (const [prop, text] of Object.entries(query.metaText ?? {}))
      if (text.trim()) p.append("metaText", `${prop}=${text.trim()}`);
    if (options.sort && options.sort !== "none" && options.sort !== "type")
      p.set("sort", options.sort);
    if (options.order) p.set("order", options.order);
    if (options.offset) p.set("offset", String(options.offset));
    p.set("limit", String(options.limit ?? 500));
    return this.get<SearchResult>(p, `Search in ${scope}`);
  }

  asset(path: string): Promise<AssetDetails> {
    return this.get<AssetDetails>({ op: "asset", path }, `Asset ${path}`);
  }

  async folders(path: string): Promise<FolderNode[]> {
    return (
      await this.get<{ folders: FolderNode[] }>(
        { op: "folders", path },
        `Folders of ${path}`,
      )
    ).folders;
  }

  async createFolder(
    parent: string,
    title: string,
    name?: string,
  ): Promise<string> {
    const f = new URLSearchParams({ op: "createFolder", parent, title });
    if (name) f.set("name", name);
    return (await this.post<{ path: string }>(f, "Create folder")).path;
  }

  async remove(paths: string[]): Promise<void> {
    await this.post(form("delete", paths), "Delete");
  }

  async move(
    paths: string[],
    dest: string,
    name?: string,
    adjust: string[] = [],
  ): Promise<MoveResult> {
    const f = form("move", paths);
    f.set("dest", dest);
    if (name) f.set("name", name);
    for (const a of adjust) f.append("adjust", a);
    const r = await this.post<{ paths: string[]; adjusted?: number }>(
      f,
      "Move",
    );
    return { paths: r.paths, adjusted: r.adjusted ?? 0 };
  }

  comments(path: string): Promise<{ comments: AssetComment[]; canComment: boolean }> {
    return this.get({ op: "comments", path }, `Comments on ${path}`);
  }

  addComment(path: string, text: string, region?: CommentRegion, color?: string): Promise<AssetComment> {
    const f = new URLSearchParams({ op: "comment", path, text });
    if (region) {
      f.set("x", String(region.x));
      f.set("y", String(region.y));
      f.set("w", String(region.w));
      f.set("h", String(region.h));
      if (region.shape && region.shape !== "rect") f.set("shape", region.shape);
      if (region.points?.length) f.set("points", region.points.map(([x, y]) => `${x},${y}`).join(" "));
      if (region.time !== undefined) f.set("time", String(region.time));
      if (color) f.set("color", color);
    }
    return this.post(f, "Comment");
  }

  async deleteComment(path: string, id: string): Promise<void> {
    await this.post(new URLSearchParams({ op: "deleteComment", path, id }), "Remove comment");
  }

  async references(path: string): Promise<DamReference[]> {
    // Shared with the Sites console: one search for references to pages and to assets.
    const res = await fetch(
      `${this.base}/bin/studio/sites.json?${new URLSearchParams({ op: "references", path })}`,
      { credentials: "same-origin" },
    );
    if (!res.ok) throw await errorOf(res, `References to ${path}`);
    return (
      ((await res.json()) as { references?: DamReference[] }).references ?? []
    );
  }

  async copy(paths: string[], dest: string): Promise<string[]> {
    const f = form("copy", paths);
    f.set("dest", dest);
    return (await this.post<{ paths: string[] }>(f, "Copy")).paths;
  }

  async reprocess(paths: string[]): Promise<void> {
    await this.post(form("reprocess", paths), "Reprocess");
  }

  async saveMetadata(
    path: string,
    fields: BasicMetadata,
    append: readonly MultiField[] = [],
  ): Promise<void> {
    const f = new URLSearchParams({ op: "metadata", path });
    for (const [key, value] of Object.entries(fields)) {
      if (Array.isArray(value)) {
        if ((append as readonly string[]).includes(key)) {
          if (value.length === 0) continue;
          f.set(`${key}@Append`, "true");
        } else if (value.length === 0) f.append(`${key}@Delete`, "true");
        for (const v of value) f.append(key, v);
      } else if (value !== undefined) {
        f.set(key, value);
      }
    }
    await this.post(f, "Save metadata");
  }

  async versions(path: string): Promise<AssetVersion[]> {
    return (
      await this.get<{ versions: AssetVersion[] }>(
        { op: "versions", path },
        `Versions of ${path}`,
      )
    ).versions;
  }

  async createVersion(
    path: string,
    label?: string,
    comment?: string,
  ): Promise<AssetVersion> {
    const f = new URLSearchParams({ op: "createVersion", path });
    if (label) f.set("label", label);
    if (comment) f.set("comment", comment);
    return (await this.post<{ version: AssetVersion }>(f, "Create version"))
      .version;
  }

  async restoreVersion(path: string, version: string): Promise<AssetVersion> {
    return (
      await this.post<{ saved: AssetVersion }>(
        new URLSearchParams({ op: "restoreVersion", path, version }),
        "Restore version",
      )
    ).saved;
  }

  schemas(
    path?: string,
  ): Promise<{ schemas: MetadataSchema[]; effective?: string | null }> {
    return this.get(
      { op: "schemas", path: path ?? DAM_ROOT },
      "Metadata schemas",
    );
  }

  saveSchema(
    name: string,
    title: string,
    tabs: SchemaTab[],
  ): Promise<MetadataSchema> {
    return this.post(
      new URLSearchParams({
        op: "saveSchema",
        name,
        title,
        definition: JSON.stringify({ tabs }),
      }),
      "Save schema",
    );
  }

  async deleteSchema(name: string): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "deleteSchema", name }),
      "Delete schema",
    );
  }

  forms(
    kind: FormKind,
    path?: string,
  ): Promise<{ forms: MetadataSchema[]; effective?: string | null }> {
    return this.get(
      { op: "forms", kind, path: path ?? DAM_ROOT },
      kind === "profile" ? "Metadata profiles" : "Folder metadata schemas",
    );
  }

  saveForm(
    kind: FormKind,
    name: string,
    title: string,
    tabs: SchemaTab[],
    values?: Record<string, FieldValue>,
  ): Promise<MetadataSchema> {
    return this.post(
      new URLSearchParams({
        op: "saveForm",
        kind,
        name,
        title,
        definition: JSON.stringify(values ? { tabs, values } : { tabs }),
      }),
      kind === "profile" ? "Save profile" : "Save schema",
    );
  }

  async deleteForm(kind: FormKind, name: string): Promise<void> {
    await this.post(new URLSearchParams({ op: "deleteForm", kind, name }), "Delete");
  }

  searchForm(): Promise<SearchForm> {
    return this.get({ op: "searchForm" }, "Search form");
  }

  smartTagTraining(tags: string[] = []): Promise<SmartTagTraining> {
    const p = new URLSearchParams({ op: "smartTagTraining" });
    for (const t of tags) p.append("tag", t);
    return this.get(p, "Smart tag training");
  }

  async trainSmartTags(tags: string[]): Promise<TrainingResult[]> {
    const f = new URLSearchParams({ op: "trainSmartTags" });
    for (const t of tags) f.append("tag", t);
    return (await this.post<{ results: TrainingResult[] }>(f, "Train")).results;
  }

  async forgetSmartTag(tag: string): Promise<void> {
    await this.post(new URLSearchParams({ op: "forgetSmartTag", tag }), "Forget");
  }

  saveSearchForm(facets: SearchFacet[]): Promise<SearchForm> {
    return this.post(
      new URLSearchParams({ op: "saveSearchForm", facets: JSON.stringify(facets) }),
      "Save search form",
    );
  }

  resetSearchForm(): Promise<SearchForm> {
    return this.post(new URLSearchParams({ op: "resetSearchForm" }), "Reset search form");
  }

  metadataValues(path: string, property: string): Promise<MetadataValues> {
    return this.get({ op: "metadataValues", path, property }, `Values of ${property}`);
  }

  async setFolderForm(
    kind: FormKind,
    folder: string,
    form: string | null,
  ): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "folderForm", kind, path: folder, form: form ?? "" }),
      kind === "profile" ? "Folder metadata profile" : "Folder metadata schema",
    );
  }

  applyProfile(
    folder: string,
  ): Promise<{ files: number; changed: number; truncated: boolean }> {
    return this.post(
      new URLSearchParams({ op: "applyProfile", path: folder }),
      "Apply metadata profile",
    );
  }

  folderMetadata(
    folder: string,
  ): Promise<{ schema: MetadataSchema | null; values: Record<string, unknown> }> {
    return this.get({ op: "folderMetadata", path: folder }, "Folder metadata");
  }

  async saveFolderMetadata(
    folder: string,
    fields: Record<string, FieldValue>,
  ): Promise<void> {
    await this.post(
      new URLSearchParams({
        op: "folderMetadataFields",
        path: folder,
        fields: JSON.stringify(fields),
      }),
      "Save folder metadata",
    );
  }

  async setFolderSchema(folder: string, schema: string | null): Promise<void> {
    await this.post(
      new URLSearchParams({
        op: "folderSchema",
        path: folder,
        schema: schema ?? "",
      }),
      "Folder schema",
    );
  }

  async saveFields(
    path: string,
    fields: Record<string, FieldValue>,
  ): Promise<void> {
    await this.post(
      new URLSearchParams({
        op: "metadataFields",
        path,
        fields: JSON.stringify(fields),
      }),
      "Save metadata",
    );
  }

  profiles(
    folder?: string,
  ): Promise<{ profiles: ProcessingProfile[]; effective?: string | null; watermark?: WatermarkSetting; admin?: boolean }> {
    return this.get(
      { op: "profiles", path: folder ?? DAM_ROOT },
      "Processing profiles",
    );
  }

  galleryPresets(): Promise<{ presets: GalleryPreset[]; admin?: boolean }> {
    return this.get({ op: "galleryPresets", path: DAM_ROOT }, "Gallery presets");
  }

  saveGalleryPreset(p: Omit<GalleryPreset, "name"> & { name?: string }): Promise<GalleryPreset> {
    const f = new URLSearchParams({
      op: "saveGalleryPreset",
      title: p.title,
      thumbs: p.thumbs,
      arrows: String(p.arrows),
      ratio: p.ratio,
      zoom: String(p.zoom),
    });
    if (p.name) f.set("name", p.name);
    return this.post(f, "Save gallery preset");
  }

  async deleteGalleryPreset(name: string): Promise<void> {
    await this.post(new URLSearchParams({ op: "deleteGalleryPreset", name }), "Delete gallery preset");
  }

  liveCopy(path: string): Promise<LiveCopyInfo> {
    return this.get({ op: "liveCopy", path }, "Linked copy");
  }

  createLiveCopy(source: string, parent: string, title: string, name?: string, push = false): Promise<RolloutResult & { path: string }> {
    const f = new URLSearchParams({ op: "createLiveCopy", source, parent, title });
    if (name) f.set("name", name);
    if (push) f.set("push", "true");
    return this.post(f, "Create linked copy");
  }

  async liveCopyPush(path: string, push: boolean): Promise<void> {
    await this.post(new URLSearchParams({ op: "liveCopyPush", path, push: String(push) }), "Linked copy");
  }

  liveCopySync(path: string, reset = false): Promise<RolloutResult> {
    const f = new URLSearchParams({ op: "liveCopySync", path });
    if (reset) f.set("reset", "true");
    return this.post(f, reset ? "Reset" : "Synchronize");
  }

  rollout(path: string): Promise<RolloutResult> {
    return this.post(new URLSearchParams({ op: "rollout", path }), "Sync");
  }

  async liveCopySuspend(path: string, suspend: boolean): Promise<void> {
    await this.post(new URLSearchParams({ op: "liveCopySuspend", path, suspend: String(suspend) }), suspend ? "Suspend" : "Resume");
  }

  async liveCopyDetach(path: string): Promise<void> {
    await this.post(new URLSearchParams({ op: "liveCopyDetach", path }), "Detach");
  }

  async liveCopyInherit(path: string, field: string): Promise<void> {
    await this.post(new URLSearchParams({ op: "liveCopyInherit", path, field }), "Inherit");
  }

  saveWatermark(setting: WatermarkSetting): Promise<WatermarkSetting> {
    return this.post(
      new URLSearchParams({
        op: "watermark",
        image: setting.image ?? "",
        position: setting.position,
        opacity: String(setting.opacity),
        size: String(setting.size),
      }),
      "Save watermark",
    );
  }

  saveProfile(
    name: string,
    title: string,
    renditions: ProfileRendition[],
  ): Promise<ProcessingProfile> {
    return this.post(
      new URLSearchParams({
        op: "saveProfile",
        name,
        title,
        renditions: JSON.stringify(renditions),
      }),
      "Save profile",
    );
  }

  imagePresets(asset?: string): Promise<ImagePresets> {
    return this.get(
      { op: "imagePresets", path: asset ?? DAM_ROOT },
      "Image presets",
    );
  }

  saveImagePreset(
    name: string,
    title: string,
    description: string,
    params: Record<string, string>,
  ): Promise<ImagePreset> {
    const f = new URLSearchParams({
      op: "saveImagePreset",
      name,
      title,
      description,
    });
    for (const [k, v] of Object.entries(params)) f.set(k, v);
    return this.post(f, "Save image preset");
  }

  imageProfiles(
    folder?: string,
  ): Promise<{
    profiles: ImageProfile[];
    effective?: string | null;
    admin: boolean;
  }> {
    return this.get(
      { op: "imageProfiles", path: folder ?? DAM_ROOT },
      "Image profiles",
    );
  }

  saveImageProfile(
    name: string,
    title: string,
    ratios: CropRatio[],
  ): Promise<ImageProfile> {
    return this.post(
      new URLSearchParams({
        op: "saveImageProfile",
        name,
        title,
        ratios: JSON.stringify(ratios),
      }),
      "Save image profile",
    );
  }

  async deleteImageProfile(name: string): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "deleteImageProfile", name }),
      "Delete image profile",
    );
  }

  async setFolderImageProfile(
    folder: string,
    profile: string | null,
  ): Promise<number> {
    return (
      await this.post<{ queued: number }>(
        new URLSearchParams({
          op: "folderImageProfile",
          path: folder,
          profile: profile ?? "",
        }),
        "Folder image profile",
      )
    ).queued;
  }

  async smartCropRun(paths: string[]): Promise<number> {
    return (
      await this.post<{ queued: number }>(
        form("smartCropRun", paths),
        "Focus crop",
      )
    ).queued;
  }

  setSmartCrop(
    path: string,
    name: string,
    left: number,
    top: number,
    width: number,
  ): Promise<SmartCrop> {
    return this.post(
      new URLSearchParams({
        op: "smartCrop",
        path,
        name,
        left: String(left),
        top: String(top),
        width: String(width),
      }),
      "Focus crop",
    );
  }

  async resetSmartCrops(path: string): Promise<SmartCrop[]> {
    return (
      await this.post<{ smartCrops: SmartCrop[] }>(
        new URLSearchParams({ op: "smartCropReset", path }),
        "Focus crop",
      )
    ).smartCrops;
  }

  async deleteImagePreset(name: string): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "deleteImagePreset", name }),
      "Delete image preset",
    );
  }

  async deleteProfile(name: string): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "deleteProfile", name }),
      "Delete profile",
    );
  }

  async setFolderProfile(
    folder: string,
    profile: string | null,
  ): Promise<void> {
    await this.post(
      new URLSearchParams({
        op: "folderProfile",
        path: folder,
        profile: profile ?? "",
      }),
      "Folder profile",
    );
  }

  async createShareLink(
    paths: string[],
    days: number,
    renditions: boolean,
    options: { originals?: boolean; amount?: number; unit?: string } = {},
  ): Promise<{ url: string; id: string; expires: string }> {
    const f = form("create", paths);
    if (options.amount && options.unit) {
      f.set("amount", String(options.amount));
      f.set("unit", options.unit);
    } else {
      f.set("days", String(days));
    }
    f.set("renditions", String(renditions));
    if (options.originals !== undefined) f.set("originals", String(options.originals));
    const res = await fetch(`${this.base}/bin/studio/dam/linkshare.json`, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        ...CSRF,
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
      },
      body: f,
    });
    if (!res.ok) throw await errorOf(res, "Share link");
    return (await res.json()) as { url: string; id: string; expires: string };
  }

  async shareLinks(path?: string): Promise<ShareLink[]> {
    const q = path ? `?${new URLSearchParams({ path })}` : "";
    const res = await fetch(`${this.base}/bin/studio/dam/linkshare.json${q}`, {
      credentials: "same-origin",
    });
    if (!res.ok) throw await errorOf(res, "Share links");
    return ((await res.json()) as { links: ShareLink[] }).links;
  }

  async revokeShareLink(id: string): Promise<void> {
    const res = await fetch(`${this.base}/bin/studio/dam/linkshare.json`, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        ...CSRF,
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
      },
      body: new URLSearchParams({ op: "revoke", id }),
    });
    if (!res.ok) throw await errorOf(res, "Revoke link");
  }

  async collections(): Promise<DamCollection[]> {
    return (
      await this.get<{ collections: DamCollection[] }>(
        { op: "collections" },
        "Collections",
      )
    ).collections;
  }

  collection(
    path: string,
  ): Promise<{ collection: DamCollection; total: number; items: DamItem[] }> {
    return this.get({ op: "collection", path }, `Collection ${path}`);
  }

  createCollection(
    title: string,
    description: string,
    isPublic: boolean,
    smart?: { scope: string; query: SearchQuery },
  ): Promise<DamCollection> {
    const f = new URLSearchParams({
      op: "createCollection",
      title,
      description,
      public: String(isPublic),
    });
    if (smart) {
      f.set("scope", smart.scope);
      f.set("query", JSON.stringify(smart.query));
    }
    return this.post(f, "Create collection");
  }

  updateCollection(
    path: string,
    fields: { title?: string; description?: string; public?: boolean },
  ): Promise<DamCollection> {
    const f = new URLSearchParams({ op: "updateCollection", collection: path });
    if (fields.title !== undefined) f.set("title", fields.title);
    if (fields.description !== undefined)
      f.set("description", fields.description);
    if (fields.public !== undefined) f.set("public", String(fields.public));
    return this.post(f, "Collection properties");
  }

  async deleteCollection(path: string): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "deleteCollection", collection: path }),
      "Delete collection",
    );
  }

  async addToCollection(collection: string, paths: string[]): Promise<number> {
    const f = form("addToCollection", paths);
    f.set("collection", collection);
    return (await this.post<{ changed: number }>(f, "Add to collection"))
      .changed;
  }

  async removeFromCollection(
    collection: string,
    paths: string[],
  ): Promise<number> {
    const f = form("removeFromCollection", paths);
    f.set("collection", collection);
    return (await this.post<{ changed: number }>(f, "Remove from collection"))
      .changed;
  }

  async importMetadata(csv: File): Promise<ImportResult> {
    const data = new FormData();
    data.append("op", "importMetadata");
    data.append("csv", csv, csv.name);
    // multipart: form-encoded bodies have a size limit in Jetty
    const res = await fetch(`${this.base}${API}`, {
      method: "POST",
      credentials: "same-origin",
      headers: CSRF,
      body: data,
    });
    if (!res.ok) throw await errorOf(res, "Import metadata");
    return (await res.json()) as ImportResult;
  }

  smartTagsStatus(folder?: string): Promise<SmartTagsStatus> {
    return this.get(
      folder ? { op: "smartTags", path: folder } : { op: "smartTags" },
      "Auto tags",
    );
  }

  async setFolderSmartTagging(
    folder: string,
    value: "on" | "off" | null,
  ): Promise<void> {
    await this.post(
      new URLSearchParams({
        op: "folderSmartTags",
        path: folder,
        value: value ?? "",
      }),
      "Folder Auto tags",
    );
  }

  saveSmartTagsSettings(st: SmartTagsSettings): Promise<SmartTagsSettings> {
    const f = new URLSearchParams({
      op: "smartTagsSettings",
      enabled: String(st.enabled),
      keywords: String(st.keywords),
      max: String(st.max),
      minConfidence: String(st.minConfidence),
    });
    for (const r of st.tagRoots) f.append("tagRoot", r);
    return this.post(f, "Save Auto tags settings");
  }

  async installSmartTagsModel(): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "smartTagsInstall" }),
      "Install the Auto tags model",
    );
  }

  async installSearchModel(): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "searchModelInstall" }),
      "Install the model for searching in any language",
    );
  }

  similar(path: string, limit = 24): Promise<SimilarResult> {
    return this.get<SimilarResult>({ op: "similar", path, limit: String(limit) }, "Find similar");
  }

  async similarIndex(paths: string[] = []): Promise<number> {
    return (await this.post<{ queued: number }>(form("similarIndex", paths), "Find similar")).queued;
  }

  colorTag(paths: string[], again = false): Promise<{ tagged: number; skipped: number; more: boolean }> {
    const f = form("colorTag", paths);
    if (again) f.set("again", "true");
    return this.post(f, "Color tags");
  }

  async smartTag(paths: string[]): Promise<number> {
    return (
      await this.post<{ queued: number }>(form("smartTag", paths), "Auto tags")
    ).queued;
  }

  changeSmartTag(
    path: string,
    tag: string,
    apply: boolean,
  ): Promise<SmartTags> {
    return this.post(
      new URLSearchParams({
        op: apply ? "smartTagApply" : "smartTagRemove",
        path,
        tag,
      }),
      "Smart tag",
    );
  }

  private async reportsGet<T>(
    params: Record<string, string>,
    what: string,
  ): Promise<T> {
    const res = await fetch(
      `${this.base}/bin/studio/dam/reports.json?${new URLSearchParams(params)}`,
      { credentials: "same-origin" },
    );
    if (!res.ok) throw await errorOf(res, what);
    return (await res.json()) as T;
  }

  private async reportsPost<T>(
    body: URLSearchParams,
    what: string,
  ): Promise<T> {
    body.set("_charset_", "utf-8"); // a report's title is free text: see post()
    const res = await fetch(`${this.base}/bin/studio/dam/reports.json`, {
      method: "POST",
      credentials: "same-origin",
      headers: CSRF,
      body,
    });
    if (!res.ok) throw await errorOf(res, what);
    return (await res.json()) as T;
  }

  reportCatalog(): Promise<ReportCatalog> {
    return this.reportsGet({ op: "catalog" }, "Report types");
  }

  async reports(): Promise<ReportInfo[]> {
    return (await this.reportsGet<{ items: ReportInfo[] }>({}, "Reports"))
      .items;
  }

  report(id: string, offset = 0, limit = 100): Promise<ReportPage> {
    return this.reportsGet(
      { id, offset: String(offset), limit: String(limit) },
      "Report",
    );
  }

  createReport(r: ReportRequest): Promise<ReportInfo> {
    const f = new URLSearchParams({
      op: "create",
      type: r.type,
      title: r.title,
    });
    if (r.description) f.set("description", r.description);
    if (r.path) f.set("path", r.path);
    if (r.from) f.set("from", r.from);
    if (r.to) f.set("to", r.to);
    if (r.columns) {
      f.set("columnsSet", "true");
      for (const c of r.columns) f.append("column", c);
    }
    for (const p of r.properties ?? []) f.append("property", p);
    if (r.excludeFragments) f.set("excludeFragments", "true");
    if (r.includeRenditions) f.set("includeRenditions", "true");
    if (r.excludeSubfolders) f.set("excludeSubfolders", "true");
    return this.reportsPost(f, "Create report");
  }

  async deleteReports(ids: string[]): Promise<number> {
    const f = new URLSearchParams({ op: "delete" });
    for (const id of ids) f.append("id", id);
    return (await this.reportsPost<{ deleted: number }>(f, "Delete report"))
      .deleted;
  }

  async setPoster(path: string, image: Blob | null): Promise<void> {
    const data = new FormData();
    data.append("op", "poster");
    data.append("path", path);
    if (image) data.append("file", image, "poster.jpg");
    else data.append("remove", "true");
    const res = await fetch(`${this.base}${API}`, {
      method: "POST",
      credentials: "same-origin",
      headers: CSRF,
      body: data,
    });
    if (!res.ok) throw await errorOf(res, "Set thumbnail");
  }

  async checkout(paths: string[]): Promise<void> {
    await this.post(form("checkout", paths), "Checkout");
  }

  async checkin(paths: string[]): Promise<void> {
    await this.post(form("checkin", paths), "Check In");
  }

  async createSet(parent: string, title: string, members: string[], kind?: "gallery" | "spin"): Promise<string> {
    const f = new URLSearchParams({ op: "createSet", parent, title });
    if (kind) f.set("kind", kind);
    members.forEach((m) => f.append("member", m));
    return (await this.post<{ path: string }>(f, "Create Image Set")).path;
  }

  async setMembers(path: string, members: string[], kind?: "gallery" | "spin"): Promise<void> {
    const f = new URLSearchParams({ op: "setMembers", path });
    if (kind) f.set("kind", kind);
    members.forEach((m) => f.append("member", m));
    await this.post(f, "Change Image Set");
  }

  async relate(paths: string[]): Promise<number> {
    return (await this.post<{ added: number }>(form("relate", paths), "Relate"))
      .added;
  }

  async unrelate(path: string, others: string[]): Promise<void> {
    const f = new URLSearchParams({ op: "unrelate", path });
    for (const o of others) f.append("other", o);
    await this.post(f, "Unrelate");
  }

  extractArchive(
    path: string,
    options: {
      into: "here" | "folder";
      conflict: "skip" | "replace" | "rename";
    },
  ): Promise<ExtractResult> {
    const f = new URLSearchParams({
      op: "extractArchive",
      path,
      conflict: options.conflict,
    });
    if (options.into === "folder") f.set("into", "folder");
    return this.post<ExtractResult>(f, "Extract archive");
  }

  async setFolderThumbnail(path: string, asset: string | null): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "folderThumbnail", path, asset: asset ?? "" }),
      "Folder thumbnail",
    );
  }

  async setFolderTitle(path: string, title: string): Promise<void> {
    await this.post(
      new URLSearchParams({ op: "folderTitle", path, title }),
      "Folder title",
    );
  }

  edit(
    path: string,
    operations: EditOperation[],
    areas?: MapArea[],
  ): Promise<EditResult> {
    const f = new URLSearchParams({ op: "edit", path });
    if (operations.length > 0) f.set("operations", JSON.stringify(operations));
    if (areas) f.set("imageMap", JSON.stringify(areas));
    return this.post<EditResult>(f, "Save image");
  }

  async findDuplicates(file: File): Promise<string[]> {
    // Hashing reads the whole file in the browser: big files (video) are not checked.
    if (file.size > MAX_HASHED || !globalThis.crypto?.subtle) return [];
    const digest = new Uint8Array(
      await crypto.subtle.digest("SHA-1", await file.arrayBuffer()),
    );
    const sha1 = [...digest]
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    const r = await this.get<{ duplicates: Array<{ path: string }> }>(
      { op: "duplicates", sha1 },
      "Duplicates",
    );
    return r.duplicates.map((d) => d.path);
  }

  /** XHR rather than fetch: fetch has no upload progress events. */
  upload(
    folder: string,
    file: File,
    onProgress?: (fraction: number) => void,
    conflict?: UploadConflict,
  ): Promise<string> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", `${this.base}/bin/studio/dam/upload`);
      xhr.setRequestHeader("X-Studio-CSRF", "1");
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
      };
      xhr.onload = () => {
        let body: { path?: string; error?: string } = {};
        try {
          body = JSON.parse(xhr.responseText) as typeof body;
        } catch {
          // not JSON
        }
        if (xhr.status >= 200 && xhr.status < 300 && body.path)
          resolve(body.path);
        else
          reject(
            new DamError(
              xhr.status,
              body.error ?? `Upload of ${file.name}: HTTP ${xhr.status}`,
            ),
          );
      };
      xhr.onerror = () =>
        reject(new DamError(0, `Upload of ${file.name} failed (network)`));
      const data = new FormData();
      data.append("folder", folder);
      if (conflict) data.append("conflict", conflict);
      data.append("file", file, file.name);
      xhr.send(data);
    });
  }

  async currentUser(): Promise<string> {
    const res = await fetch(`${this.base}/system/sling/info.sessionInfo.json`, {
      credentials: "same-origin",
    });
    if (!res.ok) return "anonymous";
    return ((await res.json()) as { userID?: string }).userID ?? "anonymous";
  }
}

function form(op: string, paths: string[]): URLSearchParams {
  const f = new URLSearchParams({ op });
  for (const p of paths) f.append("path", p);
  return f;
}
