import './blueprints.scss';
import {
  ActionButton,
  Button,
  DialogContainer,
  Text,
  TextArea,
  TextField,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useState } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { Link, useScreenTitle } from '../shell/ShellContext';
import { StudioConfirmDialog, StudioDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { VOCAB } from '../shell/vocabulary';
import type { MsmClient } from './MsmClient';
import type { BlueprintInfo } from './types';

/** AEM's Tools › Sites › Blueprints. */
export { BLUEPRINTS_URL } from './blueprintsUrl';
import { BLUEPRINTS_URL } from './blueprintsUrl';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
const parentOf = (path: string) => path.slice(0, path.lastIndexOf('/')) || '/content';

/** A configuration's node name from its title, as Create Site names a new one. */
export function blueprintName(title: string): string {
  const n = title
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return n ? `${n}_blueprint`.replace(/_blueprint_blueprint$/, '_blueprint') : '';
}

/** What the form is checked for before it is sent. */
export function blueprintProblems(form: { title: string; sitePath: string; name?: string }, taken: string[]): string[] {
  const out: string[] = [];
  if (!form.title.trim()) out.push('The source needs a title.');
  if (!/^\/content\/[^/]+(\/[^/]+)*$/.test(form.sitePath.trim()) || form.sitePath.includes('..')) out.push('The site root is a page under /content, such as /content/my-site/language-masters.');
  if (form.name !== undefined) {
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(form.name)) out.push('The name uses a-z, 0-9, _ and -.');
    else if (taken.includes(form.name)) out.push(`A configuration named ${form.name} exists already.`);
  }
  return out;
}

/**
 * The site sources (AEM's blueprint configurations): the sites Create Site copies from, a title and a site root whose
 * pages are its languages. Made here or in Create Site ("New source…"); the product's own (Experience Fragments) are
 * shown but not changed.
 */
