import {
  ActionButton,
  Button,
  Checkbox,
  DialogContainer,
  Item,
  NumberField,
  Picker,
  Text,
  TextField,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import { useCallback, useEffect, useState } from 'react';
import type { DamClient, ProcessingProfile, ProfileRendition, WatermarkSetting } from './api';
import { WatermarkSection } from './watermark';
import { nodeName } from './dialogs';
import type { AssetsRoute } from './route';
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { routeUrl } from './route';
import { FormPage } from '../shell/FormPage';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

/**
 * Tools > Assets > Processing Profiles: renditions of your own that the assets of a folder get
 * besides the standard ones. A profile is applied in the folder's properties.
 */
export function ProfilesPage({ client, navigate: _navigate }: { client: DamClient; navigate(route: AssetsRoute): void }) {
  const [profiles, setProfiles] = useState<ProcessingProfile[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ProcessingProfile | 'new' | null>(null);
  const [deleting, setDeleting] = useState<ProcessingProfile | null>(null);
  const [watermark, setWatermark] = useState<WatermarkSetting | undefined>(undefined);
  const [admin, setAdmin] = useState(false);

  const load = useCallback(async () => {
    try {
      const r = await client.profiles();
      setProfiles(r.profiles);
      setWatermark(r.watermark);
      setAdmin(!!r.admin);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client]);

  useScreenTitle('Processing Profiles', 'Processing Profiles | Sensible Studio Media');
  useEffect(() => {
    void load();
  }, [load]);

  if (editing) {
    return (
      <div className="studio-assets__profiles">
        <ProfileDialog
          client={client}
          profile={editing === 'new' ? null : editing}
          taken={(profiles ?? []).map((p) => p.name)}
          onSaved={(saved) => {
            setEditing(saved);
            void load();
          }}
          close={() => setEditing(null)}
        />
      </div>
    );
  }

  return (
    <div className="studio-assets__profiles">
      <ToolBar
        url={routeUrl({ kind: 'profiles' })}
        primary={
          <Button variant="accent" onPress={() => setEditing('new')}>
            <Add />
            Create
          </Button>
        }
      />
      {error ? (
        <ErrorState title="Processing profiles could not be loaded">{error}</ErrorState>
      ) : profiles === null ? (
        <LoadingState label="Loading processing profiles" />
      ) : profiles?.length === 0 ? (
        <EmptyState title="No processing profiles">Create one, then choose it in the properties of a folder: its files get the profile's renditions.</EmptyState>
      ) : (
        <table className="studio-assets__profiles-table studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Name</th>
              <th>Renditions</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {profiles?.map((p) => (
              <tr key={p.path}>
                <td>{p.title}</td>
                <td>
                  <code>{p.name}</code>
                </td>
                <td>{p.renditions.map(describeRendition).join(', ') || '—'}</td>
                <td>
                  <ActionButton isQuiet aria-label={`Edit ${p.title}`} onPress={() => setEditing(p)}>
                    <Edit />
                  </ActionButton>
                  <ActionButton isQuiet aria-label={`Delete ${p.title}`} onPress={() => setDeleting(p)}>
                    <Delete />
                  </ActionButton>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {profiles !== null && !error && <WatermarkSection client={client} watermark={watermark} canEdit={admin} onSaved={setWatermark} />}
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog
            title="Delete profile"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteProfile(deleting.name);
              } catch (e) {
                setError(e instanceof Error ? e.message : String(e));
              }
              void load();
            }}
            onCancel={() => setDeleting(null)}
          >
            Folders that use <strong>{deleting.title}</strong> stop getting its renditions; the ones made so far go when an asset is
            processed again.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

const EMPTY: ProfileRendition = { name: '', width: 1200, height: 1200, format: 'jpeg', quality: 80 };

/** Video renditions (made by ffmpeg) keep the film's proportions: only the height counts. */
export function isVideoFormat(format: string): boolean {
  return format === 'mp4' || format === 'webm';
}

/** `banner.jpeg (1200 x 600, watermark)`, `web.mp4 (720p)` */
export function describeRendition(r: ProfileRendition): string {
  if (isVideoFormat(r.format)) return `${r.name}.${r.format} (${r.height}p)`;
  return `${r.name}.${r.format} (${r.width} x ${r.height}${r.watermark ? ', watermark' : ''})`;
}

/** Same rule as the server: lower case letters, digits, '.', '_' and '-'. */
export function renditionName(raw: string): string {
  return nodeName(raw).replace(/^[._-]+|[._-]+$/g, '');
}

export function profileProblems(name: string, renditions: ProfileRendition[]): string[] {
  const out: string[] = [];
  if (!name) out.push('The profile needs a title or name.');
  const seen = new Set<string>();
  renditions.forEach((r, i) => {
    const label = r.name || `Rendition ${i + 1}`;
    if (!r.name) out.push(`${label}: a name is required.`);
    else if (r.name === 'original' || r.name.startsWith('cq5dam.')) out.push(`${label}: this name is reserved.`);
    if (isVideoFormat(r.format)) {
      if (!(r.height >= 144 && r.height <= 2160)) out.push(`${label}: a video's height must be 144 to 2160.`);
    } else if (!(r.width >= 1 && r.width <= 8192 && r.height >= 1 && r.height <= 8192)) out.push(`${label}: width and height must be 1 to 8192.`);
    const file = `${r.name}.${r.format}`;
    if (r.name && seen.has(file)) out.push(`${label}: ${file} is listed twice.`);
    seen.add(file);
  });
  return out;
}

function ProfileDialog({
  client,
  profile,
  taken,
  onSaved,
  close,
}: {
  client: DamClient;
  profile: ProcessingProfile | null;
  taken: string[];
  onSaved(profile: ProcessingProfile): void;
  close(): void;
}) {
  const [title, setTitle] = useState(profile?.title ?? '');
  const [renditions, setRenditions] = useState<ProfileRendition[]>(profile?.renditions.map((r) => ({ ...r })) ?? [{ ...EMPTY }]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const name = profile?.name ?? nodeName(title);
  const problems = profileProblems(name, renditions);
  const clash = !profile && taken.includes(name);
  const set = (i: number, patch: Partial<ProfileRendition>) => setRenditions((rs) => rs.map((r, k) => (k === i ? { ...r, ...patch } : r)));
  const dirty = title !== (profile?.title ?? '') || JSON.stringify(renditions) !== JSON.stringify(profile?.renditions ?? [{ ...EMPTY }]);
  const reset = () => {
    setTitle(profile?.title ?? '');
    setRenditions(profile?.renditions.map((r) => ({ ...r })) ?? [{ ...EMPTY }]);
    setError(null);
  };
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const saved = await client.saveProfile(name, title.trim() || name, renditions);
      onSaved(saved);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind="Processing profile"
      title={profile ? `Edit ${profile.title}` : 'Create Processing Profile'}
      path={profile?.path}
      dirty={dirty}
      busy={busy}
      canSave={problems.length === 0 && !clash}
      onReset={reset}
      onSave={save}
      onClose={close}
      error={error}
      wide
    >
        <TextField label="Title" value={title} onChange={setTitle} width="100%" autoFocus description={`Name: ${name || '—'}`} />
        <h3 className="studio-assets__profiles-sub">Renditions</h3>
        <div className="studio-assets__profiles-rows">
          {renditions.map((r, i) => (
            <fieldset key={i} className="studio-assets__profiles-row" aria-label={`Rendition ${i + 1}`}>
              <TextField label="Name" value={r.name} onChange={(v) => set(i, { name: renditionName(v) })} width="size-2000" />
              {isVideoFormat(r.format) ? (
                <NumberField
                  label="Height (lines)"
                  value={r.height}
                  minValue={144}
                  maxValue={2160}
                  onChange={(v) => set(i, { height: v })}
                  width="size-2400"
                  description="Videos only; never enlarged"
                />
              ) : (
                <>
                  <NumberField label="Width" value={r.width} minValue={1} maxValue={8192} onChange={(v) => set(i, { width: v })} width="size-1250" />
                  <NumberField label="Height" value={r.height} minValue={1} maxValue={8192} onChange={(v) => set(i, { height: v })} width="size-1250" />
                </>
              )}
              <Picker
                label="Format"
                selectedKey={r.format}
                onSelectionChange={(k) => {
                  const format = k as ProfileRendition['format'];
                  // a video starts at 720 lines, a picture at its box; videos get no watermark
                  set(i, isVideoFormat(format) && !isVideoFormat(r.format) ? { format, height: 720, watermark: false } : { format });
                }}
                width="size-1250"
              >
                <Item key="jpeg">JPEG</Item>
                <Item key="png">PNG</Item>
                <Item key="webp">WebP</Item>
                <Item key="mp4">MP4 video</Item>
                <Item key="webm">WebM video</Item>
              </Picker>
              {!isVideoFormat(r.format) && (
                <NumberField
                  label="Quality"
                  value={r.quality}
                  minValue={1}
                  maxValue={100}
                  isDisabled={r.format === 'png'}
                  onChange={(v) => set(i, { quality: v })}
                  width="size-1250"
                />
              )}
              {!isVideoFormat(r.format) && (
                <Checkbox isSelected={!!r.watermark} onChange={(v) => set(i, { watermark: v || undefined })}>
                  Watermark
                </Checkbox>
              )}
              <ActionButton isQuiet aria-label={`Remove rendition ${i + 1}`} onPress={() => setRenditions((rs) => rs.filter((_, k) => k !== i))}>
                <Delete />
              </ActionButton>
            </fieldset>
          ))}
        </div>
        <ActionButton onPress={() => setRenditions((rs) => [...rs, { ...EMPTY }])} isDisabled={renditions.length >= 20}>
          <Add />
          <Text>Add Rendition</Text>
        </ActionButton>
        {/* an untouched new form is not wrong yet: it says what is missing once something was typed */}
        {(dirty || !!profile) && (problems.length > 0 || clash) && (
          <ul className="studio-assets__dialog-error">
            {clash && <li>A profile named {name} exists already.</li>}
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}
    </FormPage>
  );
}
