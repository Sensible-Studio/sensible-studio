import {
  DAM_ROOT,
  DamError,
  type AssetDetails,
  type AssetItem,
  type SimilarResult,
  type AssetVersion,
  type AssetComment,
  type CommentRegion,
  type MultiField,
  type FieldValue,
  type FormKind,
  type MetadataSchema,
  type SchemaTab,
  type ProcessingProfile,
  type ProfileRendition,
  type WatermarkSetting,
  type LiveCopyInfo,
  type GalleryPreset,
  type RolloutResult,
  type MetadataValues,
  type LearnedTag,
  type SmartTagTraining,
  type TrainingResult,
  type SearchFacet,
  type SearchForm,
  type SearchQuery,
  type SearchResult,
  type ShareLink,
  type UploadConflict,
  type BasicMetadata,
  type DamClient,
  type DamCollection,
  type DamItem,
  type ImagePreset,
  type ImagePresets,
  type ImageProfile,
  type CropRatio,
  type SmartCrop,
  type DamReference,
  type EditResult,
  type ExtractResult,
  type ImportResult,
  type MoveResult,
  type FolderNode,
  type Listing,
  type ListOptions,
  type ReportCatalog,
  type ReportInfo,
  type ReportPage,
  type ReportRequest,
  type SmartTags,
  type SmartTagsSettings,
  type SmartTagsStatus,
} from '../api';
import { kindOf } from '../format';
import { sizeAfter, type EditOperation, type MapArea } from '../imageEdit';

interface Node {
  item: DamItem;
  metadata: Record<string, unknown>;
  imageMap?: string | null;
}

/** In-memory DAM with the same rules as DamApiServlet, for the UI tests. */
export class MemoryDam implements DamClient {
  readonly nodes = new Map<string, Node>();
  readonly calls: string[] = [];

  constructor() {
    this.folder(DAM_ROOT, 'Media');
  }

  folder(path: string, title: string): this {
    this.nodes.set(path, { item: { type: 'folder', path, name: path.slice(path.lastIndexOf('/') + 1), title, previews: [] }, metadata: {} });
    return this;
  }

  asset(path: string): Promise<AssetDetails>;
  asset(path: string, fields: Partial<AssetItem>, metadata?: Record<string, unknown>): this;
  asset(path: string, fields?: Partial<AssetItem>, metadata: Record<string, unknown> = {}): this | Promise<AssetDetails> {
    if (fields === undefined) return this.details(path);
    const name = path.slice(path.lastIndexOf('/') + 1);
    const item: AssetItem = {
      type: 'asset',
      path,
      name,
      title: (metadata['dc:title'] as string | undefined) ?? name,
      mimeType: 'image/jpeg',
      size: 1000,
      width: 800,
      height: 600,
      state: 'processed',
      thumbnail: `${path}/_jcr_content/renditions/cq5dam.thumbnail.319.319.png`,
      original: `${path}/_jcr_content/renditions/original`,
      ...fields,
    };
    this.nodes.set(path, { item, metadata });
    return this;
  }

  private children(path: string): DamItem[] {
    return [...this.nodes.values()].map((n) => n.item).filter((i) => i.path.slice(0, i.path.lastIndexOf('/')) === path);
  }

  private need(path: string): Node {
    const n = this.nodes.get(path);
    if (!n) throw new DamError(404, `not found: ${path}`);
    return n;
  }

  /** Folders (and everything below) the user may only look at, as the server would report it. */
  readOnly = new Set<string>();

  private denied(path: string): { canCreate?: boolean; canEdit?: boolean; canDelete?: boolean } {
    const ro = [...this.readOnly].some((r) => path === r || path.startsWith(`${r}/`));
    return ro ? { canCreate: false, canEdit: false, canDelete: false } : {};
  }

  async list(path: string, options: ListOptions = {}): Promise<Listing> {
    this.calls.push(`list ${path}${options.sort && options.sort !== 'none' ? ` ${options.sort} ${options.order ?? 'asc'}` : ''}`);
    const n = this.need(path);
    const items = this.children(path).map((i) => {
      const { canCreate: _c, ...d } = this.denied(i.path);
      return { ...i, ...(i.type === 'folder' ? this.denied(i.path) : d) } as DamItem;
    });
    if (options.sort === 'title') items.sort((a, b) => a.title.localeCompare(b.title) * (options.order === 'desc' ? -1 : 1));
    const breadcrumbs = [];
    for (let p = path; p.startsWith(DAM_ROOT); p = p.slice(0, p.lastIndexOf('/'))) {
      breadcrumbs.unshift({ path: p, title: this.nodes.get(p)?.item.title ?? p });
    }
    return { path, title: n.item.title, type: n.item.type, breadcrumbs, total: items.length, offset: 0, items, ...this.denied(path) };
  }

  /** A simple version of DamSearch: all terms in title, name or metadata; the same filters. */
  async search(scope: string, query: SearchQuery): Promise<SearchResult> {
    this.calls.push(`search ${scope} ${JSON.stringify(query)}`);
    this.need(scope);
    const terms = (query.fulltext ?? '').toLowerCase().split(/\s+/).filter(Boolean);
    const items = [...this.nodes.values()]
      .filter((n) => n.item.path.startsWith(`${scope}/`))
      .filter((n) => {
        const md = n.metadata;
        const text = [n.item.title, n.item.name, md['dc:description'], md['dc:subject'], md['cq:tags']].flat().join(' ').toLowerCase();
        if (!terms.every((t) => text.includes(t))) return false;
        const filtersAssets = !!(query.types?.length || query.tags?.length || query.orientation || Object.keys(query.meta ?? {}).length || Object.keys(query.metaText ?? {}).length);
        if (n.item.type === 'folder') return terms.length > 0 && !filtersAssets;
        if (query.types?.length && !query.types.includes(kindOf(n.item))) return false;
        const tags = (md['cq:tags'] as string[] | undefined) ?? [];
        if (query.tags?.length && !query.tags.some((w) => tags.some((t) => t === w || t.startsWith(w.endsWith(':') ? w : `${w}/`)))) return false;
        if (query.orientation) {
          const { width: w = 0, height: h = 0 } = n.item;
          const o = (w ?? 0) > (h ?? 0) ? 'landscape' : (h ?? 0) > (w ?? 0) ? 'portrait' : 'square';
          if (o !== query.orientation) return false;
        }
        const strings = (v: unknown) => (Array.isArray(v) ? v : v == null ? [] : [v]).map((x) => String(x).toLowerCase());
        for (const [prop, want] of Object.entries(query.meta ?? {}))
          if (!strings(md[prop]).some((v) => want.some((w) => w.toLowerCase() === v))) return false;
        for (const [prop, text] of Object.entries(query.metaText ?? {}))
          if (!strings(md[prop]).some((v) => v.includes(text.toLowerCase()))) return false;
        return true;
      })
      .map((n) => n.item);
    return { path: scope, total: items.length, offset: 0, items };
  }

