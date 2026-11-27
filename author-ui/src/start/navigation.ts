import { VOCAB } from '../shell/vocabulary';

/**
 * What the Start screen offers. Navigation tiles open Sensible Studio consoles (or a list of pages for the group
 * tiles); Tools are the operational consoles that ship with Sling and Composum. Add entries here.
 */

export type IconName =
  | 'query'
  | 'variations'
  | 'searchforms'
  | 'assetsconfig'
  | 'components'
  | 'sites'
  | 'assets'
  | 'xf'
  | 'cf'
  | 'projects'
  | 'repository'
  | 'packages'
  | 'bundles'
  | 'config'
  | 'installer'
  | 'jobs'
  | 'health'
  | 'requests'
  | 'resolver'
  | 'status'
  | 'users'
  | 'groups'
  | 'permissions'
  | 'truststore'
  | 'ssl'
  | 'oauth'
  | 'tags'
  | 'workflow'
  | 'wfinstances'
  | 'wflaunchers'
  | 'inbox'
  | 'templates'
  | 'about'
  | 'graph'
  | 'tools'
  | 'forms'
  | 'live'
  | 'release'
  | 'link'
  | 'translate'
  | 'review'
  | 'jobsList'
  | 'providers'
  | 'mail'
  | 'audience'
  | 'segments'
  | 'persona'
  | 'properties'
  | 'processing'
  | 'crop'
  | 'presets'
  | 'autotags'
  | 'collection'
  | 'code'
  | 'confbrowser'
  | 'osgiconfig'
  | 'models'
  | 'usergroup';

/** Home's rows: what you author every day, and what gives it its shape. System is the third row, on its own. */
export type NavSection = 'content' | 'structure';
export const NAV_SECTIONS: Array<{ id: NavSection; title: string }> = [
  { id: 'content', title: 'Content' },
  { id: 'structure', title: 'Structure' },
];

export interface NavTile {
  id: string;
  title: string;
  icon: IconName;
  section: NavSection;
  /** A URL to open, a page list to show (group tile), or nothing yet. */
  target: { kind: 'href'; url: string } | { kind: 'pages'; root: string; depth: number; xf?: boolean } | { kind: 'tools' } | { kind: 'soon' };
}

export const TEMPLATES_URL = '/libs/wcm/core/content/sites/templates.html/conf';
export const TAGS_URL = '/tags.html/content/cq:tags';

export const NAVIGATION: NavTile[] = [
  { id: 'sites', title: VOCAB.pages, icon: 'sites', section: 'content', target: { kind: 'href', url: '/sites.html/content' } },
  // the Pages console rooted at /content/experience-fragments: the same Column / Card / List views and breadcrumb
  { id: 'xf', title: VOCAB.blocks, icon: 'xf', section: 'content', target: { kind: 'href', url: '/sites.html/content/experience-fragments' } },
  { id: 'cf', title: VOCAB.records, icon: 'cf', section: 'content', target: { kind: 'href', url: '/cf.html/content/dam' } },
  { id: 'assets', title: VOCAB.media, icon: 'assets', section: 'content', target: { kind: 'href', url: '/assets.html/content/dam' } },
  { id: 'variations', title: 'Generate Variations', icon: 'variations', section: 'content', target: { kind: 'href', url: '/aem/generate-variations.html' } },
  { id: 'forms', title: VOCAB.forms, icon: 'forms', section: 'content', target: { kind: 'href', url: '/sites.html/content/forms' } },
  // work, not content: a team, a date, tasks, and the content they are about
  { id: 'projects', title: 'Projects', icon: 'projects', section: 'content', target: { kind: 'href', url: '/studio/projects.html' } },
  // structure: the shapes the content is poured into, set up rarely and by fewer people
  { id: 'templates', title: VOCAB.templates, icon: 'templates', section: 'structure', target: { kind: 'href', url: TEMPLATES_URL } },
  { id: 'models', title: VOCAB.models, icon: 'models', section: 'structure', target: { kind: 'href', url: '/cf.html/conf' } },
  { id: 'tags', title: VOCAB.tags, icon: 'tags', section: 'structure', target: { kind: 'href', url: TAGS_URL } },
];

export interface Tool {
  title: string;
  description: string;
  icon: IconName;
  /** A small second icon on the corner (see NavIcon). */
  badge?: IconName;
  url: string;
}

export interface ToolCategory {
  id: string;
  title: string;
  /** The group's own icon on Home; `badge: 'config'` when the group is the settings of something. */
  icon: IconName;
  badge?: IconName;
  tools: Tool[];
}

/**
 * System: how content moves and who may do what, then the developer's consoles. Structure (templates,
 * models, tags) lives on Home.
 */
