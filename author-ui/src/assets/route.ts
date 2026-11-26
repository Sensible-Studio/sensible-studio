import { DAM_ROOT, type SearchQuery } from './api';

/**
 * AEM's Assets URLs:
 *   /assets.html/content/dam/...         folder (or asset) browser
 *   /assets.html/content/dam/...?fulltext=&type=&tag=&modified=&size=&orientation=&meta=prop=value&metaText=prop=text&look=1
 *                                        search below that folder
 *   /assetdetails.html/content/dam/...   one asset;  #properties opens its properties
 */
export type AssetsRoute =
  | { kind: 'browse'; path: string; search?: SearchQuery }
  | {
      kind: 'details';
      path: string;
      properties: boolean;
      /** #similar: opens with the Similar rail */
      similar?: boolean;
      /** #annotate: opens drawing notes on it (AEM's Annotate) */
      annotate?: boolean;
      /** #timeline: opens with the Timeline (a comment someone was told about) */
      timeline?: boolean;
    }
  | { kind: 'edit'; path: string }
  | { kind: 'profiles' }
  | { kind: 'collections' }
  | { kind: 'schemas' }
  | { kind: 'folderschemas' }
  | { kind: 'metadataprofiles' }
  | { kind: 'collection'; path: string }
  | { kind: 'reports' }
  | { kind: 'sharedlinks' }
  /** form 'sites': the Filter rail of Pages (AEM's Sites Admin Search Rail); otherwise the Media search */
  | { kind: 'searchforms'; form?: 'sites' }
  | { kind: 'smarttags' }
  | { kind: 'imagepresets' }
  | { kind: 'imageprofiles' }
  /** Tools › Assets › Assets Configurations; `editor`: one setting's form */
  | { kind: 'assetsconfigurations'; editor?: AssetsConfigEditor }
  | { kind: 'report'; id: string };

/** AEM's names of the Assets Configurations forms (`…/assetsconfigurations/editors/<name>.html`). */
export type AssetsConfigEditor = 'uploadrestrictions' | 'digitalrightsmanagement' | 'watermark' | 'assetsomnisearch' | 'assetduplicationdetector' | 'linkshare';
export const ASSETS_CONFIG_EDITORS: AssetsConfigEditor[] = ['uploadrestrictions', 'digitalrightsmanagement', 'watermark', 'assetsomnisearch', 'assetduplicationdetector', 'linkshare'];
/** Tools › Assets › Assets Configurations (AEM's address). */
export const ASSETSCONFIG_PAGE = '/mnt/overlay/dam/gui/content/assetsconfigurations';

/** AEM's image editor URL (`/mnt/overlay/...assetedit.html/<asset>`). */
export const EDITOR_PAGE = '/mnt/overlay/dam/gui/content/assets/assetedit';
/** Tools > Assets > Processing Profiles. */
export const PROFILES_PAGE = '/mnt/overlay/dam/gui/content/processingprofilepage';
export const PROFILES_ROOT = '/conf/global/settings/dam/processing';
/** AEM's collections console and one collection. */
export const COLLECTIONS_PAGE = '/mnt/overlay/dam/gui/content/collections';
export const COLLECTION_PAGE = '/mnt/overlay/dam/gui/content/collections/collectiondetails';
export const COLLECTIONS_ROOT = '/content/dam/collections';
/** Tools > Assets > Metadata Schemas. */
export const SCHEMAS_PAGE = '/mnt/overlay/dam/gui/content/metadataschemaeditor/schemalist';
export const SCHEMAS_ROOT = '/conf/global/settings/dam/adminui-extension/metadataschema';
/** Tools > Assets > Folder Metadata Schemas. */
export const FOLDERSCHEMAS_PAGE = '/mnt/overlay/dam/gui/content/foldermetadataschemaeditor/schemalist';
export const FOLDERSCHEMAS_ROOT = '/conf/global/settings/dam/adminui-extension/foldermetadataschema';
/** Tools > Assets > Metadata Profiles. */
export const METADATAPROFILES_PAGE = '/mnt/overlay/dam/gui/content/processingprofilepage/metadataprofiles';
export const METADATAPROFILES_ROOT = '/conf/global/settings/dam/adminui-extension/metadataprofile';

