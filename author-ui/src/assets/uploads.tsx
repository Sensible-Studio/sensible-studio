import { ActionButton, ProgressBar } from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import { useCallback, useRef, useState } from 'react';
import { DamError, type DamClient, type UploadConflict } from './api';
import { frameOfFile, isVideoFile } from './video';

export interface Upload {
  id: number;
  name: string;
  folder: string;
  progress: number;
  /**
   * `conflict`: an asset of that name exists; waits for Keep both / Replace / Create version.
   * `duplicate`: the same file is in the DAM already (same SHA-1); waits for Upload anyway / Skip.
   */
  status: 'uploading' | 'done' | 'error' | 'conflict' | 'duplicate';
  error?: string;
  path?: string;
  /** the assets with the same content, for `duplicate` */
  duplicates?: string[];
}

export type UploadChoice = UploadConflict | 'skip' | 'upload';

/** At most this many files go up at the same time. */
const PARALLEL = 3;

/** Queue of uploads; `onUploaded` runs after each finished file (to refresh the listing). */
export function useUploads(client: DamClient, onUploaded: (folder: string) => void) {
  const [uploads, setUploads] = useState<Upload[]>([]);
  const nextId = useRef(1);
  type Job = { id: number; file: File; folder: string; conflict?: UploadConflict; checked?: boolean };
  const queue = useRef<Job[]>([]);
  /** files waiting for a decision: name conflict or duplicate content */
  const waiting = useRef(new Map<number, Job & { kind: 'conflict' | 'duplicate' }>());
  const running = useRef(0);

  const update = (id: number, patch: Partial<Upload>) => setUploads((list) => list.map((u) => (u.id === id ? { ...u, ...patch } : u)));

  const pump = useCallback(() => {
    while (running.current < PARALLEL && queue.current.length > 0) {
      const job = queue.current.shift()!;
      running.current++;
      if (!job.checked) {
        // Duplicate detection first: the same bytes elsewhere in the DAM (AEM's "Duplicate file detected").
        client
          .findDuplicates(job.file)
          .then(
            (dups) => {
              if (dups.length > 0) {
                waiting.current.set(job.id, { ...job, kind: 'duplicate' });
                update(job.id, { status: 'duplicate', duplicates: dups });
              } else {
                queue.current.unshift({ ...job, checked: true });
              }
            },
            () => queue.current.unshift({ ...job, checked: true }), // cannot check: upload as usual
          )
          .finally(() => {
            running.current--;
            pump();
          });
        continue;
      }
      client
        .upload(job.folder, job.file, (f) => update(job.id, { progress: f }), job.conflict)
        .then(
          (path) => {
            update(job.id, { status: 'done', progress: 1, path });
            onUploaded(job.folder);
            // studio-dam does not decode video: this browser picks the frame its thumbnails come from.
            if (isVideoFile(job.file)) {
              void frameOfFile(job.file)
                .then((frame) => (frame ? client.setPoster(path, frame).then(() => onUploaded(job.folder)) : undefined))
                .catch(() => undefined); // no thumbnail, as before; "Use frame as thumbnail" can set one
            }
          },
          (e: unknown) => {
            if (e instanceof DamError && e.status === 409 && !job.conflict) {
              waiting.current.set(job.id, { ...job, kind: 'conflict' });
              update(job.id, { status: 'conflict', progress: 0, error: e.message });
            } else {
              update(job.id, { status: 'error', error: e instanceof Error ? e.message : String(e) });
            }
          },
        )
        .finally(() => {
          running.current--;
          pump();
        });
    }
  }, [client, onUploaded]);

  const add = useCallback(
    (folder: string, files: File[]) => {
      const added = files.map((file) => ({ id: nextId.current++, file, folder }));
      queue.current.push(...added);
      setUploads((list) => [
        ...list.filter((u) => u.status !== 'done'),
        ...added.map((a) => ({ id: a.id, name: a.file.name, folder, progress: 0, status: 'uploading' as const })),
      ]);
      pump();
    },
    [pump],
  );

  /**
   * Answers a waiting file: `skip`; `upload` for a duplicate; Keep both / Replace / Create version
   * for a name conflict. Without `id`, every file waiting for that kind of answer.
   */
  const resolve = useCallback(
    (choice: UploadChoice, id?: number) => {
      const all = [...waiting.current.values()];
      const jobs =
        id === undefined
          ? all.filter((j) => (choice === 'upload' ? j.kind === 'duplicate' : j.kind === 'conflict'))
          : all.filter((j) => j.id === id);
      for (const { kind, ...job } of jobs) {
        waiting.current.delete(job.id);
        if (choice === 'skip') {
          setUploads((list) => list.filter((u) => u.id !== job.id));
        } else if (choice === 'upload') {
          update(job.id, { status: 'uploading', progress: 0, duplicates: undefined });
          queue.current.push({ ...job, checked: true });
        } else if (kind === 'conflict') {
          update(job.id, { status: 'uploading', progress: 0, error: undefined });
          queue.current.push({ ...job, conflict: choice });
        }
      }
      pump();
    },
    [pump],
  );

  const dismiss = useCallback(
    () => setUploads((list) => list.filter((u) => u.status === 'uploading' || u.status === 'conflict' || u.status === 'duplicate')),
    [],
  );

  return { uploads, add, dismiss, resolve };
}

