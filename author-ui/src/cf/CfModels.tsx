import {
  VOCAB } from '../shell/vocabulary';
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
  Picker,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { PathCrumbs } from '../shell/PathCrumbs';
import { CardView, ColumnView, ListView, ViewSwitch, readView, storeView, type BrowserItem, type BrowserListing, type ViewMode } from '../sites/views';
import { ProductBar } from '../shell/ProductBar';
import { CfValidationError, cfConsoleUrl, cfModelEditorUrl, cfModelsUrl, type CfClient, type CfConf, type CfModelSummary } from './api';
import { slugify } from './fields';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { RecordCard, RecordIcon, RecordModelIcon } from '../shell/recordIcons';
import Folder from '@spectrum-icons/workflow/Folder';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { SelectionBar } from '../shell/SelectionBar';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: CfClient;
  /** `/conf` (all configurations) or `/conf/<name>` (its models). */
  initialPath: string;
  /** For the views' thumbnails; a stub is enough — models have none. */
  studio?: StudioClient;
}

const ROOT = '/conf';
const VIEW_KEY = 'studio.models.view';
const STUB_STUDIO = { assetUrl: (p: string) => p } as unknown as StudioClient;

/** A model as the views show it: a `fragment` item whose "template" column is its status. */
type ModelItem = BrowserItem & { enabled?: boolean; description?: string };