  /** Like a server without ffmpeg: video details say so. */
  noVideoTools = false;

  private async details(path: string): Promise<AssetDetails> {
    const n = this.need(path);
    if (n.item.type !== 'asset') throw new DamError(400, 'not a file');
    const { canCreate: _c, ...denied } = this.denied(path);
    return {
      ...n.item,
      ...denied,
      smartCrops: (this.smartCropStore.get(path) ?? []).map((c) => ({ ...c })),
      metadata: n.metadata,
      renditions: [{ name: 'original', url: n.item.original ?? '', mimeType: n.item.mimeType }],
      failed: [],
      breadcrumbs: [],
      schema: this.schemaOf(path.slice(0, path.lastIndexOf('/'))),
      imageMap: n.imageMap ?? null,
      ...(this.noVideoTools && n.item.mimeType?.startsWith('video/') ? { videoTools: false } : {}),
      smartTags: this.smart.get(path) ?? null,
      related: [...(this.related.get(path) ?? [])].map((p) => {
        const o = this.nodes.get(p)!.item;
        return { path: o.path, name: o.name, title: o.title, thumbnail: null };
      }),
    };
  }

  /** Sets the stored image map (Core Components notation). */
  imageMap(path: string, value: string): this {
    this.need(path).imageMap = value;
    return this;
  }

  async folders(path: string): Promise<FolderNode[]> {
    return this.children(path)
      .filter((i) => i.type === 'folder')
      .map((f) => ({ path: f.path, name: f.name, title: f.title, hasChildren: this.children(f.path).some((c) => c.type === 'folder') }));
  }

  async createFolder(parent: string, title: string, name?: string): Promise<string> {
    const path = `${parent}/${name}`;
    this.calls.push(`createFolder ${path} "${title}"`);
    if (this.nodes.has(path)) throw new DamError(409, `"${name}" already exists`);
    this.folder(path, title);
    return path;
  }

  async remove(paths: string[]): Promise<void> {
    this.calls.push(`delete ${paths.join(',')}`);
    for (const p of paths) for (const k of [...this.nodes.keys()]) if (k === p || k.startsWith(`${p}/`)) this.nodes.delete(k);
  }

  /** References returned by {@link references}, by referenced path. */
  readonly refs = new Map<string, DamReference[]>();
  readonly adjusted: string[][] = [];

  async references(path: string): Promise<DamReference[]> {
    return [...this.refs.entries()].filter(([p]) => p === path || p.startsWith(`${path}/`)).flatMap(([, r]) => r);
  }

  async move(paths: string[], dest: string, name?: string, adjust: string[] = []): Promise<MoveResult> {
    this.calls.push(`move ${paths.join(',')} -> ${dest}${name ? ` as ${name}` : ''}`);
    this.adjusted.push(adjust);
    const moved = paths.map((p) => {
      const n = this.need(p);
      const to = `${dest}/${name ?? n.item.name}`;
      this.nodes.delete(p);
      this.nodes.set(to, { ...n, item: { ...n.item, path: to, name: name ?? n.item.name } });
      return to;
    });
    return { paths: moved, adjusted: adjust.length };
  }

  async copy(paths: string[], dest: string): Promise<string[]> {
    this.calls.push(`copy ${paths.join(',')} -> ${dest}`);
    return paths.map((p) => {
      const n = this.need(p);
      let to = `${dest}/${n.item.name}`;
      for (let i = 1; this.nodes.has(to); i++) to = `${dest}/${n.item.name.replace(/(\.[^.]*)?$/, `${i}$1`)}`;
      this.nodes.set(to, { ...n, item: { ...n.item, path: to, name: to.slice(to.lastIndexOf('/') + 1) } });
      return to;
    });
  }

  async reprocess(paths: string[]): Promise<void> {
    this.calls.push(`reprocess ${paths.join(',')}`);
  }

  async saveMetadata(path: string, fields: BasicMetadata, append: readonly MultiField[] = []): Promise<void> {
    this.calls.push(`metadata ${path} ${JSON.stringify(fields)}${append.length ? ` append ${append.join(',')}` : ''}`);
    const md = this.need(path).metadata;
    for (const [k, v] of Object.entries(fields)) {
      if (Array.isArray(v) && (append as readonly string[]).includes(k)) {
        const before = Array.isArray(md[k]) ? (md[k] as string[]) : md[k] ? [String(md[k])] : [];
        md[k] = [...before, ...v.filter((x) => !before.includes(x))];
      } else {
        md[k] = v;
      }
    }
  }

  readonly links: ShareLink[] = [];

  async createShareLink(
    paths: string[],
    days: number,
    renditions: boolean,
    options: { originals?: boolean; amount?: number; unit?: string } = {},
  ): Promise<{ url: string; id: string; expires: string }> {
    const valid = options.amount && options.unit ? `${options.amount} ${options.unit}` : `${days}d`;
    this.calls.push(`share ${paths.join(',')} ${valid}${renditions ? ' renditions' : ''}${options.originals === false ? ' no-originals' : ''}`);
    const id = `id${this.links.length + 1}`;
    const expires = new Date(Date.UTC(2026, 9, 17 + days)).toISOString();
    this.links.push({ id, paths, expires, renditions });
    return { url: `/linkshare.html?sh=token-${id}`, id, expires };
  }

  async shareLinks(path?: string): Promise<ShareLink[]> {
    return this.links.filter((l) => !path || l.paths.includes(path));
  }