export function BlueprintsConsole({ client }: { client: MsmClient }) {
  const [list, setList] = useState<BlueprintInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<BlueprintInfo | 'new' | null>(null);
  const [removing, setRemoving] = useState<BlueprintInfo | null>(null);

  useScreenTitle('Site sources', `Site sources | ${VOCAB.product}`);
  const load = useCallback(() => {
    setError(null);
    client.blueprints().then(setList, (e: unknown) => setError(message(e)));
  }, [client]);
  useEffect(load, [load]);

  return (
    <div className="studio-sites studio-bp">
      <ToolBar
        url={BLUEPRINTS_URL}
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Read the sources again</Tooltip>
          </TooltipTrigger>
        }
        primary={
          <Button variant="accent" onPress={() => setEditing('new')}>
            <Add />
            <Text>Create</Text>
          </Button>
        }
      />
      <main className="studio-sites__content studio-bp__body">
        {error ? (
          <ErrorState title="Could not read the site sources">{error}</ErrorState>
        ) : !list ? (
          <LoadingState />
        ) : list.length === 0 ? (
          <EmptyState title="No site source">A source is a site whose pages are its languages, such as /content/my-site/language-masters. Create Site makes new sites from it.</EmptyState>
        ) : (
          <>
            <p className="studio-bp__intro">
              {`Create Site makes a new site from one of these: a ${VOCAB.linkedCopy.toLowerCase()} of the languages and chapters you choose, which follows the source when it is synced.`}
            </p>
            <table className="studio-bp__table">
              <thead>
                <tr>
                  <th>Source</th>
                  <th>Site root</th>
                  <th>Sites made from it</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {list.map((b) => (
                  <tr key={b.path}>
                    <td>
                      <strong>{b.title}</strong>
                      {b.readOnly && <span className="studio-bp__tag">Product</span>}
                      {b.description && <div className="studio-bp__muted">{b.description}</div>}
                      <div className="studio-bp__path">{b.path}</div>
                    </td>
                    <td>
                      <Link to={`/sites.html${b.sitePath}`} className="studio-bp__link">
                        <code>{b.sitePath}</code>
                      </Link>
                    </td>
                    <td>
                      {(b.sites ?? []).length === 0 ? (
                        <span className="studio-bp__muted">None yet</span>
                      ) : (
                        <ul className="studio-bp__sites">
                          {b.sites!.map((s) => (
                            <li key={s}>
                              <Link to={`/sites.html${s}`} className="studio-bp__link">
                                <code>{s}</code>
                              </Link>
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                    <td className="studio-bp__actions">
                      <TooltipTrigger delay={400}>
                        <ActionButton isQuiet aria-label={`Properties of ${b.title}`} onPress={() => setEditing(b)}>
                          <Edit />
                        </ActionButton>
                        <Tooltip>Properties</Tooltip>
                      </TooltipTrigger>
                      {!b.readOnly && (
                        <TooltipTrigger delay={400}>
                          <ActionButton isQuiet aria-label={`Delete ${b.title}`} onPress={() => setRemoving(b)}>
                            <Delete />
                          </ActionButton>
                          <Tooltip>Delete</Tooltip>
                        </TooltipTrigger>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </main>

      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && (
          <BlueprintDialog
            client={client}
            blueprint={editing === 'new' ? null : editing}
            taken={(list ?? []).map((b) => b.path.slice(b.path.lastIndexOf('/') + 1))}
            onCancel={() => setEditing(null)}
            onDone={() => {
              setEditing(null);
              load();
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title="Delete source"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={() => client.deleteBlueprint(removing.path).then(load, (e: unknown) => setError(message(e)))}
          >
            {`You are going to delete ${removing.title}. Create Site no longer offers it. ${
              (removing.sites ?? []).length > 0
                ? `The ${removing.sites!.length} site${removing.sites!.length === 1 ? '' : 's'} made from it stay ${VOCAB.linkedCopy.toLowerCase()}s of ${removing.sitePath}, and syncs reach them as before.`
                : `The pages of ${removing.sitePath} stay as they are.`
            }`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

/** Create a source, or its properties (Blueprint Properties: title, site root, description). */
function BlueprintDialog({
  client,
  blueprint,
  taken,
  onCancel,
  onDone,
}: {
  client: MsmClient;
  blueprint: BlueprintInfo | null;
  taken: string[];
  onCancel(): void;
  onDone(): void;
}) {
  const [title, setTitle] = useState(blueprint?.title ?? '');
  const [sitePath, setSitePath] = useState(blueprint?.sitePath ?? '');
  const [description, setDescription] = useState(blueprint?.description ?? '');
  const [name, setName] = useState('');
  const [nameTouched, setNameTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const readOnly = !!blueprint?.readOnly;
  const shownName = nameTouched ? name : blueprintName(title);
  const problems = readOnly ? [] : blueprintProblems({ title, sitePath, ...(blueprint ? {} : { name: shownName }) }, taken);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = { title: title.trim(), sitePath: sitePath.trim().replace(/\/+$/, ''), description: description.trim() || undefined };
      if (blueprint) await client.updateBlueprint(blueprint.path, r);
      else await client.createBlueprint({ ...r, name: shownName });
      onDone();
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog
      title={blueprint ? `${blueprint.title} Properties` : 'Create source'}
      subject={blueprint ? blueprint.path : 'A site Create Site makes new sites from'}
      primaryLabel={readOnly ? undefined : blueprint ? 'Save' : 'Create'}
      cancelLabel={readOnly ? 'Close' : 'Cancel'}
      onCancel={onCancel}
      onPrimary={() => void save()}
      primaryDisabled={problems.length > 0}
      busy={busy}
    >
      <div className="studio-bp__form">
        <TextField label="Title" value={title} onChange={setTitle} isRequired isReadOnly={readOnly} width="100%" autoFocus={!readOnly} />
        {!blueprint && (
          <TextField
            label="Name"
            value={shownName}
            onChange={(v) => {
              setName(v);
              setNameTouched(true);
            }}
            width="100%"
            description={shownName ? `Kept at /apps/msm/${shownName}` : undefined}
          />
        )}
        <TextField
          label="Site root"
          value={sitePath}
          onChange={setSitePath}
          isRequired
          isReadOnly={readOnly}
          width="100%"
          description="The page whose children are the languages, such as /content/my-site/language-masters"
        />
        <TextArea label="Description" value={description} onChange={setDescription} isReadOnly={readOnly} width="100%" />
        {readOnly && <p className="studio-bp__muted">This source comes with the product; it is not changed here.</p>}
        {blueprint && !readOnly && sitePath.trim() !== blueprint.sitePath && (blueprint.sites ?? []).length > 0 && (
          <p className="studio-bp__muted">{`The sites made from it stay ${VOCAB.linkedCopy.toLowerCase()}s of ${blueprint.sitePath}; only new sites come from ${sitePath.trim() || 'the new root'}.`}</p>
        )}
        {problems.length > 0 && (title || sitePath) && (
          <ul className="studio-bp__problems">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}
        {error && (
          <p className="studio-bp__problems" role="alert">
            {error}
          </p>
        )}
        {blueprint && (
          <p className="studio-bp__muted">
            Pages of its root:{' '}
            <Link to={`/sites.html${parentOf(`${blueprint.sitePath}/x`)}`} className="studio-bp__link">
              open in {VOCAB.pages}
            </Link>
          </p>
        )}
      </div>
    </StudioDialog>
  );
}
