import { ActionButton, ProgressCircle, Text, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Edit from '@spectrum-icons/workflow/Edit';
import Sync from '@spectrum-icons/workflow/Sync';
import { useCallback, useEffect, useState } from 'react';
import { describeResult, stateLabel, type MsmClient } from './MsmClient';
import type { Overview } from './types';
import { formatWhen } from './LiveCopyStatus';
import { ScreenFrame } from '../shell/ScreenFrame';

interface Props {
  msm: MsmClient;
  /** blueprint page */
  path: string;
  onClose(): void;
  onChanged?(): void;
}

/** Every page of a blueprint and its state in each live copy, with Synchronize per page. */
export function LiveCopyOverview({ msm, path, onClose, onChanged }: Props) {
  const [data, setData] = useState<Overview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    msm.overview(path).then(setData, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [msm, path]);

  useEffect(load, [load]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !busy && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, busy]);

  const sync = async (target: string) => {
    setBusy(target);
    try {
      ToastQueue.positive(`${target}: ${describeResult(await msm.sync(target, { deep: false }))}`, { timeout: 5000 });
      load();
      onChanged?.();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="studio-wizard studio-msm-overview" role="dialog" aria-modal="true" aria-label="Linked copy Overview">
      <ScreenFrame
        identity={<div className="studio-form-page__identity"><span>Overview</span><h1>Linked copy Overview</h1><code>{path}</code></div>}
        onClose={onClose}
        closeLabel="Close linked copy overview"
        windowControlsDisabled={!!busy}
      />
      {error && (
        <div className="studio-wizard__error" role="alert">
          {error}
        </div>
      )}
      <div className="studio-wizard__body">
        {!data && !error && <ProgressCircle isIndeterminate aria-label="Loading linked copies" />}
        {data?.liveCopies.length === 0 && <p className="studio-wizard__empty">This page has no linked copies.</p>}
        {data?.liveCopies.map((lc) => (
          <section key={lc.path} className="studio-msm-overview__copy" aria-label={lc.path}>
            <h2>
              {lc.path}
              <span className="studio-msm__muted">
                {' '}
                from {lc.blueprintPath} · {lc.deep ? 'with child pages' : 'this page only'} · {lc.rolloutConfigs.map((c) => c.title).join(', ')}
              </span>
            </h2>
            <table className="studio-msm-overview__table studio-table">
              <thead>
                <tr>
                  <th>Page</th>
                  <th>Linked copy page</th>
                  <th>Status</th>
                  <th>Last sync</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {lc.pages.map((p) => (
                  <tr key={p.targetPath}>
                    <td style={{ paddingLeft: 8 + p.level * 16 }}>{p.title}</td>
                    <td className="studio-msm__muted">{p.targetPath}</td>
                    <td>
                      <span className={`studio-msm-state is-${p.status}`}>{stateLabel(p.status)}</span>
                    </td>
                    <td className="studio-msm__muted">{p.status === 'excluded' ? '' : formatWhen(p.lastRolledOut)}</td>
                    <td className="studio-msm-overview__row-actions">
                      {p.status !== 'excluded' && p.status !== 'local' && p.status !== 'suspended' && (
                        <TooltipTrigger delay={400}>
                          <ActionButton isQuiet aria-label={`Synchronize ${p.targetPath}`} isDisabled={!!busy} onPress={() => void sync(p.targetPath)}>
                            {busy === p.targetPath ? <ProgressCircle size="S" isIndeterminate aria-label="Synchronizing" /> : <Sync />}
                          </ActionButton>
                          <Tooltip>{p.status === 'missing' ? 'Create from the source' : 'Synchronize'}</Tooltip>
                        </TooltipTrigger>
                      )}
                      {p.status !== 'missing' && p.status !== 'excluded' && (
                        <TooltipTrigger delay={400}>
                          <ActionButton isQuiet aria-label={`Edit ${p.targetPath}`} onPress={() => (window.location.href = `/editor.html${p.targetPath}.html`)}>
                            <Edit />
                          </ActionButton>
                          <Tooltip>Edit</Tooltip>
                        </TooltipTrigger>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Text UNSAFE_className="studio-msm__muted">{lc.pages.length} pages</Text>
          </section>
        ))}
      </div>
    </div>
  );
}
