import { ActionButton, Button, DialogContainer, Text, ToastQueue, ToggleButton, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Clock from '@spectrum-icons/workflow/Clock';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Launch from '@spectrum-icons/workflow/Launch';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import type { MsmClient } from '../msm/MsmClient';
import { ProductBar } from '../shell/ProductBar';
import { editorUrl, formatDate, type Launch as LaunchInfo, type LaunchesClient } from './api';
import { CreateLaunchDialog } from './CreateLaunchDialog';
import { PromoteLaunchDialog } from './PromoteLaunchDialog';
import { SourcePickerDialog } from './SourcePickerDialog';
import { useNavigate, useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: LaunchesClient;
  msm: MsmClient;
  studio: StudioClient;
}

/**
 * The Launches console (`/launches.html`), AEM's Sites → Launches: every launch with its source, live
 * date, page counts and state; Create (pick the source page), Edit (the page editor on the launch's
 * page), Promote, Delete.
 */
export function LaunchesConsole({ client, msm, studio }: Props) {
  const go = useNavigate();
  const tab = useTab();
  const [launches, setLaunches] = useState<LaunchInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [user, setUser] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const [creating, setCreating] = useState<{ source: string; title: string } | null>(null);
  const [promoting, setPromoting] = useState<LaunchInfo | null>(null);

  useEffect(() => {
    studio.currentUser().then(setUser, () => setUser(null));
  }, [studio]);

  const load = useCallback(() => {
    client.list().then(
      (l) => {
        setLaunches(l);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  return (
    <div className="studio-sites studio-launches">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/launches.html"
        closable={false}
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Refresh</Tooltip>
          </TooltipTrigger>
        }
        primary={<Button variant="accent" onPress={() => setPicking(true)}>
            <Add />
            <Text>Create Release</Text>
          </Button>}
      />
      <main className="studio-sites__content">
        {error && <ErrorState title="Could not load releases">{error}</ErrorState>}
        {!launches && !error && <LoadingState label="Loading releases" />}
        {launches && launches.length === 0 && <EmptyState title="No releases">Create one from a page to prepare a future version apart from the live site.</EmptyState>}
        {launches && launches.length > 0 && (
          <table className="studio-audiences__table studio-launches__table studio-table">
            <thead>
              <tr>
                <th>Title</th>
                <th>Source</th>
                <th>Live date</th>
                <th>Pages</th>
                <th>Created</th>
                <th>State</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {launches.map((l) => (
                <tr key={l.path}>
                  <td>
                    <strong>{l.title}</strong>
                    {l.description && <div className="studio-replication__muted">{l.description}</div>}
                    <div className="studio-replication__muted">
                      <code>{l.path}</code>
                    </div>
                  </td>
                  <td>
                    <code>{l.source}</code>
                    {(l.sources?.length ?? 0) > 1 && <div className="studio-replication__muted" title={l.sources!.slice(1).join('\n')}>{`and ${l.sources!.length - 1} more`}</div>}
                    <div className="studio-replication__muted">{l.liveCopy ? 'Inherits the source (linked copy)' : 'Copy'}</div>
                  </td>
                  <td>{formatDate(l.liveDate) || '—'}</td>
                  <td>
                    {l.pages ?? 0}
                    {l.modified ? <span className="studio-replication__muted"> · {l.modified} modified</span> : null}
                  </td>
                  <td>
                    {formatDate(l.created)}
                    {l.createdBy && <div className="studio-replication__muted">by {l.createdBy}</div>}
                  </td>
                  <td>
                    {l.promoted ? <span className="studio-replication__ok">Promoted {formatDate(l.promoted)}</span> : l.productionReady && !l.autoError ? `Goes live by itself ${formatDate(l.liveDate)}` : 'In progress'}
                    {l.autoError && (
                      <div className="studio-publish__error" role="alert">
                        {l.promoted ? 'Not published' : 'Did not go live'}: {l.autoError}
                      </div>
                    )}
                  </td>
                  <td className="studio-audiences__actions">
                    <TooltipTrigger delay={400}>
                      <ActionButton isQuiet aria-label="Edit" onPress={() => go(editorUrl(l.sourceInLaunch))}>
                        <Edit />
                      </ActionButton>
                      <Tooltip>Open in the page editor</Tooltip>
                    </TooltipTrigger>
                    {!l.promoted && (
                      <TooltipTrigger delay={400}>
                        <ToggleButton
                          isQuiet
                          aria-label="Production ready"
                          isSelected={!!l.productionReady}
                          isDisabled={!l.liveDate}
                          onChange={(on) =>
                            client.update(l.path, { productionReady: on }).then(
                              () => {
                                load();
                                ToastQueue.positive(on ? `"${l.title}" goes live and is published by itself on ${formatDate(l.liveDate)}` : `"${l.title}" waits for Go live`, { timeout: 5000 });
                              },
                              (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
                            )
                          }
                        >
                          <Clock />
                        </ToggleButton>
                        <Tooltip>{l.liveDate ? 'Production ready: go live and publish by itself at the live date' : 'A release without a live date goes live only by hand'}</Tooltip>
                      </TooltipTrigger>
                    )}
                    <TooltipTrigger delay={400}>
                      <ActionButton isQuiet aria-label="Go live" onPress={() => setPromoting(l)}>
                        <Launch />
                      </ActionButton>
                      <Tooltip>Go live</Tooltip>
                    </TooltipTrigger>
                    <TooltipTrigger delay={400}>
                      <ActionButton
                        isQuiet
                        aria-label="Delete"
                        onPress={() =>
                          client.remove(l.path).then(
                            () => {
                              load();
                              ToastQueue.positive(`Release "${l.title}" deleted`, { timeout: 3000 });
                            },
                            (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
                          )
                        }
                      >
                        <Delete />
                      </ActionButton>
                      <Tooltip>Delete</Tooltip>
                    </TooltipTrigger>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </main>
      <DialogContainer onDismiss={() => setPicking(false)}>
        {picking && (
          <SourcePickerDialog
            studio={studio}
            onPick={(source, title) => {
              setPicking(false);
              setCreating({ source, title });
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setCreating(null)}>
        {creating && <CreateLaunchDialog client={client} msm={msm} source={creating.source} sourceTitle={creating.title} onCreated={load} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setPromoting(null)}>{promoting && <PromoteLaunchDialog client={client} launch={promoting} onDone={load} />}</DialogContainer>
    </div>
  );
}
