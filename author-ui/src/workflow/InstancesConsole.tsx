import { ActionButton, Checkbox, DialogContainer, Text, ToastContainer, ToastQueue } from '@adobe/react-spectrum';
import { SelectionActions } from '../shell/SelectionBar';
import Delete from '@spectrum-icons/workflow/Delete';
import History from '@spectrum-icons/workflow/History';
import Pause from '@spectrum-icons/workflow/Pause';
import Play from '@spectrum-icons/workflow/Play';
import Refresh from '@spectrum-icons/workflow/Refresh';
import SelectBox from '@spectrum-icons/workflow/SelectBox';
import Settings from '@spectrum-icons/workflow/Settings';
import StopCircle from '@spectrum-icons/workflow/StopCircle';
import { useCallback, useEffect, useState, type Key } from 'react';
import { ProductBar } from '../shell/ProductBar';
import { formatTime, stageLabel, type InstanceSummary, type WorkflowClient } from './api';
import { PurgeDialog } from './PurgeDialog';
import { HistoryDialog, instanceTitle, PayloadLink, StatusBadge } from './shared';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { Segmented } from '../shell/Segmented';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { StudioConfirmDialog } from '../shell/StudioDialog';

const TOOL = '/workflow/instances.html';

export type InstanceView = 'running' | 'failures' | 'archive';

const VIEWS: Record<InstanceView, { label: string; status: string; failed?: boolean; empty: string }> = {
  running: { label: 'Running', status: 'RUNNING,SUSPENDED', empty: 'No flow is running.' },
  failures: { label: 'Failures', status: 'RUNNING,SUSPENDED', failed: true, empty: 'No flow has a failed step.' },
  archive: { label: 'Archive', status: 'COMPLETED,ABORTED', empty: 'No flow has ended yet.' },
};

function initialView(): InstanceView {
  const v = new URLSearchParams(window.location.search).get('view');
  return v === 'failures' || v === 'archive' ? v : 'running';
}

/**
 * Workflow Instances (`/workflow/instances.html`): running and suspended workflows, the ones with a failed step
 * (AEM's Failures) and the ended ones (AEM's Archive). Open History, Suspend / Resume, Terminate, Retry the failed
 * step, Delete ended workflows.
 */