/** Tools > Assets > Smart Tags (Sensible Studio's own page: the model and what it may tag with). */
export const SMARTTAGS_PAGE = '/mnt/overlay/dam/gui/content/smarttags';
export const SMARTTAGS_ROOT = '/conf/global/settings/dam/smarttags';
/** Tools > Assets > Image Presets (Dynamic Media). */
export const IMAGEPRESETS_PAGE = '/mnt/overlay/dam/gui/content/s7dam/presets/imagepresets';
export const IMAGEPRESETS_ROOT = '/conf/global/settings/dam/dm/presets';
/** Tools > Assets > Image Profiles (Smart Crop). */
export const IMAGEPROFILES_PAGE = '/mnt/overlay/dam/gui/content/imageprofiles';
export const IMAGEPROFILES_ROOT = '/conf/global/settings/dam/dm/imageprofiles';
/** Tools > Assets > Reports; one report is the page with its path as suffix. */
export const REPORTS_PAGE = '/mnt/overlay/dam/gui/content/reports/reportspage';
/** Navigation › Assets › Shared Links (Sensible Studio's own page: the links I shared). */
export const SHAREDLINKS_PAGE = '/mnt/overlay/dam/gui/content/sharedlinks';
export const SHAREDLINKS_ROOT = '/var/studio/dam/linkshare';
/** Tools › General › Search Forms: the filters of the Media search. */
export const SEARCHFORMS_PAGE = '/libs/cq/core/content/tools/customsearch';
export const SEARCHFORMS_ROOT = '/conf/global/settings/cq/search/facets';
export const REPORTS_ROOT = '/var/studio/dam/reports';

const MODIFIED = ['1d', '7d', '30d', '365d'];
const SIZES = ['small', 'medium', 'large'] as const;
const ORIENTATIONS = ['landscape', 'portrait', 'square'] as const;

/** The search in a query string, or undefined when there is none. */
export function parseSearch(qs: string): SearchQuery | undefined {
  const p = new URLSearchParams(qs);
  const q: SearchQuery = {};
  const fulltext = p.get('fulltext')?.trim();
  if (fulltext) q.fulltext = fulltext;
  if (p.getAll('type').length) q.types = p.getAll('type');
  if (p.getAll('tag').length) q.tags = p.getAll('tag');
  const m = p.get('modified');
  if (m && MODIFIED.includes(m)) q.modified = m;
  const size = p.get('size');
  if (size && (SIZES as readonly string[]).includes(size)) q.size = size as SearchQuery['size'];
  const o = p.get('orientation');
  if (o && (ORIENTATIONS as readonly string[]).includes(o)) q.orientation = o as SearchQuery['orientation'];
  for (const pair of p.getAll('meta')) {
    const i = pair.indexOf('=');
    if (i > 0 && i < pair.length - 1) (q.meta ??= {})[pair.slice(0, i)] = [...(q.meta?.[pair.slice(0, i)] ?? []), pair.slice(i + 1)];
  }
  for (const pair of p.getAll('metaText')) {
    const i = pair.indexOf('=');
    if (i > 0 && i < pair.length - 1) (q.metaText ??= {})[pair.slice(0, i)] = pair.slice(i + 1);
  }
  if (p.get('scope') === 'all' || p.get('scope') === 'folder') q.scope = p.get('scope') as 'all' | 'folder';
  if (p.get('look') === '1') q.look = true;
  if (q.look && p.get('weak') === '1') q.weak = true;
  if (q.look && p.get('tr') === '1') q.translate = true;
  return Object.keys(q).length || p.has('fulltext') ? q : undefined;
}