function confItem(c: CfConf, models: number): ModelItem {
  return { path: c.path, name: c.path.replace(/^\/conf\//, ''), title: c.title, type: 'folder', hasChildren: models > 0 };
}

function modelItem(m: CfModelSummary): ModelItem {
  return {
    path: m.path,
    name: m.path.slice(m.path.lastIndexOf('/') + 1),
    title: m.title,
    type: 'fragment',
    hasChildren: false,
    templateTitle: m.enabled ? 'Enabled' : 'Disabled',
    description: m.description,
    enabled: m.enabled,
  };
}

/** The end of the columns: the model's title, status, path and description, and the way into the editor. */
function ModelPreview({ model, onOpen }: { model: CfModelSummary; onOpen(): void }) {
  const rows: Array<[string, string | undefined]> = [
    ['Title', model.title],
    ['Name', model.path.slice(model.path.lastIndexOf('/') + 1)],
    ['Status', model.enabled ? 'Enabled' : 'Disabled'],
    ['Configuration', model.conf ? model.conf.replace(/^\/conf\//, '') : undefined],
    ['Description', model.description || undefined],
  ];
  return (
    <aside className="studio-preview" aria-label={`Preview of ${model.title}`}>
      <div className="studio-preview__thumb studio-preview__thumb--record">
        <RecordCard kind="model" title={model.title} description={model.description} />
      </div>
      <dl className="studio-preview__meta">
        {rows
          .filter(([, v]) => v)
          .map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
      </dl>
      <Button variant="primary" onPress={onOpen} alignSelf="start">
        <Edit />
        <Text>Edit</Text>
      </Button>
    </aside>
  );
}

/**
 * The record models console (`/cf.html/conf[/<conf>]`), with the consoles' Column / Card / List views and
 * breadcrumb: the configurations under /conf as folders, the models of one inside, with create,
 * enable/disable and delete. A model opens in the model editor.
 */
export function CfModels({ client, initialPath, studio = STUB_STUDIO }: Props) {
  const [conf, setConf] = useState<string | null>(initialPath === ROOT ? null : initialPath);
  const tab = useTab();
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const go = useNavigate();
  const tabConf = tab?.place.kind === 'cf-models' ? (tab.place.conf === ROOT ? null : tab.place.conf) : undefined;
  useEffect(() => {
    if (tabConf !== undefined && tabConf !== conf) {
      setConf(tabConf);
      setSelected(new Set());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabConf]);
  const [confs, setConfs] = useState<CfConf[] | null>(null);
  const [models, setModels] = useState<CfModelSummary[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<null | 'create' | 'delete'>(null);
  const [user, setUser] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const [view, setViewState] = useState<ViewMode>(() => readView(VIEW_KEY));
  const setView = (v: ViewMode) => {
    setViewState(v);
    storeView(VIEW_KEY, v);
  };

  useScreenTitle(conf ? `${VOCAB.models} · ${conf.replace(/^\/conf\//, '')}` : VOCAB.models, `${VOCAB.models} | ${VOCAB.product}`);
  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([client.confs(), client.models()])
      .then(([c, m]) => {
        if (cancelled) return;
        // the shared configuration has no title of its own: its node name, "global", is not a word for authors
        setConfs(c.map((x) => (x.path === '/conf/global' && (!x.title || x.title === 'global') ? { ...x, title: 'Global' } : x)));
        setModels(m);
        setFailure(null);
      })
      .catch((e: unknown) => !cancelled && setFailure(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [client, version]);

  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      const m = window.location.pathname.match(/^\/cf\.html(\/conf\/.+)$/);
      setConf(m ? decodeURI(m[1]).replace(/\/$/, '') : null);
      setSelected(new Set());
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const navigate = useCallback((c: string | null) => {
    if (tabRef.current) tabRef.current.setPlace({ kind: 'cf-models', conf: c ?? ROOT });
    else window.history.pushState(null, '', cfModelsUrl(c));
    setConf(c);
    setSelected(new Set());
  }, []);

  const modelsOf = useCallback((c: string) => (models ?? []).filter((m) => m.path.startsWith(`${c}/settings/dam/cfm/models/`)), [models]);
  const rows = conf ? modelsOf(conf) : [];
  const selectedModels = rows.filter((m) => selected.has(m.path));
  const confTitle = conf ? confs?.find((c) => c.path === conf)?.title ?? conf.replace(/^\/conf\//, '') : null;

  // the tree the views read: /conf with the configurations, the chosen configuration with its models
  const listings = useMemo(() => {
    const map = new Map<string, BrowserListing<ModelItem>>();
    if (!confs || !models) return map;
    const root: ModelItem = { path: ROOT, name: 'conf', title: VOCAB.models, type: 'folder', hasChildren: confs.length > 0 };
    map.set(ROOT, { item: root, trail: [{ path: ROOT, title: VOCAB.models }], children: confs.map((c) => confItem(c, modelsOf(c.path).length)) });
    if (conf) {
      const c = confs.find((x) => x.path === conf);
      const item = c ? confItem(c, modelsOf(conf).length) : { path: conf, name: conf.replace(/^\/conf\//, ''), title: confTitle ?? conf, type: 'folder' as const, hasChildren: false };
      map.set(conf, { item, trail: [{ path: ROOT, title: VOCAB.models }, { path: conf, title: item.title }], children: modelsOf(conf).map(modelItem) });
    }
    return map;
  }, [confs, models, conf, confTitle, modelsOf]);
  const current = listings.get(conf ?? ROOT) ?? null;
  const trail = current?.trail ?? [{ path: ROOT, title: VOCAB.models }];
  const items = current?.children ?? [];
  const modelByPath = useMemo(() => new Map((models ?? []).map((m) => [m.path, m])), [models]);

  const toggle = (item: BrowserItem) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(item.path)) next.delete(item.path);
      else next.add(item.path);
      return next;
    });
  // configurations are folders; models get the record model icon (a record's shape, its slots empty)
  const modelIcon = (item: BrowserItem) => (item.type === 'folder' ? <Folder size="S" /> : <RecordModelIcon size="S" />);
  const open = (item: BrowserItem) => (item.type === 'folder' ? navigate(item.path) : go(cfModelEditorUrl(item.path)));
  const labels = {
    kind: 'Status',
    empty: conf ? `No ${VOCAB.models.toLowerCase()} yet. Create one to define the structure of content fragments.` : `No configurations. ${VOCAB.models} live in a configuration under /conf.`,
  };

  const setEnabled = async (enabled: boolean) => {
    try {
      for (const m of selectedModels) await client.setModelStatus(m.path, enabled);
      ToastQueue.positive(`${selectedModels.length} model(s) ${enabled ? 'enabled' : 'disabled'}`, { timeout: 2500 });
      setVersion((v) => v + 1);
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
  };

  return (
    <div className="studio-cfc">
      {!tab && <ProductBar user={user} />}
      {/* with a selection the bar is the selection bar every console has; without one, the console's own */}
      {selectedModels.length > 0 ? (
        <SelectionBar
          count={selectedModels.length}
          leading={['edit', 'enable', 'disable']}
          onClear={() => setSelected(new Set())}
          actions={[
            ...(selectedModels.length === 1 ? [{ id: 'edit', label: 'Edit', icon: <Edit />, run: () => go(cfModelEditorUrl(selectedModels[0].path)) }] : []),
            { id: 'enable', label: 'Enable', run: () => void setEnabled(true) },
            { id: 'disable', label: 'Disable', run: () => void setEnabled(false) },
            { id: 'delete', label: 'Delete', icon: <Delete />, danger: true, run: () => setDialog('delete') },
          ]}
        />
      ) : (
        <ScreenFrame
          identity={<PathCrumbs crumbs={trail} onGo={(p) => navigate(p === ROOT ? null : p)} />}
          actions={<Flex gap="size-100" alignItems="center" wrap>
            <ActionButton isQuiet onPress={() => go(cfConsoleUrl('/content/dam'))}>
              <RecordIcon />
              <Text>{VOCAB.records}</Text>
            </ActionButton>
            <ViewSwitch view={view} onChange={setView} />
          </Flex>}
          primary={<Button variant="accent" onPress={() => setDialog('create')} isDisabled={!confs?.length}><Add /><Text>Create model</Text></Button>}
        />
      )}

      <div className="studio-cfc__content">
        {failure ? (
          <ErrorState title="Could not load the models">{failure}</ErrorState>
        ) : !current ? (
          <LoadingState />
        ) : view === 'column' ? (
          <ColumnView
            client={studio}
            listings={listings}
            trail={trail}
            current={current}
            selected={[...selected]}
            onOpen={open}
            onToggle={toggle}
            labels={labels}
            renderIcon={modelIcon}
            renderPreview={(item) => {
              const m = modelByPath.get(item.path);
              return m ? <ModelPreview model={m} onOpen={() => go(cfModelEditorUrl(m.path))} /> : null;
            }}
          />
        ) : view === 'card' ? (
          <CardView client={studio} items={items} selected={[...selected]} onOpen={open} onToggle={toggle} labels={labels} renderIcon={modelIcon} />
        ) : (
          <ListView client={studio} items={items} selected={[...selected]} onOpen={open} onToggle={toggle} labels={labels} renderIcon={modelIcon} />
        )}
      </div>

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog === 'create' && confs && (
          <CreateModelDialog
            confs={confs}
            conf={conf}
            onCreate={async (c, title, name, description) => {
              const path = await client.createModel(c, title, name, description);
              go(cfModelEditorUrl(path));
            }}
          />
        )}
        {dialog === 'delete' && (
          <StudioConfirmDialog
            title="Delete models"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              const refused: string[] = [];
              for (const m of selectedModels) {
                try {
                  await client.deleteModel(m.path);
                } catch (e) {
                  refused.push(`${m.title}: ${e instanceof CfValidationError ? e.errors[0]?.message : e instanceof Error ? e.message : String(e)}`);
                }
              }
              if (refused.length) ToastQueue.negative(`Not deleted. ${refused.join(' ')}`, { timeout: 10000 });
              else ToastQueue.positive('Deleted', { timeout: 2500 });
              setSelected(new Set());
              setVersion((v) => v + 1);
            }}
          >
            Delete {selectedModels.length === 1 ? `the model “${selectedModels[0].title}”` : `${selectedModels.length} models`}? Models used by content fragments
            are kept.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer />
    </div>
  );
}

function CreateModelDialog({
  confs,
  conf,
  onCreate,
}: {
  confs: CfConf[];
  conf: string | null;
  onCreate(conf: string, title: string, name: string, description: string): Promise<void>;
}) {
  const dialog = useDialogContainer();
  const [target, setTarget] = useState(conf ?? confs[0]?.path ?? '');
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [nameTouched, setNameTouched] = useState(false);
  const [description, setDescription] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  // slugify answers a fallback for empty text; with no title there is no name to propose yet
  const effectiveName = nameTouched ? name : title.trim() ? slugify(title) : '';

  const submit = async () => {
    setBusy(true);
    try {
      await onCreate(target, title.trim(), effectiveName, description.trim());
    } catch (e) {
      if (e instanceof CfValidationError) setErrors(Object.fromEntries(e.errors.map((x) => [x.field, x.message])));
      else setErrors({ form: e instanceof Error ? e.message : String(e) });
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>Create model</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <Picker label="Configuration" items={confs} selectedKey={target} onSelectionChange={(k) => setTarget(String(k))} isDisabled={!!conf} isRequired>
            {(c) => <Item key={c.path}>{c.title}</Item>}
          </Picker>
          <TextField label="Model title" value={title} onChange={setTitle} isRequired autoFocus validationState={errors.title ? 'invalid' : undefined} errorMessage={errors.title} />
          <TextField
            label="Name"
            value={effectiveName}
            onChange={(n) => {
              setNameTouched(true);
              setName(n);
            }}
            description={`${target}/settings/dam/cfm/models/${effectiveName || '…'}`}
            validationState={errors.name ? 'invalid' : undefined}
            errorMessage={errors.name}
            UNSAFE_className="studio-cf-mono"
          />
          <TextArea label="Description" value={description} onChange={setDescription} />
        </Form>
        {errors.form && <div className="studio-cf-error">{errors.form}</div>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isDisabled={!title.trim() || !target || busy} isPending={busy}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
