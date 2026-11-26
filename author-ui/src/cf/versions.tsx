import {
  ActionMenu,
  Button,
  ButtonGroup,
  Content,
  DialogContainer,
  Divider,
  Flex,
  Form,
  Heading,
  Item,
  ProgressCircle,
  Text,
  TextArea,
  TextField,
  ToastQueue,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import { useEffect, useState } from 'react';
import { CfValidationError, type CfClient, type CfFragment, type CfModel, type CfValue, type CfVersion } from './api';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';

const MASTER = 'master';

/** A version's name for people: its label, else "Version 1.2". */
export const versionTitle = (v: CfVersion) => v.labels[0] ?? `Version ${v.name}`;

function when(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** A value as short readable text: tags stripped from rich text, arrays joined. */
export function plain(value: CfValue | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  if (Array.isArray(value)) return value.map((v) => plain(v as CfValue)).join(', ');
  const s = String(value);
  if (!/<[a-z][\s\S]*>/i.test(s)) return s;
  const doc = new DOMParser().parseFromString(s, 'text/html');
  return (doc.body.textContent ?? '').replace(/\s+/g, ' ').trim();
}

export interface Difference {
  name: string;
  label: string;
  version: string;
  current: string;
}

/** The master elements (and title) that differ between a version and the current fragment. */
export function differences(model: CfModel, version: CfFragment, current: CfFragment): Difference[] {
  const out: Difference[] = [];
  if (version.title !== current.title) out.push({ name: 'jcr:title', label: 'Title (content fragment)', version: version.title, current: current.title });
  const a = version.values[MASTER] ?? {};
  const b = current.values[MASTER] ?? {};
  for (const f of model.fields) {
    if (f.kind === 'tab') continue;
    const va = plain(a[f.name]);
    const vb = plain(b[f.name]);
    const same = JSON.stringify(a[f.name] ?? null) === JSON.stringify(b[f.name] ?? null);
    if (!same) out.push({ name: f.name, label: f.label, version: va, current: vb });
  }
  return out;
}

/**
 * The fragment's versions: create one (label, comment), compare one with the current state, restore one (the
 * current state is saved as a version first). Unsaved changes are not part of a version, so creating one waits
 * for Save.
 */
export function VersionsPanel({
  client,
  fragment,
  model,
  dirty,
  onRestored,
}: {
  client: CfClient;
  fragment: CfFragment;
  model: CfModel;
  dirty: boolean;
  onRestored(f: CfFragment): void;
}) {
  const [versions, setVersions] = useState<CfVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<null | { kind: 'create' } | { kind: 'compare' | 'restore'; version: CfVersion }>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let cancelled = false;
    client
      .versions(fragment.path)
      .then((v) => {
        if (cancelled) return;
        setVersions(v);
        setError(null);
      })
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [client, fragment.path, version]);

  return (
    <Flex direction="column" gap="size-150" UNSAFE_className="studio-cf-versions">
      <Flex alignItems="center" justifyContent="end">
        <Button variant="secondary" style="outline" onPress={() => setDialog({ kind: 'create' })} isDisabled={dirty || !!error}>
          <Add />
          <Text>Create version</Text>
        </Button>
      </Flex>
      {dirty && <Text UNSAFE_className="studio-cf__muted">Save your changes first: a version holds the saved content fragment.</Text>}
      {error ? (
        <Text UNSAFE_className="studio-cf__muted">Versions are not available: {error}</Text>
      ) : !versions ? (
        <ProgressCircle aria-label="Loading versions" isIndeterminate size="S" />
      ) : versions.length === 0 ? (
        <Text UNSAFE_className="studio-cf__muted">No versions yet. A version keeps the content fragment as it is now, to compare or restore later.</Text>
      ) : (
        <ol className="studio-cf-versions__list" aria-label="Versions">
          {versions.map((v) => (
            <li key={v.name} className="studio-cf-versions__item">
              <div className="studio-cf-versions__text">
                <span className="studio-cf-versions__title">{versionTitle(v)}</span>
                <span className="studio-cf-versions__meta">
                  {when(v.created)}
                  {v.createdBy ? ` · ${v.createdBy}` : ''}
                  {v.labels.length ? ` · ${v.name}` : ''}
                </span>
                {v.comment && <span className="studio-cf-versions__comment">{v.comment}</span>}
              </div>
              <ActionMenu
                aria-label={`Actions for ${versionTitle(v)}`}
                onAction={(a) => setDialog({ kind: a === 'restore' ? 'restore' : 'compare', version: v })}
              >
                <Item key="compare">Compare with current</Item>
                <Item key="restore">Restore</Item>
              </ActionMenu>
            </li>
          ))}
        </ol>
      )}

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog?.kind === 'create' && (
          <CreateVersionDialog
            onCreate={async (label, comment) => {
              const v = await client.createVersion(fragment.path, label || undefined, comment || undefined);
              ToastQueue.positive(`Created ${versionTitle(v)}`, { timeout: 2500 });
              setVersion((n) => n + 1);
            }}
          />
        )}
        {dialog?.kind === 'compare' && <CompareDialog client={client} fragment={fragment} model={model} version={dialog.version} />}
        {dialog?.kind === 'restore' && (
          <StudioConfirmDialog
            title={`Restore ${versionTitle(dialog.version)}`}
            variant="warning"
            primaryActionLabel="Restore"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                const out = await client.restoreVersion(fragment.path, dialog.version.name);
                onRestored(out.fragment);
                ToastQueue.positive(`Restored ${versionTitle(dialog.version)}. The previous state is kept as version ${out.saved.name}.`, { timeout: 5000 });
                setVersion((n) => n + 1);
              } catch (e) {
                ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
              }
            }}
          >
            The content fragment goes back to this version, every variant included. The current state is saved as a version first, so nothing is lost.
            {dirty ? ' Your unsaved changes are discarded.' : ''}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </Flex>
  );
}

