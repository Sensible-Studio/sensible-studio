/** Client of `/bin/studio/workflow.json` (studio-workflow bundle): models, their design, and running workflows. */

export const STEP_TYPES = {
  process: 'cq/workflow/components/model/process',
  participant: 'cq/workflow/components/model/participant',
  dynamicParticipant: 'cq/workflow/components/model/dynamic_participant',
  or: 'cq/workflow/components/model/or',
  and: 'cq/workflow/components/model/and',
  goto: 'cq/workflow/components/model/goto',
} as const;

export interface ModelSummary {
  /** Runtime model path, e.g. `/var/workflow/models/request_for_review`: what a workflow is started with. */
  id: string;
  name: string;
  /** The design page (`/conf/global/settings/workflow/models/<name>` or `/libs/settings/...`); none for runtime-only models. */
  designPath?: string;
  title: string;
  description?: string;
  type: 'custom' | 'default' | 'runtime';
  editable: boolean;
  transient?: boolean;
  /** Version of the runtime model; null until the first sync. */
  version?: string | null;
  synced: boolean;
  /** Stages of the model (`cq:workflowStages`), in order. */
  stages?: string[];
  lastModified?: string;
  lastModifiedBy?: string;
}

export type MetaData = Record<string, string | string[] | boolean | number | undefined>;

export interface Branch {
  path: string;
  name: string;
  steps: Step[];
}

export interface Step {
  path: string;
  name: string;
  resourceType: string;
  title?: string;
  description?: string;
  metaData: MetaData;
  split?: 'or' | 'and';
  branches?: Branch[];
}

export interface ModelDesign extends ModelSummary {
  designPath: string;
  flowPath: string;
  steps: Step[];
}

export interface Processes {
  /** label -> class name */
  processes: Record<string, string>;
  choosers: Record<string, string>;
}

export interface LauncherInfo {
  path: string;
  name: string;
  type: 'custom' | 'default';
  /** 1 created, 16 modified, 2 removed (the JCR event types, as AEM stores them) */
  eventType: number;
  glob: string;
  nodetype: string;
  conditions: string[];
  excludeList: string[];
  /** Runtime model path. */
  workflow: string;
  description?: string | null;
  enabled: boolean;
  runModes?: string;
  /** false when the glob is not a valid regular expression */
  valid: boolean;
}

/** An Auto-Assign rule: a page created with `template` below `rootPath` starts `workflow` on itself. */
export interface AutoAssignRule {
  path: string;
  /** `/conf/wknd/settings/wcm/templates/article-page-template` */
  template: string;
  /** Runtime model path. */
  workflow: string;
  rootPath: string;
  modified?: string;
  modifiedBy?: string;
}

export interface TemplateChoice {
  path: string;
  title: string;
  conf: string;
}

export interface LauncherInput {
  glob: string;
  eventType: number;
  nodetype: string;
  conditions: string[];
  excludeList: string[];
  workflow: string;
  description: string;
  enabled: boolean;
  runModes: string;
  name?: string;
}

export const EVENT_TYPES: Array<{ value: number; label: string }> = [
  { value: 1, label: 'Created' },
  { value: 16, label: 'Modified' },
  { value: 2, label: 'Removed' },
];

export interface WorkItemInfo {
  id: string;
  name: string;
  nodeId: string;
  title?: string;
  type?: string;
  status: 'ACTIVE' | 'COMPLETED' | 'FAILED' | 'TERMINATED' | string;
  assignee?: string;
  startTime?: string;
  endTime?: string;
  completedBy?: string;
  comment?: string;
  failure?: string;
  delegatedFrom?: string;
  /** When the step times out (steps with a timeout). */
  dueTime?: string | null;
  /** The workflow stage of the step. */
  stage?: string | null;
}

export interface InstanceSummary {
  id: string;
  name: string;
  modelId: string;
  modelTitle?: string;
  modelVersion?: string;
  status: 'RUNNING' | 'COMPLETED' | 'ABORTED' | 'SUSPENDED' | string;
  initiator?: string;
  startTime?: string;
  endTime?: string;
  payload?: string;
  title?: string;
  comment?: string;
  current: WorkItemInfo[];
  /** Stages of the model the instance runs on, and the one it is in (or ended in). */
  stages?: string[];
  stage?: string | null;
  failed: boolean;
  history: WorkItemInfo[];
}

