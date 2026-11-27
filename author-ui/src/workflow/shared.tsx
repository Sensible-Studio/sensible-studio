import { ProgressCircle } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { Link } from '../shell/ShellContext';
import { formatTime, payloadUrl, stageLabel, type InstanceSummary, type WorkflowClient } from './api';
import { StudioDialog } from '../shell/StudioDialog';

const STATUS_LABEL: Record<string, string> = {
  RUNNING: 'Running',
  SUSPENDED: 'Suspended',
  COMPLETED: 'Completed',
  ABORTED: 'Terminated',
  ACTIVE: 'Active',
  FAILED: 'Failed',
  TERMINATED: 'Terminated',
};

export function StatusBadge({ status, failed }: { status: string; failed?: boolean }) {
  const s = failed && status === 'RUNNING' ? 'FAILED' : status;
  return <span className={`studio-wf__status studio-wf__status--${s.toLowerCase()}`}>{STATUS_LABEL[s] ?? s}</span>;
}

export function PayloadLink({ payload }: { payload?: string | null }) {
  if (!payload) return null;
  return (
    <Link className="studio-wf__payload" to={payloadUrl(payload)} title={payload} onClick={(e) => e.stopPropagation()}>
      {payload}
    </Link>
  );
}

/** The title an instance is known by: the one given at start, else its model's. */
export function instanceTitle(i: InstanceSummary): string {
  return i.title || i.modelTitle || i.name;
}

/** The steps an instance went through, with who did what (AEM's "Open History"). */
export function HistoryDialog({ client, id, onClose }: { client: WorkflowClient; id: string; onClose(): void }) {
  const [instance, setInstance] = useState<InstanceSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    client.instance(id).then(setInstance, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, id]);
  return (
    <StudioDialog title={instance ? instanceTitle(instance) : 'Flow history'} size="L" onCancel={onClose} cancelLabel="Close">
        {error ? (
          <p className="studio-wf__failure">{error}</p>
        ) : !instance ? (
          <ProgressCircle isIndeterminate aria-label="Loading" />
        ) : (
          <>
            <dl className="studio-wf__facts">
              <dt>Status</dt>
              <dd>
                <StatusBadge status={instance.status} failed={instance.failed} />
              </dd>
              <dt>Model</dt>
              <dd>
                {instance.modelTitle} {instance.modelVersion && <span className="studio-listview__muted">v{instance.modelVersion}</span>}
              </dd>
              {instance.stage && (
                <>
                  <dt>Stage</dt>
                  <dd>{stageLabel(instance.stage, instance.stages)}</dd>
                </>
              )}
              <dt>Content</dt>
              <dd>
                <PayloadLink payload={instance.payload} />
              </dd>
              <dt>Initiator</dt>
              <dd>{instance.initiator}</dd>
              <dt>Started</dt>
              <dd>{formatTime(instance.startTime)}</dd>
              {instance.endTime && (
                <>
                  <dt>Ended</dt>
                  <dd>{formatTime(instance.endTime)}</dd>
                </>
              )}
              {instance.comment && (
                <>
                  <dt>Comment</dt>
                  <dd>{instance.comment}</dd>
                </>
              )}
            </dl>
            <div className="studio-listview studio-wf__history">
              <table className="studio-table">
                <thead>
                  <tr>
                    <th>Step</th>
                    <th>Status</th>
                    <th>Assignee</th>
                    <th>Completed by</th>
                    <th>Started</th>
                    <th>Ended</th>
                    <th>Comment</th>
                  </tr>
                </thead>
                <tbody>
                  {instance.history.map((h) => (
                    <tr key={h.id}>
                      <td>{h.title || h.nodeId}</td>
                      <td>
                        <StatusBadge status={h.status} />
                      </td>
                      <td className="studio-listview__muted">
                        {h.assignee}
                        {h.delegatedFrom && <span className="studio-wf__delegated"> (from {h.delegatedFrom})</span>}
                      </td>
                      <td className="studio-listview__muted">{h.completedBy}</td>
                      <td className="studio-listview__muted">{formatTime(h.startTime)}</td>
                      <td className="studio-listview__muted">{formatTime(h.endTime)}</td>
                      <td className={h.failure ? 'studio-wf__failure' : 'studio-listview__muted'}>{h.failure || h.comment}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
    </StudioDialog>
  );
}