  async revokeShareLink(id: string): Promise<void> {
    this.calls.push(`revoke ${id}`);
    const i = this.links.findIndex((l) => l.id === id);
    if (i >= 0) this.links.splice(i, 1);
  }

  readonly collectionList: Array<DamCollection & { members: string[] }> = [];

  async collections(): Promise<DamCollection[]> {
    return this.collectionList.map(({ members, ...c }) => ({ ...c, count: c.smart ? undefined : members.length }));
  }

  async collection(path: string): Promise<{ collection: DamCollection; total: number; items: DamItem[] }> {
    const c = this.collectionList.find((x) => x.path === path);
    if (!c) throw new DamError(404, `no collection ${path}`);
    const items = c.smart ? (await this.search(c.scope ?? DAM_ROOT, c.query ?? {})).items : c.members.map((p) => this.need(p).item);
    const { members, ...rest } = c;
    return { collection: { ...rest, count: members.length }, total: items.length, items };
  }

  async createCollection(title: string, description: string, isPublic: boolean, smart?: { scope: string; query: SearchQuery }): Promise<DamCollection> {
    this.calls.push(`createCollection "${title}"${smart ? ` smart ${smart.scope} ${JSON.stringify(smart.query)}` : ''}`);
    const name = title.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    const c = { type: 'collection' as const, path: `/content/dam/collections/${name}`, name, title, description, public: isPublic, owner: 'admin', members: [] as string[], ...(smart ? { smart: true, scope: smart.scope, query: smart.query } : {}) };
    this.collectionList.push(c);
    return c;
  }

  async updateCollection(path: string, fields: { title?: string; description?: string; public?: boolean }): Promise<DamCollection> {
    const c = this.collectionList.find((x) => x.path === path)!;
    if (fields.title !== undefined) c.title = fields.title;
    if (fields.description !== undefined) c.description = fields.description;
    if (fields.public !== undefined) c.public = fields.public;
    return c;
  }

  async deleteCollection(path: string): Promise<void> {
    this.calls.push(`deleteCollection ${path}`);
    const i = this.collectionList.findIndex((x) => x.path === path);
    if (i >= 0) this.collectionList.splice(i, 1);
  }

  async addToCollection(collection: string, paths: string[]): Promise<number> {
    this.calls.push(`addToCollection ${collection} ${paths.join(',')}`);
    const c = this.collectionList.find((x) => x.path === collection)!;
    const added = paths.filter((p) => !c.members.includes(p));
    c.members.push(...added);
    return added.length;
  }

  async removeFromCollection(collection: string, paths: string[]): Promise<number> {
    this.calls.push(`removeFromCollection ${collection} ${paths.join(',')}`);
    const c = this.collectionList.find((x) => x.path === collection)!;
    const before = c.members.length;
    c.members = c.members.filter((p) => !paths.includes(p));
    return before - c.members.length;
  }

  async importMetadata(csv: File): Promise<ImportResult> {
    this.calls.push(`importMetadata ${csv.name}`);
    return { updated: 1, skipped: [{ line: 3, path: '/content/dam/nope.jpg', reason: 'not a file' }] };
  }

  readonly schemaList: MetadataSchema[] = [];

  /** The schema of the nearest folder that has one. */
  private schemaOf(path: string): MetadataSchema | null {
    for (let p = path; p.startsWith(DAM_ROOT); p = p.slice(0, p.lastIndexOf('/'))) {
      const item = this.nodes.get(p)?.item;
      if (item?.type === 'folder' && item.metadataSchema) return this.schemaList.find((s) => s.path === item.metadataSchema) ?? null;
    }
    return null;
  }

  async schemas(path?: string): Promise<{ schemas: MetadataSchema[]; effective?: string | null }> {
    return { schemas: [...this.schemaList], effective: path ? (this.schemaOf(path)?.path ?? null) : null };
  }

  async saveSchema(name: string, title: string, tabs: SchemaTab[]): Promise<MetadataSchema> {
    this.calls.push(`saveSchema ${name} "${title}" ${tabs.map((t) => `${t.title}[${t.fields.map((f) => `${f.property}:${f.type}`).join(',')}]`).join(' ')}`);
    const s = { path: `/conf/global/settings/dam/adminui-extension/metadataschema/${name}`, name, title, tabs };
    const i = this.schemaList.findIndex((x) => x.name === name);
    if (i >= 0) this.schemaList[i] = s;
    else this.schemaList.push(s);
    return s;
  }

  async deleteSchema(name: string): Promise<void> {
    const i = this.schemaList.findIndex((x) => x.name === name);
    if (i >= 0) this.schemaList.splice(i, 1);
  }

  readonly formLists: Record<FormKind, MetadataSchema[]> = { folderSchema: [], profile: [] };
  readonly folderValues = new Map<string, Record<string, unknown>>();

  /** The form of that kind on the nearest folder that has one. */
  private formOf(kind: FormKind, path: string): MetadataSchema | null {
    for (let p = path; p.startsWith(DAM_ROOT); p = p.slice(0, p.lastIndexOf('/'))) {
      const item = this.nodes.get(p)?.item;
      const own = item?.type === 'folder' ? (kind === 'profile' ? item.metadataProfile : item.folderMetadataSchema) : null;
      if (own) return this.formLists[kind].find((s) => s.path === own) ?? null;
    }
    return null;
  }

  async forms(kind: FormKind, path?: string): Promise<{ forms: MetadataSchema[]; effective?: string | null }> {
    return { forms: [...this.formLists[kind]], effective: path ? (this.formOf(kind, path)?.path ?? null) : null };
  }

  async saveForm(kind: FormKind, name: string, title: string, tabs: SchemaTab[], values?: Record<string, FieldValue>): Promise<MetadataSchema> {
    this.calls.push(`saveForm ${kind} ${name} "${title}"${values ? ` ${JSON.stringify(values)}` : ''}`);
    const root = kind === 'profile' ? 'metadataprofile' : 'foldermetadataschema';
    const s: MetadataSchema = { path: `/conf/global/settings/dam/adminui-extension/${root}/${name}`, name, title, tabs, ...(values ? { values } : {}) };
    const list = this.formLists[kind];
    const i = list.findIndex((x) => x.name === name);
    if (i >= 0) list[i] = s;
    else list.push(s);
    return s;
  }

