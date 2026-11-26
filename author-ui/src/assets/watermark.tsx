import { ActionButton, Button, ButtonGroup, Content, Dialog, DialogContainer, Divider, Heading, Item, Picker, Slider } from '@adobe/react-spectrum';
import Edit from '@spectrum-icons/workflow/Edit';
import { useState } from 'react';
import type { DamClient, WatermarkPosition, WatermarkSetting } from './api';
import { DAM_ROOT } from './api';
import { PicturePickerDialog } from './imageSet';

export const WATERMARK_POSITIONS: Array<{ key: WatermarkPosition; label: string }> = [
  { key: 'center', label: 'Centre' },
  { key: 'top-left', label: 'Top left' },
  { key: 'top-right', label: 'Top right' },
  { key: 'bottom-left', label: 'Bottom left' },
  { key: 'bottom-right', label: 'Bottom right' },
  { key: 'tile', label: 'Repeated over the picture' },
];

export function positionLabel(p: WatermarkPosition): string {
  return WATERMARK_POSITIONS.find((x) => x.key === p)?.label ?? p;
}

/** One line saying what the watermark is, for the Processing Profiles page. */
export function watermarkSummary(w: WatermarkSetting | undefined): string {
  if (!w?.image) return 'No watermark set: renditions that ask for one are made without it.';
  if (!w.ready) return `${w.image} is missing or not a PNG, JPEG or WebP picture: renditions are made without a watermark.`;
  return `${w.image} · ${positionLabel(w.position)} · ${w.opacity}% opacity · ${w.size}% of the width`;
}

/**
 * The watermark (AEM's Assets watermark setting): a picture from Media drawn on the renditions of
 * a Processing Profile marked "Watermark". Administrators change it.
 */
export function WatermarkSection({
  client,
  watermark,
  canEdit,
  onSaved,
}: {
  client: DamClient;
  watermark: WatermarkSetting | undefined;
  canEdit: boolean;
  onSaved(w: WatermarkSetting): void;
}) {
  const [editing, setEditing] = useState(false);
  return (
    <section className="studio-assets__watermark" aria-label="Watermark">
      <div className="studio-assets__watermark-head">
        <h3 className="studio-assets__profiles-sub">Watermark</h3>
        {canEdit && (
          <ActionButton isQuiet onPress={() => setEditing(true)}>
            <Edit />
            Edit
          </ActionButton>
        )}
      </div>
      <div className="studio-assets__watermark-body">
        {watermark?.thumbnail && <img src={watermark.thumbnail} alt="" className="studio-assets__watermark-thumb" />}
        <p className={watermark?.image && !watermark.ready ? 'studio-assets__dialog-error' : 'studio-assets__muted'}>{watermarkSummary(watermark)}</p>
      </div>
      <p className="studio-assets__muted">
        Drawn on the renditions of a profile with <strong>Watermark</strong> ticked. Files keep the mark they were processed with: reprocess them after a change.
      </p>
      <DialogContainer onDismiss={() => setEditing(false)}>
        {editing && (
          <WatermarkDialog
            client={client}
            initial={watermark ?? { image: null, position: 'center', opacity: 50, size: 25 }}
            onSaved={(w) => {
              setEditing(false);
              onSaved(w);
            }}
            close={() => setEditing(false)}
          />
        )}
      </DialogContainer>
    </section>
  );
}

function WatermarkDialog({
  client,
  initial,
  onSaved,
  close,
}: {
  client: DamClient;
  initial: WatermarkSetting;
  onSaved(w: WatermarkSetting): void;
  close(): void;
}) {
  const [w, setW] = useState<WatermarkSetting>({ ...initial });
  const [picking, setPicking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async (setting: WatermarkSetting) => {
    setBusy(true);
    setError(null);
    try {
      onSaved(await client.saveWatermark(setting));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const folder = w.image ? w.image.substring(0, w.image.lastIndexOf('/')) : DAM_ROOT;
  return (
    <Dialog size="M">
      <Heading>Watermark</Heading>
      <Divider />
      <Content>
        <div className="studio-assets__watermark-form">
          <div className="studio-assets__watermark-pick">
            {w.thumbnail && <img src={w.thumbnail} alt="" className="studio-assets__watermark-thumb" />}
            <div>
              <strong>Picture</strong>
              <p className="studio-assets__muted">{w.image ?? 'None chosen. A PNG with transparency works best.'}</p>
            </div>
            <ActionButton onPress={() => setPicking(true)}>{w.image ? 'Change' : 'Choose'}</ActionButton>
          </div>
          <Picker
            label="Position"
            selectedKey={w.position}
            onSelectionChange={(k) => setW({ ...w, position: k as WatermarkPosition })}
            width="100%"
          >
            {WATERMARK_POSITIONS.map((p) => (
              <Item key={p.key}>{p.label}</Item>
            ))}
          </Picker>
          <Slider label="Opacity" value={w.opacity} minValue={1} maxValue={100} onChange={(v) => setW({ ...w, opacity: v })} width="100%" formatOptions={{ style: 'unit', unit: 'percent' }} />
          <Slider label="Size (share of the width)" value={w.size} minValue={5} maxValue={100} onChange={(v) => setW({ ...w, size: v })} width="100%" formatOptions={{ style: 'unit', unit: 'percent' }} />
          {error && <p className="studio-assets__dialog-error">{error}</p>}
        </div>
      </Content>
      <ButtonGroup>
        {initial.image && (
          <Button variant="negative" isDisabled={busy} onPress={() => void save({ ...w, image: null })}>
            Remove
          </Button>
        )}
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void save(w)} isDisabled={busy || !w.image}>
          Save
        </Button>
      </ButtonGroup>
      <DialogContainer onDismiss={() => setPicking(false)}>
        {picking && (
          <PicturePickerDialog
            client={client}
            start={folder}
            taken={[]}
            single={{ title: 'Choose the watermark', action: 'Choose' }}
            onPick={(chosen) => {
              const first = chosen[0];
              if (first) setW({ ...w, image: first.path, thumbnail: first.thumbnail ?? null, ready: true });
              setPicking(false);
            }}
            close={() => setPicking(false)}
          />
        )}
      </DialogContainer>
    </Dialog>
  );
}
