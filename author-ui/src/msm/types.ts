/** MSM data from `/bin/studio/msm.json` (see `MsmServlet`). */

/** `cq:trigger` values, as AEM stores them */
export type Trigger = 'rollout' | 'modification' | 'publish' | 'deactivate';

export interface RolloutConfigInfo {
  path: string;
  name: string;
  title: string;
  description?: string;
  trigger: Trigger;
  actions: string[];
}

export interface LiveCopyInfo {
  /** root page of the live copy */
  path: string;
  blueprintPath: string;
  deep: boolean;
  /** the status was asked for the root page itself */
  root: boolean;
  exclusions: string[];
  rolloutConfigs: RolloutConfigInfo[];
}

/** `synced`, `cancelled` (a component), `suspended` (a page, or below a cancelled container), … */
export type RelationshipState = 'synced' | 'cancelled' | 'suspended' | 'sourceMissing' | 'missing' | 'local';

export interface RelationshipInfo {
  sourcePath: string;
  targetPath: string;
  status: RelationshipState;
  /** rolled out from the blueprint (false: added in the live copy) */
  inherited: boolean;
  cancelled: boolean;
  cancelledForChildren: boolean;
  cancelledByAncestor: boolean;
  cancelledProperties: string[];
  sourceExisting: boolean;
  targetExisting: boolean;
  page: boolean;
  lastRolledOut?: string;
  lastRolledOutBy?: string;
}

export interface MsmStatus {
  path: string;
  inLiveCopy: boolean;
  liveCopy?: LiveCopyInfo;
  relationship?: RelationshipInfo;
  /** regular expressions of page properties rollouts do not update (not inherited) */
  pageExclusions?: string[];
  isSource: boolean;
  /** for a blueprint: its live copies */
  liveCopies?: Array<RelationshipInfo & { liveCopy: LiveCopyInfo }>;
  /** a page in a live copy: the settings of its Live Copy tab (page properties) */
  pageConfig?: LiveCopyPageConfig;
  /** a page: the rollout configurations it gives its live copies (Blueprint tab of the page properties) */
  blueprintConfig?: { rolloutConfigs: string[] };
}

export interface LiveCopyPageConfig {
  /** the page is the root of its live copy (deep and excluded paths are set there) */
  root: boolean;
  deep: boolean;
  /** the page names no rollout configurations of its own */
  inheritRolloutConfigs: boolean;
  /** its own rollout configurations */
  rolloutConfigs: string[];
  /** the ones that apply (own, inherited, the blueprint's, or the default) */
  effectiveRolloutConfigs: string[];
  excludedPaths: string[];
}

export interface LiveCopyConfigChange {
  /** null: inherit the rollout configurations */
  rolloutConfigs: string[] | null;
  /** root only */
  deep?: boolean;
  /** root only */
  excludedPaths?: string[];
}

export interface RolloutResult {
  updated: string[];
  created: string[];
  deleted: string[];
  skipped: string[];
  conflicts: string[];
  renamed: string[];
  /** live copy pages saved as a version before the rollout */
  versioned?: string[];
  unknownActions: string[];
  nodes: number;
}

export interface OverviewPage {
  sourcePath: string;
  targetPath: string;
  title: string;
  level: number;
  status: RelationshipState | 'excluded';
  lastRolledOut?: string;
}

export interface Overview {
  path: string;
  liveCopies: Array<LiveCopyInfo & { targetPath: string; pages: OverviewPage[] }>;
}

export interface CreateLiveCopy {
  source: string;
  parent: string;
  name: string;
  title?: string;
  deep: boolean;
  /** configuration paths; empty: inherit (the default configuration) */
  rolloutConfigs: string[];
  /** child pages, relative to the new root, left out */
  exclude?: string[];
}

export type InheritanceOp = 'cancel' | 'reenable' | 'suspend' | 'resume' | 'detach';

/** A saved version of a page (`op=revisions`). */
export interface PageRevision {
  id: string;
  /** JCR version name: 1.0, 1.1, … */
  name: string;
  label?: string;
  comment?: string;
  created?: string;
  createdBy?: string;
  title?: string;
}

/** A blueprint configuration (`/apps/msm/…`, `/libs/msm/…`): the root of a site whose children are its languages. */
export interface BlueprintInfo {
  path: string;
  title: string;
  description?: string;
  sitePath: string;
  /** the product's own (/libs): not changed from the console */
  readOnly?: boolean;
  /** the sites made from it: live copies of its source */
  sites?: string[];
}

export interface BlueprintStructure extends BlueprintInfo {
  languages: Array<{ path: string; name: string; title: string }>;
  /** child pages of the languages, by name */
  chapters: Array<{ path: string; name: string; title: string }>;
}

export interface CreateSite {
  /** the blueprint configuration; or `newBlueprint`, created together with the site */
  blueprint?: string;
  newBlueprint?: { title: string; sitePath: string };
  parent: string;
  title: string;
  name?: string;
  owner?: string;
  /** language page paths */
  languages: string[];
  /** chapter names */
  chapters: string[];
  /** live copies (true) or plain copies */
  liveCopy: boolean;
  rolloutConfigs: string[];
}
