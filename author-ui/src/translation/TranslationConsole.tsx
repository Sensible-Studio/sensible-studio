import {
  ActionButton,
  Button,
  Cell,
  Column,
  ComboBox,
  Content,
  DialogContainer,
  Flex,
  Heading,
  IllustratedMessage,
  InlineAlert,
  Item,
  Link,
  Picker,
  Radio,
  RadioGroup,
  Row,
  StatusLight,
  TableBody,
  TableHeader,
  TableView,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Folder from '@spectrum-icons/workflow/Folder';
import Globe from '@spectrum-icons/workflow/Globe';
import Plug from '@spectrum-icons/workflow/Plug';
import { useCallback, useEffect, useMemo, useState, type Key, useRef } from 'react';
import { ProductBar } from '../shell/ProductBar';
import {
  API_LABELS,
  API_PROTOCOLS,
  ConfigValidationError,
  DEFAULT_KEY_VARIABLE,
  DEFAULT_MODEL,
  MODELS,
  SECRETS_CONSOLE,
  ENCRYPTED_KEY,
  SECRET_REFERENCE,
  translationConsoleUrl,
  type Api,
  type ConfigDraft,
  type HeaderRow,
  type KeyStatus,
  type TestResult,
  type TranslationConfig,
  type TranslationConfigClient,
  type TranslationConfigs,
  type TranslationConnector,
} from './api';
import { headerProblem, PRESETS, presetById, presetOf, type Preset } from './presets';
import './translation.scss';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { ToolBar } from '../shell/ToolCrumbs';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { FormPage } from '../shell/FormPage';
import { StudioConfirmDialog } from '../shell/StudioDialog';

interface Props {
  client: TranslationConfigClient;
  /** `/conf` (all configuration folders) or `/conf/<name>` (its translation configurations). */
  initialPath: string;
}

const confTitle = (conf: string) => (conf === '/conf/global' ? 'Global' : conf.replace(/^\/conf\//, ''));

/**
 * Tools → Cloud Services → Translation (`/cloudservices/translation.html[/conf/<conf>]`): the translation cloud
 * configurations of each configuration folder, as in AEM. Content uses its site's configuration (`cq:conf`), then
 * the global one. API keys are never entered here: a configuration refers to a secret of the instance.
 */
export function TranslationConsole({ client, initialPath }: Props) {
  const tab = useTab();
  const [conf, setConf] = useState<string | null>(initialPath === '/conf' || !initialPath ? null : initialPath);
  const [data, setData] = useState<TranslationConfigs | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<null | { kind: 'edit'; config: TranslationConfig | null } | { kind: 'delete' }>(null);
  const [user, setUser] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const [testing, setTesting] = useState<string | null>(null);

  useScreenTitle(`${VOCAB.translations} · ${VOCAB.providers}`, `${VOCAB.providers} | ${VOCAB.translations} | ${VOCAB.product}`);
  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  useEffect(() => {
    let cancelled = false;
    client.configs().then(
      (d) => {
        if (cancelled) return;
        setData(d);
        setFailure(null);
      },
      (e: unknown) => !cancelled && setFailure(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      cancelled = true;
    };
  }, [client, version]);

  const tabRef = useRef(tab);
  tabRef.current = tab;
  const tabConf = tab?.place.kind === 'translation-configs' ? (tab.place.path === '/conf' ? null : tab.place.path) : undefined;
  useEffect(() => {
    if (tabConf !== undefined && tabConf !== conf) {
      setConf(tabConf);
      setSelected(new Set());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabConf]);
  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      const m = window.location.pathname.match(/^\/cloudservices\/translation\.html(\/conf\/.+)$/);
      setConf(m ? decodeURI(m[1]).replace(/\/$/, '') : null);
      setSelected(new Set());
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const navigate = useCallback((c: string | null) => {
    if (tabRef.current) tabRef.current.setPlace({ kind: 'translation-configs', path: c ?? '/conf' });
    else window.history.pushState(null, '', translationConsoleUrl(c));
    setConf(c);
    setSelected(new Set());
  }, []);

  const configsOf = (c: string) => (data?.configs ?? []).filter((x) => x.conf === c);
  const rows = conf ? configsOf(conf) : [];
  const selectedConfigs = rows.filter((r) => selected.has(r.path));

  const test = async (config: TranslationConfig) => {
    setTesting(config.path);
    try {
      const r = await client.test({ path: config.path });
      if (r.ok) ToastQueue.positive(`${config.title}: connected in ${seconds(r.ms)}`, { timeout: 4000 });
      else ToastQueue.negative(`${config.title}: ${r.error ?? 'failed'}`, { timeout: 10000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setTesting(null);
    }
  };

  if (dialog?.kind === 'edit' && data && conf) {
    return (
      <div className="studio-cfc studio-tcs">
        {!tab && <ProductBar user={user} />}
        <ConfigDialog
          client={client}
          conf={conf}
          config={dialog.config}
          connectors={data.connectors}
          close={() => setDialog(null)}
          onSaved={(c) => {
            ToastQueue.positive(`${c.title} saved`, { timeout: 2500 });
            setSelected(new Set([c.path]));
            setVersion((v) => v + 1);
          }}
        />
        <ToastContainer />
      </div>
    );
  }

  return (
    <div className="studio-cfc studio-tcs">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/cloudservices/translation.html/conf"
        extra={conf ? [{ path: conf, title: confTitle(conf) }] : []}
        onGo={(p) => navigate(p)}
        primary={<Button variant="accent" onPress={() => setDialog({ kind: 'edit', config: null })} isDisabled={!conf || !data}>
            <Add />
            <Text>Create translation configuration</Text>
          </Button>}
      />

      {selectedConfigs.length > 0 && (
        <div className="studio-cfc__selection" role="toolbar" aria-label="Selection">
          <Text>{selectedConfigs.length} selected</Text>
          {selectedConfigs.length === 1 && (
            <>
              <ActionButton isQuiet onPress={() => setDialog({ kind: 'edit', config: selectedConfigs[0] })}>
                <Edit />
                <Text>Properties</Text>
              </ActionButton>
              <ActionButton isQuiet onPress={() => void test(selectedConfigs[0])} isDisabled={testing !== null}>
                <Plug />
                <Text>{testing ? 'Testing…' : 'Test connection'}</Text>
              </ActionButton>
            </>
          )}
          <ActionButton isQuiet onPress={() => setDialog({ kind: 'delete' })}>
            <Delete />
            <Text>Delete</Text>
          </ActionButton>
          <ActionButton isQuiet onPress={() => setSelected(new Set())} marginStart="auto">
            Clear
          </ActionButton>
        </div>
      )}

      <div className="studio-cfc__content">
        {failure ? (
          <ErrorState title="Could not load the translation configurations">{failure}</ErrorState>
        ) : !data ? (
          <LoadingState />
        ) : !conf ? (
          <TableView aria-label="Configuration folders" density="spacious" height="100%" onAction={(k: Key) => navigate(String(k))}>
            <TableHeader>
              <Column key="title" isRowHeader width="2fr">
                Configuration
              </Column>
              <Column key="count" width="1fr">
                Translation configurations
              </Column>
              <Column key="path" width="2fr">
                Path
              </Column>
            </TableHeader>
            <TableBody items={data.confs.map((c) => ({ path: c }))}>
              {(c) => (
                <Row key={c.path}>
                  <Cell textValue={confTitle(c.path)}>
                    <span className="studio-cfc__cell">
                      <Folder size="S" />
                      <span>{confTitle(c.path)}</span>
                    </span>
                  </Cell>
                  <Cell>{String(configsOf(c.path).length)}</Cell>
                  <Cell>{c.path}</Cell>
                </Row>
              )}
            </TableBody>
          </TableView>
        ) : (
          <TableView
            aria-label={`Translation configurations of ${confTitle(conf)}`}
            selectionMode="multiple"
            selectionStyle="checkbox"
            selectedKeys={selected}
            onSelectionChange={(keys) => setSelected(keys === 'all' ? new Set(rows.map((r) => r.path)) : new Set([...keys].map(String)))}
            onAction={(k: Key) => {
              const c = rows.find((r) => r.path === String(k));
              if (c) setDialog({ kind: 'edit', config: c });
            }}
            density="spacious"
            overflowMode="truncate"
            height="100%"
            renderEmptyState={() => (
              <IllustratedMessage>
                <Heading>No translation configuration</Heading>
                <Content>
                  {conf === '/conf/global'
                    ? 'Create one to translate content of every site with a language model.'
                    : 'Content of this site uses the global configuration. Create one to use another model or guidance here.'}
                </Content>
              </IllustratedMessage>
            )}
          >
            <TableHeader>
              <Column key="title" isRowHeader width="2fr">
                Title
              </Column>
              <Column key="model" width="2fr">
                Translated by
              </Column>
              <Column key="key" width="2fr">
                API key
              </Column>
              <Column key="modified" width="1fr">
                Modified
              </Column>
            </TableHeader>
            <TableBody items={rows}>
              {(c) => (
                <Row key={c.path}>
                  <Cell textValue={c.title}>
                    <span className="studio-cfc__cell">
                      <Globe size="S" />
                      <span>{c.title}</span>
                    </span>
                  </Cell>
                  <Cell>{engineOf(c, data.connectors)}</Cell>
                  <Cell textValue={keyText(c.key)}>
                    <StatusLight variant={keyVariant(c.key)}>{keyText(c.key)}</StatusLight>
                  </Cell>
                  <Cell>{c.lastModified ? new Date(c.lastModified).toLocaleDateString() : ''}</Cell>
                </Row>
              )}
            </TableBody>
          </TableView>
        )}
      </div>

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog?.kind === 'delete' && (
          <StudioConfirmDialog
            title="Delete translation configurations"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                for (const c of selectedConfigs) await client.delete(c.path);
                ToastQueue.positive(`${selectedConfigs.length} deleted`, { timeout: 2500 });
              } catch (e) {
                ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
              }
              setSelected(new Set());
              setVersion((v) => v + 1);
            }}
          >
            {selectedConfigs.map((c) => c.title).join(', ')} will be deleted. Content of this site then uses the next configuration (the global
            one, or the instance's default connector). Existing language versions stay as they are.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer />
    </div>
  );
}

const seconds = (ms?: number) => (ms == null ? '' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`);

function engineOf(c: TranslationConfig, connectors: TranslationConnector[]): string {
  if (c.connector !== 'studio-ai') return connectors.find((x) => x.name === c.connector)?.label ?? c.connector;
  const api = c.settings.api ?? 'anthropic';
  const model = c.settings.model || (api === 'anthropic' ? DEFAULT_MODEL : 'default model');
  const preset = presetOf(c.settings);
  if (preset.id !== 'custom') {
    const local = preset.id === 'ollama' || preset.id === 'lmstudio';
    const name = preset.id === 'anthropic' ? 'Claude' : preset.label.replace(/ \(.*\)$/, '');
    return `${name} · ${model}${local && c.settings.endpoint ? ` · ${hostOf(c.settings.endpoint)}` : ''}`;
  }
  const where = c.settings.endpoint ? ` · ${hostOf(c.settings.endpoint)}` : '';
  return `${API_LABELS[api]} · ${model}${where}`;
}

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

export function keyText(k: KeyStatus): string {
  switch (k.status) {
    case 'found':
      if (k.encrypted) return 'Set (stored encrypted)';
      if (k.header) return k.name ? `Set in a header (${k.name})` : 'Set in a header';
      return k.default ? `Set (${k.name} variable)` : `Set (${k.name})`;
    case 'missing':
      if (k.unreadable) return 'Encrypted on another instance: enter the key again';
      return k.default ? `Missing: set ${k.name} or refer to a secret` : `Missing: secret ${k.name} not set`;
    case 'plain':
      return 'Literal key in the repository: replace it';
    default:
      return 'Not needed';
  }
}

const keyVariant = (k: KeyStatus) => (k.status === 'found' ? 'positive' : k.status === 'notNeeded' ? 'neutral' : 'negative');

// ------------------------------------------------------------------------------------------- the editor

interface FormState {
  title: string;
  name: string;
  connector: string;
  preset: string;
  api: Api;
  endpoint: string;
  model: string;
  apiKey: string;
  /** typed: the key itself, stored encrypted; reference: a secret's reference. */
  keyMode: 'typed' | 'reference';
  /** A key typed now (never loaded back). */
  apiKeyValue: string;
  instructions: string;
  doNotTranslate: string;
  maxTokens: string;
  maxChars: string;
  timeoutSeconds: string;
  headers: HeaderRow[];
}

const headerRows = (lines: string[] | undefined): HeaderRow[] =>
  (lines ?? []).map((l) => {
    const i = l.indexOf(':');
    return i < 0 ? { name: l.trim(), value: '' } : { name: l.slice(0, i).trim(), value: l.slice(i + 1).trim() };
  });

function initialState(config: TranslationConfig | null): FormState {
  const s = config?.settings ?? {};
  return {
    title: config?.title ?? '',
    name: config?.name ?? '',
    connector: config?.connector ?? 'studio-ai',
    preset: config ? presetOf(s).id : 'anthropic',
    api: s.api ?? 'anthropic',
    endpoint: s.endpoint ?? '',
    model: s.model ?? (config ? '' : DEFAULT_MODEL),
    apiKey: s.apiKey ?? (config ? '' : '$[secret:ANTHROPIC_API_KEY]'),
    keyMode: s.apiKey && ENCRYPTED_KEY.test(s.apiKey) ? 'typed' : 'reference',
    apiKeyValue: '',
    instructions: s.instructions ?? '',
    doNotTranslate: (s.doNotTranslate ?? []).join('\n'),
    maxTokens: s.maxTokens ? String(s.maxTokens) : '',
    maxChars: s.maxChars ? String(s.maxChars) : '',
    timeoutSeconds: s.timeoutSeconds ? String(s.timeoutSeconds) : '',
    headers: headerRows(s.headers),
  };
}

/** The form after choosing a provider: its protocol, endpoint, key reference, headers and a model it offers. */
export function applyPreset(f: FormState, preset: Preset): FormState {
  const previous = presetById(f.preset);
  const keepModel = f.model && !(previous?.models.includes(f.model) || f.model === DEFAULT_MODEL);
  const otherHeaders = f.headers.filter((h) => !(previous?.headers ?? []).some((p) => p.name.toLowerCase() === h.name.toLowerCase()));
  return {
    ...f,
    preset: preset.id,
    api: preset.api,
    endpoint: preset.id === 'custom' ? f.endpoint : preset.endpoint,
    model: keepModel ? f.model : preset.modelOptional ? '' : (preset.models[0] ?? ''),
    // a key typed for another provider is not this one's
    apiKey: f.keyMode === 'typed' ? (preset.id === previous?.id ? f.apiKey : '') : preset.id === 'custom' ? f.apiKey : preset.keyVariable ? `$[secret:${preset.keyVariable}]` : '',
    headers: [...(preset.headers ?? []).map((h) => ({ ...h })), ...otherHeaders],
  };
}

function draftOf(f: FormState, isNew: boolean): ConfigDraft {
  const num = (v: string) => (v.trim() ? Number(v.trim()) : undefined);
  const ai = f.connector === 'studio-ai';
  return {
    title: f.title.trim(),
    name: isNew && f.name.trim() ? f.name.trim() : undefined,
    connector: f.connector,
    settings: ai
      ? {
          api: f.api,
          endpoint: f.endpoint.trim() || undefined,
          model: f.model.trim() || undefined,
          apiKey: f.keyMode === 'typed' && f.apiKeyValue.trim() ? undefined : f.apiKey.trim() || undefined,
          apiKeyValue: f.keyMode === 'typed' && f.apiKeyValue.trim() ? f.apiKeyValue.trim() : undefined,
          instructions: f.instructions.trim() || undefined,
          doNotTranslate: f.doNotTranslate
            .split(/[\n,]/)
            .map((t) => t.trim())
            .filter(Boolean),
          maxTokens: num(f.maxTokens),
          maxChars: num(f.maxChars),
          timeoutSeconds: num(f.timeoutSeconds),
          preset: f.preset,
          headers: f.headers.filter((h) => h.name.trim() || h.value.trim()).map((h) => `${h.name.trim()}: ${h.value.trim()}`),
        }
      : { instructions: f.instructions.trim() || undefined },
  };
}

/** What is wrong with the API key field, checked before anything is sent (a pasted key never leaves the browser). */
export function apiKeyProblem(value: string): string | null {
  const v = value.trim();
  if (!v || SECRET_REFERENCE.test(v) || ENCRYPTED_KEY.test(v)) return null;
  if (/^\$\[/.test(v)) return 'Write it as $[secret:NAME] or $[env:NAME].';
  return 'A reference to a secret goes here, not the key itself: to enter the key, choose "Enter the key" (it is stored encrypted).';
}

export function ConfigDialog({
  client,
  conf,
  config,
  connectors,
  onSaved,
  close,
}: {
  client: TranslationConfigClient;
  conf: string;
  config: TranslationConfig | null;
  connectors: TranslationConnector[];
  onSaved(config: TranslationConfig): void;
  close(): void;
}) {
  const initial = useMemo(() => initialState(config), [config]);
  const [f, setF] = useState<FormState>(initial);
  const [baseline, setBaseline] = useState<FormState>(initial);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [test, setTest] = useState<TestResult | 'running' | null>(null);
  const isNew = !config;
  const ai = f.connector === 'studio-ai';
  const set = <K extends keyof FormState>(k: K) => (v: FormState[K]) => {
    setF((s) => ({ ...s, [k]: v }));
    setTest(null);
  };
  const preset = presetById(f.preset) ?? presetById('custom')!;
  const headerProblems = f.headers.map(headerProblem);
  const endpointProblem = preset.requiresEndpoint && !f.endpoint.trim() ? `Enter your endpoint, e.g. ${preset.endpointHint}` : null;
  const typedProblem = f.keyMode === 'typed' && /\s/.test(f.apiKeyValue.trim()) ? 'A key is one word, without spaces.' : null;
  const keyProblem =
    (f.keyMode === 'reference' ? apiKeyProblem(f.apiKey) : typedProblem) ?? (headerProblems.some(Boolean) ? 'header' : null) ?? (ai ? endpointProblem : null);
  const storedEncrypted = ENCRYPTED_KEY.test(f.apiKey.trim());
  const local = f.api === 'openai' && /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])/.test(f.endpoint.trim());
  const models = useMemo(() => {
    const ids = preset.id === 'custom' ? [...new Set(Object.values(MODELS).flat().map((m) => m.id))] : preset.models;
    return ids.map((id) => ({ id }));
  }, [preset]);
  const setHeader = (i: number, h: Partial<HeaderRow>) => {
    setF((s) => ({ ...s, headers: s.headers.map((x, j) => (j === i ? { ...x, ...h } : x)) }));
    setTest(null);
  };

  const save = async () => {
    setErrors({});
    if (!f.title.trim()) {
      setErrors({ title: 'A title is required.' });
      return;
    }
    if (keyProblem) return;
    setSaving(true);
    try {
      const saved = await client.save(config ? { path: config.path } : { conf }, draftOf(f, isNew));
      onSaved(saved);
      const next = initialState(saved);
      setF(next);
      setBaseline(next);
    } catch (e) {
      if (e instanceof ConfigValidationError) {
        setErrors(Object.fromEntries(e.errors.map((x) => [x.field, x.message])));
      } else {
        setErrors({ form: e instanceof Error ? e.message : String(e) });
      }
    } finally {
      setSaving(false);
    }
  };

  const runTest = async () => {
    if (keyProblem) return;
    setTest('running');
    try {
      setTest(await client.test({ config: { ...draftOf(f, isNew), title: f.title.trim() || 'Test' } }));
    } catch (e) {
      setTest({ ok: false, error: e instanceof Error ? e.message : String(e) });
    }
  };

  return (
    <FormPage
      kind="Translation provider"
      title={config ? config.title : `Create a translation configuration in ${confTitle(conf)}`}
      path={config?.path ?? conf}
      dirty={JSON.stringify(f) !== JSON.stringify(baseline)}
      busy={saving}
      canSave={!keyProblem && !!f.title.trim()}
      formLabel="Translation configuration"
      onReset={() => {
        setF(baseline);
        setErrors({});
        setTest(null);
      }}
      onSave={save}
      onClose={close}
      wide
      secondaryActions={
        <Button variant="secondary" onPress={() => void runTest()} isPending={test === 'running'} isDisabled={!!keyProblem}>
          <Plug />
          <Text>Test connection</Text>
        </Button>
      }
      error={errors.form ?? errors.conf ?? errors.connector}
    >
        <div className="studio-tcs__form">
          <Flex gap="size-200" wrap>
            <TextField
              label="Title"
              value={f.title}
              onChange={set('title')}
              isRequired
              autoFocus={isNew}
              width="size-3600"
              validationState={errors.title ? 'invalid' : undefined}
              errorMessage={errors.title}
            />
            {isNew && (
              <TextField
                label="Name"
                value={f.name}
                onChange={set('name')}
                placeholder={slug(f.title) || 'from the title'}
                width="size-2400"
                validationState={errors.name ? 'invalid' : undefined}
                errorMessage={errors.name}
                UNSAFE_className="studio-cf-mono"
              />
            )}
            <Picker
              label="Connector"
              items={connectors}
              selectedKey={f.connector}
              onSelectionChange={(k) => set('connector')(String(k))}
              width="size-3600"
            >
              {(c) => <Item key={c.name}>{c.label}</Item>}
            </Picker>
          </Flex>

          {ai && (
            <>
              <Flex gap="size-200" wrap>
                <Picker
                  label="Provider"
                  items={PRESETS}
                  selectedKey={f.preset}
                  onSelectionChange={(k) => {
                    const p = presetById(String(k));
                    if (p) setF((s) => applyPreset(s, p));
                    setTest(null);
                  }}
                  width="size-3600"
                >
                  {(p) => <Item key={p.id}>{p.label}</Item>}
                </Picker>
                <Picker
                  label="Protocol"
                  selectedKey={f.api}
                  onSelectionChange={(k) => set('api')(String(k) as Api)}
                  isDisabled={f.preset !== 'custom'}
                  width="size-3600"
                  description={f.preset === 'custom' ? 'What the server speaks.' : undefined}
                >
                  {(Object.keys(API_PROTOCOLS) as Api[]).map((a) => (
                    <Item key={a}>{API_PROTOCOLS[a]}</Item>
                  ))}
                </Picker>
              </Flex>
              <ComboBox
                label="Model"
                allowsCustomValue
                inputValue={f.model}
                onInputChange={set('model')}
                defaultItems={models}
                width="size-3600"
                description={preset.modelHint}
              >
                {(m) => <Item key={m.id}>{m.id}</Item>}
              </ComboBox>
              <TextField
                label="Endpoint"
                value={f.endpoint}
                onChange={set('endpoint')}
                width="100%"
                placeholder={
                  preset.endpointHint ??
                  (f.api === 'anthropic'
                    ? 'https://api.anthropic.com/v1/messages (default)'
                    : f.api === 'openrouter'
                      ? 'https://openrouter.ai/api/v1/chat/completions (default)'
                      : 'https://api.openai.com/v1/chat/completions (default)')
                }
                validationState={errors['settings.endpoint'] || endpointProblem ? 'invalid' : undefined}
                errorMessage={errors['settings.endpoint'] ?? endpointProblem ?? undefined}
                UNSAFE_className="studio-cf-mono"
              />
              {!local && (
                <RadioGroup
                  label="API key"
                  orientation="horizontal"
                  value={f.keyMode}
                  onChange={(v) => setF((s) => ({ ...s, keyMode: v as FormState['keyMode'], apiKey: v === 'typed' ? (storedEncrypted ? s.apiKey : '') : storedEncrypted ? '' : s.apiKey }))}
                >
                  <Radio value="typed">Enter the key</Radio>
                  <Radio value="reference">Refer to a secret</Radio>
                </RadioGroup>
              )}
              {f.keyMode === 'typed' && !local ? (
                <TextField
                  label={storedEncrypted ? 'New key' : 'Key'}
                  type="password"
                  value={f.apiKeyValue}
                  onChange={set('apiKeyValue')}
                  width="100%"
                  placeholder={storedEncrypted ? 'A key is stored (encrypted): type a new one to replace it' : 'The provider’s API key'}
                  validationState={typedProblem || errors['settings.apiKeyValue'] ? 'invalid' : undefined}
                  errorMessage={typedProblem ?? errors['settings.apiKeyValue']}
                  description="Stored encrypted with this instance's master key (kept outside the repository); never shown again. A copy of this configuration on another instance needs the key entered there."
                  autoComplete="new-password"
                  UNSAFE_className="studio-cf-mono"
                />
              ) : (
                <TextField
                  label={local ? 'API key' : 'Secret reference'}
                  value={f.apiKey}
                  onChange={set('apiKey')}
                  width="100%"
                  placeholder={
                    preset.headers?.length
                      ? 'empty: the key goes in a header below'
                      : `$[secret:${preset.keyVariable ?? DEFAULT_KEY_VARIABLE[f.api]}]`
                  }
                  validationState={apiKeyProblem(f.apiKey) || errors['settings.apiKey'] ? 'invalid' : undefined}
                  errorMessage={apiKeyProblem(f.apiKey) ?? errors['settings.apiKey']}
                  description={
                    local
                      ? 'A local server usually needs no key.'
                      : 'Sent as Authorization: Bearer. $[secret:NAME] is a "Sensible Studio Secret" OSGi configuration (or else the environment variable NAME); $[env:NAME] an environment variable.'
                  }
                  autoComplete="off"
                  UNSAFE_className="studio-cf-mono"
                />
              )}
              <div className="studio-tcs__headers" role="group" aria-label="Extra headers">
                <div className="studio-tcs__headers-title">Extra headers</div>
                {f.headers.map((h, i) => (
                  <Flex key={i} gap="size-100" alignItems="start">
                    <TextField
                      aria-label={`Header ${i + 1} name`}
                      value={h.name}
                      onChange={(v) => setHeader(i, { name: v })}
                      placeholder="Name, e.g. api-key"
                      width="size-2400"
                      UNSAFE_className="studio-cf-mono"
                    />
                    <TextField
                      aria-label={`Header ${i + 1} value`}
                      value={h.value}
                      onChange={(v) => setHeader(i, { value: v })}
                      placeholder="Value, e.g. $[secret:NAME]"
                      width="100%"
                      autoComplete="off"
                      validationState={headerProblems[i] || errors[`settings.headers.${i}`] ? 'invalid' : undefined}
                      errorMessage={headerProblems[i] ?? errors[`settings.headers.${i}`]}
                      UNSAFE_className="studio-cf-mono"
                    />
                    <ActionButton
                      isQuiet
                      aria-label={`Remove header ${i + 1}`}
                      onPress={() => {
                        setF((s) => ({ ...s, headers: s.headers.filter((_, j) => j !== i) }));
                        setTest(null);
                      }}
                    >
                      <Delete />
                    </ActionButton>
                  </Flex>
                ))}
                <div>
                  <ActionButton isQuiet onPress={() => set('headers')([...f.headers, { name: '', value: '' }])}>
                    <Add />
                    <Text>Add header</Text>
                  </ActionButton>
                </div>
                <div className="studio-tcs__hint">
                  For providers that authenticate or route differently, e.g. Azure&apos;s api-key. Credentials only as $[secret:NAME].
                </div>
              </div>
              <div className="studio-tcs__hint">
                <Link href={SECRETS_CONSOLE} target="_blank">
                  Add a secret
                </Link>{' '}
                in the OSGi configuration (Name, e.g. ANTHROPIC_API_KEY, and the key as Value). It stays in crx-quickstart, out of the repository and
                content packages.
              </div>
            </>
          )}

          <TextArea
            label="Guidance"
            value={f.instructions}
            onChange={set('instructions')}
            width="100%"
            height="size-1000"
            description="Tone and style for every translation, e.g. Friendly and informal; address readers directly."
          />
          {ai && (
            <>
              <TextArea
                label="Do not translate"
                value={f.doNotTranslate}
                onChange={set('doNotTranslate')}
                width="100%"
                height="size-1000"
                description="Brand and product names kept as they are, one per line."
              />
              <Flex gap="size-200" wrap>
                {(
                  [
                    ['maxTokens', 'Maximum output tokens', '16000'],
                    ['maxChars', 'Characters per request', '12000'],
                    ['timeoutSeconds', 'Timeout (seconds)', '180'],
                  ] as const
                ).map(([k, label, placeholder]) => (
                  <TextField
                    key={k}
                    label={label}
                    value={f[k]}
                    onChange={set(k)}
                    placeholder={placeholder}
                    inputMode="numeric"
                    width="size-2000"
                    validationState={errors[`settings.${k}`] ? 'invalid' : undefined}
                    errorMessage={errors[`settings.${k}`]}
                  />
                ))}
              </Flex>
            </>
          )}
        </div>
        {test && test !== 'running' && <TestOutcome result={test} />}
    </FormPage>
  );
}

function TestOutcome({ result }: { result: TestResult }) {
  if (result.ok) {
    return (
      <InlineAlert variant="positive" UNSAFE_className="studio-tcs__test">
        <Heading>Connected · {seconds(result.ms)}</Heading>
        <Content>
          {result.label && <div>{result.label}</div>}
          {result.sample && <div className="studio-tcs__sample">“{result.sample}”</div>}
        </Content>
      </InlineAlert>
    );
  }
  return (
    <InlineAlert variant="negative" UNSAFE_className="studio-tcs__test">
      <Heading>{result.code === 'MISSING_CREDENTIALS' ? 'No API key' : 'The connection failed'}</Heading>
      <Content>
        <div>{result.error}</div>
        {result.code === 'MISSING_CREDENTIALS' && (
          <div>
            <Link href={SECRETS_CONSOLE} target="_blank">
              Add the secret
            </Link>{' '}
            on this instance, then test again.
          </div>
        )}
      </Content>
    </InlineAlert>
  );
}

function slug(title: string): string {
  return title
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}