export function searchString(q: SearchQuery): string {
  const p = new URLSearchParams();
  p.set('fulltext', q.fulltext ?? '');
  for (const t of q.types ?? []) p.append('type', t);
  for (const t of q.tags ?? []) p.append('tag', t);
  if (q.modified) p.set('modified', q.modified);
  if (q.size) p.set('size', q.size);
  if (q.orientation) p.set('orientation', q.orientation);
  for (const [prop, values] of Object.entries(q.meta ?? {})) for (const v of values) p.append('meta', `${prop}=${v}`);
  for (const [prop, text] of Object.entries(q.metaText ?? {})) if (text) p.append('metaText', `${prop}=${text}`);
  if (q.scope) p.set('scope', q.scope);
  if (q.look) p.set('look', '1');
  if (q.look && q.weak) p.set('weak', '1');
  if (q.look && q.translate) p.set('tr', '1');
  return `?${p}`;
}

export function assetsRoute(loc: { pathname: string; hash: string; search?: string }): AssetsRoute | null {
  if (loc.pathname === `${ASSETSCONFIG_PAGE}/list.html`) return { kind: 'assetsconfigurations' };
  if (loc.pathname.startsWith(`${ASSETSCONFIG_PAGE}/editors/`)) {
    const name = loc.pathname.slice(`${ASSETSCONFIG_PAGE}/editors/`.length).replace(/\.html$/, '') as AssetsConfigEditor;
    return ASSETS_CONFIG_EDITORS.includes(name) ? { kind: 'assetsconfigurations', editor: name } : { kind: 'assetsconfigurations' };
  }
  if (loc.pathname === `${PROFILES_PAGE}.html` || loc.pathname.startsWith(`${PROFILES_PAGE}.html/`)) return { kind: 'profiles' };
  if (loc.pathname === `${SCHEMAS_PAGE}.html` || loc.pathname.startsWith(`${SCHEMAS_PAGE}.html/`)) return { kind: 'schemas' };
  if (loc.pathname === `${FOLDERSCHEMAS_PAGE}.html` || loc.pathname.startsWith(`${FOLDERSCHEMAS_PAGE}.html/`)) return { kind: 'folderschemas' };
  if (loc.pathname === `${METADATAPROFILES_PAGE}.html` || loc.pathname.startsWith(`${METADATAPROFILES_PAGE}.html/`)) return { kind: 'metadataprofiles' };
  if (loc.pathname === `${SMARTTAGS_PAGE}.html` || loc.pathname.startsWith(`${SMARTTAGS_PAGE}.html/`)) return { kind: 'smarttags' };
  if (loc.pathname === `${IMAGEPRESETS_PAGE}.html` || loc.pathname.startsWith(`${IMAGEPRESETS_PAGE}.html/`)) return { kind: 'imagepresets' };
  if (loc.pathname === `${IMAGEPROFILES_PAGE}.html` || loc.pathname.startsWith(`${IMAGEPROFILES_PAGE}.html/`)) return { kind: 'imageprofiles' };
  if (loc.pathname === `${SHAREDLINKS_PAGE}.html` || loc.pathname.startsWith(`${SHAREDLINKS_PAGE}.html/`)) return { kind: 'sharedlinks' };
  if (loc.pathname === `${SEARCHFORMS_PAGE}.html` || loc.pathname.startsWith(`${SEARCHFORMS_PAGE}.html/`))
    return loc.pathname === `${SEARCHFORMS_PAGE}.html${SEARCHFORMS_ROOT}/sites` ? { kind: 'searchforms', form: 'sites' } : { kind: 'searchforms' };
  if (loc.pathname === `${REPORTS_PAGE}.html` || loc.pathname.startsWith(`${REPORTS_PAGE}.html/`)) {
    const id = loc.pathname.slice(`${REPORTS_PAGE}.html${REPORTS_ROOT}/`.length);
    return loc.pathname.startsWith(`${REPORTS_PAGE}.html${REPORTS_ROOT}/`) && /^[0-9a-f-]{36}$/.test(id) ? { kind: 'report', id } : { kind: 'reports' };
  }
  if (loc.pathname.startsWith(`${COLLECTION_PAGE}.html/`)) {
    const p = safeDecode(loc.pathname.slice(COLLECTION_PAGE.length + 5));
    if (p.startsWith(`${COLLECTIONS_ROOT}/`) && !p.includes('..')) return { kind: 'collection', path: p };
  }
  if (loc.pathname === `${COLLECTIONS_PAGE}.html` || loc.pathname.startsWith(`${COLLECTIONS_PAGE}.html/`)) return { kind: 'collections' };
  const m = loc.pathname.match(/^(\/assets|\/assetdetails|\/mnt\/overlay\/dam\/gui\/content\/assets\/assetedit)\.html(\/.*)?$/);
  if (!m) return null;
  let path = m[2] ? safeDecode(m[2]) : DAM_ROOT;
  if (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1);
  if (path !== DAM_ROOT && !path.startsWith(`${DAM_ROOT}/`)) path = DAM_ROOT;
  if (m[1] === '/assets') {
    const search = parseSearch(loc.search ?? '');
    return search ? { kind: 'browse', path, search } : { kind: 'browse', path };
  }
  if (m[1] === EDITOR_PAGE) return { kind: 'edit', path };
  if (loc.hash === '#similar') return { kind: 'details', path, properties: false, similar: true };
  if (loc.hash === '#annotate') return { kind: 'details', path, properties: false, annotate: true };
  if (loc.hash === '#timeline') return { kind: 'details', path, properties: false, timeline: true };
  return { kind: 'details', path, properties: loc.hash === '#properties' };
}

