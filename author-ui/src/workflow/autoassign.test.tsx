import { describe, expect, it } from 'vitest';
import { workflowRoute, workflowUrl } from './api';
import { autoAssignProblems } from './AutoAssignConsole';
import { FakeWorkflow } from './testing/FakeWorkflow';

const T = '/conf/wknd/settings/wcm/templates/article-page';
const M = '/var/workflow/models/request_for_activation';

describe('auto-assign', () => {
  it('is at /workflow/autoassign.html', () => {
    expect(workflowRoute(workflowUrl.autoassign())).toEqual({ kind: 'autoassign' });
  });

  it('checks a rule before sending it', () => {
    expect(autoAssignProblems({ template: T, workflow: M, rootPath: '/content/wknd/' }, [])).toEqual([]);
    expect(autoAssignProblems({ template: '', workflow: '', rootPath: '/etc' }, [])).toHaveLength(3);
    expect(autoAssignProblems({ template: T, workflow: M, rootPath: '/content/wknd' }, [{ path: '/x', template: T, workflow: M, rootPath: '/content/wknd' }])).toEqual([
      'That rule exists already.',
    ]);
  });

  it('adds, changes and removes rules', async () => {
    const wf = new FakeWorkflow();
    const r = await wf.saveAutoAssign({ template: T, workflow: M, rootPath: '/content/wknd' });
    await wf.saveAutoAssign({ template: T, workflow: M, rootPath: '/content/wknd/us' }, r.path);
    expect((await wf.autoAssign()).rules.map((x) => x.rootPath)).toEqual(['/content/wknd/us']);
    await wf.deleteAutoAssign(r.path);
    expect((await wf.autoAssign()).rules).toEqual([]);
  });
});