export const TOOLS: ToolCategory[] = [
  {
    id: 'flows',
    title: VOCAB.flows,
    icon: 'workflow',
    tools: [
      { title: VOCAB.flows, description: 'Create and edit flows, start them', icon: 'workflow', url: '/workflow/models.html' },
      { title: 'Instances', description: 'Running, failed and ended flows', icon: 'wfinstances', url: '/workflow/instances.html' },
      { title: VOCAB.triggers, description: 'Start flows when content is created, modified or removed', icon: 'wflaunchers', url: '/workflow/launchers.html' },
      { title: 'Auto-Assign', description: 'Start a flow on every page created from a template, below a path', icon: 'wflaunchers', url: '/workflow/autoassign.html' },
      { title: VOCAB.inbox, description: 'The flow steps waiting for you', icon: 'inbox', url: '/workflow/inbox.html' },
    ],
  },
  {
    id: 'publishing',
    title: VOCAB.publishing,
    icon: 'live',
    tools: [
      { title: VOCAB.publishing, description: 'Publishing targets of this author: the live site, queues, tests', icon: 'live', url: '/replication.html' },
      { title: VOCAB.releases, description: 'Future versions of pages, edited apart from the live site and put live when ready', icon: 'release', url: '/launches.html' },
      { title: 'Site sources', description: 'Sources: the sites Create Site makes new sites from, and the sites made from each', icon: 'sites', url: '/libs/wcm/msm/gui/content/blueprintconfig.html' },
      { title: VOCAB.linkChecker, description: 'The links in pages, experience fragments, content fragments and media, and the ones that no longer lead anywhere', icon: 'link', url: '/etc/linkchecker.html' },
    ],
  },
  {
    id: 'translations',
    title: VOCAB.translations,
    icon: 'translate',
    tools: [
      {
        title: 'Review',
        description: 'Review, correct and approve what was translated; approved texts feed the translation memory',
        icon: 'review',
        url: '/translation/review.html',
      },
      {
        title: 'Jobs',
        description: 'Translate pages, content fragments and media into several languages, one job per language, from draft to complete',
        icon: 'jobsList',
        url: '/translation/projects.html',
      },
      {
        title: VOCAB.providers,
        description: 'Translation connectors: Claude, OpenAI-compatible and local models',
        icon: 'providers',
        url: '/cloudservices/translation.html/conf',
      },
      {
        title: 'Configuration',
        description: 'Which properties of pages and components are translated, per site, and whether assets are',
        icon: 'config',
        url: '/libs/cq/translation/translationrules/contexts.html',
      },
    ],
  },
  {
    id: 'forms',
    title: VOCAB.forms,
    icon: 'forms',
    badge: 'config',
    tools: [
      { title: VOCAB.forms, description: 'The forms of every site, their submissions', icon: 'forms', url: '/sites.html/content/forms' },
      { title: 'Mail', description: 'The mail server that sends submissions by email', icon: 'mail', url: '/system/mail.html' },
    ],
  },
  {
    id: 'audiences',
    title: VOCAB.audiences,
    icon: 'audience',
    tools: [
      { title: VOCAB.audiences, description: 'Who sees which experience of a personalized component', icon: 'segments', url: '/audiences.html' },
      { title: VOCAB.reports, description: 'Personalized components, their goals and how each experience performs', icon: 'graph', url: '/activities.html' },
      { title: 'Personas', description: `The visitors the ${VOCAB.visitorSimulator.toLowerCase()} can play in Preview`, icon: 'persona', url: '/bin/browser.html/libs/settings/contexthub/personas' },
      { title: 'ContextHub', description: 'What pages know about the visitor: the stores, values of your own or from a JSON address, and the fields of the bar in Preview', icon: 'config', url: '/libs/granite/contexthub/content/admin/contexthub.html' },
    ],
  },
  {
    id: 'media',
    title: VOCAB.media,
    icon: 'assets',
    badge: 'config',
    tools: [
      { title: 'Metadata Schemas', description: 'The Properties form of media, per folder', icon: 'properties', url: '/mnt/overlay/dam/gui/content/metadataschemaeditor/schemalist.html/conf/global/settings/dam/adminui-extension/metadataschema' },
      { title: 'Metadata Profiles', description: 'Default values the files uploaded to a folder get: copyright, creator, keywords', icon: 'presets', url: '/mnt/overlay/dam/gui/content/processingprofilepage/metadataprofiles.html/conf/global/settings/dam/adminui-extension/metadataprofile' },
      { title: 'Folder Metadata Schemas', description: 'Fields of your own in the properties of a folder', icon: 'properties', url: '/mnt/overlay/dam/gui/content/foldermetadataschemaeditor/schemalist.html/conf/global/settings/dam/adminui-extension/foldermetadataschema' },
      { title: 'Processing Profiles', description: 'Renditions of your own, applied per folder', icon: 'processing', url: '/mnt/overlay/dam/gui/content/processingprofilepage.html/conf/global/settings/dam/processing' },
      { title: 'Image Profiles', description: `${VOCAB.focusCrop}: the aspect ratios a folder's images get crops for`, icon: 'crop', url: '/mnt/overlay/dam/gui/content/imageprofiles.html/conf/global/settings/dam/dm/imageprofiles' },
      { title: 'Image Presets', description: `${VOCAB.imageDelivery}: named URL parameters, $banner$`, icon: 'presets', url: '/mnt/overlay/dam/gui/content/s7dam/presets/imagepresets.html/conf/global/settings/dam/dm/presets' },
      { title: VOCAB.autoTags, description: 'The image model and which tags it may choose from', icon: 'autotags', url: '/mnt/overlay/dam/gui/content/smarttags.html/conf/global/settings/dam/smarttags' },
      { title: 'Search Forms', description: 'The filters of the Media search and the Pages Filter rail: order, names, filters on properties', icon: 'searchforms', url: '/libs/cq/core/content/tools/customsearch.html/conf/global/settings/cq/search/facets' },
      { title: 'Media configurations', description: 'Upload restrictions, usage rights, the watermark, search, the duplicate check, Link Share', icon: 'assetsconfig', url: '/mnt/overlay/dam/gui/content/assetsconfigurations/list.html' },
      { title: 'Shared Links', description: 'The links you shared: what each lets download, until when; Unshare', icon: 'link', url: '/mnt/overlay/dam/gui/content/sharedlinks.html/var/studio/dam/linkshare' },
      { title: 'Reports', description: 'Upload, modification, expiration, disk usage and link share reports', icon: 'graph', url: '/mnt/overlay/dam/gui/content/reports/reportspage.html/var/studio/dam/reports' },
      { title: 'Collections', description: 'Media grouped for a purpose, and smart collections from a search', icon: 'collection', url: '/mnt/overlay/dam/gui/content/collections.html/content/dam/collections' },
    ],
  },
  {
    id: 'security',
    title: 'Users',
    icon: 'usergroup',
    tools: [
      { title: 'Users', description: 'Create and manage users, their profiles and groups', icon: 'users', url: '/security/users.html' },
      { title: 'Groups', description: 'Create and manage groups and their members', icon: 'groups', url: '/security/groups.html' },
      { title: 'Permissions', description: 'Access control entries of users and groups', icon: 'permissions', url: '/security/permissions.html' },
      { title: 'Trust Store', description: "Certificates trusted for this instance's own HTTPS connections", icon: 'truststore', url: '/security/truststore.html' },
      { title: 'OAuth', description: 'Applications that act as a user with a token, and the tokens they hold', icon: 'oauth', url: '/security/oauth.html' },
      { title: 'SSL Configuration', description: "HTTPS for this instance from its own web server: a certificate and a port", icon: 'ssl', url: '/security/ssl.html' },
      { title: 'Activity', description: 'Who created, changed, published and removed what, and when', icon: 'requests', url: '/studio/activity.html' },
    ],
  },
  {
    id: 'developer',
    title: 'Developer',
    icon: 'code',
    tools: [
      { title: VOCAB.health, description: 'How the instance is, and the nightly maintenance: repository compaction, version purge, what it recovered', icon: 'health', url: '/studio/health.html' },
      { title: 'Jobs', description: 'The work done in the background (media processing, auto tags, publishing, syncs, flows) and how each queue runs it', icon: 'jobs', url: '/mnt/overlay/granite/async/content/asyncjobs.html' },
      { title: 'Components', description: 'Every component, its group, dialog and policies, and the pages that use it', icon: 'components', url: '/libs/wcm/core/content/sites/components.html' },
      { title: 'Repository Browser', description: 'Browse and edit nodes and properties (Composum)', icon: 'repository', url: '/bin/browser.html' },
      { title: 'Site configurations', description: 'The settings a site\'s code reads (context-aware configurations): per site, inherited, with defaults', icon: 'config', url: '/studio/siteconfig.html' },
      { title: 'GraphQL', description: 'The endpoints applications query content fragments at, and their persisted queries', icon: 'code', url: '/cfm/graphql.html' },
      { title: 'GraphQL Query Editor', description: 'Write a query, run it on an endpoint, save it as a persisted query', icon: 'query', url: '/cfm/graphiql.html' },
      { title: 'Configuration Browser', description: 'Site configurations, templates and component configurations under /conf', icon: 'confbrowser', url: '/bin/browser.html/conf' },
      { title: 'Packages', description: 'Upload, build and install content packages', icon: 'packages', url: '/bin/packages.html' },
    ],
  },
  {
    id: 'console',
    title: 'Web Console',
    icon: 'bundles',
    tools: [
      { title: 'Bundles', description: 'OSGi bundles and their state', icon: 'bundles', url: '/system/console/bundles' },
      { title: 'Configuration', description: 'OSGi configurations', icon: 'osgiconfig', url: '/system/console/configMgr' },
      { title: 'OSGi Installer', description: 'What the installer picked up from instances/author/install', icon: 'installer', url: '/system/console/osgi-installer' },
      { title: 'Sling Jobs', description: "Sling's own view of every job and queue", icon: 'jobs', url: '/system/console/slingevent' },
      { title: 'Recent Requests', description: 'Request progress of the latest requests', icon: 'requests', url: '/system/console/requests' },
      { title: 'Resource Resolver', description: 'Mappings and test resolution of URLs', icon: 'resolver', url: '/system/console/jcrresolver' },
      { title: 'System Status', description: 'Sling settings and runtime information', icon: 'status', url: '/system/console/status-slingsettings' },
      { title: 'Sling Starter', description: 'The Apache Sling Starter welcome page', icon: 'about', url: '/starter.html' },
    ],
  },
];
