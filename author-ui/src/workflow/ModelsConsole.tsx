import {
  ActionButton,
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  DialogContainer,
  Divider,
  Form,
  Heading,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import { SelectionActions } from '../shell/SelectionBar';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Play from '@spectrum-icons/workflow/Play';
import SelectBox from '@spectrum-icons/workflow/SelectBox';
import ViewCard from '@spectrum-icons/workflow/ViewCard';
import ViewList from '@spectrum-icons/workflow/ViewList';
import WorkflowIcon from '@spectrum-icons/workflow/Workflow';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { studioGraniteEnv } from '../editor/dialogs/graniteEnv';
import { createDefaultRegistry, GraniteForm, useGraniteForm, type GraniteNode } from '../granite';
import { ProductBar } from '../shell/ProductBar';
import { workflowUrl, type ModelSummary, type WorkflowClient } from './api';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';

const TOOL = '/workflow/models.html';

type ViewMode = 'card' | 'list';
const VIEW_KEY = 'studio.workflow.models.view';

function readView(): ViewMode {
  try {
    return window.localStorage.getItem(VIEW_KEY) === 'card' ? 'card' : 'list';
  } catch {
    return 'list';
  }
}

function saveView(v: ViewMode) {
  try {
    window.localStorage.setItem(VIEW_KEY, v);
  } catch {
    // per-viewer convenience only
  }
}

const TYPE_LABEL: Record<ModelSummary['type'], string> = { custom: 'Custom', default: 'Default', runtime: 'Not Editable' };

interface Props {
  client: WorkflowClient;
  studio: StudioClient;
  user: string | null;
}

/**
 * Workflow Models (`/workflow/models.html`): the design models of `/conf/global/settings/workflow/models` (custom)
 * and `/libs/settings/workflow/models` (default), plus runtime models without a design. Create a model, open it in
 * the model editor, start a workflow on a payload, delete custom models.
 */
export function ModelsConsole({ client, studio, user }: Props) {
  const go = useNavigate();
  const tab = useTab();
  const [models, setModels] = useState<ModelSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<ViewMode>(readView);
  const [selected, setSelected] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [running, setRunning] = useState<ModelSummary | null>(null);
  const [deleting, setDeleting] = useState<ModelSummary[] | null>(null);

  useScreenTitle(VOCAB.flows, `${VOCAB.flows} | ${VOCAB.product}`);

  const load = useCallback(() => {
    client.models().then(
      (list) => {
        setModels(list);
        setError(null);
        setSelected((s) => s.filter((id) => list.some((m) => m.id === id)));
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  const selection = (models ?? []).filter((m) => selected.includes(m.id));
  const single = selection.length === 1 ? selection[0] : null;
  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  const open = (m: ModelSummary) => {
    if (m.designPath) go(workflowUrl.editor(m.designPath));
  };
  const switchView = (v: ViewMode) => {
    setView(v);
    saveView(v);
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (tab && !tab.active) return; // another tab is in front
      const t = e.target as HTMLElement | null;
      if (creating || running || deleting || t?.closest('input:not([type="checkbox"]), textarea, [role="dialog"]')) return;
      if (e.key === 'Escape') setSelected([]);
      if (e.key === 'e' && single?.designPath) open(single);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const remove = async (list: ModelSummary[]) => {
    setDeleting(null);
    try {
      for (const m of list) if (m.designPath) await client.deleteModel(m.designPath);
      ToastQueue.positive(`${list.length === 1 ? list[0].title : `${list.length} models`} deleted`, { timeout: 3000 });
      setSelected([]);
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
    load();
  };

  const deletable = selection.length > 0 && selection.every((m) => m.type === 'custom');
  const startable = (m: ModelSummary | null) => !!m && !!m.version;

  return (
    <div className="studio-sites studio-wf">
      {!tab && <ProductBar user={user} />}
      {selection.length > 0 ? (
        <SelectionActions count={selection.length} onClear={() => setSelected([])}>
            {single?.designPath && (
              <ActionButton isQuiet onPress={() => open(single)}>
                <Edit />
                <Text>
                  Edit <span className="studio-actionbar__hint">(e)</span>
                </Text>
              </ActionButton>
            )}
            {single && (
              <TooltipTrigger isDisabled={startable(single)}>
                <ActionButton isQuiet onPress={() => setRunning(single)} isDisabled={!startable(single)}>
                  <Play />
                  <Text>Start Flow</Text>
                </ActionButton>
                <Tooltip>Sync the model first: it has no runtime version yet.</Tooltip>
              </TooltipTrigger>
            )}
            {deletable && (
              <ActionButton isQuiet onPress={() => setDeleting(selection)}>
                <Delete />
                <Text>Delete</Text>
              </ActionButton>
            )}
          </SelectionActions>
      ) : (
        <ToolBar
          url={TOOL}
          closable={false}
          end={<>
            <ActionButton isQuiet onPress={() => setSelected((models ?? []).map((m) => m.id))} isDisabled={!models?.length}>
              <SelectBox />
              <Text>Select All</Text>
            </ActionButton>
            <TooltipTrigger>
              <ActionButton isQuiet aria-label={view === 'list' ? 'Card view' : 'List view'} onPress={() => switchView(view === 'list' ? 'card' : 'list')}>
                {view === 'list' ? <ViewCard /> : <ViewList />}
              </ActionButton>
              <Tooltip>{view === 'list' ? 'Card view' : 'List view'}</Tooltip>
            </TooltipTrigger>
          </>}
          primary={<Button variant="accent" aria-label="Create" onPress={() => setCreating(true)}>Create flow model</Button>}
        />
      )}

      <main className="studio-sites__content">
        {error ? (
          <ErrorState title="Could not load the flow models">{error}</ErrorState>
        ) : !models ? (
          <LoadingState />
        ) : models.length === 0 ? (
          <EmptyState title="No flow models">Create a flow model to get started.</EmptyState>
        ) : view === 'list' ? (
          <ModelTable models={models} selected={selected} onToggle={toggle} onOpen={open} />
        ) : (
          <ModelCards models={models} selected={selected} onToggle={toggle} onOpen={open} />
        )}
      </main>

      <DialogContainer onDismiss={() => setCreating(false)}>
        {creating && (
          <CreateModelDialog
            client={client}
            onClose={() => setCreating(false)}
            onCreated={(m) => {
              setCreating(false);
              ToastQueue.positive(`Flow model ${m.title} created`, { timeout: 3000 });
              load();
              setSelected([m.id]);
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRunning(null)}>
        {running && <RunWorkflowDialog client={client} studio={studio} model={running} onClose={() => setRunning(null)} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog title="Delete" variant="destructive" primaryActionLabel="Delete" cancelLabel="Cancel" onPrimaryAction={() => void remove(deleting)}>
            You are going to delete <strong>{deleting.map((d) => d.title).join(', ')}</strong> with its runtime model. Workflows that are
            running on it can no longer advance.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

interface ViewProps {
  models: ModelSummary[];
  selected: string[];
  onToggle(id: string): void;
  onOpen(m: ModelSummary): void;
}

function details(m: ModelSummary): string {
  return [m.version ? `v${m.version}` : 'Not synced', m.transient ? 'Transient' : null].filter(Boolean).join(', ');
}

function TypeBadge({ type }: { type: ModelSummary['type'] }) {
  return <span className={`studio-wf__type studio-wf__type--${type}`}>{TYPE_LABEL[type]}</span>;
}

function ModelTable({ models, selected, onToggle, onOpen }: ViewProps) {
  return (
    <div className="studio-listview">
      <table className="studio-table">
        <thead>
          <tr>
            <th aria-label="Select" />
            {/* a flow's name on one line: the description is what wraps */}
            <th style={{ minWidth: 220 }}>Title</th>
            <th>Description</th>
            <th>Details</th>
            <th>Synced</th>
            <th>Type</th>
          </tr>
        </thead>
        <tbody>
          {models.map((m) => {
            const isSel = selected.includes(m.id);
            return (
              <tr key={m.id} className={isSel ? 'is-selected' : undefined}>
                <td className="studio-listview__check">
                  <Checkbox aria-label={`Select ${m.title}`} isSelected={isSel} onChange={() => onToggle(m.id)} isEmphasized />
                </td>
                <td>
                  <button type="button" className="studio-listview__title" onClick={() => (m.designPath ? onOpen(m) : onToggle(m.id))}>
                    <span className="studio-wf__icon">
                      <WorkflowIcon size="S" />
                    </span>
                    {m.title}
                  </button>
                </td>
                <td className="studio-listview__muted">{m.description || 'No Description'}</td>
                <td className="studio-listview__muted">{details(m)}</td>
                <td>{m.synced ? <Checkmark size="S" UNSAFE_className="studio-wf__synced" aria-label="Synced" /> : <span className="studio-wf__unsynced">Not synced</span>}</td>
                <td>
                  <TypeBadge type={m.type} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function ModelCards({ models, selected, onToggle, onOpen }: ViewProps) {
  return (
    <ul className="studio-cards">
      {models.map((m) => {
        const isSel = selected.includes(m.id);
        return (
          <li key={m.id} className={`studio-card${isSel ? ' is-selected' : ''}`}>
            <button type="button" className="studio-card__open" onClick={() => (m.designPath ? onOpen(m) : onToggle(m.id))}>
              <span className="studio-card__thumb studio-wf__thumb">
                <WorkflowIcon size="XXL" />
              </span>
              <span className="studio-card__body">
                <span className="studio-card__type">
                  <TypeBadge type={m.type} />
                </span>
                <span className="studio-card__title">{m.title}</span>
                <span className="studio-card__meta">{m.description || 'No Description'}</span>
                <span className="studio-card__meta">
                  {details(m)} · {m.synced ? 'Synced' : 'Not synced'}
                </span>
              </span>
            </button>
            <span className="studio-card__check">
              <Checkbox aria-label={`Select ${m.title}`} isSelected={isSel} onChange={() => onToggle(m.id)} isEmphasized />
            </span>
          </li>
        );
      })}
    </ul>
  );
}

// ------------------------------------------------------------------------------------------------ dialogs

/** Machine name from a title, like the Name field of AEM's create dialogs. */
export function modelName(title: string): string {
  return title
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function CreateModelDialog({ client, onClose, onCreated }: { client: WorkflowClient; onClose(): void; onCreated(m: ModelSummary): void }) {
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const valid = title.trim().length > 0 && (!name || /^[a-z0-9_-]+$/i.test(name));

  const submit = async () => {
    if (!valid) return;
    setBusy(true);
    setFailure(null);
    try {
      onCreated(await client.createModel(title.trim(), name.trim() || undefined));
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="S">
      <Heading>Add Flow Model</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <TextField label="Title" isRequired value={title} onChange={setTitle} autoFocus />
          <TextField
            label="Name"
            value={name}
            onChange={setName}
            validationState={name && !/^[a-z0-9_-]+$/i.test(name) ? 'invalid' : undefined}
            errorMessage="Letters, digits, - and _ only"
            description={`The node name of the model. Empty: ${modelName(title) || 'derived from the title'}.`}
          />
        </Form>
        {failure && (
          <p className="studio-wf__failure" role="alert">
            {failure}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isDisabled={!valid || busy}>
          Done
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

const CORAL = 'granite/ui/components/coral/foundation';
const node = (name: string, properties: GraniteNode['properties'], children: GraniteNode[] = []): GraniteNode => ({ name, properties, children });

/** The payload field is a Granite path field, so it gets the same repository picker as component dialogs. */
const RUN_FORM: GraniteNode = node('run', { 'sling:resourceType': `${CORAL}/container` }, [
  node('items', {}, [
    node('payload', {
      'sling:resourceType': `${CORAL}/form/pathfield`,
      fieldLabel: 'Payload',
      name: './payload',
      rootPath: '/content',
      required: true,
      fieldDescription: 'The page or file the flow runs on.',
    }),
  ]),
]);

export function RunWorkflowDialog({ client, studio, model, payload, onClose }: { client: WorkflowClient; studio: StudioClient; model: ModelSummary; payload?: string; onClose(): void }) {
  const registry = useMemo(() => createDefaultRegistry(), []);
  const env = useMemo(() => studioGraniteEnv(studio), [studio]);
  const content = useMemo(() => (payload ? { payload } : {}), [payload]);
  const form = useGraniteForm(RUN_FORM, content, registry, env);
  const [title, setTitle] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = async () => {
    if (form.validate().length) {
      setShowErrors(true);
      return;
    }
    const value = form.serialize().find((e) => e.name === './payload')?.value?.trim();
    if (!value) {
      setShowErrors(true);
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      await client.start(model.id, value, title.trim() || undefined, comment.trim() || undefined);
      ToastQueue.positive(`Flow ${model.title} started on ${value}`, { timeout: 4000 });
      onClose();
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>Run Flow</Heading>
      <Divider />
      <Content>
        <p className="studio-wf__runmodel">
          <WorkflowIcon size="S" /> {model.title} {model.version && <span className="studio-listview__muted">v{model.version}</span>}
        </p>
        <GraniteForm form={form} registry={registry} env={env} showErrors={showErrors} />
        <Form>
          <TextField label="Title" value={title} onChange={setTitle} description="Shown in the inbox and the list of instances." />
          <TextArea label="Comment" value={comment} onChange={setComment} />
        </Form>
        {failure && (
          <p className="studio-wf__failure" role="alert">
            {failure}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isDisabled={busy}>
          Run
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
