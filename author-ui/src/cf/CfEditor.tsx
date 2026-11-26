import {
  ActionButton,
  Button,
  ButtonGroup,
  Content,
  DialogContainer,
  Divider,
  Flex,
  Form,
  Heading,
  Item,
  LabeledValue,
  ListView,
  StatusLight,
  TabList,
  TabPanels,
  Tabs,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
  useDialogContainer,
  View,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import RailRight from '@spectrum-icons/workflow/RailRight';
import Delete from '@spectrum-icons/workflow/Delete';
import { useCallback, useEffect, useMemo, useRef, useState, type Key } from 'react';
import { cfConsoleUrl, CfValidationError, parentOf, type CfClient, type CfField, type CfFragment, type CfModel } from './api';
import { changes, tabsOf, toFormValue, validate, type FormValue } from './fields';
import { FieldEditor } from './FieldEditor';
import { ReferencesPanel } from './references';
import { VersionsPanel } from './versions';
import { LanguagesPanel } from './translation';
import { AssetPickerDialog, FragmentPickerDialog } from './pickers';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { InspectorShell } from '../shell/InspectorShell';

interface Props {
  client: CfClient;
  path: string;
}

type Values = Record<string, FormValue>;

interface Props2 {
  title: string;
  description: string;
  tags: string[];
}

type Picker = { kind: 'asset' | 'fragment'; field: CfField; current?: string; resolve(path: string | null): void };

const MASTER = 'master';

function formValuesOf(model: CfModel, fragment: CfFragment, variation: string): Values {
  const stored = fragment.values[variation] ?? {};
  const out: Values = {};
  for (const f of model.fields) if (f.kind !== 'tab') out[f.name] = toFormValue(f, stored[f.name]);
  return out;
}

function contentTypesOf(model: CfModel, fragment: CfFragment, variation: string): Record<string, string> {
  const stored = fragment.contentTypes[variation] ?? {};
  const out: Record<string, string> = {};
  for (const f of model.fields) if (f.kind === 'longtext') out[f.name] = stored[f.name] ?? f.mimeType ?? 'text/plain';
  return out;
}

const propsOf = (f: CfFragment): Props2 => ({ title: f.title, description: f.description ?? '', tags: [...(f.tags ?? [])] });

function relative(iso: string | null | undefined): string {
  if (!iso) return '';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.round(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.round(diff / 3600)} h ago`;
  return new Date(iso).toLocaleDateString();
}

/**
 * The content fragment editor (`/editor.html/content/dam/.../<fragment>`): variations on the left, the
 * elements of the model in the middle, fragment properties on the right. Saves only what changed.
 */
export function CfEditor({ client, path }: Props) {
  const [fragment, setFragment] = useState<CfFragment | null>(null);
  const [model, setModel] = useState<CfModel | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [variation, setVariation] = useState(() => decodeURIComponent(window.location.hash.slice(1)) || MASTER);
  const [initial, setInitial] = useState<Values>({});
  const [values, setValues] = useState<Values>({});
  const [initialTypes, setInitialTypes] = useState<Record<string, string>>({});
  const [types, setTypes] = useState<Record<string, string>>({});
  const [initialProps, setInitialProps] = useState<Props2>({ title: '', description: '', tags: [] });
  const [props, setProps] = useState<Props2>({ title: '', description: '', tags: [] });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [showErrors, setShowErrors] = useState(false);
  const [saving, setSaving] = useState(false);
  /** `leave` carries where to go, or — asked by the shell's guard — what to answer. */
  const [dialog, setDialog] = useState<null | { kind: 'new-variation' } | { kind: 'delete-variation' } | { kind: 'switch'; to: string } | { kind: 'leave'; url?: string; answer?: (ok: boolean) => void }>(null);
  const tab = useTab();
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const go = useNavigate();
  useScreenTitle(fragment ? fragment.title : null, fragment ? `${fragment.title} | Sensible Studio Content Fragment` : null);
  const [picker, setPicker] = useState<Picker | null>(null);
  // below 1100px the side panel is a drawer over the form
  const [panelOpen, setPanelOpen] = useState(false);

  // ------------------------------------------------------------------------------------------ loading

  const load = useCallback(
    (f: CfFragment, m: CfModel, v: string) => {
      const variationName = f.variations.some((x) => x.name === v) ? v : MASTER;
      const vals = formValuesOf(m, f, variationName);
      const cts = contentTypesOf(m, f, variationName);
      setFragment(f);
      setModel(m);
      setVariation(variationName);
      setInitial(vals);
      setValues(vals);
      setInitialTypes(cts);
      setTypes(cts);
      setInitialProps(propsOf(f));
      setProps(propsOf(f));
      setErrors({});
      setShowErrors(false);
      if (!tabRef.current) window.history.replaceState(null, '', `${window.location.pathname}${variationName === MASTER ? '' : `#${encodeURIComponent(variationName)}`}`);
    },
    [],
  );

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const f = await client.fragment(path);
        if (!f.model) throw new Error('This content fragment has no model; it cannot be edited here.');
        const m = await client.model(f.model);
        if (cancelled) return;
        load(f, m, variation);
      } catch (e) {
        if (!cancelled) setFailure(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, path]);

  // ------------------------------------------------------------------------------------------ state

  const changed = useMemo(() => (model ? changes(model.fields, initial, values) : {}), [model, initial, values]);
  const changedTypes = useMemo(
    () => Object.fromEntries(Object.entries(types).filter(([k, v]) => initialTypes[k] !== v)),
    [types, initialTypes],
  );
  const propsChanged =
    props.title !== initialProps.title ||
    props.description !== initialProps.description ||
    props.tags.join('\n') !== initialProps.tags.join('\n');
  const dirty = Object.keys(changed).length > 0 || Object.keys(changedTypes).length > 0 || propsChanged;

  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;
  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current) e.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, []);

  const clientErrors = useMemo(() => {
    if (!model) return {};
    const out: Record<string, string> = {};
    for (const f of model.fields) {
      if (f.kind === 'tab') continue;
      // required is enforced on master only, like the server
      const message = validate(variation === MASTER ? f : { ...f, required: false }, values[f.name] ?? null);
      if (message) out[f.name] = message;
    }
    return out;
  }, [model, values, variation]);

  const errorOf = (name: string) => errors[name] ?? (showErrors || name in changed ? clientErrors[name] ?? null : null);

  // ------------------------------------------------------------------------------------------ actions

  const save = useCallback(async () => {
    if (!fragment || !model || saving) return;
    // Same rule as the server: only changed elements must be valid (old content may predate the model).
    const blocking = Object.keys(clientErrors).filter((k) => k in changed);
    if (blocking.length) {
      setShowErrors(true);
      ToastQueue.negative(`${blocking.length} field${blocking.length > 1 ? 's need' : ' needs'} attention`, { timeout: 5000 });
      return;
    }
    if (!dirty) return;
    setSaving(true);
    try {
      const f = await client.save({
        path: fragment.path,
        variation,
        values: changed,
        contentTypes: Object.keys(changedTypes).length ? changedTypes : undefined,
        title: props.title !== initialProps.title ? props.title : undefined,
        description: props.description !== initialProps.description ? props.description : undefined,
        tags: props.tags.join('\n') !== initialProps.tags.join('\n') ? props.tags : undefined,
      });
      load(f, model, variation);
      ToastQueue.positive('Saved', { timeout: 2500 });
    } catch (e) {
      if (e instanceof CfValidationError) {
        setErrors(Object.fromEntries(e.errors.map((x) => [x.field, x.message])));
        setShowErrors(true);
        ToastQueue.negative(e.message, { timeout: 5000 });
      } else {
        ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
      }
    } finally {
      setSaving(false);
    }
  }, [client, fragment, model, saving, clientErrors, changed, changedTypes, dirty, variation, props, initialProps, load]);

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

  const switchTo = (v: string) => {
    if (v === variation || !fragment || !model) return;
    if (dirty) setDialog({ kind: 'switch', to: v });
    else load(fragment, model, v);
  };

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

  const browse = (kind: 'asset' | 'fragment', field: CfField, current?: string) =>
    new Promise<string | null>((resolve) => setPicker({ kind, field, current, resolve }));

  const setValue = (name: string, v: FormValue) => {
    setValues((prev) => ({ ...prev, [name]: v }));
    if (errors[name]) setErrors((prev) => Object.fromEntries(Object.entries(prev).filter(([k]) => k !== name)));
  };

  // ------------------------------------------------------------------------------------------ render

  const folder = parentOf(path);
  if (failure) {
    return (
      <div className="studio-cf">
        <EditorBar title="Content Fragment" onBack={() => leave(cfConsoleUrl(folder))} />
        <ErrorState title={`Could not open ${path}`}>{failure}</ErrorState>
      </div>
    );
  }
  if (!fragment || !model) {
    return (
      <div className="studio-cf">
        <EditorBar title="Content Fragment" onBack={() => leave(cfConsoleUrl(folder))} />
        <LoadingState label="Loading content fragment" />
      </div>
    );
  }

  const tabs = tabsOf(model.fields);
  const variationTitle = fragment.variations.find((v) => v.name === variation)?.title ?? variation;
  const renderFields = (fields: CfField[]) => (
    <Form aria-label={`${fragment.title}: ${variationTitle}`} onSubmit={(e) => e.preventDefault()} UNSAFE_className="studio-cf__form">
      {fields.map((f) => (
        <div key={f.name} className="studio-cf__field" data-field={f.name}>
          <FieldEditor
            field={f}
            value={values[f.name] ?? null}
            onChange={(v) => setValue(f.name, v)}
            error={errorOf(f.name)}
            contentType={types[f.name] ?? f.mimeType ?? 'text/plain'}
            onContentTypeChange={(t) => setTypes((prev) => ({ ...prev, [f.name]: t }))}
            client={client}
            browse={browse}
          />
        </div>
      ))}
    </Form>
  );

  return (
    <div className="studio-cf">
      <EditorBar
        title={props.title || fragment.name}
        subtitle={model.title}
        onBack={() => leave(cfConsoleUrl(folder))}
        status={
          saving ? (
            <StatusLight variant="info">Saving…</StatusLight>
          ) : dirty ? (
            <StatusLight variant="notice">Unsaved changes</StatusLight>
          ) : (
            <StatusLight variant="positive">Saved {relative(fragment.modified)}</StatusLight>
          )
        }
        actions={
          <>
            <ActionButton
              UNSAFE_className="studio-cf__paneltoggle"
              aria-label={panelOpen ? 'Hide the side panel' : 'Show properties, references and versions'}
              aria-pressed={panelOpen}
              onPress={() => setPanelOpen((o) => !o)}
            >
              <RailRight />
            </ActionButton>
            <Button variant="accent" onPress={() => void save()} isDisabled={!dirty || saving} isPending={saving}>
              Save
            </Button>
          </>
        }
      />

      <div className="studio-cf__body">
        <aside className="studio-cf__rail" aria-label="Variants">
          <Flex justifyContent="space-between" alignItems="center" marginBottom="size-100">
            <Heading level={3} margin={0} UNSAFE_className="studio-cf__railtitle">
              Variants
            </Heading>
            <Flex gap="size-50">
              <TooltipTrigger>
                <ActionButton isQuiet aria-label="Create variant" onPress={() => setDialog({ kind: 'new-variation' })}>
                  <Add />
                </ActionButton>
                <Tooltip>Create variant</Tooltip>
              </TooltipTrigger>
              <TooltipTrigger>
                <ActionButton
                  isQuiet
                  aria-label="Delete variant"
                  isDisabled={variation === MASTER}
                  onPress={() => setDialog({ kind: 'delete-variation' })}
                >
                  <Delete />
                </ActionButton>
                <Tooltip>Delete variant</Tooltip>
              </TooltipTrigger>
            </Flex>
          </Flex>
          <ListView
            aria-label="Variants"
            selectionMode="single"
            selectionStyle="highlight"
            disallowEmptySelection
            density="compact"
            selectedKeys={[variation]}
            onSelectionChange={(keys) => {
              const k = [...(keys as Set<Key>)][0];
              if (k) switchTo(String(k));
            }}
            items={fragment.variations}
          >
            {(v) => (
              <Item key={v.name} textValue={v.title}>
                <Text>{v.title}</Text>
                {v.description ? <Text slot="description">{v.description}</Text> : null}
              </Item>
            )}
          </ListView>
        </aside>

        <main className="studio-cf__main">
          <div className="studio-cf__sheet">
            <div className="studio-cf__sheethead">
              <Heading level={2} margin={0}>
                {variationTitle}
              </Heading>
              <Text UNSAFE_className="studio-cf__muted">{variation === MASTER ? 'Master variant: the content used by default' : `Variant “${variation}”`}</Text>
            </div>
            {tabs.length > 1 ? (
              <Tabs aria-label="Model sections">
                <TabList>
                  {tabs.map((t, i) => (
                    <Item key={i}>{t.title}</Item>
                  ))}
                </TabList>
                <TabPanels>
                  {tabs.map((t, i) => (
                    <Item key={i}>{renderFields(t.fields)}</Item>
                  ))}
                </TabPanels>
              </Tabs>
            ) : (
              renderFields(tabs[0].fields)
            )}
          </div>
        </main>

        <InspectorShell
          kind="Content Fragment"
          subject={fragment.title}
          path={fragment.path}
          onClose={() => setPanelOpen(false)}
          label="Content Fragment panel"
          className={`studio-cf__props${panelOpen ? ' is-open' : ''}`}
        >
          <Tabs aria-label="Content Fragment panel" density="compact" UNSAFE_className="studio-cf__paneltabs">
            <TabList>
              <Item key="properties">Details</Item>
              <Item key="references">References</Item>
              <Item key="versions">Versions</Item>
              <Item key="languages">Languages</Item>
            </TabList>
            <TabPanels UNSAFE_className="studio-cf__panel">
              <Item key="properties">
                <Flex direction="column" gap="size-200" marginTop="size-200">
                  <TextField label="Title" isRequired value={props.title} onChange={(title) => setProps((p) => ({ ...p, title }))} width="100%" />
                  <TextArea label="Description" value={props.description} onChange={(description) => setProps((p) => ({ ...p, description }))} width="100%" />
                  <TagsEditor tags={props.tags} onChange={(tags) => setProps((p) => ({ ...p, tags }))} />
                  <Divider size="S" />
                  <LabeledValue label="Model" value={model.title} />
                  <LabeledValue label="Name" value={fragment.name} />
                  <LabeledValue label="Path" value={fragment.path} UNSAFE_className="studio-cf__wrap" />
                  {fragment.modified && (
                    <LabeledValue label="Modified" value={`${new Date(fragment.modified).toLocaleString()}${fragment.modifiedBy ? ` · ${fragment.modifiedBy}` : ''}`} />
                  )}
                </Flex>
              </Item>
              <Item key="references">
                <div className="studio-cf__panelbody">
                  <ReferencesPanel client={client} path={fragment.path} version={fragment.modified} onOpen={leave} />
                </div>
              </Item>
              <Item key="languages">
                <div className="studio-cf__panelbody">
                  <LanguagesPanel
                    client={client}
                    fragment={fragment}
                    model={model}
                    dirty={dirty}
                    onOpen={leave}
                    onReload={async () => {
                      const f = await client.fragment(fragment.path);
                      dirtyRef.current = false;
                      load(f, model, variation);
                    }}
                  />
                </div>
              </Item>
              <Item key="versions">
                <div className="studio-cf__panelbody">
                  <VersionsPanel
                    client={client}
                    fragment={fragment}
                    model={model}
                    dirty={dirty}
                    onRestored={(f) => {
                      dirtyRef.current = false;
                      load(f, model, variation);
                    }}
                  />
                </div>
              </Item>
            </TabPanels>
          </Tabs>
        </InspectorShell>
      </div>

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog?.kind === 'new-variation' && (
          <NewVariationDialog
            onCreate={async (title, description) => {
              const f = await client.createVariation(fragment.path, title, undefined, description);
              const created = f.variations[f.variations.length - 1]?.name ?? MASTER;
              load(f, model, created);
              ToastQueue.positive(`Created variant ${title}`, { timeout: 2500 });
            }}
          />
        )}
        {dialog?.kind === 'delete-variation' && (
          <StudioConfirmDialog
            title="Delete variant"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                const f = await client.deleteVariation(fragment.path, variation);
                load(f, model, MASTER);
                ToastQueue.positive(`Deleted variant ${variationTitle}`, { timeout: 2500 });
              } catch (e) {
                ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
              }
            }}
          >
            Delete the variation “{variationTitle}”? Its content is removed; the master variation is not affected.
          </StudioConfirmDialog>
        )}
        {dialog?.kind === 'switch' && (
          <StudioConfirmDialog
            title="Unsaved changes"
            variant="warning"
            primaryActionLabel="Discard changes"
            cancelLabel="Keep editing"
            onPrimaryAction={() => load(fragment, model, dialog.to)}
          >
            Switching the variation discards your unsaved changes to “{variationTitle}”.
          </StudioConfirmDialog>
        )}
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
            Your changes to this content fragment are not saved.
          </StudioConfirmDialog>
        )}
      </DialogContainer>

      <DialogContainer
        onDismiss={() => {
          picker?.resolve(null);
          setPicker(null);
        }}
      >
        {picker?.kind === 'asset' && (
          <AssetPickerDialog
            client={client}
            root={picker.field.rootPath ?? '/content/dam'}
            initial={picker.current}
            onSelect={(p) => {
              picker.resolve(p);
              setPicker(null);
            }}
          />
        )}
        {picker?.kind === 'fragment' && (
          <FragmentPickerDialog
            client={client}
            root={picker.field.rootPath ?? '/content/dam'}
            allowedModels={picker.field.allowedModels}
            initial={picker.current}
            onSelect={(p) => {
              picker.resolve(p);
              setPicker(null);
            }}
          />
        )}
      </DialogContainer>
      <ToastContainer />
    </div>
  );
}

// ------------------------------------------------------------------------------------------- parts

function EditorBar({
  title,
  subtitle,
  onBack,
  status,
  actions,
}: {
  title: string;
  subtitle?: string;
  onBack(): void;
  status?: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <ScreenFrame
      identity={<div className="studio-cf__title"><span className="studio-cf__titletext">{title}</span>{subtitle && <span className="studio-cf__subtitle">{subtitle}</span>}</div>}
      actions={status}
      primary={actions}
      onClose={onBack}
      closeLabel="Close content fragment editor"
    />
  );
}

export function TagsEditor({ tags, onChange }: { tags: string[]; onChange(tags: string[]): void }) {
  const [draft, setDraft] = useState('');
  const add = () => {
    const t = draft.trim();
    if (t && !tags.includes(t)) onChange([...tags, t]);
    setDraft('');
  };
  return (
    <View>
      <TextField
        label="Tags"
        placeholder="namespace:tag/path, then Enter"
        value={draft}
        onChange={setDraft}
        onKeyDown={(e) => e.key === 'Enter' && add()}
        onBlur={add}
        width="100%"
      />
      <div className="studio-cf__tags">
        {tags.map((t) => (
          <span key={t} className="studio-cf__tag">
            {t}
            <button type="button" aria-label={`Remove ${t}`} onClick={() => onChange(tags.filter((x) => x !== t))}>
              ×
            </button>
          </span>
        ))}
      </div>
    </View>
  );
}

function NewVariationDialog({ onCreate }: { onCreate(title: string, description: string): Promise<void> }) {
  const dialog = useDialogContainer();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const create = async () => {
    if (!title.trim()) return;
    setBusy(true);
    try {
      await onCreate(title.trim(), description.trim());
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>Create variant</Heading>
      <Divider />
      <Content>
        <Form onSubmit={(e) => (e.preventDefault(), void create())}>
          <TextField label="Title" isRequired autoFocus value={title} onChange={setTitle} />
          <TextArea label="Description" value={description} onChange={setDescription} />
          <Text>The new variant starts with the content of master.</Text>
        </Form>
        {error && <Text UNSAFE_className="studio-cf-error">{error}</Text>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void create()} isDisabled={!title.trim()} isPending={busy}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
