/**
 * The product's words — the only place a module is named. Every label, tile, crumb root, tab title and
 * empty-state text reads from here, so a word changes in one line (see `claude/vocabulary.md`).
 *
 * The data format underneath still speaks AEM's identifiers (`cq:`, `dam:`, `/content/dam`,
 * `/content/experience-fragments`) until the native format and the importer land; those never show.
 */
export const VOCAB = {
  product: 'Sensible Studio',
  home: 'Home',
  system: 'System',

  pages: 'Pages',
  page: 'Page',
  /** AEM's own names since 26.11.2026 (they were Blocks / Records / Record models) */
  blocks: 'Experience Fragments',
  block: 'Experience Fragment',
  records: 'Content Fragments',
  record: 'Content Fragment',
  models: 'Content Fragment Models',
  model: 'Content Fragment Model',
  media: 'Media',
  /** one media item, in prose */
  file: 'File',
  forms: 'Forms',
  templates: 'Templates',
  template: 'Template',
  tags: 'Tags',

  /** what AEM called a policy / design dialog */
  configuration: 'Component configuration',
  styles: 'Styles',

  flows: 'Flows',
  flow: 'Flow',
  triggers: 'Triggers',
  inbox: 'Inbox',

  sync: 'Sync',
  linkedCopy: 'Linked copy',
  linkedCopies: 'Linked copies',
  source: 'Source',
  syncChanges: 'Sync changes',
  pushUpdate: 'Push update',
  syncRules: 'Sync rules',

  publishing: 'Publishing',
  publish: 'Publish',
  unpublish: 'Unpublish',
  live: 'Live',

  releases: 'Releases',
  release: 'Release',
  goLive: 'Go live',

  translations: 'Translations',
  languageVersion: 'Language version',
  translationJob: 'Translation job',
  providers: 'Providers',

  audiences: 'Audiences',
  audience: 'Audience',
  experiences: 'Experiences',
  experience: 'Experience',
  personalize: 'Personalize',
  visitorSimulator: 'Visitor simulator',
  reports: 'Reports',

  imageDelivery: 'Image delivery',
  focusCrop: 'Focus crop',
  autoTags: 'Auto tags',
  history: 'History',
  linkChecker: 'Link checker',
  health: 'Health',
} as const;

/** The console a content root belongs to, for crumbs and tab titles. */
export const ROOT_TITLES: Record<string, string> = {
  '/content': VOCAB.pages,
  '/content/experience-fragments': VOCAB.blocks,
  '/content/forms': VOCAB.forms,
  '/content/dam': VOCAB.media,
  '/conf': VOCAB.templates,
  '/content/cq:tags': VOCAB.tags,
};
