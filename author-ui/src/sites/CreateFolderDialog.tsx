import { Form, TextField } from '@adobe/react-spectrum';
import { useState } from 'react';
import type { SiteItem } from '../editor/api/StudioClient';
import { StudioDialog } from '../shell/StudioDialog';
import { nameFromTitle } from './names';

interface Props {
  parent: string;
  create(title: string, name?: string): Promise<SiteItem>;
  onClose(): void;
  onCreated(folder: SiteItem): void;
}

/** Create ▸ Folder: a place to keep pages or experience fragments in. */
export function CreateFolderDialog({ parent, create, onClose, onCreated }: Props) {
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const effectiveName = name.trim() || nameFromTitle(title);
  const nameOk = !name.trim() || /^[A-Za-z0-9_.~-]+$/.test(name.trim());
  const ok = title.trim().length > 0 && effectiveName.length > 0 && nameOk;
  const run = async () => {
    if (!ok || busy) return;
    setBusy(true);
    setError(null);
    try {
      onCreated(await create(title.trim(), effectiveName));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog title="Create Folder" subject={parent} size="S" primaryLabel="Create" onCancel={onClose} onPrimary={() => void run()} primaryDisabled={!ok} busy={busy}>
      <Form onSubmit={(e) => (e.preventDefault(), void run())}>
        <TextField label="Title" value={title} onChange={setTitle} isRequired autoFocus />
        <TextField
          label="Name"
          value={name}
          onChange={setName}
          placeholder={nameFromTitle(title) || 'from the title'}
          validationState={nameOk ? undefined : 'invalid'}
          errorMessage="Letters, digits and - _ . ~ only"
          description={effectiveName ? `${parent}/${effectiveName}` : undefined}
        />
      </Form>
      {error && (
        <p role="alert" className="studio-dialog-standard__error" style={{ margin: 0, color: 'var(--spectrum-semantic-negative-color-text-small, #c9252d)', fontSize: 13 }}>
        {error}
        </p>
      )}
    </StudioDialog>
  );
}
