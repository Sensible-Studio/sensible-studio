import {
  ActionButton,
  Button,
  Checkbox,
  ComboBox,
  DialogContainer,
  Flex,
  Item,
  Picker,
  Switch,
  Text,
  TextField,
  ToastContainer,
  ToastQueue,
} from '@adobe/react-spectrum';
import { SelectionActions } from '../shell/SelectionBar';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Properties from '@spectrum-icons/workflow/Properties';
import SelectBox from '@spectrum-icons/workflow/SelectBox';
import { useCallback, useEffect, useState } from 'react';
import { ProductBar } from '../shell/ProductBar';
import { EVENT_TYPES, type LauncherInfo, type LauncherInput, type ModelSummary, type WorkflowClient } from './api';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { FormPage } from '../shell/FormPage';
import { StudioConfirmDialog } from '../shell/StudioDialog';

const TOOL = '/workflow/launchers.html';

const NODE_TYPES = ['cq:Page', 'cq:PageContent', 'dam:Asset', 'dam:AssetContent', 'nt:unstructured', 'nt:file', 'sling:Folder', 'sling:OrderedFolder'];
const RUN_MODES: Array<{ id: string; label: string }> = [
  { id: 'author', label: 'Author' },
  { id: 'publish', label: 'Publish' },
  { id: 'author,publish', label: 'Author & Publish' },
];

export const eventLabel = (t: number) => EVENT_TYPES.find((e) => e.value === t)?.label ?? String(t);

/**
 * Workflow Launchers (`/workflow/launchers.html`): which content changes start which workflow. Create a launcher,
 * edit its properties, enable or disable it, delete it. Launchers shipped below /libs are listed read-only.
 */