  /** Search Forms: null = the default form. */
  searchFacets: SearchFacet[] | null = null;

  async searchForm(): Promise<SearchForm> {
    return this.searchFacets
      ? { facets: structuredClone(this.searchFacets), custom: true, modified: '2026-11-23T22:00:00Z', modifiedBy: 'admin' }
      : {
          facets: [
            { type: 'types', label: 'File type' },
            { type: 'tags', label: 'Tags' },
            { type: 'modified', label: 'Modified' },
            { type: 'size', label: 'File size' },
            { type: 'orientation', label: 'Orientation' },
            { type: 'colors', label: 'Color' },
          ],
          custom: false,
        };
  }

  async saveSearchForm(facets: SearchFacet[]): Promise<SearchForm> {
    this.calls.push(`saveSearchForm ${facets.map((f) => f.type + (f.property ? `(${f.property})` : '')).join(',')}`);
    const seen = new Set<string>();
    for (const f of facets) {
      const key = f.property ? `${f.type} ${f.property}` : f.type;
      if (seen.has(key)) throw new DamError(400, `the ${f.label} filter is there twice`);
      seen.add(key);
      if ((f.type === 'options' || f.type === 'text') && !f.property) throw new DamError(400, `"${f.label}" needs a metadata property, such as dc:creator`);
      if (f.type === 'options' && !f.options?.length) throw new DamError(400, `"${f.label}" needs at least one value to choose`);
    }
    this.searchFacets = structuredClone(facets);
    return this.searchForm();
  }

  async resetSearchForm(): Promise<SearchForm> {
    this.calls.push('resetSearchForm');
    this.searchFacets = null;
    return this.searchForm();
  }

  async metadataValues(path: string, property: string): Promise<MetadataValues> {
    const counts = new Map<string, number>();
    for (const n of this.nodes.values()) {
      if (n.item.type !== 'asset' || !n.item.path.startsWith(`${path}/`)) continue;
      const v = n.metadata[property];
      for (const x of Array.isArray(v) ? v : v == null ? [] : [v]) counts.set(String(x), (counts.get(String(x)) ?? 0) + 1);
    }
    const values = [...counts].map(([value, count]) => ({ value, count })).sort((a, b) => b.count - a.count || a.value.localeCompare(b.value));
    return { property, values: values.slice(0, 50), more: values.length > 50, truncated: false };
  }

  async deleteForm(kind: FormKind, name: string): Promise<void> {
    const list = this.formLists[kind];
    const i = list.findIndex((x) => x.name === name);
    if (i >= 0) list.splice(i, 1);
  }

  async setFolderForm(kind: FormKind, folder: string, form: string | null): Promise<void> {
    this.calls.push(`folderForm ${kind} ${folder} ${form ?? '-'}`);
    const item = this.need(folder).item;
    if (item.type !== 'folder') return;
    if (kind === 'profile') item.metadataProfile = form;
    else item.folderMetadataSchema = form;
  }

  async applyProfile(folder: string): Promise<{ files: number; changed: number; truncated: boolean }> {
    this.calls.push(`applyProfile ${folder}`);
    return { files: 0, changed: 0, truncated: false };
  }

  async folderMetadata(folder: string): Promise<{ schema: MetadataSchema | null; values: Record<string, unknown> }> {
    return { schema: this.formOf('folderSchema', folder), values: { ...(this.folderValues.get(folder) ?? {}) } };
  }

  async saveFolderMetadata(folder: string, fields: Record<string, FieldValue>): Promise<void> {
    this.calls.push(`folderMetadata ${folder} ${JSON.stringify(fields)}`);
    const v = { ...(this.folderValues.get(folder) ?? {}) };
    for (const [k, x] of Object.entries(fields)) {
      if (x === null) delete v[k];
      else v[k] = x;
    }
    this.folderValues.set(folder, v);
  }

  async setFolderSchema(folder: string, schema: string | null): Promise<void> {
    this.calls.push(`folderSchema ${folder} ${schema ?? '-'}`);
    const item = this.need(folder).item;
    if (item.type === 'folder') item.metadataSchema = schema;
  }

  async saveFields(path: string, fields: Record<string, FieldValue>): Promise<void> {
    this.calls.push(`fields ${path} ${JSON.stringify(fields)}`);
    Object.assign(this.need(path).metadata, fields);
  }

  readonly profileList: ProcessingProfile[] = [];

  smartSettings: SmartTagsSettings = { enabled: false, tagRoots: [], keywords: true, max: 5, minConfidence: 0.05 };
  smartInstalled = false;
  /** smart tags per asset path */
  readonly smart = new Map<string, SmartTags>();

  async setFolderSmartTagging(folder: string, value: 'on' | 'off' | null): Promise<void> {
    this.calls.push(`folderSmartTags ${folder} ${value ?? '-'}`);
    const item = this.need(folder).item;
    if (item.type === 'folder') item.smartTagging = value;
  }

  async smartTagsStatus(folder?: string): Promise<SmartTagsStatus> {
    let effective = this.smartSettings.enabled;
    for (let p = folder; p && p.startsWith(DAM_ROOT); p = p.slice(0, p.lastIndexOf('/'))) {
      const it = this.nodes.get(p)?.item;
      if (it?.type === 'folder' && it.smartTagging) {
        effective = it.smartTagging === 'on';
        break;
      }
    }
    return {
      effective,
      settings: this.smartSettings,
      model: { model: 'siglip-base-patch16-224', license: 'Apache-2.0', installed: this.smartInstalled, files: [] },
      install: { state: this.smartInstalled ? 'done' : 'idle', bytes: 0, total: 0 },
      searchModel: { model: 'siglip-base-patch16-256-multilingual', license: 'Apache-2.0', installed: this.searchInstalled, files: [] },
      searchInstall: { state: this.searchInstalled ? 'done' : 'idle', bytes: 0, total: 0 },
      admin: true,
    };
  }

  async saveSmartTagsSettings(st: SmartTagsSettings): Promise<SmartTagsSettings> {
    this.calls.push(`smartTagsSettings ${JSON.stringify(st)}`);
    this.smartSettings = st;
    return st;
  }

