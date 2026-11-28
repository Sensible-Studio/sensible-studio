import {
  ActionButton,
  Button,
  Checkbox,
  DialogContainer,
  Item,
  Menu,
  MenuTrigger,
  ProgressCircle,
  SearchField,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Branch1 from '@spectrum-icons/workflow/Branch1';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronUp from '@spectrum-icons/workflow/ChevronUp';
import Branch2 from '@spectrum-icons/workflow/Branch2';
import Delete from '@spectrum-icons/workflow/Delete';
import Gears from '@spectrum-icons/workflow/Gears';
import Play from '@spectrum-icons/workflow/Play';
import Properties from '@spectrum-icons/workflow/Properties';
import RailLeft from '@spectrum-icons/workflow/RailLeft';
import Redo from '@spectrum-icons/workflow/Redo';
import Refresh from '@spectrum-icons/workflow/Refresh';
import User from '@spectrum-icons/workflow/User';
import UserArrow from '@spectrum-icons/workflow/UserArrow';
import Wrench from '@spectrum-icons/workflow/Wrench';
import { Fragment, useCallback, useEffect, useMemo, useState, type DragEvent, type ReactElement } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { ComponentDialog, type OpenDialog } from '../editor/dialogs/ComponentDialog';
import { studioGraniteEnv } from '../editor/dialogs/graniteEnv';
import type { DialogResponse, GraniteEnv, GraniteNode, PostEntry } from '../granite';
import { flatten, STEP_TYPES, stepKind, workflowUrl, type ModelDesign, type Processes, type Step, type WorkflowClient } from './api';
import { RunWorkflowDialog } from './ModelsConsole';
import { workflowRegistry } from './registry';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { FormPage } from '../shell/FormPage';
import { ScreenFrame } from '../shell/ScreenFrame';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

/** The steps the side panel offers, in the order AEM users expect them. */
export const PALETTE: Array<{ kind: keyof typeof STEP_TYPES; title: string; description: string }> = [
  { kind: 'participant', title: 'Participant Step', description: 'Gives the step to a user or group and waits' },
  { kind: 'dynamicParticipant', title: 'Dynamic Participant Step', description: 'Gives the step to whom a chooser selects' },
  { kind: 'process', title: 'Process Step', description: 'Runs a registered process' },
  { kind: 'or', title: 'OR Split', description: 'Continues with one of its branches' },
  { kind: 'and', title: 'AND Split', description: 'Runs all branches, then joins' },
  { kind: 'goto', title: 'Goto Step', description: 'Jumps to another step while its rule holds' },
];

const ICONS: Record<keyof typeof STEP_TYPES | 'unknown', (props: { size?: 'S' | 'M' }) => ReactElement> = {
  process: (p) => <Gears {...p} />,
  participant: (p) => <User {...p} />,
  dynamicParticipant: (p) => <UserArrow {...p} />,
  or: (p) => <Branch1 {...p} />,
  and: (p) => <Branch2 {...p} />,
  goto: (p) => <Redo {...p} />,
  unknown: (p) => <Gears {...p} />,
};

const DRAG_TYPE = 'application/x-studio-workflow-step';
type DragPayload = { kind: 'new'; resourceType: string; title: string } | { kind: 'move'; path: string };

function readDrag(e: DragEvent): DragPayload | null {
  try {
    const raw = e.dataTransfer.getData(DRAG_TYPE);
    return raw ? (JSON.parse(raw) as DragPayload) : null;
  } catch {
    return null;
  }
}

interface Props {
  client: WorkflowClient;
  studio: StudioClient;
  path: string;
}

/**
 * The workflow model editor (`/editor.html/conf/global/settings/workflow/models/<m>.html`): the flow from start to
 * end with OR/AND branches side by side. Drag steps from the side panel (or move existing ones) onto the flow,
 * configure them with their component dialog, and Sync to write the runtime model workflows start with.
 */
export function ModelEditor({ client, studio, path }: Props) {
  const go = useNavigate();
  const tab = useTab();
  const [model, setModel] = useState<ModelDesign | null>(null);
  useScreenTitle(model ? `${VOCAB.flow} · ${model.title}` : null, model ? `${model.title} | ${VOCAB.flows} | ${VOCAB.product}` : null);
  const [error, setError] = useState<string | null>(null);
  const [panel, setPanel] = useState(true);
  const [filter, setFilter] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [dialog, setDialog] = useState<(OpenDialog & { resourceType: string }) | null>(null);
  const [editingProps, setEditingProps] = useState(false);
  const [running, setRunning] = useState(false);
  const [processes, setProcesses] = useState<Processes | null>(null);
  const registry = useMemo(() => workflowRegistry(client), [client]);
  const env: GraniteEnv = useMemo(() => studioGraniteEnv(studio, dialog?.path), [studio, dialog?.path]);

  const load = useCallback(
    () =>
      client.model(path).then(
        (m) => {
          setModel(m);
          setError(null);
        },
        (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
      ),
    [client, path],
  );
  useEffect(() => {
    void load();
    client.processes().then(setProcesses, () => setProcesses(null));
  }, [client, load]);

  const editable = !!model?.editable;
  const steps = useMemo(() => (model ? flatten(model.steps) : []), [model]);
  const selectedStep = steps.find((s) => s.path === selected) ?? null;

  const act = async (work: () => Promise<unknown>, success?: string) => {
    setBusy(true);
    try {
      await work();
      if (success) ToastQueue.positive(success, { timeout: 2500 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      await load();
      setBusy(false);
    }
  };

  const drop = (payload: DragPayload, container: string, before?: string) =>
    act(async () => {
      if (payload.kind === 'new') {
        const created = await client.addStep(container, payload.resourceType, payload.title, before);
        setSelected(created.path);
      } else if (payload.path !== `${container}/${before}`) {
        await client.moveStep(payload.path, container, before);
      }
    });

  const configure = async (step: Step) => {
    try {
      const [response, content] = await Promise.all([studio.getDialog(step.resourceType, 'cq:dialog', step.path), studio.getContent(step.path)]);
      if (!response.root) {
        ToastQueue.info(`This step has no dialog. ${response.warnings.join(' ')}`.trim(), { timeout: 6000 });
        return;
      }
      setDialog({
        title: step.title || step.name,
        path: step.path,
        resourceType: step.resourceType,
        dialog: response as DialogResponse & { root: GraniteNode },
        content,
        variant: 'component',
      });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
  };

  const saveDialog = async (entries: PostEntry[]) => {
    if (!dialog) return;
    await client.post(
      dialog.path,
      entries.map((e) => [e.name, e.value]),
    );
    if (stepKind(dialog.resourceType) === 'or' || stepKind(dialog.resourceType) === 'and') await client.branches(dialog.path);
    else await client.touch(dialog.path);
    setDialog(null);
    await load();
  };

  const remove = (step: Step) =>
    act(async () => {
      await client.deleteStep(step.path);
      setSelected(null);
    }, `${step.title || step.name} deleted`);

  const editCopy = () =>
    act(async () => {
      const copy = await client.copyModel(path);
      go(workflowUrl.editor(copy.designPath));
    });

  const sync = () =>
    act(async () => {
      const r = await client.sync(path);
      ToastQueue.positive(`Synced: runtime model version ${r.version}`, { timeout: 3000 });
    });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (tab && !tab.active) return; // another tab is in front
      const t = e.target as HTMLElement | null;
      if (dialog || editingProps || running || t?.closest('input, textarea, [role="dialog"]')) return;
      if (e.key === 'Escape') setSelected(null);
      if ((e.key === 'Delete' || e.key === 'Backspace') && selectedStep && editable) void remove(selectedStep);
      if (e.key === 'Enter' && selectedStep && editable) void configure(selectedStep);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const palette = PALETTE.filter((p) => !filter || `${p.title} ${p.description}`.toLowerCase().includes(filter.toLowerCase()));
  const close = () => (tab ? tab.leave(workflowUrl.models()) : go(workflowUrl.models()));

  const describe = (step: Step): string | null => {
    const m = step.metaData;
    switch (stepKind(step.resourceType)) {
      case 'process': {
        const p = String(m.PROCESS ?? '');
        if (!p) return 'No process selected';
        const label = processes && Object.entries(processes.processes).find(([l, c]) => c === p || l === p)?.[0];
        // the process is registered under AEM's word for the content a flow is about; the card says it in ours
        return (label ?? p.slice(p.lastIndexOf('.') + 1)).replace('Set Payload Properties', 'Set properties on the content');
      }
      case 'participant':
        return m.PARTICIPANT ? `Assigned to ${String(m.PARTICIPANT)}` : 'No participant selected';
      case 'dynamicParticipant': {
        const c = String(m.DYNAMIC_PARTICIPANT ?? '');
        if (!c) return 'No chooser selected';
        return processes && Object.entries(processes.choosers).find(([l, k]) => k === c || l === c)?.[0] || c.slice(c.lastIndexOf('.') + 1);
      }
      case 'goto': {
        const target = steps.find((s) => s.path === m.TARGET_NODE_PATH);
        return target ? `Go to ${target.title || target.name}` : 'No target step';
      }
      default:
        return step.description || null;
    }
  };

  // ------------------------------------------------------------------------------------------ flow rendering

  const onDrop = (payload: DragPayload, container: string, before?: string) => {
    setDragging(false);
    void drop(payload, container, before);
  };

  const renderStep = (step: Step) => {
    const kind = stepKind(step.resourceType);
    const Icon = ICONS[kind];
    const isSel = selected === step.path;
    const box = (
      <div
        className={`studio-wfe__step studio-wfe__step--${kind}${isSel ? ' is-selected' : ''}`}
        role="button"
        tabIndex={0}
        aria-pressed={isSel}
        draggable={editable}
        onDragStart={(e) => {
          e.dataTransfer.setData(DRAG_TYPE, JSON.stringify({ kind: 'move', path: step.path } satisfies DragPayload));
          e.dataTransfer.effectAllowed = 'move';
          setDragging(true);
        }}
        onDragEnd={() => setDragging(false)}
        onClick={(e) => {
          e.stopPropagation();
          setSelected(isSel ? null : step.path);
        }}
        onDoubleClick={() => editable && void configure(step)}
      >
        <span className="studio-wfe__stepicon">
          <Icon size="S" />
        </span>
        <span className="studio-wfe__steptext">
          <span className="studio-wfe__steptitle">{step.title || PALETTE.find((p) => p.kind === kind)?.title || step.name}</span>
          {describe(step) && <span className="studio-wfe__stepdesc">{describe(step)}</span>}
          {typeof step.metaData.workflowStage === 'string' && step.metaData.workflowStage && (
            <span className="studio-wfe__stage" title="Flow stage">
              {step.metaData.workflowStage}
            </span>
          )}
        </span>
        {isSel && editable && (
          <span className="studio-wfe__toolbar" role="toolbar" aria-label="Step actions" onClick={(e) => e.stopPropagation()}>
            <TooltipTrigger>
              <ActionButton isQuiet aria-label="Configure" onPress={() => void configure(step)}>
                <Wrench />
              </ActionButton>
              <Tooltip>Configure</Tooltip>
            </TooltipTrigger>
            <TooltipTrigger>
              <ActionButton isQuiet aria-label="Delete" onPress={() => void remove(step)}>
                <Delete />
              </ActionButton>
              <Tooltip>Delete</Tooltip>
            </TooltipTrigger>
          </span>
        )}
      </div>
    );
    if (!step.branches) return box;
    return (
      <div className="studio-wfe__split">
        {box}
        <div className="studio-wfe__branches">
          {step.branches.map((b, i) => (
            <div key={b.path} className="studio-wfe__branch" aria-label={`Branch ${i + 1}`}>
              {renderContainer(b.path, b.steps)}
            </div>
          ))}
        </div>
        <div className="studio-wfe__join" aria-hidden />
      </div>
    );
  };

  const renderContainer = (container: string, list: Step[]) => (
    <div className="studio-wfe__container">
      {list.map((s) => (
        <Fragment key={s.path}>
          <DropZone container={container} before={s.name} editable={editable} dragging={dragging} onDrop={onDrop} />
          {renderStep(s)}
        </Fragment>
      ))}
      <DropZone container={container} last editable={editable} dragging={dragging} onDrop={onDrop} />
    </div>
  );

  if (error) {
    return (
      <div className="studio-wfe">
        <ScreenFrame identity={<strong>Flow model</strong>} onClose={close} closeLabel="Close flow model" />
        <ErrorState title="Could not open the flow model" action={{ label: 'Back to Flow Models', onPress: close }}>{error}</ErrorState>
      </div>
    );
  }
  if (!model) {
    return (
      <div className="studio-wfe studio-wfe--loading">
        <ScreenFrame identity={<strong>Flow model</strong>} onClose={close} closeLabel="Close flow model" />
        <LoadingState label="Loading flow model" />
      </div>
    );
  }

  if (editingProps) {
    return (
      <div className="studio-wfe">
        <ModelPropertiesDialog
          model={model}
          onClose={() => setEditingProps(false)}
          onSave={async (title, description, transient, stages) => {
            const content = `${model.designPath}/jcr:content`;
            await client.post(content, [
              ['jcr:title', title],
              ['jcr:description', description],
              transient ? ['transient', 'true'] : ['transient@Delete', ''],
              ...(stages.length
                ? [['cq:workflowStages@TypeHint', 'String[]'] as [string, string], ...stages.map((st): [string, string] => ['cq:workflowStages', st])]
                : [['cq:workflowStages@Delete', ''] as [string, string]]),
            ]);
            await client.touch(content);
            await load();
          }}
        />
        <ToastContainer placement="bottom" />
      </div>
    );
  }

  return (
    <div className="studio-wfe">
      <ScreenFrame
        navigation={
          <>
          <TooltipTrigger>
            <ActionButton isQuiet aria-label="Toggle Side Panel" onPress={() => setPanel((p) => !p)} isDisabled={!editable}>
              <RailLeft />
            </ActionButton>
            <Tooltip>Toggle Side Panel</Tooltip>
          </TooltipTrigger>
          {editable && (
            <TooltipTrigger>
              <ActionButton isQuiet aria-label="Model Properties" onPress={() => setEditingProps(true)}>
                <Properties />
              </ActionButton>
              <Tooltip>Model Properties</Tooltip>
            </TooltipTrigger>
          )}
          </>
        }
        identity={<><h1 className="studio-wfe__title">{model.title}</h1><span className={`studio-wfe__status${model.synced ? ' is-synced' : ''}`}>{model.version ? `v${model.version}` : 'Never synced'} · {model.synced ? 'Synced' : 'Changes not synced'}</span></>}
        actions={
          <>
          {busy && <ProgressCircle size="S" isIndeterminate aria-label="Saving" />}
          <ActionButton isQuiet onPress={() => setRunning(true)} isDisabled={!model.version}>
            <Play />
            <Text>Start Flow</Text>
          </ActionButton>
          {model.type === 'default' && (
            <TooltipTrigger>
              <Button variant="secondary" onPress={() => void editCopy()} isDisabled={busy}>
                Edit
              </Button>
              <Tooltip>Copy this default model to /conf/global so it can be edited</Tooltip>
            </TooltipTrigger>
          )}
          </>
        }
        primary={editable ? (
            <Button variant="accent" onPress={() => void sync()} isDisabled={busy || model.synced}>
              <Refresh />
              <Text>Sync</Text>
            </Button>
          ) : undefined}
        onClose={close}
        closeLabel="Close flow model"
      />
      <div className="studio-wfe__body">
        {editable && panel && (
          <aside className="studio-wfe__panel" aria-label="Steps">
            <h2 className="studio-wfe__paneltitle">Steps</h2>
            <SearchField aria-label="Filter steps" value={filter} onChange={setFilter} width="100%" />
            <p className="studio-wfe__group">Flow</p>
            <ul className="studio-wfe__palette">
              {palette.map((p) => {
                const Icon = ICONS[p.kind];
                const payload: DragPayload = { kind: 'new', resourceType: STEP_TYPES[p.kind], title: p.title };
                return (
                  <li
                    key={p.kind}
                    className="studio-wfe__paletteitem"
                    draggable
                    onDragStart={(e) => {
                      e.dataTransfer.setData(DRAG_TYPE, JSON.stringify(payload));
                      e.dataTransfer.effectAllowed = 'copyMove';
                      setDragging(true);
                    }}
                    onDragEnd={() => setDragging(false)}
                    onDoubleClick={() => void drop(payload, model.flowPath)}
                    title="Drag onto the flow, or double-click to append"
                  >
                    <span className="studio-wfe__stepicon">
                      <Icon size="S" />
                    </span>
                    <span className="studio-wfe__steptext">
                      <span className="studio-wfe__steptitle">{p.title}</span>
                      <span className="studio-wfe__stepdesc">{p.description}</span>
                    </span>
                  </li>
                );
              })}
            </ul>
          </aside>
        )}
        <main
          className="studio-wfe__canvas"
          onClick={() => setSelected(null)}
          ref={(el) => {
            // a flow with branches is wider than a narrow window, and it is drawn around its middle:
            // opened at the left edge, its start was out of sight
            if (el && !el.dataset.centred) {
              el.dataset.centred = '1';
              requestAnimationFrame(() => {
                el.scrollLeft = Math.max(0, (el.scrollWidth - el.clientWidth) / 2);
              });
            }
          }}
        >
          {!editable && (
            <p className="studio-wfe__readonly">
              {model.type === 'default'
                ? 'This is a default model. Edit makes an editable copy under /conf/global that replaces it.'
                : 'This runtime model has no design to edit.'}
            </p>
          )}
          <div className="studio-wfe__flow">
            <div className="studio-wfe__terminal">Flow Start</div>
            {renderContainer(model.flowPath, model.steps)}
            <div className="studio-wfe__terminal">Flow End</div>
          </div>
        </main>
      </div>

      {dialog && (
        <ComponentDialog {...dialog} registry={registry} env={env} onSubmit={saveDialog} onClose={() => setDialog(null)} />
      )}
      <DialogContainer onDismiss={() => setRunning(false)}>
        {running && <RunWorkflowDialog client={client} studio={studio} model={model} onClose={() => setRunning(false)} />}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

interface DropZoneProps {
  container: string;
  before?: string;
  last?: boolean;
  editable: boolean;
  dragging: boolean;
  onDrop(payload: DragPayload, container: string, before?: string): void;
}

const INSERT_LABEL = 'Drag steps here';

/** Where a step can be dropped: between two steps, or at the end of the flow or of a branch. */
function DropZone({ container, before, last, editable, dragging, onDrop }: DropZoneProps) {
  const [over, setOver] = useState(false);
  if (!editable) return <span className="studio-wfe__connector" aria-hidden />;
  return (
    <div
      className={`studio-wfe__drop${over ? ' is-over' : ''}${dragging ? ' is-active' : ''}${last ? ' is-last' : ''}`}
      data-container={container}
      data-before={before}
      onDragOver={(e) => {
        if (!e.dataTransfer.types.includes(DRAG_TYPE)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        const payload = readDrag(e);
        if (payload) onDrop(payload, container, before);
      }}
    >
      {last && (
        // like AEM's "Drag components here": clicking it offers the steps as a menu
        <MenuTrigger>
          <ActionButton isQuiet UNSAFE_className="studio-wfe__drophint" aria-label={`${INSERT_LABEL} (or choose one)`}>
            {INSERT_LABEL}
          </ActionButton>
          <Menu
            aria-label="Insert step"
            onAction={(k) => {
              const p = PALETTE.find((x) => x.kind === k);
              if (p) onDrop({ kind: 'new', resourceType: STEP_TYPES[p.kind], title: p.title }, container);
            }}
          >
            {PALETTE.map((p) => (
              <Item key={p.kind} textValue={p.title}>
                {p.title}
              </Item>
            ))}
          </Menu>
        </MenuTrigger>
      )}
    </div>
  );
}

function ModelPropertiesDialog({
  model,
  onClose,
  onSave,
}: {
  model: ModelDesign;
  onClose(): void;
  onSave(title: string, description: string, transient: boolean, stages: string[]): Promise<void>;
}) {
  const [title, setTitle] = useState(model.title);
  const [description, setDescription] = useState(model.description ?? '');
  const [transient, setTransient] = useState(!!model.transient);
  const [stages, setStages] = useState<string[]>(model.stages ?? []);
  const move = (i: number, by: number) =>
    setStages((list) => {
      const next = [...list];
      const [x] = next.splice(i, 1);
      next.splice(i + by, 0, x);
      return next;
    });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const dirty =
    title !== model.title ||
    description !== (model.description ?? '') ||
    transient !== !!model.transient ||
    JSON.stringify(stages) !== JSON.stringify(model.stages ?? []);
  const reset = () => {
    setTitle(model.title);
    setDescription(model.description ?? '');
    setTransient(!!model.transient);
    setStages(model.stages ?? []);
    setFailure(null);
  };
  const save = async () => {
    setBusy(true);
    try {
      await onSave(
        title.trim(),
        description,
        transient,
        stages.map((x) => x.trim()).filter(Boolean),
      );
      setBusy(false);
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <FormPage
      kind="Flow model"
      title="Flow Model Properties"
      path={model.designPath}
      dirty={dirty}
      busy={busy}
      canSave={!!title.trim()}
      onReset={reset}
      onSave={save}
      onClose={onClose}
      error={failure}
      wide
    >
        <div className="studio-wf__model-properties">
          <TextField label="Title" isRequired value={title} onChange={setTitle} autoFocus />
          <TextArea label="Description" value={description} onChange={setDescription} />
          <Checkbox isSelected={transient} onChange={setTransient}>
            Transient Flow
          </Checkbox>
          <p className="studio-wf__hint">A transient flow keeps no history: its instance is removed a minute after it ends.</p>
          <TextField label="Path" value={model.designPath} isReadOnly />
        </div>
        <fieldset className="studio-wf__conditions studio-wf__stages">
          <legend>Stages</legend>
          <p className="studio-wf__hint">
            The phases of the workflow, in order. Each step can belong to one (Workflow Stage in its dialog); the Inbox and the
            instances show the stage a flow is in.
          </p>
          {stages.map((st, i) => (
            <div key={i} className="studio-wf__stagerow">
              <TextField aria-label={`Stage ${i + 1}`} value={st} onChange={(v) => setStages((l) => l.map((x, j) => (j === i ? v : x)))} width="100%" />
              <ActionButton aria-label={`Move stage ${i + 1} up`} isDisabled={i === 0} onPress={() => move(i, -1)}>
                <ChevronUp />
              </ActionButton>
              <ActionButton aria-label={`Move stage ${i + 1} down`} isDisabled={i === stages.length - 1} onPress={() => move(i, 1)}>
                <ChevronDown />
              </ActionButton>
              <ActionButton aria-label={`Remove stage ${i + 1}`} onPress={() => setStages((l) => l.filter((_, j) => j !== i))}>
                <Delete />
              </ActionButton>
            </div>
          ))}
          <ActionButton onPress={() => setStages((l) => [...l, ''])}>
            <Add />
            <Text>Add Stage</Text>
          </ActionButton>
        </fieldset>
    </FormPage>
  );
}