export function InstancesConsole({ client, user }: { client: WorkflowClient; user: string | null }) {
  const tab = useTab();
  const [view, setView] = useState<InstanceView>(initialView);
  const [items, setItems] = useState<InstanceSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [history, setHistory] = useState<string | null>(null);
  const [purging, setPurging] = useState(false);
  const [confirm, setConfirm] = useState<{ kind: 'terminate' | 'delete'; list: InstanceSummary[] } | null>(null);

  useScreenTitle(`${VOCAB.flow} Instances`, `${VOCAB.flow} Instances | ${VOCAB.product}`);

  const load = useCallback(() => {
    const v = VIEWS[view];
    client.instances(v.status, v.failed).then(
      (list) => {
        setItems(list);
        setError(null);
        setSelected((s) => s.filter((id) => list.some((i) => i.id === id)));
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client, view]);
  useEffect(load, [load]);

  const switchView = (k: Key) => {
    const v = k as InstanceView;
    setView(v);
    setItems(null);
    setSelected([]);
    if (tab) return; // in the shell the URL is the tab's place
    const url = new URL(window.location.href);
    if (v === 'running') url.searchParams.delete('view');
    else url.searchParams.set('view', v);
    window.history.replaceState(null, '', url);
  };

  const selection = (items ?? []).filter((i) => selected.includes(i.id));
  const single = selection.length === 1 ? selection[0] : null;
  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const run = async (work: (i: InstanceSummary) => Promise<unknown>, list: InstanceSummary[], done: string) => {
    try {
      for (const i of list) await work(i);
      ToastQueue.positive(`${list.length === 1 ? instanceTitle(list[0]) : `${list.length} workflows`} ${done}`, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
    // the engine works in jobs: give it a moment before reading the new state
    window.setTimeout(load, 600);
  };

  const failedItems = (i: InstanceSummary) => i.current.filter((c) => c.status === 'FAILED');
  const live = selection.length > 0 && selection.every((i) => i.status === 'RUNNING' || i.status === 'SUSPENDED');
  const ended = selection.length > 0 && selection.every((i) => i.status === 'COMPLETED' || i.status === 'ABORTED');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (tab && !tab.active) return; // another tab is in front
      const t = e.target as HTMLElement | null;
      if (history || confirm || t?.closest('input:not([type="checkbox"]), textarea, [role="dialog"]')) return;
      if (e.key === 'Escape') setSelected([]);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  return (
    <div className="studio-sites studio-wf">
      {!tab && <ProductBar user={user} />}
      {selection.length > 0 ? (
        <SelectionActions count={selection.length} onClear={() => setSelected([])}>
            {single && (
              <ActionButton isQuiet onPress={() => setHistory(single.id)}>
                <History />
                <Text>Open History</Text>
              </ActionButton>
            )}
            {live && selection.some((i) => i.status === 'RUNNING') && (
              <ActionButton isQuiet onPress={() => void run((i) => client.suspend(i.id), selection.filter((i) => i.status === 'RUNNING'), 'suspended')}>
                <Pause />
                <Text>Suspend</Text>
              </ActionButton>
            )}
            {live && selection.some((i) => i.status === 'SUSPENDED') && (
              <ActionButton isQuiet onPress={() => void run((i) => client.resume(i.id), selection.filter((i) => i.status === 'SUSPENDED'), 'resumed')}>
                <Play />
                <Text>Resume</Text>
              </ActionButton>
            )}
            {selection.some((i) => failedItems(i).length > 0) && (
              <ActionButton
                isQuiet
                onPress={() =>
                  void run(
                    async (i) => {
                      for (const f of failedItems(i)) await client.retry(f.id);
                    },
                    selection.filter((i) => failedItems(i).length > 0),
                    'retried',
                  )
                }
              >
                <Refresh />
                <Text>Retry Step</Text>
              </ActionButton>
            )}
            {live && (
              <ActionButton isQuiet onPress={() => setConfirm({ kind: 'terminate', list: selection })}>
                <StopCircle />
                <Text>Terminate</Text>
              </ActionButton>
            )}
            {ended && (
              <ActionButton isQuiet onPress={() => setConfirm({ kind: 'delete', list: selection })}>
                <Delete />
                <Text>Delete</Text>
              </ActionButton>
            )}
          </SelectionActions>
      ) : (
        <ToolBar
          url={TOOL}
          closable={false}
        >
            <Segmented
              label="Which flows"
              items={(Object.keys(VIEWS) as InstanceView[]).map((k) => ({ key: k, label: VIEWS[k].label }))}
              value={view}
              onChange={switchView}
            />
          <span>
            <ActionButton isQuiet onPress={load} aria-label="Refresh">
              <Refresh />
            </ActionButton>
            {view === 'archive' && (
              <ActionButton isQuiet onPress={() => setPurging(true)}>
                <Settings />
                <Text>Purge Settings</Text>
              </ActionButton>
            )}
            <ActionButton isQuiet onPress={() => setSelected((items ?? []).map((i) => i.id))} isDisabled={!items?.length}>
              <SelectBox />
              <Text>Select All</Text>
            </ActionButton>
          </span>
        </ToolBar>
      )}

      <main className="studio-sites__content">
        {error ? (
          <ErrorState title="Could not load the flows">{error}</ErrorState>
        ) : !items ? (
          <LoadingState />
        ) : items.length === 0 ? (
          <EmptyState title={VIEWS[view].label}>{VIEWS[view].empty}</EmptyState>
        ) : (
          <div className="studio-listview">
            <table className="studio-table">
              <thead>
                <tr>
                  <th aria-label="Select" />
                  <th>Title</th>
                  <th>Status</th>
                  <th>{view === 'archive' ? 'Ended' : 'Current Step'}</th>
                  <th>Stage</th>
                  <th>Payload</th>
                  <th>Initiator</th>
                  <th>Start Time</th>
                  <th>Model</th>
                  <th>Version</th>
                </tr>
              </thead>
              <tbody>
                {items.map((i) => {
                  const isSel = selected.includes(i.id);
                  const failure = failedItems(i)[0];
                  return (
                    <tr key={i.id} className={isSel ? 'is-selected' : undefined}>
                      <td className="studio-listview__check">
                        <Checkbox aria-label={`Select ${instanceTitle(i)}`} isSelected={isSel} onChange={() => toggle(i.id)} isEmphasized />
                      </td>
                      <td>
                        <button type="button" className="studio-listview__title" onClick={() => setHistory(i.id)}>
                          {instanceTitle(i)}
                        </button>
                        {i.comment && <div className="studio-listview__muted studio-wf__sub">{i.comment}</div>}
                      </td>
                      <td>
                        <StatusBadge status={i.status} failed={i.failed} />
                      </td>
                      <td className="studio-listview__muted">
                        {view === 'archive'
                          ? formatTime(i.endTime)
                          : i.current.map((c) => (
                              <div key={c.id}>
                                {c.title}
                                {c.assignee ? ` · ${c.assignee}` : ''}
                                {c.failure && <div className="studio-wf__failure studio-wf__sub">{c.failure}</div>}
                              </div>
                            ))}
                        {!failure && view !== 'archive' && i.current.length === 0 && <span>Working…</span>}
                      </td>
                      <td className="studio-listview__muted">{stageLabel(i.stage, i.stages)}</td>
                      <td>
                        <PayloadLink payload={i.payload} />
                      </td>
                      <td className="studio-listview__muted">{i.initiator}</td>
                      <td className="studio-listview__muted">{formatTime(i.startTime)}</td>
                      <td className="studio-listview__muted">{i.modelTitle}</td>
                      <td className="studio-listview__muted">{i.modelVersion}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </main>

      <DialogContainer onDismiss={() => setPurging(false)}>
        {purging && <PurgeDialog client={client} onClose={() => setPurging(false)} onPurged={() => load()} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setHistory(null)} type="modal">
        {history && <HistoryDialog client={client} id={history} onClose={() => setHistory(null)} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setConfirm(null)}>
        {confirm && (
          <StudioConfirmDialog
            title={confirm.kind === 'terminate' ? 'Terminate' : 'Delete'}
            variant="destructive"
            primaryActionLabel={confirm.kind === 'terminate' ? 'Terminate' : 'Delete'}
            cancelLabel="Cancel"
            onPrimaryAction={() => {
              const list = confirm.list;
              setConfirm(null);
              if (confirm.kind === 'terminate') void run((i) => client.terminate(i.id), list, 'terminated');
              else {
                void run((i) => client.deleteInstance(i.id), list, 'deleted');
                setSelected([]);
              }
            }}
          >
            {confirm.kind === 'terminate'
              ? `You are going to terminate ${confirm.list.map(instanceTitle).join(', ')}. Its open steps are closed and it does not continue.`
              : `You are going to delete ${confirm.list.map(instanceTitle).join(', ')} and its history.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}