  /** the multilingual model */
  searchInstalled = false;

  async installSearchModel(): Promise<void> {
    this.calls.push('searchModelInstall');
    this.searchInstalled = true;
  }

  async installSmartTagsModel(): Promise<void> {
    this.calls.push('smartTagsInstall');
    this.smartInstalled = true;
  }

  /** what `similar` answers */
  similarTo: SimilarResult = { path: '', items: [], compared: 0, notIndexed: 0 };

  async similar(path: string, limit = 24): Promise<SimilarResult> {
    this.calls.push(`similar ${path} ${limit}`);
    return { ...this.similarTo, path };
  }

  async similarIndex(paths: string[] = []): Promise<number> {
    this.calls.push(`similarIndex ${paths.join(',')}`);
    return this.similarTo.notIndexed;
  }

  /** Smart Tag training: learned tags; a tag needs `minExamples` pictures with it in `examples`. */
  learned: LearnedTag[] = [];
  trainingExamples: Record<string, number> = {};

  async smartTagTraining(tags: string[] = []): Promise<SmartTagTraining> {
    return {
      models: structuredClone(this.learned),
      minExamples: 5,
      goodExamples: 10,
      ...(tags.length
        ? { ready: tags.map((tag) => ({ tag, title: tag.slice(tag.lastIndexOf('/') + 1), tagged: this.trainingExamples[tag] ?? 0, examples: this.trainingExamples[tag] ?? 0 })) }
        : {}),
    };
  }

  async trainSmartTags(tags: string[]): Promise<TrainingResult[]> {
    this.calls.push(`trainSmartTags ${tags.join(',')}`);
    return tags.map((tag) => {
      const examples = this.trainingExamples[tag] ?? 0;
      const title = tag.slice(tag.lastIndexOf('/') + 1);
      if (examples < 5) return { tag, title, examples, error: `Only ${examples} pictures with this tag were looked at; it takes 5 or more` };
      const m: LearnedTag = { tag, title, examples, recall: 0.8, others: 1, threshold: 0.8, trained: '2026-11-24T12:00:00Z', trainedBy: 'admin' };
      this.learned = [...this.learned.filter((x) => x.tag !== tag), m];
      return m;
    });
  }

  async forgetSmartTag(tag: string): Promise<void> {
    this.calls.push(`forgetSmartTag ${tag}`);
    this.learned = this.learned.filter((x) => x.tag !== tag);
  }

  async colorTag(paths: string[], again = false): Promise<{ tagged: number; skipped: number; more: boolean }> {
    this.calls.push(`colorTag ${paths.join(',')}${again ? ' again' : ''}`);
    return { tagged: paths.length, skipped: 0, more: false };
  }

  async smartTag(paths: string[]): Promise<number> {
    this.calls.push(`smartTag ${paths.join(',')}`);
    return paths.length;
  }

  async changeSmartTag(path: string, tag: string, apply: boolean): Promise<SmartTags> {
    this.calls.push(`smartTag${apply ? 'Apply' : 'Remove'} ${path} ${tag}`);
    const st = this.smart.get(path) ?? { tags: [], keywords: [] };
    const next = { ...st, tags: st.tags.filter((t) => t.id !== tag), keywords: st.keywords.filter((k) => `keyword:${k}` !== tag) };
    this.smart.set(path, next);
    if (apply) {
      const md = this.need(path).metadata;
      md['cq:tags'] = [...((md['cq:tags'] as string[] | undefined) ?? []), tag];
    }
    return next;
  }

  /** Reports kept in memory: their description and rows (first row the header). */
  readonly reportStore = new Map<string, { info: ReportInfo; rows: string[][] }>();

  async reportCatalog(): Promise<ReportCatalog> {
    const t = (id: string, title: string, description: string, columns: boolean, dates: boolean, folder = true) => ({ id, title, description, columns, dates, folder });
    return {
      types: [
        t('assetadditionreport', 'Upload', 'Report of uploaded files', true, true),
        t('assetmodificationreport', 'Modification', 'Report of modified files', true, true),
        t('assetexpiryreport', 'Expiration', 'Report of expired files', true, true),
        t('foldersizeandstrengthreport', 'Disk Usage', 'Report of disk usage', false, false),
        t('foldercontentreport', 'Files', 'Report of files in folder', true, false),
        t('linksharereport', 'Link Share', 'Report of shared links', false, true, false),
      ],
      columns: [
        { id: 'title', title: 'Title', default: true },
        { id: 'path', title: 'Path', default: true },
        { id: 'type', title: 'Type', default: true },
        { id: 'size', title: 'Size', default: true },
        { id: 'addedBy', title: 'Added By', default: false },
      ],
    };
  }

  async reports(): Promise<ReportInfo[]> {
    return [...this.reportStore.values()].map((r) => r.info).reverse();
  }

  async report(id: string, offset = 0, limit = 100): Promise<ReportPage> {
    const r = this.reportStore.get(id);
    if (!r) throw new DamError(404, `no report ${id}`);
    return { ...r.info, header: r.rows[0], offset, items: r.rows.slice(1 + offset, 1 + offset + limit) };
  }

  /** Files: the assets below the folder; the other types report nothing here. */
  async createReport(q: ReportRequest): Promise<ReportInfo> {
    this.calls.push(`createReport ${JSON.stringify(q)}`);
    if (!q.title) throw new DamError(400, 'a report needs a title');
    const catalog = await this.reportCatalog();
    const type = catalog.types.find((t) => t.id === q.type);
    if (!type) throw new DamError(400, 'unknown report type');
    const cols = q.columns ?? ['title', 'path'];
    const header = [...cols.map((c) => catalog.columns.find((x) => x.id === c)?.title ?? c), ...(q.properties ?? [])];
    const under = (q.path ?? DAM_ROOT) + '/';
    const assets = q.type === 'foldercontentreport' ? [...this.nodes.values()].filter((n) => n.item.type === 'asset' && n.item.path.startsWith(under)) : [];
    const rows = assets.map((n) => [
      ...cols.map((c) => (c === 'title' ? n.item.title : c === 'path' ? n.item.path : c === 'type' ? ((n.item as AssetItem).mimeType ?? '') : c === 'size' ? String((n.item as AssetItem).size ?? '') : '')),
      ...(q.properties ?? []).map((p) => String(n.metadata[p] ?? '')),
    ]);
    const id = `00000000-0000-4000-8000-${String(this.reportStore.size + 1).padStart(12, '0')}`;
    const info: ReportInfo = {
      id,
      title: q.title,
      description: q.description ?? null,
      type: q.type,
      typeTitle: type.title,
      path: type.folder ? (q.path ?? DAM_ROOT) : null,
      from: q.from ?? null,
      to: q.to ?? null,
      owner: 'admin',
      created: new Date(2026, 9, 18, 9, 30).toISOString(),
      status: 'completed',
      rows: rows.length,
      truncated: false,
      summary: { assets: rows.length, size: assets.reduce((t, n) => t + ((n.item as AssetItem).size ?? 0), 0) },
    };
    this.reportStore.set(id, { info, rows: [header, ...rows] });
    return info;
  }

