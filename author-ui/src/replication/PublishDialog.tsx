import { Button, ButtonGroup, Checkbox, Content, Divider, Heading, ProgressCircle, Radio, RadioGroup, Text, TextField, ToastQueue, useDialogContainer } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { ReplicationClient, UnpublishedReference } from './api';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  client: ReplicationClient;
  /** The pages (or assets) to publish or unpublish. */
  paths: string[];
  title: string;
  /** `deactivate`: AEM's Unpublish Page (no references, no tree option by default). */
  action?: 'activate' | 'deactivate';
  /** Pages: offer to publish the child pages too. */
  offerDeep?: boolean;
  /** Start with "also what is below" ticked (Publish with the pages below). */
  initialDeep?: boolean;
  /** false for content that uses nothing else (tags): the references are not looked up or spoken of */
  uses?: boolean;
  /** What the paths are, for the heading and the texts: 'Page' (default), 'Item', 'Record'… */
  noun?: string;
  /** What `offerDeep` includes: 'pages' (default), 'files and folders'… */
  below?: string;
  onDone(): void;
}

const TYPE_LABEL: Record<UnpublishedReference['type'], string> = { asset: 'Asset', 'experience-fragment': 'Experience Fragment', template: 'Template', segment: 'Audience', page: 'Page' };
const STATUS_LABEL: Record<UnpublishedReference['status'], string> = { never: 'Not published', modified: 'Modified since published', deactivated: 'Unpublished' };

