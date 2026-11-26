import { Checkbox, TextArea, TextField } from '@adobe/react-spectrum';
import { useState } from 'react';
import { StudioDialog } from '../shell/StudioDialog';
import type { TemplatesClient, TemplateSummary } from './api';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** What Save sends for each template: only those whose title or description would change. */
export function templateChanges(templates: TemplateSummary[], titles: Record<string, string>, description: string | null): Array<{ path: string; title: string; description: string }> {
  const out: Array<{ path: string; title: string; description: string }> = [];
  for (const t of templates) {
    const title = (titles[t.path] ?? t.title).trim();
    const desc = description === null ? (t.description ?? '') : description.trim();
    if (title !== t.title || desc !== (t.description ?? '')) out.push({ path: t.path, title, description: desc });
  }
  return out;
}

/**
 * Properties of several templates at once, as AEM's Properties on a selection: each template's title, and one
 * description for all of them when asked. Thumbnails stay with each template's own Properties.
 */
export function BulkTemplatePropertiesDialog({ client, templates, onDone, close }: { client: Pick<TemplatesClient, 'saveProperties'>; templates: TemplateSummary[]; onDone(saved: number): void; close(): void }) {
  const [titles, setTitles] = useState<Record<string, string>>({});
  const [same, setSame] = useState(false);
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const changes = templateChanges(templates, titles, same ? description : null);
  const untitled = templates.some((t) => !(titles[t.path] ?? t.title).trim());

  const save = async () => {
    setBusy(true);
    setError(null);
    let saved = 0;
    try {
      for (const c of changes) {
        await client.saveProperties(c.path, c.title, c.description);
        saved++;
      }
      onDone(saved);
      close();
    } catch (e) {
      setError(`${saved} of ${changes.length} saved. ${message(e)}`);
      if (saved > 0) onDone(saved);
      setBusy(false);
    }
  };

  return (
    <StudioDialog title="Properties" subject={`${templates.length} templates`} size="M" primaryLabel="Save" onCancel={close} onPrimary={() => void save()} primaryDisabled={changes.length === 0 || untitled} busy={busy}>
      <div className="studio-bulktemplates">
        {templates.map((t) => (
          <TextField
            key={t.path}
            label={`Title of ${t.name}`}
            value={titles[t.path] ?? t.title}
            onChange={(v) => setTitles({ ...titles, [t.path]: v })}
            isDisabled={busy}
            width="100%"
            validationState={(titles[t.path] ?? t.title).trim() ? undefined : 'invalid'}
            errorMessage="A template needs a title"
          />
        ))}
        <Checkbox isSelected={same} onChange={setSame} isDisabled={busy}>
          The same description on all of them
        </Checkbox>
        {same && <TextArea label="Description" value={description} onChange={setDescription} isDisabled={busy} width="100%" description="Replaces the description each template has; empty removes it." />}
        <p className="studio-bulktemplates__note">The thumbnail is in each template's own Properties.</p>
        {error && (
          <p className="studio-bulktemplates__error" role="alert">
            {error}
          </p>
        )}
      </div>
    </StudioDialog>
  );
}