  async deleteReports(ids: string[]): Promise<number> {
    this.calls.push(`deleteReports ${ids.join(',')}`);
    return ids.filter((id) => this.reportStore.delete(id)).length;
  }

  imagePresetList: ImagePreset[] = [];
  admin = true;

  async imagePresets(asset?: string): Promise<ImagePresets> {
    this.calls.push(`imagePresets ${asset ?? ''}`.trim());
    const url = asset && this.nodes.get(asset)?.item.type === 'asset' ? `/is/image${asset.slice(DAM_ROOT.length).replace(/\.[^./]+$/, '')}` : undefined;
    return {
      presets: this.imagePresetList.map((p) => ({ ...p, url: url ? `${url}?$${p.name}$` : undefined })),
      url,
      parameters: ['wid', 'hei', 'fmt', 'qlt', 'fit', 'crop', 'rotate', 'flip', 'op_usm', 'bgc'],
      admin: this.admin,
    };
  }

  imageProfileList: ImageProfile[] = [];
  smartCropStore = new Map<string, SmartCrop[]>();

  async imageProfiles(folder?: string): Promise<{ profiles: ImageProfile[]; effective?: string | null; admin: boolean }> {
    this.calls.push(`imageProfiles ${folder ?? ''}`.trim());
    let effective: string | null = null;
    for (let p = folder ?? ''; p.startsWith(DAM_ROOT) && !effective; p = p.slice(0, p.lastIndexOf('/'))) {
      const item = this.nodes.get(p)?.item;
      if (item?.type === 'folder' && item.imageProfile) effective = item.imageProfile;
    }
    return { profiles: [...this.imageProfileList], effective, admin: this.admin };
  }

  async saveImageProfile(name: string, title: string, ratios: CropRatio[]): Promise<ImageProfile> {
    this.calls.push(`saveImageProfile ${name} ${ratios.map((r) => `${r.name}:${r.width}x${r.height}`).join(',')}`);
    if (!this.admin) throw new DamError(403, 'only admin can change image profiles');
    const p = { path: `/conf/global/settings/dam/dm/imageprofiles/${name}`, name, title, ratios };
    this.imageProfileList = [...this.imageProfileList.filter((x) => x.name !== name), p];
    return p;
  }

  async deleteImageProfile(name: string): Promise<void> {
    this.calls.push(`deleteImageProfile ${name}`);
    this.imageProfileList = this.imageProfileList.filter((x) => x.name !== name);
  }

  async setFolderImageProfile(folder: string, profile: string | null): Promise<number> {
    this.calls.push(`folderImageProfile ${folder} ${profile ?? ''}`.trim());
    const n = this.need(folder);
    if (n.item.type === 'folder') n.item.imageProfile = profile;
    return [...this.nodes.values()].filter((x) => x.item.type === 'asset' && x.item.path.startsWith(`${folder}/`)).length;
  }

  async smartCropRun(paths: string[]): Promise<number> {
    this.calls.push(`smartCropRun ${paths.join(',')}`);
    return paths.length;
  }

  async setSmartCrop(path: string, name: string, left: number, top: number, width: number): Promise<SmartCrop> {
    this.calls.push(`smartCrop ${path} ${name} ${left.toFixed(3)},${top.toFixed(3)},${width.toFixed(3)}`);
    const crops = this.smartCropStore.get(path) ?? [];
    const c = crops.find((x) => x.name === name);
    if (!c) throw new DamError(404, `no focus crop ${name}`);
    let height = ((width * c.imageWidth) / c.imageHeight) * (c.ratioHeight / c.ratioWidth);
    if (height > 1) {
      height = 1;
      width = ((height * c.imageHeight) / c.imageWidth) * (c.ratioWidth / c.ratioHeight);
    }
    Object.assign(c, { left: Math.min(left, 1 - width), top: Math.min(top, 1 - height), width, height, manual: true });
    return { ...c };
  }

  async resetSmartCrops(path: string): Promise<SmartCrop[]> {
    this.calls.push(`smartCropReset ${path}`);
    const crops = this.smartCropStore.get(path) ?? [];
    for (const c of crops) c.manual = false;
    return crops.map((c) => ({ ...c }));
  }

  async saveImagePreset(name: string, title: string, description: string, params: Record<string, string>): Promise<ImagePreset> {
    this.calls.push(`saveImagePreset ${name} ${JSON.stringify(params)}`);
    if (!this.admin) throw new DamError(403, 'only admin can change image presets');
    const clean = Object.fromEntries(Object.entries(params).filter(([, v]) => v.trim()));
    const preset: ImagePreset = {
      name,
      title,
      description,
      params: clean,
      query: Object.entries(clean)
        .map(([k, v]) => `${k}=${v}`)
        .join('&'),
    };
    this.imagePresetList = [...this.imagePresetList.filter((p) => p.name !== name), preset];
    return preset;
  }

  async deleteImagePreset(name: string): Promise<void> {
    this.calls.push(`deleteImagePreset ${name}`);
    this.imagePresetList = this.imagePresetList.filter((p) => p.name !== name);
  }

