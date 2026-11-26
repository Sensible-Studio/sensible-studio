import { VOCAB } from './vocabulary';
import { assetsRoute, routeUrl, type AssetsRoute } from '../assets/route';
import { encodePath } from '../cf/api';
import { propertiesRoute } from '../pageprops/api';
import { securityRoute, type SecurityRoute } from '../security/SecurityApp';
import { tagsPath } from '../tags/api';
import { templatesRoute, type TemplatesRoute } from '../templates/api';
import { COMPONENTS_URL, componentOfUrl, componentUrl } from '../components/api';
import { BLUEPRINTS_URL } from '../msm/blueprintsUrl';
import { isRuleEditor, isRulesList, ruleEditorUrl, RULES_URL } from '../translation/rules';
import { VARIATIONS_URL } from '../variations/api';
import { workflowRoute, workflowUrl, type WorkflowRoute } from '../workflow/api';

/**
 * A place is somewhere in Studio a person can be: a console at a path, an editor on a page, a form on an
 * item. Every place has exactly one URL — AEM's, so bookmarks and links from before keep working — and
 * every URL parses to one place. The shell's tabs and history are stacks of places; a screen gets its
 * place as a prop instead of reading `window.location`.
 */
export type Place =
  /** The Start screen; `view` is its own sub-view (`tools/deployment`, a group of pages), kept in the hash. */
  | { kind: 'start'; view?: string }
  | { kind: 'sites'; path: string }
  /** The page editor: a page, or the structure / initial content of an editable template. */
  | { kind: 'editor'; path: string }
  | { kind: 'page-properties'; item: string }
  | { kind: 'assets'; route: AssetsRoute }
  | { kind: 'cf'; path: string }
  | { kind: 'cf-editor'; path: string }
  | { kind: 'cf-models'; conf: string }
  | { kind: 'cf-model-editor'; path: string }
  | { kind: 'templates'; route: TemplatesRoute }
  | { kind: 'workflow'; route: WorkflowRoute }
  | { kind: 'replication' }
  | { kind: 'launches' }
  | { kind: 'linkchecker' }
  | { kind: 'health' }
  | { kind: 'jobs' }
  | { kind: 'activity' }
  | { kind: 'siteconfig'; path: string }
  | { kind: 'projects'; name: string | null }
  /** The submissions of a form (its page path). */
  | { kind: 'form-submissions'; form: string }
  | { kind: 'mail' }
  | { kind: 'graphql' }
  | { kind: 'components' }
  /** Site sources: AEM's blueprint configurations. */
  | { kind: 'blueprints' }
  /** One component's details: its path (`/apps/wknd/components/accordion`). */
  | { kind: 'component'; path: string }
  | { kind: 'graphiql'; endpoint: string | null; query: string | null }
  | { kind: 'audiences'; conf: string | null }
  /** Tools › ContextHub: the stores and the bar of a configuration */
  | { kind: 'contexthub'; conf: string | null }
  | { kind: 'activities'; root: string | null }
  | { kind: 'tags'; path: string }
  | { kind: 'security'; route: SecurityRoute; id: string | null; principal: string | null }
  | { kind: 'translation-configs'; path: string }
  /** Translation Configuration: the contexts, or one (null: a new one). */
  | { kind: 'translation-rules' }
  | { kind: 'variations' }
  | { kind: 'translation-rule'; context: string | null }
  /** `path` null: the review list; a path: one copy's review. */
  | { kind: 'translation-review'; path: string | null }
  /** `name` null: the list of projects; a name: one project. */
  | { kind: 'translation-projects'; name: string | null };

export type PlaceKind = Place['kind'];

export interface LocationLike {
  pathname: string;
  search: string;
  hash: string;
}

export const START_URL = '/studio/start.html';
export const DEFAULT_EDITOR_PAGE = (import.meta.env.VITE_STUDIO_START_PAGE as string | undefined) ?? '/content/wknd/us/en';

const strip = (s: string) => s.replace(/\/$/, '');

function cfFragment(pathname: string): string | null {
  const m = pathname.match(/^\/editor\.html(\/content\/dam\/.+)$/);
  return m ? decodeURI(m[1]) : null;
}

