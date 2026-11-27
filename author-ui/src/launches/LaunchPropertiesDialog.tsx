import { Button, ButtonGroup, Content, Divider, Form, Heading, Text, TextArea, TextField, useDialogContainer } from '@adobe/react-spectrum';
import { useState } from 'react';
import { StudioPickerDialog } from '../shell/StudioDialog';
import type { Launch, LaunchesClient } from './api';

/** `2026-12-04T09:00` in the browser's time zone, for a datetime-local field. */
function localValue(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * The properties of a release after it was created: its title, description and live date. (Production ready has
 * its switch on the row; the pages are edited in the page editor.)
 */
export function LaunchPropertiesDialog({ client, launch, onSaved }: { client: LaunchesClient; launch: Launch; onSaved(): void }) {
  const dialog = useDialogContainer();
  const [title, setTitle] = useState(launch.title);
  const [description, setDescription] = useState(launch.description ?? '');
  const [liveDate, setLiveDate] = useState(localValue(launch.liveDate));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const at = liveDate ? new Date(liveDate) : null;
  const dateOk = !at || !Number.isNaN(at.getTime());
  const dirty = title.trim() !== launch.title || description !== (launch.description ?? '') || liveDate !== localValue(launch.liveDate);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await client.update(launch.path, { title: title.trim(), description, liveDate: at ? at.toISOString() : '' });
      onSaved();
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <StudioPickerDialog size="M">
      <Heading>Release properties</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            if (dirty && title.trim() && dateOk) void save();
          }}
        >
          <TextField label="Release title" value={title} onChange={setTitle} isRequired autoFocus />
          <TextArea label="Description" value={description} onChange={setDescription} />
          <TextField
            type="datetime-local"
            label="Live date"
            value={liveDate}
            onChange={setLiveDate}
            description={launch.productionReady ? 'The release goes live by itself at this date' : 'When the release is meant to go live (for information; going live is manual)'}
          />
        </Form>
        {launch.promoted && <Text UNSAFE_className="studio-publish__hint">This release has gone live. Its properties are kept for the record.</Text>}
        {error && (
          <div className="studio-publish__error" role="alert">
            {error}
          </div>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void save()} isDisabled={busy || !dirty || !title.trim() || !dateOk}>
          Save
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}
