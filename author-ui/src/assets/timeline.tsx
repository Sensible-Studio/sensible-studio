import { ActionButton, Button, DialogContainer, ProgressCircle, TextArea, TextField } from '@adobe/react-spectrum';
import Revert from '@spectrum-icons/workflow/Revert';
import { useCallback, useEffect, useState } from 'react';
import type { AssetVersion, DamClient } from './api';
import { StudioConfirmDialog } from '../shell/StudioDialog';

/**
 * The Timeline rail of the asset details: versions newest first, "Save as Version" (label and
 * comment), and Revert to a version (the current state is kept as a version of its own).
 */
export function Timeline({ client, path, onRestored }: { client: DamClient; path: string; onRestored(): void }) {
  const [versions, setVersions] = useState<AssetVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [label, setLabel] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState<AssetVersion | null>(null);

  const load = useCallback(async () => {
    try {
      setVersions(await client.versions(path));
      setError(null);
    } catch (e) {
      setVersions([]);
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client, path]);

  useEffect(() => {
    setVersions(null);
    void load();
  }, [load]);

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="studio-assets__timeline">
      <h2>Timeline</h2>
      <form
        className="studio-assets__timeline-form"
        onSubmit={(e) => {
          e.preventDefault();
          void run(async () => {
            await client.createVersion(path, label.trim() || undefined, comment.trim() || undefined);
            setLabel('');
            setComment('');
          });
        }}
      >
        <TextField label="Label" value={label} onChange={setLabel} width="100%" isDisabled={busy} />
        <TextArea label="Comment" value={comment} onChange={setComment} width="100%" isDisabled={busy} />
        <Button type="submit" variant="primary" isDisabled={busy}>
          Save as Version
        </Button>
      </form>
      {error && <p className="studio-assets__dialog-error">{error}</p>}
      {versions === null ? (
        <ProgressCircle size="S" isIndeterminate aria-label="Loading versions" />
      ) : versions.length === 0 ? (
        <p className="studio-assets__muted">No versions yet.</p>
      ) : (
        <ol className="studio-assets__versions" aria-label="Versions">
          {versions.map((v) => (
            <li key={v.name}>
              <div className="studio-assets__version-head">
                <strong>{v.labels.length ? v.labels.join(', ') : `Version ${v.name}`}</strong>
                <ActionButton isQuiet aria-label={`Revert to ${v.name}`} isDisabled={busy} onPress={() => setRestoring(v)}>
                  <Revert size="S" />
                </ActionButton>
              </div>
              <small>
                {v.labels.length ? `${v.name} · ` : ''}
                {new Date(v.created).toLocaleString()}
                {v.createdBy ? ` · ${v.createdBy}` : ''}
              </small>
              {v.comment && <p>{v.comment}</p>}
            </li>
          ))}
        </ol>
      )}
      <DialogContainer onDismiss={() => setRestoring(null)}>
        {restoring && (
          <StudioConfirmDialog
            title="Revert to version"
            variant="confirmation"
            primaryActionLabel="Revert"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              void run(async () => {
                await client.restoreVersion(path, restoring.name);
                onRestored();
              })
            }
            onCancel={() => setRestoring(null)}
          >
            The asset goes back to version {restoring.name}
            {restoring.labels.length ? ` (${restoring.labels.join(', ')})` : ''}. Its current state is saved as a new version first, so this can be
            undone.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}
