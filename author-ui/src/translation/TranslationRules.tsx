import './rules.scss';
import {
  ActionButton,
  Button,
  Checkbox,
  ComboBox,
  DialogContainer,
  Item,
  TextField,
  Tooltip,
  TooltipTrigger,
  Text,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { TagsField } from '../assets/AssetDetails';
import { HttpComponentsClient, type ComponentSummary } from '../components/api';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { FormPage, FormSection } from '../shell/FormPage';
import { Link, useNavigate, useScreenTitle } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { VOCAB } from '../shell/vocabulary';
import {
  comparable,
  contextFor,
  contextProblems,
  newContext,
  ruleEditorUrl,
  RULES_URL,
  type RulesPreview,
  type TranslationContext,
  type TranslationRulesClient,
} from './rules';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
const when = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '');
const few = (list: string[], n = 4) => (list.length <= n ? list.join(', ') : `${list.slice(0, n).join(', ')} and ${list.length - n} more`);

// ------------------------------------------------------------------------------------------------ the contexts

/**
 * AEM's Translation Configuration: the contexts (content paths) with their translation rules. A page is translated
 * with the rules of the most specific context above it; `/content` is the built-in one.
 */
export function TranslationContexts({ client }: { client: TranslationRulesClient }) {
  const navigate = useNavigate();
  const [contexts, setContexts] = useState<TranslationContext[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<TranslationContext | null>(null);

  useScreenTitle(`${VOCAB.translations} · Configuration`, `Translation Configuration | ${VOCAB.product}`);
  const load = useCallback(() => {
    setError(null);
    client.list().then(setContexts, (e: unknown) => setError(message(e)));
  }, [client]);
  useEffect(load, [load]);

  return (
    <div className="studio-sites studio-trrules">
      <ToolBar
        url={RULES_URL}
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Read the rules again</Tooltip>
          </TooltipTrigger>
        }
        primary={
          <Button variant="accent" onPress={() => navigate(ruleEditorUrl(null))}>
            <Add />
            <Text>Add context</Text>
          </Button>
        }
      />
      <main className="studio-sites__content studio-trrules__body">
        {error ? (
          <ErrorState title="Could not read the translation rules">{error}</ErrorState>
        ) : !contexts ? (
          <LoadingState />
        ) : contexts.length === 0 ? (
          <EmptyState title="No context">Add one for the pages under a path.</EmptyState>
        ) : (
          <>
            <p className="studio-trrules__intro">
              A page is translated with the rules of the most specific context above it: <code>/content/my-site</code> for the pages of
              WKND, <code>/content</code> for the others.
            </p>
            <table className="studio-trrules__table">
              <thead>
                <tr>
                  <th>Context</th>
                  <th>Translated properties</th>
                  <th>Components</th>
                  <th>Assets</th>
                  <th>Modified</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {contexts.map((c) => (
                  <tr key={c.path}>
                    <td>
                      <Link to={ruleEditorUrl(c.path)} className="studio-trrules__link">
                        <code>{c.path}</code>
                      </Link>
                      {c.builtIn && <span className="studio-trrules__tag">Built-in</span>}
                    </td>
                    <td>
                      {few(c.properties)}
                      {c.propertySuffixes.length > 0 && <div className="studio-trrules__muted">{`and names ending in ${c.propertySuffixes.join(', ')}`}</div>}
                    </td>
                    <td>
                      {c.resourceTypes.length === 0 && c.excludedResourceTypes.length === 0 ? (
                        <span className="studio-trrules__muted">None of their own</span>
                      ) : (
                        <>
                          {c.resourceTypes.length > 0 && <div>{`${c.resourceTypes.length} with properties of their own`}</div>}
                          {c.excludedResourceTypes.length > 0 && <div className="studio-trrules__muted">{`${c.excludedResourceTypes.length} not translated`}</div>}
                        </>
                      )}
                    </td>
                    <td>{c.translateAssets ? few(c.assetProperties, 3) : <span className="studio-trrules__muted">Not translated</span>}</td>
                    <td>{c.builtIn ? <span className="studio-trrules__muted">OSGi configuration</span> : [when(c.modified), c.modifiedBy].filter(Boolean).join(' · ')}</td>
                    <td>
                      {!c.builtIn && (
                        <TooltipTrigger delay={400}>
                          <ActionButton isQuiet aria-label={c.path === '/content' ? `Reset ${c.path}` : `Delete ${c.path}`} onPress={() => setRemoving(c)}>
                            <Delete />
                          </ActionButton>
                          <Tooltip>{c.path === '/content' ? 'Back to the built-in rules' : 'Delete the context'}</Tooltip>
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
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title={removing.path === '/content' ? 'Reset /content' : 'Delete context'}
            variant="destructive"
            primaryActionLabel={removing.path === '/content' ? 'Reset' : 'Delete'}
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              client.remove(removing.path).then(load, (e: unknown) => setError(message(e)))
            }
          >
            {removing.path === '/content'
              ? 'The pages under /content are translated with the built-in rules again (the OSGi configuration).'
              : `The pages under ${removing.path} are translated with the rules of the context above it again. Copies made already stay as they are.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ one context

type Tab = 'properties' | 'components' | 'filters' | 'references' | 'preview';
const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'properties', label: 'Properties' },
  { id: 'components', label: 'Components' },
  { id: 'filters', label: 'Filters' },
  { id: 'references', label: 'References' },
  { id: 'preview', label: 'Preview' },
];

const components = new HttpComponentsClient();

/** One context's rules, as AEM's Translation Rules Editor: Properties, Components, Filters, References; and a preview. */
export function TranslationContextEditor({ client, context }: { client: TranslationRulesClient; context: string | null }) {
  const navigate = useNavigate();
  const [all, setAll] = useState<TranslationContext[] | null>(null);
  const [loaded, setLoaded] = useState<TranslationContext | null>(null);
  const [rules, setRules] = useState<TranslationContext | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [tab, setTab] = useState<Tab>('properties');
  const [types, setTypes] = useState<ComponentSummary[]>([]);

  useScreenTitle(context ? `Translation · ${context}` : 'Translation · New context', `${context ?? 'New context'} | Translation Configuration | ${VOCAB.product}`);

  useEffect(() => {
    client.list().then(
      (list) => {
        setAll(list);
        const own = context ? list.find((c) => c.path === context) ?? null : null;
        if (context && !own) {
          setError(`There is no context ${context}.`);
          return;
        }
        // a new context starts as a copy of the rules that apply under /content
        const start = own ? { ...newContext(own, own.path), builtIn: own.builtIn } : newContext(contextFor(list, '/content'), '');
        setLoaded(start);
        setRules(start);
      },
      (e: unknown) => setError(message(e)),
    );
    components.list().then(setTypes, () => setTypes([]));
  }, [client, context]);

  const others = useMemo(() => (all ?? []).filter((c) => c.path !== context), [all, context]);
  const problems = rules ? contextProblems(rules, others) : [];
  const dirty = !!rules && !!loaded && comparable(rules) !== comparable(loaded);
  const set = (patch: Partial<TranslationContext>) => setRules((r) => (r ? { ...r, ...patch } : r));
  const close = () => navigate(RULES_URL);

  const save = async () => {
    if (!rules) return;
    setBusy(true);
    setSaveError(null);
    try {
      const saved = await client.save({ ...rules, path: rules.path.trim().replace(/\/+$/, '') }, context && !loaded?.builtIn ? context : null);
      setLoaded(saved);
      setRules(saved);
      if (saved.path !== context) navigate(ruleEditorUrl(saved.path), { replace: true });
    } catch (e) {
      setSaveError(message(e));
    } finally {
      setBusy(false);
    }
  };

  if (error) {
    return (
      <div className="studio-sites studio-trrules">
        <ToolBar url={RULES_URL} />
        <ErrorState title="Could not open the context" action={{ label: 'All contexts', onPress: close }}>
          {error}
        </ErrorState>
      </div>
    );
  }
  if (!rules) return <LoadingState />;

  const typeItems = types.map((t) => ({ id: t.resourceType, title: t.title }));
  return (
    <div className="studio-trrules">
      <FormPage
        kind="Translation context"
        title={context ? context : 'Create Context'}
        path={context && !loaded?.builtIn ? `/conf/global/settings/translation/rules` : undefined}
        dirty={dirty}
        busy={busy}
        canSave={problems.length === 0}
        requireDirty={!loaded?.builtIn || dirty}
        primaryLabel={loaded?.builtIn ? 'Save as own rules' : 'Save'}
        tabs={TABS}
        activeTab={tab}
        onTabChange={(id) => setTab(id as Tab)}
        onReset={() => (dirty ? setRules(loaded) : close())}
        onSave={save}
        onClose={close}
        error={saveError}
        wide
      >
        {tab === 'properties' && (
          <>
            <FormSection
              title="Context"
              description="The pages under this path are translated with these rules, unless a context deeper down has its own."
            >
              <TextField
                label="Path"
                value={rules.path}
                onChange={(v) => set({ path: v })}
                isReadOnly={loaded?.builtIn || context === '/content'}
                width="size-4600"
                description={
                  loaded?.builtIn
                    ? 'The built-in rules, from the OSGi configuration. Saving keeps them in /conf; Reset in the list brings these back.'
                    : 'For example /content/my-site.'
                }
              />
            </FormSection>
            <FormSection title="Properties" description="Translated in every component, and in the page, when they hold text. Paths, numbers and settings are never sent.">
              <TagsField label="Translated properties" values={rules.properties} onChange={(v) => set({ properties: v })} />
              <TagsField label="Translated name endings" values={rules.propertySuffixes} onChange={(v) => set({ propertySuffixes: v })} />
              <p className="studio-trrules__muted">For instance Label: accessibilityLabel and signInLabel are translated too. Not for names with a namespace (cq:…).</p>
              <TagsField label="Never translated" values={rules.excludedProperties} onChange={(v) => set({ excludedProperties: v })} />
            </FormSection>
          </>
        )}

        {tab === 'components' && (
          <FormSection title="Components" description="Properties one component has of its own, translated on top of the ones in Properties.">
            {rules.resourceTypes.length === 0 && <p className="studio-trrules__muted">No component has properties of its own here.</p>}
            {rules.resourceTypes.map((t, i) => (
              <fieldset key={i} className="studio-trrules__component" aria-label={`Component ${t.resourceType || i + 1}`}>
                <ComboBox
                  label="Resource type"
                  allowsCustomValue
                  inputValue={t.resourceType}
                  onInputChange={(v) => set({ resourceTypes: rules.resourceTypes.map((x, j) => (j === i ? { ...x, resourceType: v } : x)) })}
                  onSelectionChange={(k) => k != null && set({ resourceTypes: rules.resourceTypes.map((x, j) => (j === i ? { ...x, resourceType: String(k) } : x)) })}
                  defaultItems={typeItems}
                  width="size-4600"
                >
                  {(item) => (
                    // the field gets the resource type; the title helps to find it
                    <Item key={item.id} textValue={item.id}>
                      <Text>{item.id}</Text>
                      <Text slot="description">{item.title}</Text>
                    </Item>
                  )}
                </ComboBox>
                <TagsField
                  label="Its translated properties"
                  values={t.properties}
                  onChange={(v) => set({ resourceTypes: rules.resourceTypes.map((x, j) => (j === i ? { ...x, properties: v } : x)) })}
                />
                <ActionButton isQuiet aria-label={`Remove ${t.resourceType || 'component'}`} onPress={() => set({ resourceTypes: rules.resourceTypes.filter((_, j) => j !== i) })}>
                  <Delete />
                </ActionButton>
              </fieldset>
            ))}
            <div>
              <ActionButton onPress={() => set({ resourceTypes: [...rules.resourceTypes, { resourceType: '', properties: [] }] })}>
                <Add />
                <Text>Add component</Text>
              </ActionButton>
            </div>
          </FormSection>
        )}

        {tab === 'filters' && (
          <FormSection title="Not translated components" description="Their texts stay as they are in every language version: code, embeds, a brand's slogan.">
            <TagsField label="Resource types" values={rules.excludedResourceTypes} onChange={(v) => set({ excludedResourceTypes: v })} />
            <p className="studio-trrules__muted">The components inside them stay as they are too.</p>
          </FormSection>
        )}

        {tab === 'references' && (
          <FormSection title="Assets" description="When a page is translated with what it uses, the images and files it shows.">
            <Checkbox isSelected={rules.translateAssets} onChange={(v) => set({ translateAssets: v })}>
              Translate the assets a page shows: they get language versions with their metadata translated
            </Checkbox>
            <TagsField label="Translated metadata" values={rules.assetProperties} onChange={(v) => set({ assetProperties: v })} />
            <p className="studio-trrules__muted">Assets without such texts stay shared by every language. Experience Fragments and content fragments a page uses are translated with it either way.</p>
          </FormSection>
        )}

        {tab === 'preview' && <Preview client={client} rules={rules} saved={!dirty && !loaded?.builtIn ? rules.path : null} />}

        {problems.length > 0 && (
          <ul className="studio-trrules__problems" role="alert">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}
      </FormPage>
    </div>
  );
}

/** The texts the saved rules take from a page, to check them before translating. */
function Preview({ client, rules, saved }: { client: TranslationRulesClient; rules: TranslationContext; saved: string | null }) {
  const [page, setPage] = useState(rules.path && rules.path !== '/content' ? rules.path : '');
  const [result, setResult] = useState<RulesPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const show = () => {
    setBusy(true);
    setError(null);
    client.preview(page.trim()).then(
      (r) => {
        setResult(r);
        setBusy(false);
      },
      (e: unknown) => {
        setError(message(e));
        setResult(null);
        setBusy(false);
      },
    );
  };
  return (
    <FormSection
      title="Preview"
      description="The texts of a page that are sent to translation, with the saved rules of the context that applies to it. Save first to see your changes."
    >
      <div className="studio-trrules__preview-bar">
        <TextField label="Page" value={page} onChange={setPage} width="size-6000" description="A page path, such as /content/my-site/language-masters/en/faqs" />
        <Button variant="secondary" onPress={show} isDisabled={!page.trim().startsWith('/content/') || busy}>
          Show the texts
        </Button>
      </div>
      {error && <p className="studio-trrules__problems">{error}</p>}
      {result && (
        <>
          <p className="studio-trrules__muted">
            {`${result.total} text${result.total === 1 ? '' : 's'}, with the rules of `}
            <code>{result.context}</code>
            {saved && result.context !== saved ? ' (a context deeper down than this one applies to that page)' : ''}
          </p>
          <table className="studio-trrules__table">
            <thead>
              <tr>
                <th>Where</th>
                <th>Property</th>
                <th>Text</th>
              </tr>
            </thead>
            <tbody>
              {result.texts.map((t, i) => (
                <tr key={i}>
                  <td>
                    {t.label}
                    {t.node && <div className="studio-trrules__muted">{t.node}</div>}
                  </td>
                  <td>
                    <code>{t.property}</code>
                  </td>
                  <td className="studio-trrules__text">{t.html ? t.text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() : t.text}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </FormSection>
  );
}