function cfModelsPath(pathname: string): string | null {
  const m = pathname.match(/^\/cf\.html(\/conf(?:\/[^/]+)*)\/?$/);
  return m ? decodeURI(m[1]) : null;
}

function cfModelEditorPath(pathname: string): string | null {
  const m = pathname.match(/^\/editor\.html(\/conf\/.+\/settings\/dam\/cfm\/models\/[^/]+)\/?$/);
  return m ? decodeURI(m[1]) : null;
}

function cfFolder(pathname: string): string | null {
  const m = pathname.match(/^\/cf\.html(\/content\/dam(?:\/.*)?)?$/);
  if (!m) return null;
  return m[1] ? strip(decodeURI(m[1])) || '/content/dam' : '/content/dam';
}

function editorPage(loc: LocationLike): string | null {
  // pages, and the structure / initial content of editable templates (the template editor)
  const m = loc.pathname.match(/^\/editor\.html(\/content\/.+?|\/conf\/.+\/settings\/wcm\/templates\/[^/]+\/(?:structure|initial))(\.html)?$/);
  if (m) return decodeURI(m[1]);
  const hash = loc.hash.slice(1);
  if (loc.pathname.startsWith('/editor.html')) return hash.startsWith('/content/') ? hash : DEFAULT_EDITOR_PAGE;
  return null;
}

function sitesPath(pathname: string): string | null {
  const m = pathname.match(/^\/sites\.html(\/content(?:\/.*)?)?$/);
  if (!m) return null;
  return m[1] ? strip(decodeURI(m[1])) || '/content' : '/content';
}

/** The place a URL names. Every URL names one; unknown URLs are the Start screen, as before. */
export function parsePlace(loc: LocationLike): Place {
  return matchLocation(loc) ?? { kind: 'start' };
}