function CreateVersionDialog({ onCreate }: { onCreate(label: string, comment: string): Promise<void> }) {
  const dialog = useDialogContainer();
  const [label, setLabel] = useState('');
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    try {
      await onCreate(label.trim(), comment.trim());
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof CfValidationError ? e.errors[0]?.message ?? e.message : e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>Create version</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <TextField
            label="Label"
            description="Optional, e.g. Release. Unique per content fragment."
            value={label}
            onChange={(l) => {
              setLabel(l);
              setError(null);
            }}
            autoFocus
            validationState={error ? 'invalid' : undefined}
            errorMessage={error ?? undefined}
          />
          <TextArea label="Comment" value={comment} onChange={setComment} />
        </Form>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isPending={busy}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

function CompareDialog({ client, fragment, model, version }: { client: CfClient; fragment: CfFragment; model: CfModel; version: CfVersion }) {
  const dialog = useDialogContainer();
  const [diff, setDiff] = useState<Difference[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    client
      .versionContent(fragment.path, version.name)
      .then((v) => !cancelled && setDiff(differences(model, v, fragment)))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [client, fragment, model, version]);

  return (
    <StudioDialog size="L">
      <Heading>{versionTitle(version)} compared with the current content fragment</Heading>
      <Divider />
      <Content>
        {error ? (
          <Text>{error}</Text>
        ) : !diff ? (
          <ProgressCircle aria-label="Loading version" isIndeterminate />
        ) : diff.length === 0 ? (
          <Text>The master variant is the same as in this version.</Text>
        ) : (
          <table className="studio-cf-diff studio-table" aria-label="Differences">
            <thead>
              <tr>
                <th scope="col">Element</th>
                <th scope="col">{versionTitle(version)}</th>
                <th scope="col">Current</th>
              </tr>
            </thead>
            <tbody>
              {diff.map((d) => (
                <tr key={d.name}>
                  <th scope="row">{d.label}</th>
                  <td className="studio-cf-diff__old">{d.version || <em>empty</em>}</td>
                  <td className="studio-cf-diff__new">{d.current || <em>empty</em>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Close
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