export function routeUrl(route: AssetsRoute): string {
  if (route.kind === 'assetsconfigurations') return route.editor ? `${ASSETSCONFIG_PAGE}/editors/${route.editor}.html` : `${ASSETSCONFIG_PAGE}/list.html`;
  if (route.kind === 'profiles') return `${PROFILES_PAGE}.html${PROFILES_ROOT}`;
  if (route.kind === 'schemas') return `${SCHEMAS_PAGE}.html${SCHEMAS_ROOT}`;
  if (route.kind === 'folderschemas') return `${FOLDERSCHEMAS_PAGE}.html${FOLDERSCHEMAS_ROOT}`;
  if (route.kind === 'metadataprofiles') return `${METADATAPROFILES_PAGE}.html${METADATAPROFILES_ROOT}`;
  if (route.kind === 'collections') return `${COLLECTIONS_PAGE}.html${COLLECTIONS_ROOT}`;
  if (route.kind === 'smarttags') return `${SMARTTAGS_PAGE}.html${SMARTTAGS_ROOT}`;
  if (route.kind === 'imagepresets') return `${IMAGEPRESETS_PAGE}.html${IMAGEPRESETS_ROOT}`;
  if (route.kind === 'imageprofiles') return `${IMAGEPROFILES_PAGE}.html${IMAGEPROFILES_ROOT}`;
  if (route.kind === 'reports') return `${REPORTS_PAGE}.html${REPORTS_ROOT}`;
  if (route.kind === 'sharedlinks') return `${SHAREDLINKS_PAGE}.html${SHAREDLINKS_ROOT}`;
  if (route.kind === 'searchforms') return `${SEARCHFORMS_PAGE}.html${SEARCHFORMS_ROOT}${route.form === 'sites' ? '/sites' : ''}`;
  if (route.kind === 'report') return `${REPORTS_PAGE}.html${REPORTS_ROOT}/${route.id}`;
  if (route.kind === 'collection') return `${COLLECTION_PAGE}.html${encodeURI(route.path)}`;
  const page = route.kind === 'browse' ? '/assets' : route.kind === 'edit' ? EDITOR_PAGE : '/assetdetails';
  const query = route.kind === 'browse' && route.search ? searchString(route.search) : '';
  return `${page}.html${encodeURI(route.path)}${query}${route.kind === 'details' ? (route.properties ? '#properties' : route.similar ? '#similar' : route.annotate ? '#annotate' : route.timeline ? '#timeline' : '') : ''}`;
}

function safeDecode(s: string): string {
  try {
    return decodeURI(s);
  } catch {
    return s;
  }
}
