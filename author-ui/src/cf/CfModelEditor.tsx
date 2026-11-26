import {
  ActionButton,
  ActionMenu,
  Button,
  Checkbox,
  CheckboxGroup,
  DialogContainer,
  Divider,
  Flex,
  Heading,
  InlineAlert,
  Item,
  ListView,
  NumberField,
  Picker,
  Radio,
  RadioGroup,
  StatusLight,
  Switch,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  Content,
  isTextDropItem,
  useDragAndDrop,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Settings from '@spectrum-icons/workflow/Settings';
import { useCallback, useEffect, useMemo, useRef, useState, type Key } from 'react';
import {
  CfValidationError,
  cfConsoleUrl,
  cfModelsUrl,
  type CfClient,
  type CfField,
  type CfModel,
  type CfModelSummary,
  type FieldKind,
} from './api';
import { DataTypeIcon } from './DataTypeIcon';
import { checkFields, DATA_TYPES, errorsByField, MULTI_KINDS, newField, propertyName, typeTitle, uniqueName } from './modelTypes';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { InspectorShell } from '../shell/InspectorShell';

interface Props {
  client: CfClient;
  path: string;
}

/** A field being edited; `key` is the item id, or a client key until the first save. */
type EField = CfField & { key: string };

type FieldErrors = Record<string, string>;

const KIND_TYPE = 'application/x-studio-cf-kind';
const FIELD_TYPE = 'application/x-studio-cf-field';
const MODEL = '__model__';

let counter = 0;
const clientKey = () => `new-${++counter}`;

const strip = (fields: EField[]): CfField[] =>
  fields.map(({ key: _key, ...f }) => {
    void _key;
    return f;
  });

const snapshot = (title: string, description: string, fields: EField[]) => JSON.stringify([title, description, strip(fields)]);

const confOf = (path: string) => path.slice(0, path.indexOf('/settings/'));

/**
 * The content fragment model editor (`/editor.html/conf/<conf>/settings/dam/cfm/models/<model>`): data types on
 * the left (click or drag them into the model), the model's elements in the middle (drag or move to reorder), the
 * selected element's properties, or the model's, on the right. Saves the whole model at once.
 */
export function CfModelEditor({ client, path }: Props) {
  const [model, setModel] = useState<CfModel | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [fields, setFields] = useState<EField[]>([]);
  const [baseline, setBaseline] = useState('');
  const [selected, setSelected] = useState<string>(MODEL);
  const [serverErrors, setServerErrors] = useState<{ model: FieldErrors; fields: Record<string, FieldErrors> }>({ model: {}, fields: {} });
  const [showErrors, setShowErrors] = useState(false);
  const [saving, setSaving] = useState(false);
  const [models, setModels] = useState<CfModelSummary[]>([]);
  const [usage, setUsage] = useState<number | null>(null);
  const [dialog, setDialog] = useState<null | { kind: 'leave'; url?: string; answer?: (ok: boolean) => void } | { kind: 'delete' }>(null);
  const tab = useTab();
  const go = useNavigate();
  useScreenTitle(model ? `Model · ${model.title}` : null, model ? `${model.title} | Sensible Studio Model` : null);
  // fields whose element name follows the label (new fields, until the name is edited)
  const followLabel = useRef(new Set<string>());

  const load = useCallback((m: CfModel) => {
    const f = m.fields.map((x) => ({ ...x, key: x.id ?? clientKey() }));
    followLabel.current.clear();
    setModel(m);
    setTitle(m.title);
    setDescription(m.description ?? '');
    setFields(f);
    setBaseline(snapshot(m.title, m.description ?? '', f));
    setServerErrors({ model: {}, fields: {} });
    setShowErrors(false);
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([client.model(path), client.models()])
      .then(([m, all]) => {
        if (cancelled) return;
        load(m);
        setModels(all);
      })
      .catch((e: unknown) => !cancelled && setFailure(e instanceof Error ? e.message : String(e)));
    client
      .modelUsage(path)
      .then((u) => !cancelled && setUsage(u.fragments.length))
      .catch(() => !cancelled && setUsage(null));
    return () => {
      cancelled = true;
    };
  }, [client, path, load]);

  const dirty = model !== null && snapshot(title, description, fields) !== baseline;
  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;

  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current) e.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, []);

  // client checks, by field key
  const clientErrors = useMemo(() => {
    const byIndex = errorsByField(checkFields(strip(fields)));
    const out: Record<string, FieldErrors> = {};
    for (const [i, errs] of Object.entries(byIndex.fields)) out[fields[Number(i)].key] = errs;
    return out;
  }, [fields]);
  const errorsOf = (key: string): FieldErrors => ({ ...(showErrors ? clientErrors[key] : pickNameErrors(clientErrors[key])), ...serverErrors.fields[key] });
  const titleError = serverErrors.model.title ?? (showErrors && !title.trim() ? 'A title is required.' : undefined);

  const taken = (except?: string) => fields.filter((f) => f.key !== except && f.kind !== 'tab').map((f) => f.name);

  const insert = (kinds: FieldKind[], at?: number) => {
    const added: EField[] = [];
    const names = taken();
    for (const kind of kinds) {
      const f = { ...newField(kind, names, confRoot(path)), key: clientKey() };
      names.push(f.name);
      if (kind !== 'tab') followLabel.current.add(f.key);
      added.push(f);
    }
    setFields((prev) => {
      const index = at ?? (selected === MODEL ? prev.length : prev.findIndex((f) => f.key === selected) + 1 || prev.length);
      return [...prev.slice(0, index), ...added, ...prev.slice(index)];
    });
    if (added.length) setSelected(added[added.length - 1].key);
  };

  const update = (key: string, patch: Partial<CfField>) => {
    setFields((prev) =>
      prev.map((f) => {
        if (f.key !== key) return f;
        const next = { ...f, ...patch };
        if (patch.label !== undefined && followLabel.current.has(key)) {
          next.name = uniqueName(propertyName(patch.label), prev.filter((x) => x.key !== key && x.kind !== 'tab').map((x) => x.name));
        }
        return next;
      }),
    );
    if (patch.name !== undefined) followLabel.current.delete(key);
    setServerErrors((e) => (e.fields[key] ? { ...e, fields: { ...e.fields, [key]: {} } } : e));
  };

  const move = (key: string, by: number) =>
    setFields((prev) => {
      const i = prev.findIndex((f) => f.key === key);
      const j = i + by;
      if (i < 0 || j < 0 || j >= prev.length) return prev;
      const next = [...prev];
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });

  const remove = (key: string) => {
    setFields((prev) => prev.filter((f) => f.key !== key));
    if (selected === key) setSelected(MODEL);
  };

  const duplicate = (key: string) =>
    setFields((prev) => {
      const i = prev.findIndex((f) => f.key === key);
      if (i < 0) return prev;
      const { id: _id, ...src } = prev[i];
      void _id;
      const copy: EField = {
        ...structuredClone(src),
        key: clientKey(),
        label: `${src.label} copy`,
        name: src.kind === 'tab' ? '' : uniqueName(src.name, prev.map((f) => f.name)),
      };
      setSelected(copy.key);
      return [...prev.slice(0, i + 1), copy, ...prev.slice(i + 1)];
    });

  const save = useCallback(async () => {
    if (!model || saving) return;
    const problems = checkFields(strip(fields));
    if (problems.length || !title.trim()) {
      setShowErrors(true);
      const first = errorsByField(problems).fields;
      const firstIndex = Object.keys(first).map(Number).sort((a, b) => a - b)[0];
      if (firstIndex !== undefined) setSelected(fields[firstIndex].key);
      else if (!title.trim()) setSelected(MODEL);
      ToastQueue.negative(`${problems.length + (title.trim() ? 0 : 1)} problem(s) to fix before saving`, { timeout: 5000 });
      return;
    }
    setSaving(true);
    try {
      const saved = await client.saveModel({ path: model.path, title: title.trim(), description: description.trim(), fields: strip(fields) });
      const keepSelection = fields.findIndex((f) => f.key === selected);
      load(saved);
      setSelected(keepSelection >= 0 && saved.fields[keepSelection]?.id ? saved.fields[keepSelection].id! : MODEL);
      ToastQueue.positive('Model saved', { timeout: 2500 });
    } catch (e) {
      if (e instanceof CfValidationError) {
        const byIndex = errorsByField(e.errors);
        const byKey: Record<string, FieldErrors> = {};
        for (const [i, errs] of Object.entries(byIndex.fields)) if (fields[Number(i)]) byKey[fields[Number(i)].key] = errs;
        setServerErrors({ model: byIndex.model, fields: byKey });
        setShowErrors(true);
        const firstKey = Object.keys(byKey)[0];
        if (firstKey) setSelected(firstKey);
        ToastQueue.negative(`${e.errors.length} problem(s) to fix before saving`, { timeout: 5000 });
      } else {
        ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
      }
    } finally {
      setSaving(false);
    }
  }, [client, model, fields, title, description, selected, saving, load]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (tab && !tab.active) return; // another tab is in front
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        void save();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [save, tab]);

  const leave = (url: string) => {
    if (dirty) setDialog({ kind: 'leave', url });
    else if (tab) tab.leave(url);
    else go(url);
  };

  // In the shell, Back / Forward / a link / closing the tab ask first while there are unsaved changes.
  useEffect(() => {
    if (!tab) return;
    tab.setGuard(() => (dirtyRef.current ? new Promise<boolean>((answer) => setDialog({ kind: 'leave', answer })) : Promise.resolve(true)));
    return () => tab.setGuard(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab?.id]);

  const setEnabled = async (enabled: boolean) => {
    if (!model) return;
    try {
      const m = await client.setModelStatus(model.path, enabled);
      setModel((prev) => (prev ? { ...prev, enabled: m.enabled } : prev));
      ToastQueue.positive(enabled ? 'Model enabled' : 'Model disabled: no new content fragments can use it', { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
  };

  // ------------------------------------------------------------------------------------------- drag and drop

  const palette = useDragAndDrop({
    getItems: (keys) => [...keys].map((k) => ({ [KIND_TYPE]: String(k), 'text/plain': typeTitle(String(k) as FieldKind) })),
    getAllowedDropOperations: () => ['copy'],
  });

  const readKinds = async (items: Parameters<NonNullable<Parameters<typeof useDragAndDrop>[0]['onInsert']>>[0]['items']) => {
    const kinds: FieldKind[] = [];
    for (const item of items) {
      if (isTextDropItem(item) && item.types.has(KIND_TYPE)) kinds.push((await item.getText(KIND_TYPE)) as FieldKind);
    }
    return kinds;
  };

  const elements = useDragAndDrop({
    getItems: (keys) => [...keys].map((k) => ({ [FIELD_TYPE]: String(k), 'text/plain': String(k) })),
    acceptedDragTypes: [KIND_TYPE, FIELD_TYPE],
    getDropOperation: (_target, types) => (types.has(KIND_TYPE) ? 'copy' : 'move'),
    onReorder: (e) => {
      setFields((prev) => {
        const moving = prev.filter((f) => e.keys.has(f.key));
        const rest = prev.filter((f) => !e.keys.has(f.key));
        let index = rest.findIndex((f) => f.key === e.target.key);
        if (index < 0) return prev;
        if (e.target.dropPosition === 'after') index++;
        return [...rest.slice(0, index), ...moving, ...rest.slice(index)];
      });
    },
    onInsert: async (e) => {
      const kinds = await readKinds(e.items);
      const index = fields.findIndex((f) => f.key === e.target.key);
      insert(kinds, index < 0 ? undefined : e.target.dropPosition === 'after' ? index + 1 : index);
    },
    onRootDrop: async (e) => insert(await readKinds(e.items), fields.length),
  });

  if (failure) {
    return (
      <div className="studio-cf">
        <ScreenFrame identity={<strong>Content Fragment Model</strong>} onClose={() => go(cfModelsUrl())} closeLabel="Close model editor" />
        <ErrorState title="Could not open the model" action={{ label: 'Back to models', onPress: () => go(cfModelsUrl()) }}>{failure}</ErrorState>
      </div>
    );
  }
  if (!model) {
    return (
      <div className="studio-cf">
        <ScreenFrame identity={<strong>Content Fragment Model</strong>} onClose={() => go(cfModelsUrl())} closeLabel="Close model editor" />
        <LoadingState label="Loading model" />
      </div>
    );
  }

  const current = fields.find((f) => f.key === selected) ?? null;
  // ListView re-renders an item only when its object changes: carry the first error in the item
  const listItems = fields.map((f) => ({ ...f, problem: Object.values(errorsOf(f.key))[0] as string | undefined }));
  const conf = confOf(model.path);

  return (
    <div className="studio-cf studio-cfme">
      <ScreenFrame
        identity={<div className="studio-cf__title"><span className="studio-cf__titletext">{title || model.title}</span><span className="studio-cf__subtitle">Content Fragment Model · {conf.replace(/^\/conf\//, '')}</span></div>}
        actions={
          <StatusLight variant={dirty ? 'notice' : model.enabled ? 'positive' : 'neutral'}>
            {dirty ? 'Unsaved changes' : model.enabled ? 'Enabled' : 'Disabled'}
          </StatusLight>
        }
        primary={<Button variant="accent" onPress={() => void save()} isDisabled={!dirty || saving} isPending={saving}>
          Save
        </Button>}
        onClose={() => leave(cfModelsUrl(conf))}
        closeLabel="Close model editor"
      />

      <div className="studio-cf__body studio-cfme__body">
        <aside className="studio-cf__rail" aria-label="Data types">
          <Heading level={3} marginTop={0} UNSAFE_className="studio-cf__railtitle">
            Data types
          </Heading>
          <Text UNSAFE_className="studio-cf__muted">Drag into the model, or select to add after the selected element.</Text>
          <ListView
            aria-label="Data types"
            items={DATA_TYPES}
            selectionMode="none"
            density="compact"
            marginTop="size-150"
            onAction={(k: Key) => insert([String(k) as FieldKind])}
            dragAndDropHooks={palette.dragAndDropHooks}
          >
            {(t) => (
              <Item key={t.kind} textValue={t.title}>
                <DataTypeIcon kind={t.kind} />
                <Text>{t.title}</Text>
                <Text slot="description">{t.description}</Text>
              </Item>
            )}
          </ListView>
        </aside>

        <main className="studio-cf__main">
          <div className="studio-cf__sheet">
            <div className="studio-cfme__head">
              <div className="studio-cf__sheethead">
                <Heading level={2} margin={0}>
                  {title || 'Untitled model'}
                </Heading>
                <span className="studio-cf__muted">{description || 'No description'}</span>
              </div>
              <ActionButton onPress={() => setSelected(MODEL)} isQuiet={selected !== MODEL} aria-pressed={selected === MODEL}>
                <Settings />
                <Text>Model properties</Text>
              </ActionButton>
            </div>
            {usage !== null && usage > 0 && (
              <InlineAlert variant="info" marginBottom="size-200">
                <Heading>Used by {usage === 200 ? '200+' : usage} content fragment{usage === 1 ? '' : 's'}</Heading>
                <Content>
                  Renaming or removing an element does not move existing values: content fragments keep them under the old name.
                </Content>
              </InlineAlert>
            )}
            <ListView
              aria-label="Elements"
              items={listItems}
              selectionMode="single"
              selectionStyle="highlight"
              selectedKeys={selected === MODEL ? [] : [selected]}
              onSelectionChange={(keys) => {
                const k = [...(keys as Set<Key>)][0];
                setSelected(k === undefined ? MODEL : String(k));
              }}
              dragAndDropHooks={elements.dragAndDropHooks}
              minHeight="size-2400"
              UNSAFE_className="studio-cfme__elements"
              renderEmptyState={() => (
                <div className="studio-cfme__empty">
                  <Add />
                  <span>Drag data types here, or select one on the left.</span>
                </div>
              )}
            >
              {(f) => {
                return (
                  <Item key={f.key} textValue={f.label || f.name}>
                    <DataTypeIcon kind={f.kind} />
                    <Text UNSAFE_className={f.kind === 'tab' ? 'studio-cfme__tab' : undefined}>
                      {f.label || '(no label)'}
                      {f.required ? ' *' : ''}
                    </Text>
                    <Text slot="description">
                      {f.problem ? `⚠ ${f.problem}` : f.kind === 'tab' ? 'Tab' : `${f.name} · ${typeTitle(f.kind)}${f.multiple && f.kind !== 'tags' ? ' · multiple' : ''}`}
                    </Text>
                    <ActionMenu
                      aria-label={`Actions for ${f.label}`}
                      onAction={(a) => {
                        if (a === 'up') move(f.key, -1);
                        else if (a === 'down') move(f.key, 1);
                        else if (a === 'duplicate') duplicate(f.key);
                        else if (a === 'delete') remove(f.key);
                      }}
                    >
                      <Item key="up">Move up</Item>
                      <Item key="down">Move down</Item>
                      <Item key="duplicate">Duplicate</Item>
                      <Item key="delete">Delete</Item>
                    </ActionMenu>
                  </Item>
                );
              }}
            </ListView>
          </div>
        </main>

        <InspectorShell
          kind={current ? 'Model element' : 'Model'}
          subject={current?.label || title || model.title}
          path={current ? `${model.path}/${current.name}` : model.path}
          onClose={() => setSelected(MODEL)}
          label={current ? 'Element properties' : 'Model properties'}
          className="studio-cf__props"
        >
          {current ? (
            <FieldProperties
              key={current.key}
              field={current}
              isNew={!current.id}
              errors={errorsOf(current.key)}
              models={models}
              onChange={(patch) => update(current.key, patch)}
              onDelete={() => remove(current.key)}
            />
          ) : (
            <Flex direction="column" gap="size-200">
              <Heading level={3} margin={0} UNSAFE_className="studio-cf__railtitle">
                Model properties
              </Heading>
              <TextField
                label="Model title"
                isRequired
                value={title}
                onChange={setTitle}
                width="100%"
                validationState={titleError ? 'invalid' : undefined}
                errorMessage={titleError}
              />
              <TextArea label="Description" value={description} onChange={setDescription} width="100%" />
              <Switch isSelected={model.enabled} onChange={(on) => void setEnabled(on)}>
                Enabled
              </Switch>
              <Text UNSAFE_className="studio-cf__muted">
                A disabled model is not offered for new content fragments; existing content fragments keep working. Takes effect immediately.
              </Text>
              <Divider size="S" />
              <LabeledValue label="Path" value={model.path} />
              <LabeledValue label="Elements" value={String(fields.filter((f) => f.kind !== 'tab').length)} />
              <LabeledValue label="Used by" value={usage === null ? '…' : `${usage === 200 ? '200+' : usage} content fragment${usage === 1 ? '' : 's'}`} />
              {usage !== null && usage > 0 && (
                <ActionButton isQuiet onPress={() => leave(cfConsoleUrl('/content/dam'))} alignSelf="start">
                  Open the content fragments console
                </ActionButton>
              )}
              <Divider size="S" />
              <ActionButton onPress={() => setDialog({ kind: 'delete' })} isDisabled={usage !== 0} alignSelf="start">
                <Delete />
                <Text>Delete model</Text>
              </ActionButton>
              {usage !== null && usage > 0 && <Text UNSAFE_className="studio-cf__muted">Models used by content fragments cannot be deleted.</Text>}
            </Flex>
          )}
        </InspectorShell>
      </div>

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog?.kind === 'leave' && (
          <StudioConfirmDialog
            title="Unsaved changes"
            variant="warning"
            primaryActionLabel="Leave"
            cancelLabel="Keep editing"
            onCancel={() => dialog.answer?.(false)}
            onPrimaryAction={() => {
              dirtyRef.current = false;
              if (dialog.answer) dialog.answer(true);
              else if (tab) tab.leave(dialog.url!);
              else go(dialog.url!);
            }}
          >
            Your changes to this model are not saved.
          </StudioConfirmDialog>
        )}
        {dialog?.kind === 'delete' && (
          <StudioConfirmDialog
            title="Delete model"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteModel(model.path);
                dirtyRef.current = false;
                if (tab) tab.leave(cfModelsUrl(conf));
                else go(cfModelsUrl(conf));
              } catch (e) {
                ToastQueue.negative(e instanceof CfValidationError ? e.errors[0]?.message ?? e.message : e instanceof Error ? e.message : String(e), { timeout: 8000 });
              }
            }}
          >
            Delete the model “{model.title}”? This cannot be undone.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer />
    </div>
  );
}

/** Before the first save attempt, only name problems show (they come from typing); the rest waits for Save. */
function pickNameErrors(errs: FieldErrors | undefined): FieldErrors {
  return errs?.name ? { name: errs.name } : {};
}

/** Reference elements start at the configuration's DAM folder when it has one (e.g. /content/dam/wknd-shared). */
function confRoot(modelPath: string): string {
  return `/content/dam/${confOf(modelPath).replace(/^\/conf\//, '')}`;
}

function LabeledValue({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span className="studio-cf-label">{label}</span>
      <div className="studio-cf__wrap">{value}</div>
    </div>
  );
}

// ------------------------------------------------------------------------------------------- element properties

function FieldProperties({
  field,
  isNew,
  errors,
  models,
  onChange,
  onDelete,
}: {
  field: EField;
  isNew: boolean;
  errors: FieldErrors;
  models: CfModelSummary[];
  onChange(patch: Partial<CfField>): void;
  onDelete(): void;
}) {
  const invalid = (k: string) => (errors[k] ? ('invalid' as const) : undefined);
  const v = field.validation ?? {};
  const setValidation = (patch: Partial<NonNullable<CfField['validation']>>) => {
    const next = { ...v, ...patch };
    for (const k of Object.keys(next) as (keyof typeof next)[]) if (next[k] === undefined || next[k] === '') delete next[k];
    onChange({ validation: Object.keys(next).length ? next : undefined });
  };
  const isTab = field.kind === 'tab';

  return (
    <Flex direction="column" gap="size-200">
      <Flex alignItems="center" gap="size-100">
        <DataTypeIcon kind={field.kind} />
        <Heading level={3} margin={0} UNSAFE_className="studio-cf__railtitle">
          {typeTitle(field.kind)}
        </Heading>
      </Flex>

      <TextField
        label={isTab ? 'Tab label' : 'Field label'}
        isRequired
        value={field.label}
        onChange={(label) => onChange({ label })}
        width="100%"
        validationState={invalid('label')}
        errorMessage={errors.label}
      />
      {!isTab && (
        <TextField
          label="Property name"
          isRequired
          value={field.name}
          onChange={(name) => onChange({ name: name.trim() })}
          width="100%"
          UNSAFE_className="studio-cf-mono"
          validationState={invalid('name')}
          errorMessage={errors.name}
          description={isNew ? 'Follows the label until you change it.' : 'Content Fragments store values under this name.'}
        />
      )}

      {!isTab && (
        <>
          <TextArea label="Description" value={field.description ?? ''} onChange={(description) => onChange({ description: description || undefined })} width="100%" />
          {['text', 'longtext', 'number'].includes(field.kind) && (
            <TextField label="Placeholder" value={field.emptyText ?? ''} onChange={(emptyText) => onChange({ emptyText: emptyText || undefined })} width="100%" />
          )}
          {field.kind !== 'boolean' && (
            <Checkbox isSelected={field.required} onChange={(required) => onChange({ required })}>
              Required
            </Checkbox>
          )}
          {(field.kind === 'text' || field.kind === 'longtext') && (
            <Checkbox isSelected={!!field.translatable} onChange={(translatable) => onChange({ translatable })}>
              Translatable
            </Checkbox>
          )}
          {MULTI_KINDS.includes(field.kind) && (
            <Checkbox isSelected={field.multiple} onChange={(multiple) => onChange({ multiple, valueType: withMultiplicity(field.valueType, multiple) })}>
              Multiple values
            </Checkbox>
          )}
        </>
      )}

      <KindProperties field={field} errors={errors} models={models} onChange={onChange} setValidation={setValidation} />

      <Divider size="S" />
      <ActionButton onPress={onDelete} alignSelf="start">
        <Delete />
        <Text>Remove {isTab ? 'tab' : 'element'}</Text>
      </ActionButton>
    </Flex>
  );
}

function withMultiplicity(valueType: string, multiple: boolean): string {
  const base = valueType.replace(/\[\]$/, '');
  return multiple ? `${base}[]` : base;
}

function KindProperties({
  field,
  errors,
  models,
  onChange,
  setValidation,
}: {
  field: EField;
  errors: FieldErrors;
  models: CfModelSummary[];
  onChange(patch: Partial<CfField>): void;
  setValidation(patch: Partial<NonNullable<CfField['validation']>>): void;
}) {
  const v = field.validation ?? {};
  const invalid = (k: string) => (errors[k] ? ('invalid' as const) : undefined);
  const num = (x: unknown) => (typeof x === 'number' ? x : typeof x === 'string' && x.trim() !== '' && !Number.isNaN(Number(x)) ? Number(x) : NaN);

  switch (field.kind) {
    case 'text':
      return (
        <>
          <NumberField label="Maximum length" minValue={1} value={field.maxLength ?? 255} onChange={(n) => onChange({ maxLength: Number.isNaN(n) ? undefined : n })} width="100%" />
          <TextField label="Default value" value={typeof field.defaultValue === 'string' ? field.defaultValue : ''} onChange={(d) => onChange({ defaultValue: d || undefined })} width="100%" />
          <TextField
            label="Validation pattern"
            description="A regular expression the value must match"
            value={v.regex ?? ''}
            onChange={(regex) => setValidation({ regex })}
            width="100%"
            UNSAFE_className="studio-cf-mono"
            validationState={invalid('validation.regex')}
            errorMessage={errors['validation.regex']}
          />
          {v.regex && <TextField label="Error message" value={v.message ?? ''} onChange={(message) => setValidation({ message })} width="100%" />}
        </>
      );
    case 'longtext':
      return (
        <Picker label="Default type" selectedKey={field.mimeType ?? 'text/html'} onSelectionChange={(k) => onChange({ mimeType: String(k) })} width="100%">
          <Item key="text/html">Rich text</Item>
          <Item key="text/x-markdown">Markdown</Item>
          <Item key="text/plain">Plain text</Item>
        </Picker>
      );
    case 'number': {
      const decimal = field.valueType.startsWith('double');
      return (
        <>
          <RadioGroup
            label="Type"
            orientation="horizontal"
            value={decimal ? 'double' : 'long'}
            onChange={(t) => onChange({ valueType: withMultiplicity(t, field.multiple), step: t === 'double' ? 'any' : '1' })}
          >
            <Radio value="long">Integer</Radio>
            <Radio value="double">Fraction</Radio>
          </RadioGroup>
          <NumberField label="Default value" value={num(field.defaultValue)} onChange={(n) => onChange({ defaultValue: Number.isNaN(n) ? undefined : n })} width="100%" step={decimal ? 0.01 : 1} />
          <Flex gap="size-100">
            <NumberField label="Minimum" value={v.min ?? NaN} onChange={(n) => setValidation({ min: Number.isNaN(n) ? undefined : n })} width="50%" />
            <NumberField
              label="Maximum"
              value={v.max ?? NaN}
              onChange={(n) => setValidation({ max: Number.isNaN(n) ? undefined : n })}
              width="50%"
              validationState={invalid('validation.max')}
              errorMessage={errors['validation.max']}
            />
          </Flex>
          {(v.min != null || v.max != null) && <TextField label="Error message" value={v.message ?? ''} onChange={(message) => setValidation({ message })} width="100%" />}
        </>
      );
    }
    case 'boolean':
      return (
        <Switch isSelected={field.defaultValue === true || field.defaultValue === 'true'} onChange={(on) => onChange({ defaultValue: on })}>
          Checked by default
        </Switch>
      );
    case 'date':
      return (
        <RadioGroup
          label="Type"
          value={field.dateType ?? 'datetime'}
          onChange={(t) => onChange({ dateType: t, valueType: `calendar/${t}` })}
        >
          <Radio value="date">Date</Radio>
          <Radio value="datetime">Date and time</Radio>
          <Radio value="time">Time</Radio>
        </RadioGroup>
      );
    case 'enumeration':
      return <OptionsEditor field={field} error={errors.options} onChange={onChange} />;
    case 'tags':
      return <TextField label="Root path" value={field.rootPath ?? ''} onChange={(rootPath) => onChange({ rootPath: rootPath || undefined })} width="100%" placeholder="/content/cq:tags" UNSAFE_className="studio-cf-mono" />;
    case 'content-reference':
      return (
        <>
          <TextField label="Root path" value={field.rootPath ?? ''} onChange={(rootPath) => onChange({ rootPath })} width="100%" UNSAFE_className="studio-cf-mono" />
          <Checkbox
            isSelected={v.type === 'cfm.validation.contenttype.image'}
            onChange={(on) => setValidation({ type: on ? 'cfm.validation.contenttype.image' : undefined })}
          >
            Accept only images
          </Checkbox>
        </>
      );
    case 'fragment-reference':
      return (
        <>
          <CheckboxGroup
            label="Allowed models"
            description="None selected: any model"
            value={field.allowedModels ?? []}
            onChange={(allowedModels) => onChange({ allowedModels })}
          >
            {models.map((m) => (
              <Checkbox key={m.path} value={m.path}>
                {m.title}
              </Checkbox>
            ))}
          </CheckboxGroup>
          <TextField label="Root path" value={field.rootPath ?? ''} onChange={(rootPath) => onChange({ rootPath })} width="100%" UNSAFE_className="studio-cf-mono" />
        </>
      );
    case 'tab':
      return <Text UNSAFE_className="studio-cf__muted">The elements after this placeholder appear in a tab of the content fragment editor, until the next tab.</Text>;
    default:
      return null;
  }
}

function OptionsEditor({ field, error, onChange }: { field: EField; error?: string; onChange(patch: Partial<CfField>): void }) {
  const options = field.options ?? [];
  const set = (i: number, patch: { value?: string; label?: string }) =>
    onChange({ options: options.map((o, j) => (j === i ? { ...o, ...patch } : o)) });
  const values = options.map((o) => o.value).filter(Boolean);
  return (
    <fieldset className="studio-cfme__options">
      <legend className="studio-cf-label">Options</legend>
      <RadioGroup
        label="Show as"
        orientation="horizontal"
        value={field.multiple ? 'checkboxes' : 'dropdown'}
        onChange={(s) => onChange({ multiple: s === 'checkboxes', valueType: s === 'checkboxes' ? 'string[]' : 'string' })}
      >
        <Radio value="dropdown">Dropdown (one)</Radio>
        <Radio value="checkboxes">Checkboxes (several)</Radio>
      </RadioGroup>
      <div className="studio-cfme__optionrows" role="list" aria-label="Options">
        {options.map((o, i) => (
          <div key={i} className="studio-cfme__optionrow" role="listitem">
            <TextField aria-label={`Option ${i + 1} value`} placeholder="Value" value={o.value} onChange={(value) => set(i, { value })} width="100%" UNSAFE_className="studio-cf-mono" />
            <TextField aria-label={`Option ${i + 1} label`} placeholder="Label (optional)" value={o.label === o.value ? '' : o.label} onChange={(label) => set(i, { label: label || o.value })} width="100%" />
            <ActionButton isQuiet aria-label={`Remove option ${i + 1}`} onPress={() => onChange({ options: options.filter((_, j) => j !== i) })}>
              <Delete />
            </ActionButton>
          </div>
        ))}
      </div>
      <ActionButton
        onPress={() => onChange({ options: [...options, { value: uniqueName('option', values), label: '' }] })}
        alignSelf="start"
      >
        <Add />
        <Text>Add option</Text>
      </ActionButton>
      {error && <div className="studio-cf-error">{error}</div>}
      {!field.multiple && values.length > 0 && (
        <Picker
          label="Default value"
          selectedKey={typeof field.defaultValue === 'string' ? field.defaultValue : '__none__'}
          onSelectionChange={(k) => onChange({ defaultValue: k === '__none__' ? undefined : String(k) })}
          width="100%"
          items={[{ value: '__none__', label: 'None' }, ...options.filter((o) => o.value)]}
        >
          {(o) => <Item key={o.value}>{o.label || o.value}</Item>}
        </Picker>
      )}
    </fieldset>
  );
}