/** The place a location names, or null when it is no screen of Studio (the OSGi console, a content page…). */
export function matchLocation(loc: LocationLike): Place | null {
  const { pathname } = loc;
  if (pathname === START_URL || pathname === '/' || pathname === '') {
    const view = loc.hash.replace(/^#/, '');
    return view ? { kind: 'start', view } : { kind: 'start' };
  }
  const templates = templatesRoute(loc);
  if (templates) return { kind: 'templates', route: templates };
  const properties = propertiesRoute(loc);
  if (properties) return { kind: 'page-properties', item: properties };
  let m = pathname.match(/^\/cloudservices\/translation\.html(\/conf(?:\/[^/]+)*)?\/?$/);
  if (m) return { kind: 'translation-configs', path: decodeURI(m[1] ?? '/conf') };
  if (isRulesList(pathname)) return { kind: 'translation-rules' };
  if (pathname === VARIATIONS_URL) return { kind: 'variations' };
  if (isRuleEditor(pathname)) return { kind: 'translation-rule', context: new URLSearchParams(loc.search).get('context') || null };
  m = pathname.match(/^\/translation\/projects\.html(?:\/([^/]+))?$/);
  if (m) return { kind: 'translation-projects', name: m[1] ? decodeURIComponent(m[1]) : null };
  m = pathname.match(/^\/translation\/review\.html(\/content\/.+)?$/);
  if (m) return { kind: 'translation-review', path: m[1] ? decodeURI(m[1]) : null };
  const workflow = workflowRoute(pathname);
  if (workflow) return { kind: 'workflow', route: workflow };
  const modelEditor = cfModelEditorPath(pathname);
  if (modelEditor) return { kind: 'cf-model-editor', path: modelEditor };
  const models = cfModelsPath(pathname);
  if (models) return { kind: 'cf-models', conf: models };
  const fragment = cfFragment(pathname);
  if (fragment) return { kind: 'cf-editor', path: fragment };
  const folder = cfFolder(pathname);
  if (folder) return { kind: 'cf', path: folder };
  const page = editorPage(loc);
  if (page) return { kind: 'editor', path: page };
  const sites = sitesPath(pathname);
  if (sites) return { kind: 'sites', path: sites };
  if (/^\/replication\.html/.test(pathname)) return { kind: 'replication' };
  if (/^\/system\/mail\.html/.test(pathname)) return { kind: 'mail' };
  if (/^\/cfm\/graphql\.html/.test(pathname)) return { kind: 'graphql' };
  if (/^\/libs\/wcm\/core\/content\/sites\/components\.html\/?$/.test(pathname)) return { kind: 'components' };
  if (pathname === BLUEPRINTS_URL) return { kind: 'blueprints' };
  const component = componentOfUrl(pathname);
  if (component) return { kind: 'component', path: component };
  if (/^\/cfm\/graphiql\.html/.test(pathname)) {
    const q = new URLSearchParams(loc.search);
    return { kind: 'graphiql', endpoint: q.get('endpoint'), query: q.get('query') };
  }
  if (/^\/launches\.html/.test(pathname)) return { kind: 'launches' };
  if (/^\/etc\/linkchecker\.html/.test(pathname)) return { kind: 'linkchecker' };
  if (/^\/studio\/health\.html/.test(pathname)) return { kind: 'health' };
  if (/^\/mnt\/overlay\/granite\/async\/content\/asyncjobs\.html/.test(pathname)) return { kind: 'jobs' };
  if (/^\/studio\/activity\.html/.test(pathname)) return { kind: 'activity' };
  m = pathname.match(/^\/studio\/projects\.html(?:\/([^/]+))?\/?$/);
  if (m) return { kind: 'projects', name: m[1] ? decodeURIComponent(m[1]) : null };
  m = pathname.match(/^\/studio\/siteconfig\.html(\/.*)?$/);
  if (m) return { kind: 'siteconfig', path: decodeURI(m[1] ?? '').replace(/\/+$/, '') || '/content' };
  m = pathname.match(/^\/forms\/submissions\.html(\/content\/forms\/.+?)\/?$/);
  if (m) return { kind: 'form-submissions', form: decodeURI(m[1]) };
  m = pathname.match(/^\/audiences\.html(\/conf\/[^/]+)?/);
  if (m) return { kind: 'audiences', conf: m[1] ?? null };
  m = pathname.match(/^\/libs\/granite\/contexthub\/content\/admin\/contexthub\.html(\/conf\/[^/]+)?/);
  if (m) return { kind: 'contexthub', conf: m[1] ?? null };
  m = pathname.match(/^\/activities\.html(\/content\/[^/]+)?/);
  if (m) return { kind: 'activities', root: m[1] ?? null };
  const tags = tagsPath(pathname);
  if (tags) return { kind: 'tags', path: tags };
  const security = securityRoute(pathname);
  if (security) {
    const params = new URLSearchParams(loc.search);
    return { kind: 'security', route: security, id: params.get('id'), principal: params.get('principal') };
  }
  const assets = assetsRoute(loc);
  if (assets) return { kind: 'assets', route: assets };
  return null;
}

/** `matchLocation` for a URL string; an absolute URL of another origin is never a place. */
export function matchPlace(url: string): Place | null {
  const base = typeof window === 'undefined' ? 'http://studio.local' : window.location.origin;
  let a: URL;
  try {
    a = new URL(url, base);
  } catch {
    return null;
  }
  if (a.origin !== base) return null;
  return matchLocation({ pathname: a.pathname, search: a.search, hash: a.hash });
}

/** The one URL of a place — AEM's URL for that screen. */
export function urlOf(place: Place): string {
  switch (place.kind) {
    case 'start':
      return place.view ? `${START_URL}#${place.view}` : START_URL;
    case 'sites':
      return `/sites.html${encodeURI(place.path)}`;
    case 'editor':
      return `/editor.html${encodeURI(place.path)}.html`;
    case 'page-properties':
      return `/mnt/overlay/wcm/core/content/sites/properties.html?item=${encodeURIComponent(place.item)}`;
    case 'assets':
      return routeUrl(place.route);
    case 'cf':
      return `/cf.html${encodeURI(place.path)}`;
    case 'cf-editor':
      return `/editor.html${encodePath(place.path)}`;
    case 'cf-models':
      return `/cf.html${encodeURI(place.conf)}`;
    case 'cf-model-editor':
      return `/editor.html${encodePath(place.path)}`;
    case 'templates': {
      const r = place.route;
      if (r.screen === 'console') return `/libs/wcm/core/content/sites/templates.html${r.conf ? encodeURI(r.conf) : '/conf'}`;
      if (r.screen === 'wizard') return `/libs/wcm/core/content/sites/createtemplatewizard.html${encodeURI(r.conf)}`;
      return `/mnt/overlay/wcm/core/content/sites/templates/properties.html?item=${encodeURIComponent(r.template)}`;
    }
    case 'workflow':
      return place.route.kind === 'model' ? workflowUrl.editor(place.route.path) : workflowUrl[place.route.kind]();
    case 'replication':
      return '/replication.html';
    case 'launches':
      return '/launches.html';
    case 'linkchecker':
      return '/etc/linkchecker.html';
    case 'health':
      return '/studio/health.html';
    case 'jobs':
      return '/mnt/overlay/granite/async/content/asyncjobs.html';
    case 'activity':
      return '/studio/activity.html';
    case 'siteconfig':
      return `/studio/siteconfig.html${encodeURI(place.path)}`;
    case 'projects':
      return place.name ? `/studio/projects.html/${encodeURIComponent(place.name)}` : '/studio/projects.html';
    case 'form-submissions':
      return `/forms/submissions.html${encodeURI(place.form)}`;
    case 'mail':
      return '/system/mail.html';
    case 'graphql':
      return '/cfm/graphql.html';
    case 'components':
      return COMPONENTS_URL;
    case 'blueprints':
      return BLUEPRINTS_URL;
    case 'component':
      return componentUrl(place.path);
    case 'graphiql': {
      const q = new URLSearchParams();
      if (place.endpoint) q.set('endpoint', place.endpoint);
      if (place.query) q.set('query', place.query);
      return q.toString() ? `/cfm/graphiql.html?${q}` : '/cfm/graphiql.html';
    }
    case 'audiences':
      return `/audiences.html${place.conf ? encodeURI(place.conf) : ''}`;
    case 'contexthub':
      return `/libs/granite/contexthub/content/admin/contexthub.html${place.conf ? encodeURI(place.conf) : ''}`;
    case 'activities':
      return `/activities.html${place.root ? encodeURI(place.root) : ''}`;
    case 'tags':
      return `/tags.html${encodeURI(place.path)}`;
    case 'security': {
      const q = new URLSearchParams();
      if (place.id) q.set('id', place.id);
      if (place.principal) q.set('principal', place.principal);
      const s = q.toString();
      return `/security/${place.route}.html${s ? `?${s}` : ''}`;
    }
    case 'translation-configs':
      return `/cloudservices/translation.html${encodeURI(place.path)}`;
    case 'translation-rules':
      return RULES_URL;
    case 'variations':
      return VARIATIONS_URL;
    case 'translation-rule':
      return ruleEditorUrl(place.context);
    case 'translation-review':
      return `/translation/review.html${place.path ? encodeURI(place.path) : ''}`;
    case 'translation-projects':
      return `/translation/projects.html${place.name ? `/${encodeURIComponent(place.name)}` : ''}`;
  }
}

/** Parses a URL string (path, query and hash) rather than a Location. */
export function placeOfUrl(url: string): Place {
  const a = new URL(url, 'http://studio.local');
  return parsePlace({ pathname: a.pathname, search: a.search, hash: a.hash });
}

/** Two places are the same place (the same URL). */
export const samePlace = (a: Place, b: Place) => urlOf(a) === urlOf(b);

const last = (path: string) => path.slice(path.lastIndexOf('/') + 1) || path;

/** A short name for a tab or a history entry, before the screen reports a better one. */
export function titleOf(place: Place): string {
  const V = VOCAB;
  switch (place.kind) {
    case 'start':
      return V.home;
    case 'sites':
      if (place.path === '/content') return V.pages;
      if (place.path === '/content/experience-fragments') return V.blocks;
      if (place.path === '/content/forms') return V.forms;
      return `${place.path.startsWith('/content/experience-fragments/') ? V.blocks : place.path.startsWith('/content/forms/') ? V.forms : V.pages} · ${last(place.path)}`;
    case 'editor':
      return last(place.path);
    case 'page-properties':
      return `Properties · ${last(place.item)}`;
    case 'assets': {
      const r = place.route;
      if (r.kind === 'browse') return r.path === '/content/dam' ? V.media : `${V.media} · ${last(r.path)}`;
      if (r.kind === 'details' || r.kind === 'edit' || r.kind === 'collection') return last(r.path);
      return { profiles: 'Processing Profiles', schemas: 'Metadata Schemas', folderschemas: 'Folder Metadata Schemas', metadataprofiles: 'Metadata Profiles', smarttags: V.autoTags, imagepresets: 'Image Presets', imageprofiles: 'Image Profiles', reports: `${V.media} Reports`, sharedlinks: 'Shared Links', searchforms: 'Search Forms', assetsconfigurations: r.kind === 'assetsconfigurations' && r.editor ? `Assets Configurations · ${ {uploadrestrictions:'Upload Restrictions',digitalrightsmanagement:'DRM',watermark:'Watermark',assetsomnisearch:'Omnisearch',assetduplicationdetector:'Duplicates',linkshare:'Link Share'}[r.editor] }` : 'Assets Configurations', report: `${V.media} Report`, collections: 'Collections' }[r.kind];
    }
    case 'cf':
      return place.path === '/content/dam' ? V.records : `${V.records} · ${last(place.path)}`;
    case 'cf-editor':
      return last(place.path);
    case 'cf-models':
      return V.models;
    case 'cf-model-editor':
      return `${V.model} · ${last(place.path)}`;
    case 'templates':
      return place.route.screen === 'console' ? V.templates : place.route.screen === 'wizard' ? `Create ${V.template}` : `${V.template} · ${last(place.route.template)}`;
    case 'workflow':
      return place.route.kind === 'model' ? `${V.flow} · ${last(place.route.path)}` : { models: V.flows, instances: `${V.flow} Instances`, launchers: V.triggers, autoassign: 'Auto-Assign', inbox: V.inbox }[place.route.kind];
    case 'replication':
      return V.publishing;
    case 'launches':
      return V.releases;
    case 'linkchecker':
      return V.linkChecker;
    case 'health':
      return V.health;
    case 'jobs':
      return 'Jobs';
    case 'activity':
      return 'Activity';
    case 'siteconfig':
      return 'Site configurations';
    case 'projects':
      return 'Projects';
    case 'form-submissions':
      return `Submissions · ${last(place.form)}`;
    case 'mail':
      return 'Mail';
    case 'graphql':
      return 'GraphQL';
    case 'components':
      return 'Components';
    case 'blueprints':
      return 'Site sources';
    case 'component':
      return `Component · ${last(place.path)}`;
    case 'graphiql':
      return place.query ? `GraphQL · ${place.query}` : 'GraphQL Query Editor';
    case 'variations':
      return 'Generate Variations';
    case 'audiences':
      return V.audiences;
    case 'contexthub':
      return 'ContextHub';
    case 'activities':
      return V.reports;
    case 'tags':
      return place.path === '/content/cq:tags' ? V.tags : `${V.tags} · ${last(place.path)}`;
    case 'security':
      return { users: 'Users', groups: 'Groups', permissions: 'Permissions', truststore: 'Trust Store', oauth: 'OAuth', ssl: 'SSL Configuration', user: place.id ? `User · ${place.id}` : 'User', group: place.id ? `Group · ${place.id}` : 'Group' }[place.route];
    case 'translation-configs':
      return `${V.translations} · ${V.providers}`;
    case 'translation-rules':
      return `${V.translations} · Configuration`;
    case 'translation-rule':
      return place.context ? `Translation · ${place.context}` : 'Translation · New context';
    case 'translation-review':
      return place.path ? `Review · ${last(place.path)}` : `${V.translations} · Review`;
    case 'translation-projects':
      return place.name ? `${V.translationJob} · ${place.name}` : `${V.translations} · Jobs`;
  }
}
