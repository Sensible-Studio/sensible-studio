import { Checkbox, Item, Picker, Text, TextArea, TextField } from '@adobe/react-spectrum';
import { useState } from 'react';
import { AssetTagsField } from '../assets/tagsField';
import type { PostEntry } from '../granite';
import { StudioDialog } from '../shell/StudioDialog';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

type Tri = 'keep' | 'yes' | 'no';
type When = 'keep' | 'set' | 'clear';

/** What a bulk edit changes; `null` fields are left as each page has them. */
export interface BulkPageEdit {
  /** tags added to (or, with `replaceTags`, put instead of) each page's own */
  tags: string[];
  replaceTags: boolean;
  hideInNav: Tri;
  onTime: { mode: When; value: string };
  offTime: { mode: When; value: string };
}

export const NO_EDIT: BulkPageEdit = { tags: [], replaceTags: false, hideInNav: 'keep', onTime: { mode: 'keep', value: '' }, offTime: { mode: 'keep', value: '' } };

export function editTouches(e: BulkPageEdit): boolean {
  return e.tags.length > 0 || e.replaceTags || e.hideInNav !== 'keep' || e.onTime.mode !== 'keep' || e.offTime.mode !== 'keep';
}

/** A local date-time field's value as the instant it means, or null. */
function instant(local: string): string | null {
  const d = new Date(local);
  return local && !Number.isNaN(d.getTime()) ? d.toISOString() : null;
}

/** The Sling POST of one page's `jcr:content` for an edit; `current` are the tags the page has now. */
export function bulkEntries(e: BulkPageEdit, current: string[]): PostEntry[] {
  const out: PostEntry[] = [];
  if (e.tags.length > 0 || e.replaceTags) {
    const tags = e.replaceTags ? e.tags : [...current, ...e.tags.filter((t) => !current.includes(t))];
    if (tags.length === 0) out.push({ name: './cq:tags@Delete', value: 'true' });
    else {
      for (const t of tags) out.push({ name: './cq:tags', value: t });
      out.push({ name: './cq:tags@TypeHint', value: 'String[]' });
    }
  }
  if (e.hideInNav === 'yes') out.push({ name: './hideInNav', value: 'true' }, { name: './hideInNav@TypeHint', value: 'Boolean' });
  else if (e.hideInNav === 'no') out.push({ name: './hideInNav@Delete', value: 'true' });
  for (const [name, w] of [
    ['onTime', e.onTime],
    ['offTime', e.offTime],
  ] as const) {
    const at = instant(w.value);
    if (w.mode === 'set' && at) out.push({ name: `./${name}`, value: at }, { name: `./${name}@TypeHint`, value: 'Date' });
    else if (w.mode === 'clear') out.push({ name: `./${name}@Delete`, value: 'true' });
  }
  return out;
}

/** The page was changed now, by this user: what makes it "modified" for publishing, as any edit does. */
export function modifiedEntries(user: string | null | undefined, now = new Date()): PostEntry[] {
  const out: PostEntry[] = [
    { name: './cq:lastModified', value: now.toISOString() },
    { name: './cq:lastModified@TypeHint', value: 'Date' },
  ];
  if (user) out.push({ name: './cq:lastModifiedBy', value: user });
  return out;
}

export interface BulkPagesClient {
  /** Sling POST to a node */
  post(path: string, entries: PostEntry[]): Promise<void>;
  /** the tags of a page (`jcr:content/cq:tags`) */
  tagsOf(path: string): Promise<string[]>;
}

export const httpBulkPages = (post: (path: string, entries: PostEntry[]) => Promise<void>): BulkPagesClient => ({
  post,
  async tagsOf(path) {
    const res = await fetch(`${encodeURI(path)}/jcr:content.json`, { credentials: 'same-origin', cache: 'no-store' });
    if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
    const c = (await res.json()) as Record<string, unknown>;
    const t = c['cq:tags'];
    return Array.isArray(t) ? t.map(String) : typeof t === 'string' ? [t] : [];
  },
});

function TimeField({ label, value, onChange, isDisabled }: { label: string; value: { mode: When; value: string }; onChange(v: { mode: When; value: string }): void; isDisabled: boolean }) {
  return (
    <div className="studio-bulkpages__row">
      <Picker label={label} selectedKey={value.mode} onSelectionChange={(k) => onChange({ ...value, mode: k as When })} isDisabled={isDisabled} width="size-2400">
        <Item key="keep">Leave as it is</Item>
        <Item key="set">Set to…</Item>
        <Item key="clear">Remove</Item>
      </Picker>
      {value.mode === 'set' && (
        <label className="studio-bulkpages__when">
          <span>{label}</span>
          <input type="datetime-local" aria-label={`${label}: date and time`} value={value.value} onChange={(e) => onChange({ ...value, value: e.target.value })} disabled={isDisabled} />
        </label>
      )}
    </div>
  );
}

