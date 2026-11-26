import { createDefaultRegistry, type GraniteRegistry } from '../granite';
import type { SelectOption } from '../granite/registry';
import { security } from '../security/api';
import { designOf, flatten, type Step, type WorkflowClient } from './api';

const DS = 'studio/workflow/datasources';

/**
 * The Granite registry of the model editor: every Sensible Studio widget plus the datasources the step dialogs of
 * studio-workflow-content use (registered processes and participant choosers, users and groups, the model's steps).
 */
export function workflowRegistry(client: WorkflowClient): GraniteRegistry {
  const r = createDefaultRegistry();
  let processes: ReturnType<WorkflowClient['processes']> | null = null;
  const loadProcesses = () => (processes ??= client.processes());
  const options = (byLabel: Record<string, string>): SelectOption[] =>
    Object.entries(byLabel).map(([label, className]) => ({ value: className, text: label }));

  r.registerDataSource(`${DS}/processes`, async () => options((await loadProcesses()).processes));
  r.registerDataSource(`${DS}/choosers`, async () => options((await loadProcesses()).choosers));
  r.registerDataSource(`${DS}/principals`, async () => {
    const [groups, users] = await Promise.all([security.groups(''), security.users('', false)]);
    return [
      ...groups.map((g) => ({ value: g.id, text: `${g.name} (group)` })),
      ...users.filter((u) => !u.disabled).map((u) => ({ value: u.id, text: u.name === u.id ? u.id : `${u.name} (${u.id})` })),
    ];
  });
  r.registerDataSource(`${DS}/stages`, async (_node, env) => {
    if (!env.contentPath) return [];
    const model = await client.model(designOf(env.contentPath));
    return (model.stages ?? []).map((s) => ({ value: s, text: s }));
  });
  r.registerDataSource(`${DS}/steps`, async (_node, env) => {
    if (!env.contentPath) return [];
    const model = await client.model(designOf(env.contentPath));
    return flatten(model.steps)
      .filter((s: Step) => s.path !== env.contentPath)
      .map((s) => ({ value: s.path, text: s.title || s.name }));
  });
  // the OR split dialog shows as many Branch tabs as the split has branches
  const branches = (n: number) => ({ field: './branches', is: String(n) });
  r.registerBehavior('studio.workflow.split', {
    hide: [
      { target: 'branch3', when: [branches(2)] },
      { target: 'branch4', when: [branches(2), branches(3)] },
      { target: 'branch5', when: [branches(2), branches(3), branches(4)] },
    ],
  });
  return r;
}
