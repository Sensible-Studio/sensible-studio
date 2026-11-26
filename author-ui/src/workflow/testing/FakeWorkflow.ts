import type { StudioClient } from '../../editor/api/StudioClient';
import {
  type AutoAssignRule,
  type TemplateChoice,
  STEP_TYPES,
  type InboxEntry,
  type InstanceSummary,
  type LauncherInfo,
  type LauncherInput,
  type MaintenanceInfo,
  type ModelDesign,
  type ModelSummary,
  type Processes,
  type Step,
  type WorkflowClient,
} from '../api';
import { modelName } from '../ModelsConsole';

/** Test doubles of the workflow server and of Sensible Studio, recording the calls of the UI. */
export const CUSTOM = '/conf/global/settings/workflow/models';

export function step(path: string, resourceType: string, title: string, metaData: Step['metaData'] = {}, branches?: Step[][]): Step {
  return {
    path,
    name: path.slice(path.lastIndexOf('/') + 1),
    resourceType,
    title,
    metaData,
    ...(branches
      ? { split: resourceType === STEP_TYPES.and ? ('and' as const) : ('or' as const), branches: branches.map((steps, i) => ({ path: `${path}/${i + 1}`, name: String(i + 1), steps })) }
      : {}),
  };
}

/** A workflow server in memory that records the calls of the UI. */
export class FakeWorkflow implements WorkflowClient {
  calls: string[] = [];
  list: ModelSummary[] = [
    { id: '/var/workflow/models/review', name: 'review', designPath: `${CUSTOM}/review`, title: 'Review', description: 'Ask an editor', type: 'custom', editable: true, version: '1.2', synced: false },
    { id: '/var/workflow/models/publish', name: 'publish', designPath: '/libs/settings/workflow/models/publish', title: 'Publish', type: 'default', editable: false, version: '1.0', synced: true },
    { id: '/var/workflow/models/draft', name: 'draft', designPath: `${CUSTOM}/draft`, title: 'Draft', type: 'custom', editable: true, version: null, synced: false },
  ];
  flow = `${CUSTOM}/review/jcr:content/flow`;
  design: ModelDesign = {
    ...this.list[0],
    designPath: `${CUSTOM}/review`,
    flowPath: this.flow,
    steps: [
      step(`${this.flow}/participant`, STEP_TYPES.participant, 'Approve', { PARTICIPANT: 'editors' }),
      step(`${this.flow}/or`, STEP_TYPES.or, 'Decide', {}, [
        [step(`${this.flow}/or/1/process`, STEP_TYPES.process, 'Mark approved', { PROCESS: 'com.example.Mark' })],
        [step(`${this.flow}/or/2/goto`, STEP_TYPES.goto, 'Again', { TARGET_NODE_PATH: `${this.flow}/participant` })],
      ]),
    ],
  };

  async models() {
    return this.list;
  }
  async model(path: string) {
    this.calls.push(`model ${path}`);
    return this.design;
  }
  async processes(): Promise<Processes> {
    return { processes: { 'Mark Approved': 'com.example.Mark', 'No Operation': 'com.example.Noop' }, choosers: {} };
  }
  async createModel(title: string, name?: string) {
    this.calls.push(`create ${title} ${name ?? ''}`.trim());
    const n = name ?? modelName(title);
    const m: ModelSummary = { id: `/var/workflow/models/${n}`, name: n, designPath: `${CUSTOM}/${n}`, title, type: 'custom', editable: true, version: '1.0', synced: true };
    this.list.push(m);
    return { ...m, designPath: m.designPath!, flowPath: `${m.designPath}/jcr:content/flow`, steps: [] };
  }
  async deleteModel(path: string) {
    this.calls.push(`deleteModel ${path}`);
  }
  async sync(path: string) {
    this.calls.push(`sync ${path}`);
    return { id: '/var/workflow/models/review', version: '1.3' };
  }
  async addStep(container: string, resourceType: string, title?: string, before?: string) {
    this.calls.push(`addStep ${container} ${resourceType} ${title ?? ''} ${before ?? ''}`.trim());
    return { path: `${container}/new` };
  }
  async moveStep(path: string, container: string, before?: string) {
    this.calls.push(`moveStep ${path} ${container} ${before ?? ''}`.trim());
  }
  async deleteStep(path: string) {
    this.calls.push(`deleteStep ${path}`);
  }
  async branches(path: string) {
    this.calls.push(`branches ${path}`);
  }
  async touch(path: string) {
    this.calls.push(`touch ${path}`);
  }
  async post(path: string, entries: Array<[string, string]>) {
    this.calls.push(`post ${path} ${entries.map(([k, v]) => `${k}=${v}`).join('&')}`);
  }
  async start(model: string, payload: string) {
    this.calls.push(`start ${model} ${payload}`);
    return { id: '/var/workflow/instances/server0/x/review_1' };
  }
  async instances(status?: string, failed?: boolean) {
    this.calls.push(`instances ${status ?? ''}${failed ? ' failed' : ''}`.trim());
    const wanted = status ? status.split(',') : null;
    return this.instanceList.filter((i) => (!wanted || wanted.includes(i.status)) && (!failed || i.failed));
  }
  async inbox() {
    return this.inboxList;
  }
  async complete(item: string, route?: string, comment?: string) {
    this.calls.push(`complete ${item} ${route ?? ''} ${comment ?? ''}`.trim());
  }
  instanceList: InstanceSummary[] = [];
  inboxList: InboxEntry[] = [];
  launcherList: LauncherInfo[] = [];