export interface RouteInfo {
  id: string;
  name: string;
  default: boolean;
}

export interface InboxEntry extends WorkItemInfo {
  workflow: InstanceSummary;
  routes: RouteInfo[];
}

export interface MaintenanceInfo {
  /** Ended workflows older than this many days are removed; 0 keeps them. */
  purgeDays: number;
  lastRun?: string | null;
  lastRemoved: number;
  /** Completed and terminated workflows there are now. */
  ended: number;
  oldestEnded?: string | null;
  /** Whether the signed-in user may change the settings. */
  admin: boolean;
}

export interface WorkflowClient {
  models(): Promise<ModelSummary[]>;
  model(designPath: string): Promise<ModelDesign>;
  processes(): Promise<Processes>;
  createModel(title: string, name?: string, description?: string): Promise<ModelDesign>;
  deleteModel(designPath: string): Promise<void>;
  /** Writes the runtime model from the design; returns the new version. */
  sync(designPath: string): Promise<{ id: string; version: string }>;
  addStep(container: string, resourceType: string, title?: string, before?: string): Promise<{ path: string }>;
  moveStep(path: string, container: string, before?: string): Promise<void>;
  deleteStep(path: string): Promise<void>;
  /** After a split's dialog: makes its branch containers match its `branches` property. */
  branches(splitPath: string): Promise<void>;
  /** Marks the model as changed since the last sync (after writing a step's or the model's properties). */
  touch(path: string): Promise<void>;
  /** Sling POST to a design resource (step dialogs, model properties). */
  post(path: string, entries: Array<[string, string]>): Promise<void>;
  start(model: string, payload: string, title?: string, comment?: string): Promise<{ id: string }>;
  /** Default models are copied to /conf before editing; returns the copy. */
  copyModel(defaultDesignPath: string): Promise<ModelDesign>;
  /** `status`: one or more of RUNNING, SUSPENDED, COMPLETED, ABORTED (comma separated); `failed`: only instances with a failed step. */
  instances(status?: string, failed?: boolean): Promise<InstanceSummary[]>;
  instance(id: string): Promise<InstanceSummary>;
  inbox(): Promise<InboxEntry[]>;
  complete(item: string, route?: string, comment?: string): Promise<void>;
  delegate(item: string, assignee: string): Promise<void>;
  terminate(id: string): Promise<void>;
  suspend(id: string): Promise<void>;
  resume(id: string): Promise<void>;
  retry(item: string): Promise<void>;
  deleteInstance(id: string): Promise<void>;
  launchers(): Promise<LauncherInfo[]>;
  /** Creates (no `path`) or updates a custom launcher. */
  saveLauncher(input: LauncherInput, path?: string): Promise<LauncherInfo>;
  enableLauncher(path: string, enabled: boolean): Promise<void>;
  deleteLauncher(path: string): Promise<void>;
  /** Auto-Assign: the rules and the templates to choose from. */
  autoAssign(): Promise<{ rules: AutoAssignRule[]; templates: TemplateChoice[] }>;
  /** Stores a rule (a new one without `path`). */
  saveAutoAssign(rule: { template: string; workflow: string; rootPath: string }, path?: string): Promise<AutoAssignRule>;
  deleteAutoAssign(path: string): Promise<void>;
  maintenance(): Promise<MaintenanceInfo>;
  setPurge(days: number): Promise<void>;
  /** Removes workflows that ended more than `days` days ago now; returns how many. */
  purgeNow(days: number): Promise<number>;
}

const URL_ = '/bin/studio/workflow.json';
const FORM = 'application/x-www-form-urlencoded;charset=UTF-8';