/** A local date-time for the "later" field: now + 1 hour, rounded to the next quarter. */
function defaultLater(): string {
  const d = new Date(Date.now() + 3600_000);
  d.setMinutes(Math.ceil(d.getMinutes() / 15) * 15, 0, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * AEM's Publish Page dialog, with Manage Publication's "later": the page, the content it uses that is
 * not on publish yet (assets, fragments, its template's parts), each with a checkbox, and Now or a
 * date-time. Publish replicates the page and what is ticked; later schedules it. The same dialog does
 * Unpublish Page (`action="deactivate"`).
 */
export function PublishDialog({ client, paths, title, action = 'activate', offerDeep = false, noun = 'Page', below = 'pages', initialDeep = false, uses = true, onDone }: Props) {
  const dialog = useDialogContainer();
  const publishing = action === 'activate';
  const [references, setReferences] = useState<UnpublishedReference[] | null>(publishing && uses ? null : []);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [deep, setDeep] = useState(initialDeep);
  const [checked, setChecked] = useState(0);
  const [when, setWhen] = useState<'now' | 'later'>('now');
  const [later, setLater] = useState(defaultLater);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // AEM as a Cloud Service's Destination: the publish tier, or the preview tier when there is one
  const [hasPreview, setHasPreview] = useState(false);
  const [destination, setDestination] = useState<'publish' | 'preview'>('publish');
  const tier = destination === 'preview' ? ('preview' as const) : undefined;

  useEffect(() => {
    let live = true;
    client.agents().then(
      (agents) => live && setHasPreview(agents.some((a) => a.tier === 'preview' && a.enabled)),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [client]);

  useEffect(() => {
    if (!publishing || !uses) return;
    let live = true;
    setReferences(null);
    (async () => {
      // many pages (a bulk publish): eight at a time, with the count shown while it runs
      const all = new Map<string, UnpublishedReference>();
      for (let i = 0; i < paths.length && live; i += 8) {
        const batch = await Promise.all(paths.slice(i, i + 8).map((p) => client.references(p, tier).catch(() => [])));
        for (const refs of batch) for (const r of refs) if (!all.has(r.path)) all.set(r.path, r);
        if (live) setChecked(Math.min(paths.length, i + 8));
      }
      if (!live) return;
      // a page that is itself being published is not its own reference
      for (const p of paths) all.delete(p);
      setReferences([...all.values()]);
      setSelected(new Set(all.keys()));
    })();
    return () => {
      live = false;
    };
  }, [client, paths, publishing, tier, uses]);

  const at = when === 'later' && !tier ? new Date(later) : null;
  const atInvalid = when === 'later' && !tier && (!at || Number.isNaN(at.getTime()) || at.getTime() <= Date.now());

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const options = { deep, at: at ? at.toISOString() : undefined, tier };
      const extra = [...selected];
      if (publishing) {
        if (extra.length) await client.activate(extra, { at: options.at, tier });
        await client.activate(paths, options);
      } else {
        await client.deactivate(paths, options);
      }
      const what = paths.length === 1 ? title : `${paths.length} items`;
      const done = tier ? (publishing ? 'sent to Preview' : 'removed from Preview') : publishing ? 'published' : 'unpublished';
      ToastQueue.positive(
        at
          ? `${what} will be ${publishing ? 'published' : 'unpublished'} ${at.toLocaleString()}`
          : `${what} ${done}${extra.length ? ` with ${extra.length} reference${extra.length === 1 ? '' : 's'}` : ''}${deep ? `, with the ${below} below` : ''}`,
        { timeout: 5000 },
      );
      onDone();
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const toggle = (p: string, on: boolean) =>
    setSelected((s) => {
      const n = new Set(s);
      if (on) n.add(p);
      else n.delete(p);
      return n;
    });

  const verb = publishing ? 'Publish' : 'Unpublish';
  return (
    <StudioDialog size="M">
      <Heading>
        {verb} {paths.length === 1 ? noun : `${paths.length} ${noun}s`}
      </Heading>
      <Divider />
      <Content>
        {hasPreview && (
          // a block of its own: the field is inline, and the text below would run beside it
          <div className="studio-publish__destination">
            <RadioGroup
              label="Destination"
              value={destination}
              onChange={(v) => setDestination(v as 'publish' | 'preview')}
              orientation="horizontal"
              isDisabled={busy}
            >
              <Radio value="publish">Publish</Radio>
              <Radio value="preview">Preview</Radio>
            </RadioGroup>
          </div>
        )}
        <Text>
          <strong>{title}</strong>{' '}
          {tier
            ? publishing
              ? `${paths.length === 1 ? 'goes' : 'go'} to the preview instance only, for review: the published site does not change.`
              : `${paths.length === 1 ? 'is' : 'are'} removed from the preview instance only.`
            : publishing
              ? `${paths.length === 1 ? 'goes' : 'go'} to the publish instances through the publishing agents.`
              : `${paths.length === 1 ? 'is' : 'are'} removed from the publish instances by the publishing agents.`}
        </Text>
        {references === null ? (
          <div style={{ marginTop: 12 }}>
            <ProgressCircle size="S" isIndeterminate aria-label="Looking for references" />
            {paths.length > 8 && (
              <Text UNSAFE_className="studio-publish__hint">
                {' '}
                Looking at what they use: {checked} of {paths.length}
              </Text>
            )}
          </div>
        ) : references.length > 0 ? (
          <div className="studio-publish__refs">
            <Text UNSAFE_className="studio-publish__hint">
              {paths.length === 1 ? 'The page uses' : 'They use'} content that is not {tier ? 'on Preview' : 'published'} yet. {tier ? 'Send' : 'Publish'} it too, or{' '}
              {paths.length === 1 ? 'the page shows' : 'the pages show'} gaps on {tier ? 'Preview' : 'publish'}.
            </Text>
            <ul>
              {references.map((r) => (
                <li key={r.path}>
                  <Checkbox isSelected={selected.has(r.path)} onChange={(on) => toggle(r.path, on)} isDisabled={busy}>
                    <span className="studio-publish__title">{r.title}</span>
                    <span className="studio-publish__meta">
                      {TYPE_LABEL[r.type]} · {STATUS_LABEL[r.status]}
                    </span>
                  </Checkbox>
                  <code className="studio-publish__path">{r.path}</code>
                </li>
              ))}
            </ul>
          </div>
        ) : publishing && uses ? (
          <Text UNSAFE_className="studio-publish__hint">Everything the page uses is already {tier ? 'on Preview' : 'published'}.</Text>
        ) : null}
        {offerDeep && (
          <Checkbox isSelected={deep} onChange={setDeep} isDisabled={busy} marginTop="size-150">
            Also the {below} below it
          </Checkbox>
        )}
        {!tier && (
          <RadioGroup label={publishing ? 'Publish' : 'Unpublish'} value={when} onChange={(v) => setWhen(v as 'now' | 'later')} orientation="horizontal" marginTop="size-150" isDisabled={busy}>
            <Radio value="now">Now</Radio>
            <Radio value="later">Later</Radio>
          </RadioGroup>
        )}
        {when === 'later' && !tier && (
          <TextField
            type="datetime-local"
            label="Date and time"
            value={later}
            onChange={setLater}
            validationState={atInvalid ? 'invalid' : undefined}
            errorMessage="Pick a moment in the future"
            description="The request waits in the publishing queue until then"
            isDisabled={busy}
          />
        )}
        {error && (
          <p className="studio-publish__error" role="alert">
            {error}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant={publishing ? 'accent' : 'negative'} onPress={() => void submit()} isPending={busy} isDisabled={references === null || atInvalid}>
          {tier ? (publishing ? 'Send to Preview' : 'Remove from Preview') : when === 'later' ? `${verb} later` : verb}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
