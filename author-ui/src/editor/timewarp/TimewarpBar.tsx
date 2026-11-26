import { ActionButton, Button, ButtonGroup, Content, DialogTrigger, Divider, Form, Heading, ProgressCircle, Text, TextField, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Revert from '@spectrum-icons/workflow/Revert';
import SaveFloppy from '@spectrum-icons/workflow/SaveFloppy';
import { useEffect, useState } from 'react';
import { formatWhen } from '../../msm/LiveCopyStatus';
import type { MsmClient } from '../../msm/MsmClient';
import type { PageRevision } from '../../msm/types';
import type { Launch } from '../../launches/api';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  msm: MsmClient;
  pagePath: string;
  /** Saves a version of the page now. */
  onCreateVersion?(label: string, comment: string): Promise<unknown>;
  /** The moment the page is shown for (epoch ms); null = now. */
  time: number | null;
  onTime(time: number | null): void;
  /** Launches made from this page: the future side of the timeline (the page renders from the launch due by then). */
  launches?: Launch[];
}

/** The version in force at a moment: the newest created at or before it. */
export function versionAt(revisions: PageRevision[], time: number): PageRevision | null {
  let best: PageRevision | null = null;
  for (const r of revisions) {
    const created = r.created ? Date.parse(r.created) : NaN;
    if (!Number.isNaN(created) && created <= time && (!best || created > Date.parse(best.created!))) best = r;
  }
  return best;
}

/** `datetime-local` value of a moment, in the browser's time zone. */
export function toLocalInput(time: number): string {
  const d = new Date(time);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * AEM's Timewarp mode: pick a moment and the page renders from the version that was current then.
 * The saved versions are a timeline the author can jump to.
 */
export function TimewarpBar({ msm, pagePath, time, onTime, onCreateVersion, launches = [] }: Props) {
  const [revisions, setRevisions] = useState<PageRevision[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    msm.revisions(pagePath).then(
      (r) => live && setRevisions(r),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [msm, pagePath, reload]);

  const now = Date.now();
  const at = time ?? now;
  const current = revisions ? versionAt(revisions, at) : null;
  const sorted = revisions ? [...revisions].sort((a, b) => Date.parse(a.created ?? '') - Date.parse(b.created ?? '')) : [];
  const future = launches.filter((l) => l.liveDate && !l.promoted && Date.parse(l.liveDate) > now).sort((a, b) => Date.parse(a.liveDate!) - Date.parse(b.liveDate!));
  const launchAt = time !== null && time > now ? [...future].reverse().find((l) => Date.parse(l.liveDate!) <= time) ?? null : null;

  return (
    <div className="studio-timewarp" role="region" aria-label="History">
      <label className="studio-timewarp__field">
        <span>Show the page as of</span>
        <input
          type="datetime-local"
          value={toLocalInput(at)}
          max={toLocalInput(now)}
          onChange={(e) => {
            const t = Date.parse(e.target.value);
            if (!Number.isNaN(t)) onTime(Math.min(t, Date.now()));
          }}
        />
      </label>
      <TooltipTrigger delay={400}>
        <ActionButton isQuiet isDisabled={time === null} onPress={() => onTime(null)} aria-label="Back to now">
          <Revert />
        </ActionButton>
        <Tooltip>Back to the current page</Tooltip>
      </TooltipTrigger>
      <div className="studio-timewarp__timeline" aria-label="Versions">
        {error && <Text>{error}</Text>}
        {!revisions && !error && <ProgressCircle size="S" isIndeterminate aria-label="Loading versions" />}
        {revisions && sorted.length === 0 && <Text UNSAFE_className="studio-timewarp__muted">No versions of this page yet: it shows as it is now.</Text>}
        {sorted.map((r) => {
          const created = Date.parse(r.created ?? '');
          return (
            <button
              key={r.id}
              type="button"
              className={`studio-timewarp__version${current?.id === r.id ? ' is-current' : ''}`}
              title={`${r.label ? `${r.label} · ` : ''}${r.comment ?? ''}`.trim() || r.name}
              onClick={() => !Number.isNaN(created) && onTime(created)}
            >
              <strong>{r.label || r.name}</strong>
              <span>{formatWhen(r.created)}</span>
            </button>
          );
        })}
        {future.map((l) => (
          <button
            key={l.path}
            type="button"
            className={`studio-timewarp__version studio-timewarp__launch${launchAt?.path === l.path ? ' is-current' : ''}`}
            title={`Release "${l.title}" goes live ${new Date(l.liveDate!).toLocaleString()}`}
            onClick={() => onTime(Date.parse(l.liveDate!))}
          >
            <strong>Release: {l.title}</strong>
            <span>{new Date(l.liveDate!).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span>
          </button>
        ))}
      </div>
      <Text UNSAFE_className="studio-timewarp__status">
        {time === null ? 'Current page' : launchAt ? `Release “${launchAt.title}” (live ${new Date(launchAt.liveDate!).toLocaleString()})` : time > now ? 'No release due by this time: current page' : current ? `Version ${current.label || current.name} (${formatWhen(current.created)})` : 'No version from before this time: current page'}
      </Text>
      {onCreateVersion && (
        <DialogTrigger type="popover">
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Create version">
              <SaveFloppy />
            </ActionButton>
            <Tooltip>Save a version of the page as it is now</Tooltip>
          </TooltipTrigger>
          {(close) => <CreateVersion onCreate={async (l, c) => { await onCreateVersion(l, c); setReload((n) => n + 1); }} close={close} />}
        </DialogTrigger>
      )}
    </div>
  );
}

function CreateVersion({ onCreate, close }: { onCreate(label: string, comment: string): Promise<void>; close(): void }) {
  const [label, setLabel] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    try {
      await onCreate(label.trim(), comment.trim());
      ToastQueue.positive('Version saved', { timeout: 3000 });
      close();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    } finally {
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>Create Version</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <TextField label="Label" value={label} onChange={setLabel} autoFocus />
          <TextField label="Comment" value={comment} onChange={setComment} />
        </Form>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isPending={busy}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
