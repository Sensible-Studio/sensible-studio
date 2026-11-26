import { ActionButton, DialogContainer, ProgressCircle, Text, ToastQueue } from '@adobe/react-spectrum';
import Revert from '@spectrum-icons/workflow/Revert';
import { useCallback, useEffect, useState } from 'react';
import type { MsmClient } from './MsmClient';
import { formatWhen } from './LiveCopyStatus';
import type { PageRevision } from './types';
import './msm.scss';
import { StudioConfirmDialog } from '../shell/StudioDialog';

interface Props {
  msm: MsmClient;
  /** a page */
  path: string;
  /** how many to show */
  limit?: number;
  onRestored?(): void;
}

/**
 * The saved versions of a page (rollouts with the "Standard sync with versions" configuration save one before
 * they change the page), with Restore.
 */
export function PageVersions({ msm, path, limit = 5, onRestored }: Props) {
  const [revisions, setRevisions] = useState<PageRevision[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [restoring, setRestoring] = useState<PageRevision | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    msm.revisions(path).then(
      (r) => {
        setRevisions(r);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [msm, path]);

  useEffect(load, [load]);

  const restore = async (r: PageRevision) => {
    setBusy(true);
    try {
      setRevisions(await msm.restore(path, r.id));
      ToastQueue.positive(`Version ${r.name} restored; the previous state was saved as a version`, { timeout: 5000 });
      onRestored?.();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(false);
    }
  };

  if (error) return <p className="studio-msm__muted">{error}</p>;
  if (!revisions) return <ProgressCircle size="S" isIndeterminate aria-label="Loading versions" />;
  if (!revisions.length) return <p className="studio-msm__muted">No versions yet.</p>;

  return (
    <>
      <ul className="studio-msm-versions">
        {revisions.slice(0, limit).map((r) => (
          <li key={r.id}>
            <div className="studio-msm-versions__text">
              <strong>
                {r.name}
                {r.label ? ` · ${r.label}` : ''}
              </strong>
              <span>
                {formatWhen(r.created)}
                {r.createdBy ? ` by ${r.createdBy}` : ''}
              </span>
              {r.comment && <span className="studio-msm__muted">{r.comment}</span>}
            </div>
            <ActionButton isQuiet isDisabled={busy} onPress={() => setRestoring(r)} aria-label={`Restore version ${r.name}`}>
              <Revert />
              <Text>Restore</Text>
            </ActionButton>
          </li>
        ))}
      </ul>
      {revisions.length > limit && <p className="studio-msm__muted">{revisions.length - limit} older versions</p>}
      <DialogContainer onDismiss={() => setRestoring(null)}>
        {restoring && (
          <StudioConfirmDialog
            title={`Restore version ${restoring.name}`}
            variant="warning"
            primaryActionLabel="Restore"
            cancelLabel="Cancel"
            onPrimaryAction={() => void restore(restoring)}
          >
            The page content goes back to this version. Its current state is saved as a new version first, so this can be
            undone.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </>
  );
}