/**
 * Properties of several pages at once, as AEM's bulk edit of page properties: the fields that make sense
 * for many pages (tags, Hide in Navigation, On Time, Off Time). What is not touched stays as each page has it.
 */
export function BulkPagePropertiesDialog({ client, pages, user, onDone, close }: { client: BulkPagesClient; pages: Array<{ path: string; title: string }>; user?: string | null; onDone(saved: number): void; close(): void }) {
  const [edit, setEdit] = useState<BulkPageEdit>(NO_EDIT);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const timeMissing = (edit.onTime.mode === 'set' && !instant(edit.onTime.value)) || (edit.offTime.mode === 'set' && !instant(edit.offTime.value));
  const on = edit.onTime.mode === 'set' ? instant(edit.onTime.value) : null;
  const off = edit.offTime.mode === 'set' ? instant(edit.offTime.value) : null;
  const order = on && off && on >= off;

  const save = async () => {
    setBusy(true);
    setError(null);
    let saved = 0;
    try {
      for (const p of pages) {
        const current = edit.tags.length > 0 && !edit.replaceTags ? await client.tagsOf(p.path) : [];
        await client.post(`${p.path}/jcr:content`, [...bulkEntries(edit, current), ...modifiedEntries(user)]);
        saved++;
      }
      onDone(saved);
      close();
    } catch (e) {
      setError(`${saved} of ${pages.length} saved. ${message(e)}`);
      if (saved > 0) onDone(saved);
      setBusy(false);
    }
  };

  return (
    <StudioDialog
      title="Properties"
      subject={`${pages.length} pages`}
      size="M"
      primaryLabel="Save"
      onCancel={close}
      onPrimary={() => void save()}
      primaryDisabled={!editTouches(edit) || timeMissing || !!order}
      busy={busy}
    >
      <div className="studio-bulkpages">
        <p className="studio-bulkpages__intro">What you set here is saved on every selected page; everything else stays as each page has it. Titles and other fields of one page are in its own Properties.</p>
        <AssetTagsField
          label="Tags"
          values={edit.tags}
          onChange={(tags) => setEdit({ ...edit, tags })}
          description={edit.replaceTags ? 'Put instead of the tags of every page.' : "Added to each page's own tags."}
        />
        <Checkbox isSelected={edit.replaceTags} onChange={(v) => setEdit({ ...edit, replaceTags: v })} isDisabled={busy}>
          Replace the tags the pages have
        </Checkbox>
        <Picker label="Hide in Navigation" selectedKey={edit.hideInNav} onSelectionChange={(k) => setEdit({ ...edit, hideInNav: k as Tri })} isDisabled={busy} width="size-2400">
          <Item key="keep">Leave as it is</Item>
          <Item key="yes">Hide</Item>
          <Item key="no">Show</Item>
        </Picker>
        <TimeField label="On Time" value={edit.onTime} onChange={(onTime) => setEdit({ ...edit, onTime })} isDisabled={busy} />
        <TimeField label="Off Time" value={edit.offTime} onChange={(offTime) => setEdit({ ...edit, offTime })} isDisabled={busy} />
        {order && <p className="studio-bulkpages__error">The Off Time must be after the On Time.</p>}
        {error && (
          <p className="studio-bulkpages__error" role="alert">
            {error}
          </p>
        )}
      </div>
    </StudioDialog>
  );
}

/** Create ▾ Version for several pages: the same label and comment on a version of each. */
export function BulkVersionDialog({
  pages,
  createVersion,
  onDone,
  close,
}: {
  pages: Array<{ path: string; title: string }>;
  createVersion(path: string, label?: string, comment?: string): Promise<unknown>;
  onDone(created: number): void;
  close(): void;
}) {
  const [label, setLabel] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const create = async () => {
    setBusy(true);
    setError(null);
    let made = 0;
    try {
      for (const p of pages) {
        await createVersion(p.path, label.trim() || undefined, comment.trim() || undefined);
        made++;
      }
      onDone(made);
      close();
    } catch (e) {
      setError(`${made} of ${pages.length} created. ${message(e)}`);
      if (made > 0) onDone(made);
      setBusy(false);
    }
  };
  return (
    <StudioDialog title="Create Version" subject={`${pages.length} pages`} size="S" primaryLabel="Create" onCancel={close} onPrimary={() => void create()} busy={busy}>
      <div className="studio-bulkpages">
        <Text>A version of each selected page, as it is now.</Text>
        <TextField label="Label" value={label} onChange={setLabel} isDisabled={busy} width="100%" description="The same on every page" />
        <TextArea label="Comment" value={comment} onChange={setComment} isDisabled={busy} width="100%" />
        {error && (
          <p className="studio-bulkpages__error" role="alert">
            {error}
          </p>
        )}
      </div>
    </StudioDialog>
  );
}
