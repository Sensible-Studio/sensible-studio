import { Checkbox, Item, Picker, TextField } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { StudioDialog } from '../shell/StudioDialog';
import { partsOf, xfFolderFor, type Generation, type PromptTemplate, type VariationsClient, type XfTemplateChoice } from './api';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** The title a fragment gets first: the first title-like part of the first variation chosen. */
export function defaultXfTitle(g: Generation, chosen: number[], template: PromptTemplate): string {
  const v = g.variations?.[Math.min(...chosen)] ?? {};
  const first = template.fields.map((f) => (v[f.key] ?? '').trim()).find(Boolean) ?? template.title;
  return first.length > 60 ? `${first.slice(0, 59)}…` : first;
}

/**
 * AEM's "Export to Experience Fragment" of Generate Variations: the variations chosen become the variations of a new
 * experience fragment (the first its master), each made of the site's Title, Text and Button components.
 */
export function ExportToXfDialog({
  client,
  generation,
  template,
  onCancel,
  onDone,
}: {
  client: VariationsClient;
  generation: Generation;
  template: PromptTemplate;
  onCancel(): void;
  onDone(result: { fragment: string; variations: string[] }): void;
}) {
  const all = (generation.variations ?? []).map((_, i) => i);
  const [chosen, setChosen] = useState<number[]>(generation.favorites.length ? [...generation.favorites] : all);
  const [parent, setParent] = useState(xfFolderFor(generation.input?.page));
  const [title, setTitle] = useState(() => defaultXfTitle(generation, generation.favorites.length ? generation.favorites : all, template));
  const [templates, setTemplates] = useState<XfTemplateChoice[] | null>(null);
  const [xfTemplate, setXfTemplate] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    client.xfTemplates().then(
      (t) => {
        if (!live) return;
        setTemplates(t);
        const site = parent.split('/')[3];
        setXfTemplate((t.find((x) => site && x.path.startsWith(`/conf/${site}/`)) ?? t[0])?.path ?? null);
      },
      (e: unknown) => live && (setTemplates([]), setError(message(e))),
    );
    return () => {
      live = false;
    };
    // the folder only proposes a template once
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client]);

  const ready = chosen.length > 0 && !!title.trim() && !!xfTemplate && parent.trim().startsWith('/content/experience-fragments');
  const submit = async () => {
    if (!ready || !xfTemplate) return;
    setBusy(true);
    setError(null);
    try {
      onDone(
        await client.exportToXf({
          parent: parent.trim().replace(/\/+$/, ''),
          title: title.trim(),
          template: xfTemplate,
          parts: partsOf(generation.variations ?? [], chosen, template.fields),
        }),
      );
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog title="Export to Experience Fragment" subject={template.title} primaryLabel="Export" onCancel={onCancel} onPrimary={() => void submit()} busy={busy}>
      <div className="studio-variations__export">
        <fieldset>
          <legend>Variations</legend>
          {all.map((i) => (
            <Checkbox
              key={i}
              isSelected={chosen.includes(i)}
              onChange={(on) => setChosen((c) => (on ? [...c, i].sort((a, b) => a - b) : c.filter((x) => x !== i)))}
            >
              {`${i + 1}. ${(generation.variations?.[i]?.[template.fields[0].key] ?? '').slice(0, 70)}`}
            </Checkbox>
          ))}
        </fieldset>
        <p className="studio-variations__muted">
          Each becomes a variation of the fragment (the first its master), made of the site&apos;s Title, Text and Button components.
        </p>
        <TextField label="Title" isRequired value={title} onChange={setTitle} width="100%" />
        <TextField
          label="Folder"
          isRequired
          value={parent}
          onChange={setParent}
          description="Under /content/experience-fragments"
          width="100%"
        />
        <Picker
          label="Template"
          isRequired
          items={templates ?? []}
          selectedKey={xfTemplate}
          onSelectionChange={(k) => setXfTemplate(k === null ? null : String(k))}
          isDisabled={!templates}
          placeholder={templates && templates.length === 0 ? 'No experience fragment template' : 'Choose'}
          width="100%"
        >
          {(t) => <Item key={t.path}>{t.title}</Item>}
        </Picker>
        {error && (
          <p className="studio-variations__problem" role="alert">
            {error}
          </p>
        )}
      </div>
    </StudioDialog>
  );
}