  async profiles(folder?: string): Promise<{ profiles: ProcessingProfile[]; effective?: string | null; watermark?: WatermarkSetting; admin?: boolean }> {
    let effective: string | null = null;
    for (let p = folder ?? ''; p.startsWith(DAM_ROOT) && !effective; p = p.slice(0, p.lastIndexOf('/'))) {
      const item = this.nodes.get(p)?.item;
      if (item?.type === 'folder' && item.processingProfile) effective = item.processingProfile;
    }
    return { profiles: [...this.profileList], effective, watermark: { ...this.watermark }, admin: true };
  }

  galleryPresetList: GalleryPreset[] = [];

  async galleryPresets(): Promise<{ presets: GalleryPreset[]; admin?: boolean }> {
    return { presets: [...this.galleryPresetList], admin: true };
  }

  async saveGalleryPreset(p: Omit<GalleryPreset, 'name'> & { name?: string }): Promise<GalleryPreset> {
    if (!p.title.trim()) throw new Error('A preset needs a title');
    const name = p.name || p.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    const saved = { ...p, name } as GalleryPreset;
    this.galleryPresetList = [...this.galleryPresetList.filter((x) => x.name !== name), saved];
    return saved;
  }

  async deleteGalleryPreset(name: string): Promise<void> {
    this.galleryPresetList = this.galleryPresetList.filter((x) => x.name !== name);
  }

  liveCopyInfo: Record<string, LiveCopyInfo> = {};
  liveCopyCalls: string[] = [];

  async liveCopy(path: string): Promise<LiveCopyInfo> {
    return this.liveCopyInfo[path] ?? { liveCopies: [] };
  }

  async createLiveCopy(source: string, parent: string, title: string, name?: string, push = false): Promise<RolloutResult & { path: string }> {
    const n = name || title.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    this.liveCopyCalls.push(`create ${source} ${parent}/${n} ${title}${push ? ' push' : ''}`);
    return { path: `${parent}/${n}`, added: 1, updated: 0, removed: 0, skipped: [] };
  }

  async liveCopySync(path: string, reset = false): Promise<RolloutResult> {
    this.liveCopyCalls.push(`${reset ? 'reset' : 'sync'} ${path}`);
    return { added: 0, updated: 1, removed: 0, skipped: [] };
  }

  async rollout(path: string): Promise<RolloutResult> {
    this.liveCopyCalls.push(`rollout ${path}`);
    return { added: 1, updated: 2, removed: 0, skipped: [] };
  }

  async liveCopySuspend(path: string, suspend: boolean): Promise<void> {
    this.liveCopyCalls.push(`${suspend ? 'suspend' : 'resume'} ${path}`);
  }

  async liveCopyPush(path: string, push: boolean): Promise<void> {
    this.liveCopyCalls.push(`${push ? 'push' : 'nopush'} ${path}`);
  }

  async liveCopyDetach(path: string): Promise<void> {
    this.liveCopyCalls.push(`detach ${path}`);
  }

  async liveCopyInherit(path: string, field: string): Promise<void> {
    this.liveCopyCalls.push(`inherit ${path} ${field}`);
  }

  watermark: WatermarkSetting = { image: null, position: 'center', opacity: 50, size: 25, ready: false, thumbnail: null };

  async saveWatermark(setting: WatermarkSetting): Promise<WatermarkSetting> {
    if (setting.opacity < 1 || setting.opacity > 100) throw new Error('opacity must be 1 to 100');
    this.watermark = { ...setting, ready: !!setting.image, thumbnail: null };
    return { ...this.watermark };
  }

  async saveProfile(name: string, title: string, renditions: ProfileRendition[]): Promise<ProcessingProfile> {
    this.calls.push(`saveProfile ${name} "${title}" ${renditions.map((r) => `${r.name}.${r.format}:${r.width}x${r.height}`).join(',')}`);
    const p = { path: `/conf/global/settings/dam/processing/${name}`, name, title, renditions };
    const i = this.profileList.findIndex((x) => x.name === name);
    if (i >= 0) this.profileList[i] = p;
    else this.profileList.push(p);
    return p;
  }

  async deleteProfile(name: string): Promise<void> {
    this.calls.push(`deleteProfile ${name}`);
    const i = this.profileList.findIndex((x) => x.name === name);
    if (i >= 0) this.profileList.splice(i, 1);
  }

  async setFolderProfile(folder: string, profile: string | null): Promise<void> {
    this.calls.push(`folderProfile ${folder} ${profile ?? '-'}`);
    const item = this.need(folder).item;
    if (item.type === 'folder') item.processingProfile = profile;
  }

  async setPoster(path: string, image: Blob | null): Promise<void> {
    this.calls.push(`poster ${path} ${image ? `${image.type} ${image.size}` : 'remove'}`);
    const item = this.need(path).item as AssetItem;
    if (!item.mimeType?.startsWith('video/')) throw new DamError(400, 'only videos have a poster frame');
    item.poster = image ? `${path}/_jcr_content/renditions/cq5dam.poster.jpg` : null;
    item.state = 'processing';
  }

  async checkout(paths: string[]): Promise<void> {
    this.calls.push(`checkout ${paths.join(',')}`);
    for (const p of paths) {
      const item = this.need(p).item as AssetItem;
      if (item.checkedOutBy && item.checkedOutBy !== 'admin') throw new DamError(423, `${item.name} is checked out by ${item.checkedOutBy}`);
      item.checkedOutBy = 'admin';
    }
  }

  async checkin(paths: string[]): Promise<void> {
    this.calls.push(`checkin ${paths.join(',')}`);
    for (const p of paths) (this.need(p).item as AssetItem).checkedOutBy = null;
  }

  /** Relations, both ways. */
  readonly related = new Map<string, Set<string>>();

  /** image sets: path → member paths */
  readonly sets = new Map<string, string[]>();