async function call<T>(method: 'GET' | 'POST', params: Array<[string, string | undefined]>, keepEmpty = false): Promise<T> {
  const qs = new URLSearchParams(params.filter((p): p is [string, string] => p[1] !== undefined && (keepEmpty || p[1] !== '')));
  const res =
    method === 'GET'
      ? await fetch(`${URL_}?${qs}`, { credentials: 'same-origin' })
      : await fetch(URL_, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': FORM }, body: qs });
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error((body as { error?: string }).error || `HTTP ${res.status}`);
  return body;
}

export class HttpWorkflowClient implements WorkflowClient {
  models = () => call<ModelSummary[]>('GET', [['op', 'models']]);
  model = (designPath: string) => call<ModelDesign>('GET', [['op', 'model'], ['path', designPath]]);
  processes = () => call<Processes>('GET', [['op', 'processes']]);
  createModel = (title: string, name?: string, description?: string) =>
    call<ModelDesign>('POST', [['op', 'createModel'], ['title', title], ['name', name], ['description', description]]);
  deleteModel = async (designPath: string) => {
    await call('POST', [['op', 'deleteModel'], ['path', designPath]]);
  };
  sync = (designPath: string) => call<{ id: string; version: string }>('POST', [['op', 'sync'], ['path', designPath]]);
  addStep = (container: string, resourceType: string, title?: string, before?: string) =>
    call<{ path: string }>('POST', [['op', 'addStep'], ['container', container], ['resourceType', resourceType], ['title', title], ['before', before]]);
  moveStep = async (path: string, container: string, before?: string) => {
    await call('POST', [['op', 'moveStep'], ['path', path], ['container', container], ['before', before]]);
  };
  deleteStep = async (path: string) => {
    await call('POST', [['op', 'deleteStep'], ['path', path]]);
  };
  branches = async (splitPath: string) => {
    await call('POST', [['op', 'branches'], ['path', splitPath]]);
  };
  touch = async (path: string) => {
    await call('POST', [['op', 'touch'], ['path', path]]);
  };
  post = async (path: string, entries: Array<[string, string]>) => {
    const body = new URLSearchParams(entries);
    body.set('_charset_', 'utf-8');
    const res = await fetch(path.split('/').map(encodeURIComponent).join('/'), {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': FORM, Accept: 'application/json' },
      body,
    });
    if (!res.ok) throw new Error(`Saving ${path} failed: HTTP ${res.status}`);
  };
  start = (model: string, payload: string, title?: string, comment?: string) =>
    call<{ id: string }>('POST', [['op', 'start'], ['model', model], ['payload', payload], ['title', title], ['comment', comment]]);
  copyModel = (defaultDesignPath: string) => call<ModelDesign>('POST', [['op', 'copyModel'], ['path', defaultDesignPath]]);
  instances = (status?: string, failed?: boolean) =>
    call<InstanceSummary[]>('GET', [['op', 'instances'], ['status', status], ['failed', failed ? 'true' : undefined]]);
  instance = (id: string) => call<InstanceSummary>('GET', [['op', 'instance'], ['id', id]]);
  inbox = () => call<InboxEntry[]>('GET', [['op', 'inbox']]);
  complete = async (item: string, route?: string, comment?: string) => {
    await call('POST', [['op', 'complete'], ['item', item], ['route', route], ['comment', comment]]);
  };
  delegate = async (item: string, assignee: string) => {
    await call('POST', [['op', 'delegate'], ['item', item], ['assignee', assignee]]);
  };
  terminate = async (id: string) => {
    await call('POST', [['op', 'terminate'], ['id', id]]);
  };
  suspend = async (id: string) => {
    await call('POST', [['op', 'suspend'], ['id', id]]);
  };
  resume = async (id: string) => {
    await call('POST', [['op', 'resume'], ['id', id]]);
  };
  retry = async (item: string) => {
    await call('POST', [['op', 'retry'], ['item', item]]);
  };
  deleteInstance = async (id: string) => {
    await call('POST', [['op', 'deleteInstance'], ['id', id]]);
  };
  launchers = () => call<LauncherInfo[]>('GET', [['op', 'launchers']]);
  saveLauncher = (input: LauncherInput, path?: string) =>
    call<LauncherInfo>(
      'POST',
      [
        ['op', 'saveLauncher'],
        ['path', path ?? ''],
        ['name', input.name ?? ''],
        ['glob', input.glob],
        ['eventType', String(input.eventType)],
        ['nodetype', input.nodetype],
        ...input.conditions.map((c): [string, string] => ['conditions', c]),
        ...input.excludeList.map((c): [string, string] => ['excludeList', c]),
        ['workflow', input.workflow],
        ['description', input.description],
        ['enabled', String(input.enabled)],
        ['runModes', input.runModes],
      ],
      true,
    );
  enableLauncher = async (path: string, enabled: boolean) => {
    await call('POST', [['op', 'enableLauncher'], ['path', path], ['enabled', String(enabled)]]);
  };
  deleteLauncher = async (path: string) => {
    await call('POST', [['op', 'deleteLauncher'], ['path', path]]);
  };
  autoAssign = () => call<{ rules: AutoAssignRule[]; templates: TemplateChoice[] }>('GET', [['op', 'autoAssign']]);
  saveAutoAssign = (rule: { template: string; workflow: string; rootPath: string }, path?: string) =>
    call<AutoAssignRule>('POST', [
      ['op', 'saveAutoAssign'],
      ['path', path ?? ''],
      ['template', rule.template],
      ['workflow', rule.workflow],
      ['rootPath', rule.rootPath],
    ]);
  deleteAutoAssign = async (path: string) => {
    await call('POST', [['op', 'deleteAutoAssign'], ['path', path]]);
  };
  maintenance = () => call<MaintenanceInfo>('GET', [['op', 'maintenance']]);
  setPurge = async (days: number) => {
    await call('POST', [['op', 'setPurge'], ['days', String(days)]], true);
  };
  purgeNow = async (days: number) => (await call<{ removed: number }>('POST', [['op', 'purgeNow'], ['days', String(days)]], true)).removed;
}

// ------------------------------------------------------------------------------------------------ routes

export type WorkflowConsole = 'models' | 'instances' | 'launchers' | 'autoassign' | 'inbox';
export type WorkflowRoute = { kind: WorkflowConsole } | { kind: 'model'; path: string };

const MODEL_EDITOR = /^\/editor\.html((?:\/conf\/[^/]+|\/libs)\/settings\/workflow\/models\/.+?)(?:\.html)?\/?$/;

/**
 * `/workflow/{models,instances,launchers,inbox}.html` (consoles) and
 * `/editor.html/conf/global/settings/workflow/models/<m>.html` (model editor).
 */
export function workflowRoute(pathname: string): WorkflowRoute | null {
  const c = pathname.match(/^\/workflow\/(models|instances|launchers|autoassign|inbox)\.html$/);
  if (c) return { kind: c[1] as WorkflowConsole };
  const m = pathname.match(MODEL_EDITOR);
  return m ? { kind: 'model', path: decodeURI(m[1]) } : null;
}

export const workflowUrl = {
  models: () => '/workflow/models.html',
  instances: () => '/workflow/instances.html',
  launchers: () => '/workflow/launchers.html',
  autoassign: () => '/workflow/autoassign.html',
  inbox: () => '/workflow/inbox.html',
  editor: (designPath: string) => `/editor.html${encodeURI(designPath)}.html`,
};

/** The design page a step (or branch, or flow) belongs to. */
export function designOf(path: string): string {
  const i = path.indexOf('/jcr:content');
  return i < 0 ? path : path.slice(0, i);
}

/** Steps of a model in flow order, branches included (the targets of a Goto step). */
export function flatten(steps: Step[]): Step[] {
  return steps.flatMap((s) => [s, ...(s.branches ?? []).flatMap((b) => flatten(b.steps))]);
}

export function stepKind(resourceType: string): keyof typeof STEP_TYPES | 'unknown' {
  const rt = resourceType.replace(/^\/(apps|libs)\//, '');
  const hit = (Object.keys(STEP_TYPES) as Array<keyof typeof STEP_TYPES>).find((k) => STEP_TYPES[k] === rt);
  return hit ?? 'unknown';
}

/** Where to open a payload: pages in the page editor, assets in their details, anything else in the repository browser. */
export function payloadUrl(payload: string): string {
  const page = payload.replace(/\/jcr:content(\/.*)?$/, '');
  if (page.startsWith('/content/dam/')) return `/assetdetails.html${encodeURI(page)}`;
  if (page.startsWith('/content/')) return `/editor.html${encodeURI(page)}.html`;
  return `/bin/browser.html${encodeURI(payload)}`;
}

export function formatTime(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** "Approval (2 of 3)", or null when the model has no stages. */
export function stageLabel(stage: string | null | undefined, stages: string[] | undefined): string | null {
  if (!stage) return null;
  const i = stages ? stages.indexOf(stage) : -1;
  return i < 0 || !stages ? stage : `${stage} (${i + 1} of ${stages.length})`;
}