export function LaunchersConsole({ client, user }: { client: WorkflowClient; user: string | null }) {
  const tab = useTab();
  const [items, setItems] = useState<LauncherInfo[] | null>(null);
  const [models, setModels] = useState<ModelSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [editing, setEditing] = useState<LauncherInfo | 'new' | null>(null);
  const [deleting, setDeleting] = useState<LauncherInfo[] | null>(null);

  useScreenTitle(VOCAB.triggers, `${VOCAB.triggers} | ${VOCAB.product}`);

  const load = useCallback(() => {
    client.launchers().then(
      (list) => {
        setItems(list);
        setError(null);
        setSelected((s) => s.filter((p) => list.some((l) => l.path === p)));
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);
  useEffect(load, [load]);
  useEffect(() => {
    client.models().then(setModels, () => setModels([]));
  }, [client]);

  const modelTitle = (id: string) => models.find((m) => m.id === id)?.title ?? id;
  const selection = (items ?? []).filter((l) => selected.includes(l.path));
  const single = selection.length === 1 ? selection[0] : null;
  const custom = selection.length > 0 && selection.every((l) => l.type === 'custom');
  const toggle = (path: string) => setSelected((s) => (s.includes(path) ? s.filter((x) => x !== path) : [...s, path]));

  const run = async (work: () => Promise<unknown>, success: string) => {
    try {
      await work();
      ToastQueue.positive(success, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
    load();
  };

  const setEnabled = (enabled: boolean) =>
    run(async () => {
      for (const l of selection) await client.enableLauncher(l.path, enabled);
    }, `${selection.length === 1 ? 'Launcher' : `${selection.length} launchers`} ${enabled ? 'enabled' : 'disabled'}`);

  if (editing) {
    return (
      <div className="studio-sites studio-wf">
        {!tab && <ProductBar user={user} />}
        <LauncherDialog
          launcher={editing === 'new' ? null : editing}
          models={models}
          onClose={() => setEditing(null)}
          onSave={async (input) => {
            const saved = await client.saveLauncher(input, editing === 'new' ? undefined : editing.path);
            setEditing(saved);
            ToastQueue.positive(editing === 'new' ? 'Trigger created' : 'Trigger saved', { timeout: 3000 });
            load();
            setSelected([saved.path]);
            return saved;
          }}
        />
        <ToastContainer placement="bottom" />
      </div>
    );
  }

  return (
    <div className="studio-sites studio-wf">
      {!tab && <ProductBar user={user} />}
      {selection.length > 0 ? (
        <SelectionActions count={selection.length} onClear={() => setSelected([])}>
            {single && (
              <ActionButton isQuiet onPress={() => setEditing(single)}>
                <Properties />
                <Text>{single.type === 'custom' ? 'Properties' : 'View Properties'}</Text>
              </ActionButton>
            )}
            {custom && selection.some((l) => !l.enabled) && (
              <ActionButton isQuiet onPress={() => void setEnabled(true)}>
                <Text>Enable</Text>
              </ActionButton>
            )}
            {custom && selection.some((l) => l.enabled) && (
              <ActionButton isQuiet onPress={() => void setEnabled(false)}>
                <Text>Disable</Text>
              </ActionButton>
            )}
            {custom && (
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
          end={
            <ActionButton isQuiet onPress={() => setSelected((items ?? []).map((l) => l.path))} isDisabled={!items?.length}>
              <SelectBox />
              <Text>Select All</Text>
            </ActionButton>
          }
          primary={<Button variant="accent" onPress={() => setEditing('new')}>Create trigger</Button>}
        />
      )}

      <main className="studio-sites__content">
        {error ? (
          <ErrorState title="Could not load the triggers">{error}</ErrorState>
        ) : !items ? (
          <LoadingState />
        ) : items.length === 0 ? (
          <EmptyState title="No triggers">Create one to start a flow when content is created, modified, or removed.</EmptyState>
        ) : (
          <div className="studio-listview">
            <table className="studio-table">
              <thead>
                <tr>
                  <th aria-label="Select" />
                  <th>Path</th>
                  <th>Description</th>
                  <th>Event Type</th>
                  <th>Node Type</th>
                  <th>Conditions</th>
                  <th>Flow</th>
                  <th>Enabled</th>
                  <th>Exclude</th>
                  <th>Run Modes</th>
                </tr>
              </thead>
              <tbody>
                {items.map((l) => {
                  const isSel = selected.includes(l.path);
                  return (
                    <tr key={l.path} className={isSel ? 'is-selected' : undefined}>
                      <td className="studio-listview__check">
                        <Checkbox aria-label={`Select ${l.glob}`} isSelected={isSel} onChange={() => toggle(l.path)} isEmphasized />
                      </td>
                      <td>
                        <button type="button" className="studio-listview__title studio-wf__glob" onClick={() => setEditing(l)}>
                          {l.glob}
                        </button>
                        {!l.valid && <div className="studio-wf__failure studio-wf__sub">Not a valid regular expression</div>}
                      </td>
                      <td className="studio-listview__muted">{l.description}</td>
                      <td className="studio-listview__muted">{eventLabel(l.eventType)}</td>
                      <td className="studio-listview__muted">{l.nodetype}</td>
                      <td className="studio-listview__muted studio-wf__code">{l.conditions.join(', ')}</td>
                      <td className="studio-listview__muted">{modelTitle(l.workflow)}</td>
                      <td>{l.enabled ? <span className="studio-wf__on">Enabled</span> : <span className="studio-listview__muted">Disabled</span>}</td>
                      <td className="studio-listview__muted studio-wf__code">{l.excludeList.join(', ')}</td>
                      <td className="studio-listview__muted">{l.runModes}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </main>

      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog
            title="Delete"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={() => {
              const list = deleting;
              setDeleting(null);
              setSelected([]);
              void run(async () => {
                for (const l of list) await client.deleteLauncher(l.path);
              }, `${list.length === 1 ? 'Launcher' : `${list.length} launchers`} deleted`);
            }}
          >
            You are going to delete the trigger{deleting.length > 1 ? 's' : ''} for <strong>{deleting.map((l) => l.glob).join(', ')}</strong>.
            Running flows are not affected.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

/** Lines of a list, one per entry; empty ones removed. */
const listOf = (s: string) =>
  s
    .split('\n')
    .map((x) => x.trim())
    .filter(Boolean);

export function LauncherDialog({
  launcher,
  models,
  onClose,
  onSave,
}: {
  launcher: LauncherInfo | null;
  models: ModelSummary[];
  onClose(): void;
  onSave(input: LauncherInput): Promise<LauncherInfo>;
}) {
  const readOnly = launcher?.type === 'default';
  const [eventType, setEventType] = useState<string>(String(launcher?.eventType ?? 16));
  const [nodetype, setNodetype] = useState(launcher?.nodetype ?? 'cq:PageContent');
  const [glob, setGlob] = useState(launcher?.glob ?? '');
  const [runModes, setRunModes] = useState<string>(launcher?.runModes ?? 'author');
  const [conditions, setConditions] = useState<string[]>(launcher?.conditions.length ? launcher.conditions : []);
  const [workflow, setWorkflow] = useState<string | null>(launcher?.workflow ?? null);
  const [description, setDescription] = useState(launcher?.description ?? '');
  const [enabled, setEnabled] = useState(launcher?.enabled ?? true);
  const [exclude, setExclude] = useState((launcher?.excludeList ?? []).join('\n'));
  const [busy, setBusy] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const initial = () => ({
    eventType: String(launcher?.eventType ?? 16),
    nodetype: launcher?.nodetype ?? 'cq:PageContent',
    glob: launcher?.glob ?? '',
    runModes: launcher?.runModes ?? 'author',
    conditions: launcher?.conditions.length ? launcher.conditions : [],
    workflow: launcher?.workflow ?? null,
    description: launcher?.description ?? '',
    enabled: launcher?.enabled ?? true,
    exclude: (launcher?.excludeList ?? []).join('\n'),
  });
  const baseline = initial();
  const dirty =
    JSON.stringify({ eventType, nodetype, glob, runModes, conditions, workflow, description, enabled, exclude }) !== JSON.stringify(baseline);
  const reset = () => {
    const value = initial();
    setEventType(value.eventType);
    setNodetype(value.nodetype);
    setGlob(value.glob);
    setRunModes(value.runModes);
    setConditions(value.conditions);
    setWorkflow(value.workflow);
    setDescription(value.description);
    setEnabled(value.enabled);
    setExclude(value.exclude);
    setFailure(null);
    setShowErrors(false);
  };

  const globError = (() => {
    if (!glob.trim()) return 'The path is required';
    try {
      new RegExp(glob);
      return null;
    } catch {
      return 'Not a valid regular expression';
    }
  })();
  const conditionError = conditions.find((c) => c.trim() && !/^[^=!]+(==|!=)/.test(c.trim()));
  const valid = !globError && !!workflow && !!nodetype.trim() && !conditionError;
  const synced = models.filter((m) => m.version);

  const submit = async () => {
    if (!valid) {
      setShowErrors(true);
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      const saved = await onSave({
        glob: glob.trim(),
        eventType: Number(eventType),
        nodetype: nodetype.trim(),
        conditions: conditions.map((c) => c.trim()).filter(Boolean),
        excludeList: listOf(exclude),
        workflow: workflow ?? '',
        description: description.trim(),
        enabled,
        runModes,
      });
      setEventType(String(saved.eventType));
      setNodetype(saved.nodetype);
      setGlob(saved.glob);
      setRunModes(saved.runModes ?? 'author');
      setConditions(saved.conditions);
      setWorkflow(saved.workflow);
      setDescription(saved.description ?? '');
      setEnabled(saved.enabled);
      setExclude(saved.excludeList.join('\n'));
      setBusy(false);
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind="Flow trigger"
      title={launcher ? 'Trigger Properties' : 'Create Flow Trigger'}
      path={launcher?.path}
      dirty={dirty}
      busy={busy}
      canSave={!readOnly}
      requireDirty={!!launcher}
      primaryLabel={readOnly ? null : 'Save'}
      onReset={reset}
      onSave={submit}
      onClose={onClose}
      error={failure}
      wide
    >
        <div className="studio-wf__launcher-form" aria-readonly={readOnly || undefined} aria-disabled={busy || undefined}>
          <Flex gap="size-200" wrap>
            <Picker label="Event Type" isRequired selectedKey={eventType} onSelectionChange={(k) => k !== null && setEventType(String(k))} items={EVENT_TYPES} width="size-2400">
              {(e) => <Item key={String(e.value)}>{e.label}</Item>}
            </Picker>
            <ComboBox
              label="Node Type"
              isRequired
              allowsCustomValue
              inputValue={nodetype}
              onInputChange={setNodetype}
              defaultItems={NODE_TYPES.map((t) => ({ id: t }))}
              width="size-3000"
              validationState={showErrors && !nodetype.trim() ? 'invalid' : undefined}
              description="Only changes of nodes of this type start the flow."
            >
              {(t) => <Item key={t.id}>{t.id}</Item>}
            </ComboBox>
            <Picker label="Run Mode(s)" isRequired selectedKey={runModes} onSelectionChange={(k) => k !== null && setRunModes(String(k))} items={RUN_MODES} width="size-2400">
              {(r) => <Item key={r.id}>{r.label}</Item>}
            </Picker>
          </Flex>
          <TextField
            label="Path"
            isRequired
            value={glob}
            onChange={setGlob}
            validationState={showErrors && globError ? 'invalid' : undefined}
            errorMessage={globError ?? undefined}
            description="A regular expression the whole path of the changed node must match, e.g. /content/wknd(/.*)/jcr:content"
            UNSAFE_className="studio-wf__codefield"
          />
          <fieldset className="studio-wf__conditions">
            <legend>Conditions</legend>
            <p className="studio-wf__hint">All must hold. name==value, name!=value, or name!= for “has a value”; the name may be a relative path.</p>
            {conditions.map((c, i) => (
              <Flex key={i} gap="size-100" alignItems="end">
                <TextField
                  aria-label={`Condition ${i + 1}`}
                  value={c}
                  onChange={(v) => setConditions((cs) => cs.map((x, j) => (j === i ? v : x)))}
                  width="100%"
                  validationState={showErrors && c.trim() && !/^[^=!]+(==|!=)/.test(c.trim()) ? 'invalid' : undefined}
                  UNSAFE_className="studio-wf__codefield"
                />
                {!readOnly && (
                  <ActionButton aria-label={`Remove condition ${i + 1}`} onPress={() => setConditions((cs) => cs.filter((_, j) => j !== i))}>
                    <Delete />
                  </ActionButton>
                )}
              </Flex>
            ))}
            {!readOnly && (
              <ActionButton onPress={() => setConditions((cs) => [...cs, ''])}>
                <Add />
                <Text>Add</Text>
              </ActionButton>
            )}
          </fieldset>
          <Picker
            label="Flow Model"
            isRequired
            selectedKey={workflow}
            onSelectionChange={(k) => setWorkflow(k === null ? null : String(k))}
            items={synced.some((m) => m.id === workflow) || !workflow ? synced : [...synced, { id: String(workflow), title: String(workflow) } as ModelSummary]}
            validationState={showErrors && !workflow ? 'invalid' : undefined}
            errorMessage="Choose the flow to start"
            width="100%"
          >
            {(m) => <Item key={m.id}>{m.title}</Item>}
          </Picker>
          <TextField label="Description" value={description} onChange={setDescription} />
          <Switch isSelected={enabled} onChange={setEnabled}>
            Activate
          </Switch>
          <label className="studio-wf__textarea">
            <span>Exclude List</span>
            <textarea value={exclude} onChange={(e) => setExclude(e.target.value)} rows={3} readOnly={readOnly} className="studio-wf__code" />
            <span className="studio-wf__hint">
              One per line: property names (regular expressions) whose change alone does not start the flow, and
              event-user-data:changedByWorkflowProcess to ignore changes made by flows.
            </span>
          </label>
        </div>
    </FormPage>
  );
}
