import {
  ActionButton,
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  DialogContainer,
  Divider,
  Heading,
  Item,
  Picker,
  TextField,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import { useCallback, useEffect, useState } from 'react';
import type { DamClient, GalleryPreset } from './api';
import { StudioConfirmDialog, StudioDialog } from '../shell/StudioDialog';

export const THUMB_OPTIONS: Array<{ key: GalleryPreset['thumbs']; label: string }> = [
  { key: 'below', label: 'Below the picture' },
  { key: 'left', label: 'Left of the picture' },
  { key: 'right', label: 'Right of the picture' },
  { key: 'dots', label: 'Dots instead of thumbnails' },
  { key: 'none', label: 'None' },
];

export const RATIO_OPTIONS: Array<{ key: GalleryPreset['ratio'] | 'own'; label: string }> = [
  { key: 'own', label: 'Each picture’s own' },
  { key: '16:9', label: '16:9' },
  { key: '3:2', label: '3:2' },
  { key: '4:3', label: '4:3' },
  { key: '1:1', label: '1:1' },
];

/** "Thumbnails below · arrows · 16:9 · enlarging" */
export function describeGalleryPreset(p: Pick<GalleryPreset, 'thumbs' | 'arrows' | 'ratio' | 'zoom'>): string {
  const thumbs = p.thumbs === 'none' ? 'No thumbnails' : p.thumbs === 'dots' ? 'Dots' : `Thumbnails ${p.thumbs === 'below' ? 'below' : `on the ${p.thumbs}`}`;
  return [thumbs, p.arrows ? 'arrows' : '', p.ratio || 'own shapes', p.zoom ? 'enlarging' : ''].filter(Boolean).join(' · ');
}

const EMPTY: Omit<GalleryPreset, 'name'> = { title: '', thumbs: 'below', arrows: false, ratio: '', zoom: false };

/**
 * Gallery presets (AEM's viewer presets for image sets), on the Image Presets page: how a gallery
 * looks, saved under a name and chosen in the Responsive Image component or its policy.
 */
export function GalleryPresetsSection({ client }: { client: DamClient }) {
  const [list, setList] = useState<GalleryPreset[] | null>(null);
  const [admin, setAdmin] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<GalleryPreset | 'new' | null>(null);
  const [deleting, setDeleting] = useState<GalleryPreset | null>(null);
  const load = useCallback(async () => {
    try {
      const r = await client.galleryPresets();
      setList(r.presets);
      setAdmin(!!r.admin);
      setError(null);
    } catch (e) {
      setList([]);
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client]);
  useEffect(() => {
    void load();
  }, [load]);
  return (
    <section className="studio-assets__gallerypresets" aria-label="Gallery Presets">
      <div className="studio-assets__watermark-head">
        <h3 className="studio-assets__profiles-sub">Gallery Presets</h3>
        {admin && (
          <ActionButton isQuiet aria-label="Create Gallery Preset" onPress={() => setEditing('new')}>
            <Add />
            Create
          </ActionButton>
        )}
      </div>
      <p className="studio-assets__muted">
        How an image set shown as a gallery looks, saved under a name: choose it in the Responsive Image component (tab Gallery) or in its
        template’s policy. Options set on the component win over its preset.
      </p>
      {error && <p className="studio-assets__dialog-error">{error}</p>}
      {list !== null && list.length > 0 && (
        <table className="studio-assets__profiles-table studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Name</th>
              <th>Look</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {list.map((p) => (
              <tr key={p.name}>
                <td>{p.title}</td>
                <td>
                  <code>{p.name}</code>
                </td>
                <td>{describeGalleryPreset(p)}</td>
                <td>
                  {admin && (
                    <>
                      <ActionButton isQuiet aria-label={`Edit ${p.title}`} onPress={() => setEditing(p)}>
                        <Edit />
                      </ActionButton>
                      <ActionButton isQuiet aria-label={`Delete ${p.title}`} onPress={() => setDeleting(p)}>
                        <Delete />
                      </ActionButton>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {list !== null && list.length === 0 && !error && <p className="studio-assets__muted">No gallery presets yet.</p>}
      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && (
          <GalleryPresetDialog
            client={client}
            preset={editing === 'new' ? null : editing}
            onSaved={() => {
              setEditing(null);
              void load();
            }}
            close={() => setEditing(null)}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog
            title="Delete gallery preset"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteGalleryPreset(deleting.name);
              } catch (e) {
                setError(e instanceof Error ? e.message : String(e));
              }
              void load();
            }}
            onCancel={() => setDeleting(null)}
          >
            Galleries that use <strong>{deleting.title}</strong> go back to their own options or the template’s.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </section>
  );
}

function GalleryPresetDialog({
  client,
  preset,
  onSaved,
  close,
}: {
  client: DamClient;
  preset: GalleryPreset | null;
  onSaved(p: GalleryPreset): void;
  close(): void;
}) {
  const [p, setP] = useState<Omit<GalleryPreset, 'name'>>(preset ? { ...preset } : { ...EMPTY });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      onSaved(await client.saveGalleryPreset({ ...p, name: preset?.name }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>{preset ? `Edit ${preset.title}` : 'Create Gallery Preset'}</Heading>
      <Divider />
      <Content>
        <div className="studio-assets__watermark-form">
          <TextField label="Title" value={p.title} onChange={(title) => setP({ ...p, title })} isRequired width="100%" autoFocus />
          <Picker label="Thumbnails" selectedKey={p.thumbs} onSelectionChange={(k) => setP({ ...p, thumbs: k as GalleryPreset['thumbs'] })} width="100%">
            {THUMB_OPTIONS.map((o) => (
              <Item key={o.key}>{o.label}</Item>
            ))}
          </Picker>
          <Picker
            label="Shape of the pictures"
            selectedKey={p.ratio || 'own'}
            onSelectionChange={(k) => setP({ ...p, ratio: (k === 'own' ? '' : k) as GalleryPreset['ratio'] })}
            width="100%"
          >
            {RATIO_OPTIONS.map((o) => (
              <Item key={o.key}>{o.label}</Item>
            ))}
          </Picker>
          <Checkbox isSelected={p.arrows} onChange={(arrows) => setP({ ...p, arrows })}>
            Arrows on the picture
          </Checkbox>
          <Checkbox isSelected={p.zoom} onChange={(zoom) => setP({ ...p, zoom })}>
            Let visitors enlarge the picture
          </Checkbox>
          <p className="studio-assets__muted">{describeGalleryPreset(p)}</p>
          {error && <p className="studio-assets__dialog-error">{error}</p>}
        </div>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void save()} isDisabled={busy || !p.title.trim()}>
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
