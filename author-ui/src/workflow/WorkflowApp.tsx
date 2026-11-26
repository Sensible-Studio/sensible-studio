import { useEffect, useMemo, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { HttpWorkflowClient, type WorkflowClient, type WorkflowRoute } from './api';
import { InboxConsole } from './InboxConsole';
import { InstancesConsole } from './InstancesConsole';
import { LaunchersConsole } from './LaunchersConsole';
import { AutoAssignConsole } from './AutoAssignConsole';
import { ModelEditor } from './ModelEditor';
import { ModelsConsole } from './ModelsConsole';

/**
 * The workflow screens: `/workflow/{models,instances,launchers,inbox}.html` and the model editor under
 * `/editor.html/.../settings/workflow/models/`.
 */
export function WorkflowApp({ studio, route, client }: { studio: StudioClient; route: WorkflowRoute; client?: WorkflowClient }) {
  const wf = useMemo(() => client ?? new HttpWorkflowClient(), [client]);
  const [user, setUser] = useState<string | null>(null);
  useEffect(() => {
    studio.currentUser().then(setUser, () => setUser(null));
  }, [studio]);
  switch (route.kind) {
    case 'models':
      return <ModelsConsole client={wf} studio={studio} user={user} />;
    case 'instances':
      return <InstancesConsole client={wf} user={user} />;
    case 'launchers':
      return <LaunchersConsole client={wf} user={user} />;
    case 'autoassign':
      return <AutoAssignConsole client={wf} user={user} />;
    case 'inbox':
      return <InboxConsole client={wf} user={user} />;
    default:
      return <ModelEditor client={wf} studio={studio} path={route.path} />;
  }
}
