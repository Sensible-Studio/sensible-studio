import { ActionButton, DialogContainer, ProgressCircle, Text, ToastQueue } from '@adobe/react-spectrum';
import Branch1 from '@spectrum-icons/workflow/Branch1';
import LinkOff from '@spectrum-icons/workflow/LinkOff';
import Pause from '@spectrum-icons/workflow/Pause';
import Play from '@spectrum-icons/workflow/Play';
import Refresh from '@spectrum-icons/workflow/Refresh';
import Sync from '@spectrum-icons/workflow/Sync';
import ViewTree from '@spectrum-icons/workflow/TreeExpandAll';
import { useCallback, useEffect, useState } from 'react';
import { describeResult, stateLabel, type MsmClient } from './MsmClient';
import { LiveCopyOverview } from './LiveCopyOverview';
import { RolloutDialog } from './RolloutDialog';
import type { MsmStatus } from './types';
import './msm.scss';
import { StudioConfirmDialog } from '../shell/StudioDialog';

interface Props {
  msm: MsmClient;
  /** a page */
  path: string;
  /** after a rollout, synchronisation or inheritance change */
  onChanged?(): void;
}

export function formatWhen(iso?: string): string {
  if (!iso) return 'never';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Loads the MSM status of a path; `reload` after changes. */
export function useMsmStatus(msm: MsmClient, path: string | null) {
  const [state, setState] = useState<{ path: string | null; status: MsmStatus | null; error: string | null }>({ path: null, status: null, error: null });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    if (!path) return;
    let cancelled = false;
    msm.status(path).then(
      (status) => !cancelled && setState({ path, status, error: null }),
      (e: unknown) => !cancelled && setState({ path, status: null, error: e instanceof Error ? e.message : String(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [msm, path, version]);
  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { ...(state.path === path ? state : { path, status: null, error: null }), reload, setStatus: (status: MsmStatus) => setState({ path, status, error: null }) };
}

/**
 * The MSM part of a page's details, like AEM's Live Copy and Blueprint information:
 * - in a live copy: its blueprint page, status, last rollout, and Synchronize, Reset, Suspend / Resume, Detach;
 * - a blueprint: its live copies with their status, Rollout and the Live Copy Overview.
 */
export function LiveCopyStatus({ msm, path, onChanged }: Props) {
  const { status, error, reload, setStatus } = useMsmStatus(msm, path);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirmDetach, setConfirmDetach] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const [rollout, setRollout] = useState(false);
  const [overview, setOverview] = useState(false);

  const act = async (what: string, work: () => Promise<string | void>) => {
    setBusy(what);
    try {
      const message = await work();
      if (message) ToastQueue.positive(message, { timeout: 5000 });
      reload();
      onChanged?.();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  if (error) return <p className="studio-msm__muted">{error}</p>;
  if (!status) return <ProgressCircle size="S" isIndeterminate aria-label="Loading linked copy status" />;

  const rel = status.relationship;
  const lc = status.liveCopy;
  return (
    <div className="studio-msm">
      {status.inLiveCopy && rel && lc && (
        <section className="studio-msm__section" aria-label="Linked copy">
          <h3>Linked copy</h3>
          <dl className="studio-msm__facts">
            <div>
              <dt>Status</dt>
              <dd>
                <span className={`studio-msm-state is-${rel.status}`}>{stateLabel(rel.status)}</span>
              </dd>
            </div>
            <div>
              <dt>Source</dt>
              <dd>
                <a href={`/sites.html${rel.sourcePath.slice(0, rel.sourcePath.lastIndexOf('/'))}`} title={rel.sourcePath}>
                  {rel.sourcePath}
                </a>
              </dd>
            </div>
            {!lc.root && (
              <div>
                <dt>Linked copy</dt>
                <dd title={`from ${lc.blueprintPath}`}>{lc.path}</dd>
              </div>
            )}
            <div>
              <dt>Last sync</dt>
              <dd>
                {formatWhen(rel.lastRolledOut)}
                {rel.lastRolledOutBy ? ` by ${rel.lastRolledOutBy}` : ''}
              </dd>
            </div>
            <div>
              <dt>Sync changes configs</dt>
              <dd>{lc.rolloutConfigs.map((c) => c.title).join(', ') || 'none'}</dd>
            </div>
            {rel.cancelledProperties.length > 0 && (
              <div>
                <dt>Own values</dt>
                <dd>{rel.cancelledProperties.join(', ')}</dd>
              </div>
            )}
          </dl>
          {rel.inherited && (
            <div className="studio-msm__actions">
              <ActionButton
                isDisabled={!!busy || rel.cancelled || !rel.sourceExisting}
                onPress={() => void act('sync', async () => `Synchronized: ${describeResult(await msm.sync(path, { deep: false }))}`)}
              >
                <Sync />
                <Text>Synchronize</Text>
              </ActionButton>
              <ActionButton
                isDisabled={!!busy || !rel.sourceExisting}
                onPress={() => setConfirmReset(true)}
              >
                <Refresh />
                <Text>Reset</Text>
              </ActionButton>
              {rel.cancelled && !rel.cancelledByAncestor ? (
                <ActionButton
                  isDisabled={!!busy}
                  onPress={() =>
                    void act('resume', async () => {
                      const s = await msm.inheritance(path, 'resume');
                      if (s) setStatus(s);
                      return 'Linked copy resumed and synchronized';
                    })
                  }
                >
                  <Play />
                  <Text>Resume</Text>
                </ActionButton>
              ) : (
                <ActionButton
                  isDisabled={!!busy || rel.cancelled}
                  onPress={() =>
                    void act('suspend', async () => {
                      const s = await msm.inheritance(path, 'suspend', { deep: false });
                      if (s) setStatus(s);
                      return 'Linked copy suspended: rollouts leave this page alone';
                    })
                  }
                >
                  <Pause />
                  <Text>Suspend</Text>
                </ActionButton>
              )}
              <ActionButton isDisabled={!!busy} onPress={() => setConfirmDetach(true)}>
                <LinkOff />
                <Text>Detach</Text>
              </ActionButton>
              {busy && <ProgressCircle size="S" isIndeterminate aria-label="Working" />}
            </div>
          )}
          {!rel.inherited && <p className="studio-msm__muted">This page was created in the live copy; it is not synchronized.</p>}
        </section>
      )}

      {status.isSource && status.liveCopies && (
        <section className="studio-msm__section" aria-label="Linked copies">
          <h3>
            Live Copies ({status.liveCopies.length})
          </h3>
          <ul className="studio-msm__copies">
            {status.liveCopies.map((c) => (
              <li key={c.targetPath}>
                <a href={`/editor.html${c.targetPath}.html`} className="studio-msm__copy" title={`Open ${c.targetPath}`}>
                  {c.targetPath}
                </a>
                <span className={`studio-msm-state is-${c.status}`}>{stateLabel(c.status)}</span>
                <span className="studio-msm__muted">{formatWhen(c.lastRolledOut)}</span>
              </li>
            ))}
          </ul>
          <div className="studio-msm__actions">
            <ActionButton onPress={() => setRollout(true)} isDisabled={!!busy}>
              <Branch1 />
              <Text>Sync changes…</Text>
            </ActionButton>
            <ActionButton onPress={() => setOverview(true)}>
              <ViewTree />
              <Text>Linked copy Overview</Text>
            </ActionButton>
          </div>
        </section>
      )}

      {!status.inLiveCopy && !status.isSource && <p className="studio-msm__muted">Neither a linked copy nor a source.</p>}

      <DialogContainer onDismiss={() => setConfirmReset(false)}>
        {confirmReset && (
          <StudioConfirmDialog
            title="Reset linked copy page"
            variant="warning"
            primaryActionLabel="Reset"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              void act('reset', async () => `Reset: ${describeResult(await msm.sync(path, { deep: false, reset: true }))}`)
            }
          >
            Every cancelled inheritance on this page is re-enabled and the page is synchronized with the blueprint: the
            changes made in the linked copy to inherited content are lost.
          </StudioConfirmDialog>
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setConfirmDetach(false)}>
        {confirmDetach && rel && (
          <StudioConfirmDialog
            title="Detach linked copy"
            variant="destructive"
            primaryActionLabel="Detach"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              void act('detach', async () => {
                await msm.inheritance(path, 'detach');
                return 'Detached: the page is no longer synchronized';
              })
            }
          >
            {status.liveCopy?.root
              ? 'The linked copy is turned into normal pages: no sync will reach them any more. This cannot be undone.'
              : 'This page and the pages below it leave the linked copy for good: no sync will reach them any more.'}
          </StudioConfirmDialog>
        )}
      </DialogContainer>

      <DialogContainer onDismiss={() => setRollout(false)}>
        {rollout && status.liveCopies && (
          <RolloutDialog
            msm={msm}
            path={path}
            liveCopies={status.liveCopies}
            onDone={() => {
              reload();
              onChanged?.();
            }}
          />
        )}
      </DialogContainer>

      {overview && <LiveCopyOverview msm={msm} path={path} onClose={() => setOverview(false)} onChanged={onChanged} />}
    </div>
  );
}