const CHOICES: Array<[UploadConflict | 'skip', string]> = [
  ['rename', 'Keep both'],
  ['replace', 'Replace'],
  ['version', 'Create version'],
  ['skip', 'Skip'],
];

export function UploadPanel({
  uploads,
  onDismiss,
  onResolve,
}: {
  uploads: Upload[];
  onDismiss(): void;
  onResolve?(choice: UploadChoice, id?: number): void;
}) {
  if (uploads.length === 0) return null;
  const busy = uploads.filter((u) => u.status === 'uploading').length;
  const failed = uploads.filter((u) => u.status === 'error').length;
  const conflicts = uploads.filter((u) => u.status === 'conflict').length;
  const duplicates = uploads.filter((u) => u.status === 'duplicate').length;
  const waiting = conflicts + duplicates;
  return (
    <section className="studio-assets__uploads" aria-label="Uploads" aria-live="polite">
      <header>
        <strong>
          {waiting
            ? [conflicts && `${conflicts} name conflict${conflicts > 1 ? 's' : ''}`, duplicates && `${duplicates} duplicate${duplicates > 1 ? 's' : ''}`]
                .filter(Boolean)
                .join(', ')
            : busy
              ? `Uploading ${busy} of ${uploads.length}`
              : failed
                ? `${failed} upload${failed > 1 ? 's' : ''} failed`
                : 'Upload complete'}
        </strong>
        {busy === 0 && waiting === 0 && (
          <ActionButton isQuiet aria-label="Close uploads" onPress={onDismiss}>
            <Close size="S" />
          </ActionButton>
        )}
      </header>
      {conflicts > 1 && onResolve && (
        <div className="studio-assets__upload-choices" role="group" aria-label="For all conflicts">
          <span>All:</span>
          {CHOICES.map(([c, label]) => (
            <ActionButton key={c} isQuiet onPress={() => onResolve(c)}>
              {label}
            </ActionButton>
          ))}
        </div>
      )}
      <ul>
        {uploads.map((u) => (
          <li key={u.id} className={`is-${u.status}`}>
            <div className="studio-assets__upload-name" title={u.name}>
              {u.name}
            </div>
            {u.status === 'conflict' ? (
              <>
                <div className="studio-assets__upload-error">An file named {u.name} already exists.</div>
                {onResolve && (
                  <div className="studio-assets__upload-choices" role="group" aria-label={`Conflict for ${u.name}`}>
                    {CHOICES.map(([c, label]) => (
                      <ActionButton key={c} isQuiet onPress={() => onResolve(c, u.id)}>
                        {label}
                      </ActionButton>
                    ))}
                  </div>
                )}
              </>
            ) : u.status === 'duplicate' ? (
              <>
                <div className="studio-assets__upload-error">
                  The same file is already in the DAM: {(u.duplicates ?? []).slice(0, 3).join(', ')}
                  {(u.duplicates ?? []).length > 3 ? ` and ${(u.duplicates ?? []).length - 3} more` : ''}
                </div>
                {onResolve && (
                  <div className="studio-assets__upload-choices" role="group" aria-label={`Duplicate ${u.name}`}>
                    <ActionButton isQuiet onPress={() => onResolve('upload', u.id)}>
                      Upload anyway
                    </ActionButton>
                    <ActionButton isQuiet onPress={() => onResolve('skip', u.id)}>
                      Skip
                    </ActionButton>
                  </div>
                )}
              </>
            ) : u.status === 'error' ? (
              <div className="studio-assets__upload-error">{u.error}</div>
            ) : (
              <ProgressBar
                aria-label={`Uploading ${u.name}`}
                value={Math.round(u.progress * 100)}
                size="S"
                width="100%"
                valueLabel={u.status === 'done' ? 'Uploaded' : undefined}
                showValueLabel
              />
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