  async copyModel(path: string) {
    this.calls.push(`copyModel ${path}`);
    return { ...this.design, type: 'custom' as const, editable: true, designPath: `${CUSTOM}/${path.slice(path.lastIndexOf('/') + 1)}` };
  }
  async instance(id: string) {
    const i = this.instanceList.find((x) => x.id === id);
    if (!i) throw new Error(`No flow instance ${id}`);
    return i;
  }
  async delegate(item: string, assignee: string) {
    this.calls.push(`delegate ${item} ${assignee}`);
  }
  async terminate(id: string) {
    this.calls.push(`terminate ${id}`);
  }
  async suspend(id: string) {
    this.calls.push(`suspend ${id}`);
  }
  async resume(id: string) {
    this.calls.push(`resume ${id}`);
  }
  async retry(item: string) {
    this.calls.push(`retry ${item}`);
  }
  async deleteInstance(id: string) {
    this.calls.push(`deleteInstance ${id}`);
  }
  async launchers() {
    return this.launcherList;
  }
  async saveLauncher(input: LauncherInput, path?: string) {
    this.calls.push(`saveLauncher ${path ?? 'new'} ${JSON.stringify(input)}`);
    const l: LauncherInfo = { ...input, path: path ?? `/conf/global/settings/workflow/launcher/config/${input.name || 'l'}`, name: 'l', type: 'custom', valid: true };
    return l;
  }
  async enableLauncher(path: string, enabled: boolean) {
    this.calls.push(`enableLauncher ${path} ${enabled}`);
  }
  async deleteLauncher(path: string) {
    this.calls.push(`deleteLauncher ${path}`);
  }
  autoAssignRules: AutoAssignRule[] = [];
  templateChoices: TemplateChoice[] = [];
  async autoAssign() {
    return { rules: [...this.autoAssignRules], templates: this.templateChoices };
  }
  async saveAutoAssign(rule: { template: string; workflow: string; rootPath: string }, path?: string) {
    this.calls.push(`saveAutoAssign ${path ?? 'new'} ${rule.template} ${rule.workflow} ${rule.rootPath}`);
    const saved: AutoAssignRule = { ...rule, path: path ?? `/conf/global/settings/workflow/autoassign/r${this.autoAssignRules.length + 1}` };
    this.autoAssignRules = [...this.autoAssignRules.filter((r) => r.path !== saved.path), saved];
    return saved;
  }
  async deleteAutoAssign(path: string) {
    this.calls.push(`deleteAutoAssign ${path}`);
    this.autoAssignRules = this.autoAssignRules.filter((r) => r.path !== path);
  }
  maintenanceInfo: MaintenanceInfo = { purgeDays: 30, lastRun: null, lastRemoved: 0, ended: 4, oldestEnded: '2026-09-01T10:00:00Z', admin: true };
  async maintenance() {
    return this.maintenanceInfo;
  }
  async setPurge(days: number) {
    this.calls.push(`setPurge ${days}`);
    this.maintenanceInfo = { ...this.maintenanceInfo, purgeDays: days };
  }
  async purgeNow(days: number) {
    this.calls.push(`purgeNow ${days}`);
    return 3;
  }
}

export const studio = {
  currentUser: async () => 'admin',
  listChildren: async () => [],
  assetUrl: (p: string) => p,
  assetThumbnail: (p: string) => p,
  assetInfo: async () => null,
  pageImage: async () => null,
  getDialog: async () => ({ resourceType: '', dialogName: 'cq:dialog', chain: [], warnings: [], root: null }),
  getContent: async () => ({}),
} as unknown as StudioClient;