  async createSet(parent: string, title: string, members: string[], kind?: string): Promise<string> {
    this.calls.push(`createSet ${parent} ${title} ${members.join(',')}${kind ? ` ${kind}` : ''}`);
    const path = `${parent}/${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
    this.sets.set(path, members);
    return path;
  }

  async setMembers(path: string, members: string[], kind?: string): Promise<void> {
    this.calls.push(`setMembers ${path} ${members.join(',')}${kind ? ` ${kind}` : ''}`);
    this.sets.set(path, members);
  }

  async relate(paths: string[]): Promise<number> {
    this.calls.push(`relate ${paths.join(',')}`);
    let added = 0;
    for (const a of paths)
      for (const b of paths) {
        if (a === b) continue;
        const set = this.related.get(a) ?? new Set<string>();
        if (!set.has(b)) {
          set.add(b);
          added++;
        }
        this.related.set(a, set);
      }
    return added / 2;
  }

  async unrelate(path: string, others: string[]): Promise<void> {
    this.calls.push(`unrelate ${path} ${others.join(',')}`);
    for (const o of others) {
      this.related.get(path)?.delete(o);
      this.related.get(o)?.delete(path);
    }
  }

  async extractArchive(path: string, options: { into: 'here' | 'folder'; conflict: string }): Promise<ExtractResult> {
    this.calls.push(`extract ${path} ${options.into} ${options.conflict}`);
    const parent = path.slice(0, path.lastIndexOf('/'));
    const into = options.into === 'folder' ? `${parent}/${path.slice(parent.length + 1).replace(/\.[^.]*$/, '')}` : parent;
    if (!this.nodes.has(into)) this.folder(into, into.slice(into.lastIndexOf('/') + 1));
    const created = [`${into}/a.jpg`, `${into}/b.jpg`];
    for (const c of created) this.asset(c, { state: 'processing' });
    return { created, folders: [], skipped: ['run.sh'], into };
  }

  /** Versions by asset path, oldest first. */
  readonly versionsOf = new Map<string, AssetVersion[]>();

  readonly commentsByPath = new Map<string, AssetComment[]>();

  async comments(path: string): Promise<{ comments: AssetComment[]; canComment: boolean }> {
    return { comments: [...(this.commentsByPath.get(path) ?? [])], canComment: true };
  }

  async addComment(path: string, text: string, region?: CommentRegion, color?: string): Promise<AssetComment> {
    if (!text.trim()) throw new Error('Write a comment first');
    const list = this.commentsByPath.get(path) ?? [];
    const c: AssetComment = {
      id: `c${list.length + 1}`,
      text: text.trim(),
      author: 'admin',
      created: new Date(Date.UTC(2026, 10, 23, 10, list.length)).toISOString(),
      mine: true,
      ...(region ? { region, color: color ?? '#e68619' } : {}),
    };
    this.commentsByPath.set(path, [...list, c]);
    this.calls.push(`comment ${path}${region ? ' note' : ''}`);
    return c;
  }

  async deleteComment(path: string, id: string): Promise<void> {
    this.commentsByPath.set(path, (this.commentsByPath.get(path) ?? []).filter((c) => c.id !== id));
  }

  async versions(path: string): Promise<AssetVersion[]> {
    this.need(path);
    return [...(this.versionsOf.get(path) ?? [])].reverse();
  }

  async createVersion(path: string, label?: string, comment?: string): Promise<AssetVersion> {
    this.calls.push(`createVersion ${path}${label ? ` "${label}"` : ''}${comment ? ` (${comment})` : ''}`);
    this.need(path);
    const list = this.versionsOf.get(path) ?? [];
    if (label) for (const v of list) v.labels = v.labels.filter((l) => l !== label);
    const v: AssetVersion = { name: `1.${list.length}`, created: new Date(Date.UTC(2026, 9, 17, 12, list.length)).toISOString(), labels: label ? [label] : [], comment: comment ?? null, createdBy: 'admin' };
    list.push(v);
    this.versionsOf.set(path, list);
    return v;
  }

  async restoreVersion(path: string, version: string): Promise<AssetVersion> {
    if (!(this.versionsOf.get(path) ?? []).some((v) => v.name === version)) throw new DamError(404, `no such version: ${version}`);
    const saved = await this.createVersion(path, undefined, `Before restoring version ${version}`);
    this.calls.push(`restoreVersion ${path} ${version}`);
    return saved;
  }

  readonly edits: Array<{ path: string; operations: EditOperation[]; areas?: MapArea[] }> = [];

  async edit(path: string, operations: EditOperation[], areas?: MapArea[]): Promise<EditResult> {
    this.calls.push(`edit ${path}`);
    this.edits.push({ path, operations, areas });
    const n = this.need(path);
    if (n.item.type !== 'asset') throw new DamError(400, 'not a file');
    const size = sizeAfter({ width: n.item.width ?? 0, height: n.item.height ?? 0 }, operations);
    if (operations.length > 0) Object.assign(n.item, { ...size, state: 'processing' });
    if (areas) n.imageMap = areas.length ? `${areas.length} areas` : null;
    return { edited: operations.length > 0, ...size };
  }

  async setFolderThumbnail(path: string, asset: string | null): Promise<void> {
    this.calls.push(`folderThumbnail ${path} ${asset ?? '-'}`);
    const item = this.need(path).item;
    if (item.type === 'folder') item.thumbnailAsset = asset;
  }

  async setFolderTitle(path: string, title: string): Promise<void> {
    this.calls.push(`folderTitle ${path} "${title}"`);
    this.need(path).item.title = title;
  }

  /** Duplicates by content: here, by the fake's `contents` map (file name → asset paths with the same bytes). */
  readonly sameContent = new Map<string, string[]>();

  async findDuplicates(file: File): Promise<string[]> {
    return this.sameContent.get(file.name) ?? [];
  }

  async upload(folder: string, file: File, onProgress?: (f: number) => void, conflict?: UploadConflict): Promise<string> {
    this.calls.push(`upload ${folder}/${file.name}${conflict ? ` ${conflict}` : ''}`);
    let path = `${folder}/${file.name}`;
    if (this.nodes.has(path)) {
      if (!conflict) throw new DamError(409, `${file.name} already exists`);
      if (conflict === 'rename') {
        for (let i = 1; this.nodes.has(path); i++) path = `${folder}/${file.name.replace(/(\.[^.]*)?$/, `${i}$1`)}`;
      }
      if (conflict === 'version') await this.createVersion(path, undefined, 'Before upload of a new version');
    }
    onProgress?.(1);
    this.asset(path, { state: 'processing', thumbnail: null });
    return path;
  }

  async currentUser(): Promise<string> {
    return 'admin';
  }
}
