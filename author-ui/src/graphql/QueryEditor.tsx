import { ActionButton, Button, DialogContainer, Item, Picker, SearchField, Text, TextField, ToastQueue, ToggleButton, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Brackets from '@spectrum-icons/workflow/Brackets';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Copy from '@spectrum-icons/workflow/Copy';
import Data from '@spectrum-icons/workflow/Data';
import Delete from '@spectrum-icons/workflow/Delete';
import Link from '@spectrum-icons/workflow/Link';
import Play from '@spectrum-icons/workflow/Play';
import SaveFloppy from '@spectrum-icons/workflow/SaveFloppy';
import TextAlignLeft from '@spectrum-icons/workflow/TextAlignLeft';
import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { InspectorShell } from '../shell/InspectorShell';
import { useScreenTitle } from '../shell/ShellContext';
import { StudioConfirmDialog, StudioDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { VOCAB } from '../shell/vocabulary';
import { executeUrl, prettify, type GqlEndpoint, type GqlField, type GqlResult, type GqlSchema, type GraphQLClient, type PersistedQuery } from './api';
import { GRAPHIQL_URL } from './GraphQLConsole';

const STARTER = `# Write a query and run it with Run (⌘↵).
# The Schema panel lists what can be asked; a click adds a field.
{
  __typename
}
`;

const baseType = (t: string) => t.replace(/[[\]!]/g, '');

/**
 * AEM's GraphQL Query Editor (GraphiQL): a query, its variables and the result, on one endpoint; the
 * persisted queries of the endpoint's configuration on the left, the schema on the right.
 */
export function QueryEditor({ client, endpoint: wanted, query: wantedQuery }: { client: GraphQLClient; endpoint?: string | null; query?: string | null }) {
  const [endpoints, setEndpoints] = useState<GqlEndpoint[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [key, setKey] = useState<string>(wanted ?? '');
  const endpoint = useMemo(() => endpoints?.find((e) => (e.conf ?? 'global') === key) ?? null, [endpoints, key]);
  const [persisted, setPersisted] = useState<PersistedQuery[]>([]);
  const [current, setCurrent] = useState<PersistedQuery | null>(null);
  const [text, setText] = useState(STARTER);
  const [variables, setVariables] = useState('');
  const [result, setResult] = useState<GqlResult | null>(null);
  const [running, setRunning] = useState(false);
  // open beside the editors when there is room; on a narrow window it would cover them
  const [schemaOpen, setSchemaOpen] = useState(() => typeof window === 'undefined' || window.matchMedia?.('(min-width: 1181px)').matches !== false);
  const [schema, setSchema] = useState<GqlSchema | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [removing, setRemoving] = useState<PersistedQuery | null>(null);
  const editor = useRef<HTMLTextAreaElement>(null);

  useScreenTitle(`GraphQL · ${endpoint?.title ?? 'Query Editor'}`, `Query Editor | GraphQL | ${VOCAB.product}`);

  useEffect(() => {
    client.endpoints().then(
      (list) => {
        setEndpoints(list);
        setKey((k) => (list.some((e) => (e.conf ?? 'global') === k) ? k : (list.find((e) => e.conf)?.conf ?? 'global')));
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);

  const loadPersisted = useCallback(() => {
    if (!endpoint?.conf) return setPersisted([]);
    client.persisted(endpoint.conf).then(setPersisted, () => setPersisted([]));
  }, [client, endpoint]);
  useEffect(loadPersisted, [loadPersisted]);

  // a persisted query named in the address opens once its list is read
  const opened = useRef(false);
  useEffect(() => {
    if (opened.current || !wantedQuery || persisted.length === 0) return;
    const q = persisted.find((p) => p.name === wantedQuery);
    if (q) {
      opened.current = true;
      open(q);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- opens once
  }, [persisted, wantedQuery]);

  useEffect(() => {
    if (!endpoint) return;
    setSchema(null);
    setSchemaError(null);
    client.schema(endpoint).then(setSchema, (e: unknown) => setSchemaError(e instanceof Error ? e.message : String(e)));
  }, [client, endpoint]);

  const open = (q: PersistedQuery) => {
    setCurrent(q);
    setText(q.query.endsWith('\n') ? q.query : `${q.query}\n`);
    setVariables('');
    setResult(null);
  };
  const fresh = () => {
    setCurrent(null);
    setText(STARTER);
    setVariables('');
    setResult(null);
  };

  const varsError = useMemo(() => {
    if (!variables.trim()) return null;
    try {
      const v = JSON.parse(variables) as unknown;
      return v && typeof v === 'object' && !Array.isArray(v) ? null : 'Variables are a JSON object: { "limit": 5 }';
    } catch (e) {
      return e instanceof Error ? e.message : 'Not JSON';
    }
  }, [variables]);

  const run = useCallback(async () => {
    if (!endpoint || running || varsError) return;
    setRunning(true);
    try {
      setResult(await client.run(endpoint, text, variables.trim() ? (JSON.parse(variables) as Record<string, unknown>) : undefined));
    } catch (e) {
      setResult({ status: 0, ms: 0, body: { errors: [{ message: e instanceof Error ? e.message : String(e) }] } });
    } finally {
      setRunning(false);
    }
  }, [client, endpoint, running, text, variables, varsError]);

  // ⌘↵ / Ctrl+↵ runs from anywhere on the screen
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        e.preventDefault();
        void run();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [run]);

  /** Tab indents in the editors instead of leaving them. */
  const onEditorKey = (e: ReactKeyboardEvent<HTMLTextAreaElement>, set: (v: string) => void) => {
    if (e.key !== 'Tab' || e.metaKey || e.ctrlKey || e.altKey) return;
    e.preventDefault();
    const t = e.currentTarget;
    const { selectionStart: s, selectionEnd: end, value } = t;
    const next = `${value.slice(0, s)}  ${value.slice(end)}`;
    set(next);
    requestAnimationFrame(() => t.setSelectionRange(s + 2, s + 2));
  };

  /** Puts a field at the caret (the Schema panel). */
  const insert = (snippet: string) => {
    const t = editor.current;
    if (!t) return setText((v) => `${v}${snippet}`);
    const { text: next, caret } = insertField(t.value, t.selectionStart, t.selectionEnd, snippet);
    setText(next);
    requestAnimationFrame(() => {
      t.focus();
      t.setSelectionRange(caret, caret);
    });
  };

  const dirty = current ? text.trim() !== current.query.trim() : false;
  const copyAddress = (path: string) =>
    navigator.clipboard?.writeText(`${window.location.origin}${path}`).then(
      () => ToastQueue.positive('Address copied', { timeout: 2000 }),
      () => ToastQueue.negative('The browser did not allow copying', { timeout: 4000 }),
    );

  if (error) return <ErrorState title="Could not read the GraphQL endpoints">{error}</ErrorState>;

  return (
    <div className="studio-sites studio-gql">
      <ToolBar
        url={GRAPHIQL_URL}
        extra={endpoint ? [{ path: `gql:${endpoint.conf ?? 'global'}`, title: current ? `${endpoint.title} · ${current.name}` : endpoint.title }] : []}
        onGo={() => {}}
        end={
          <>
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet onPress={() => setText(prettify(text))}>
                <TextAlignLeft />
                <Text>Prettify</Text>
              </ActionButton>
              <Tooltip>One field per line</Tooltip>
            </TooltipTrigger>
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet onPress={() => setSaving(true)} isDisabled={!endpoint?.conf}>
                <SaveFloppy />
                <Text>{current ? (dirty ? 'Save query' : 'Save as…') : 'Save as persisted query'}</Text>
              </ActionButton>
              <Tooltip>{endpoint?.conf ? `Stored in /conf/${endpoint.conf}, run with GET at /graphql/execute.json` : 'Persisted queries belong to a configuration: pick its endpoint'}</Tooltip>
            </TooltipTrigger>
            <ToggleButton isQuiet isSelected={schemaOpen} onChange={setSchemaOpen}>
              <Data />
              <Text>Schema</Text>
            </ToggleButton>
          </>
        }
        primary={
          <Button variant="accent" onPress={() => void run()} isPending={running} isDisabled={!endpoint || !!varsError}>
            <Play />
            <Text>Run</Text>
          </Button>
        }
      >
        <Picker aria-label="Endpoint" selectedKey={key} onSelectionChange={(k) => k != null && (setKey(String(k)), fresh())} isDisabled={!endpoints} width="size-3000" minWidth="size-1600" flexShrink={1}>
          {(endpoints ?? []).map((e) => (
            <Item key={e.conf ?? 'global'} textValue={e.title}>
              {e.title}
            </Item>
          ))}
        </Picker>
      </ToolBar>

      {!endpoints ? (
        <LoadingState />
      ) : (
        <main className={`studio-gql__body${schemaOpen ? ' has-schema' : ''}`}>
          <nav className="studio-gql__queries" aria-label="Persisted queries">
            <header>
              <h2>Persisted queries</h2>
              <TooltipTrigger delay={400}>
                <ActionButton isQuiet aria-label="New query" onPress={fresh}>
                  <Add />
                </ActionButton>
                <Tooltip>New query</Tooltip>
              </TooltipTrigger>
            </header>
            {!endpoint?.conf ? (
              <p className="studio-gql__muted">The global endpoint has none: persisted queries belong to a configuration.</p>
            ) : persisted.length === 0 ? (
              <p className="studio-gql__muted">None in /conf/{endpoint.conf} yet. Write a query and save it.</p>
            ) : (
              <ul>
                {persisted.map((q) => (
                  <li key={q.path} className={current?.path === q.path ? 'is-current' : undefined}>
                    <button type="button" onClick={() => open(q)} aria-current={current?.path === q.path ? 'true' : undefined}>
                      <span>{q.name}</span>
                      {q.lastModified && <small>{new Date(q.lastModified).toLocaleDateString()}</small>}
                    </button>
                    <TooltipTrigger delay={400}>
                      <ActionButton isQuiet aria-label={`Delete ${q.name}`} onPress={() => setRemoving(q)}>
                        <Delete />
                      </ActionButton>
                      <Tooltip>Delete {q.name}</Tooltip>
                    </TooltipTrigger>
                  </li>
                ))}
              </ul>
            )}
          </nav>

          <section className="studio-gql__editors" aria-label="Query">
            <label className="studio-gql__label" htmlFor="studio-gql-query">
              Query{current ? ` · ${current.name}${dirty ? ' (changed)' : ''}` : ''}
            </label>
            <textarea
              id="studio-gql-query"
              ref={editor}
              className="studio-gql__code"
              spellCheck={false}
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => onEditorKey(e, setText)}
            />
            <label className="studio-gql__label" htmlFor="studio-gql-vars">
              Query variables
            </label>
            <textarea
              id="studio-gql-vars"
              className={`studio-gql__code studio-gql__vars${varsError ? ' is-invalid' : ''}`}
              spellCheck={false}
              placeholder='{ "limit": 5 }'
              value={variables}
              onChange={(e) => setVariables(e.target.value)}
              onKeyDown={(e) => onEditorKey(e, setVariables)}
              aria-invalid={!!varsError}
            />
            {varsError && (
              <p className="studio-gql__error" role="alert">
                {varsError}
              </p>
            )}
          </section>

          <section className="studio-gql__result" aria-label="Result">
            <header>
              <h2>Result</h2>
              {result && (
                <span className={`studio-gql__status${result.status >= 200 && result.status < 300 && !(result.body as { errors?: unknown })?.errors ? '' : ' is-error'}`}>
                  {result.status ? `HTTP ${result.status}` : 'Not sent'} · {result.ms} ms
                </span>
              )}
              {current && endpoint?.conf && (
                <TooltipTrigger delay={400}>
                  <ActionButton isQuiet onPress={() => void copyAddress(executeUrl(current))}>
                    <Link />
                    <Text>Copy GET address</Text>
                  </ActionButton>
                  <Tooltip>{executeUrl(current)}</Tooltip>
                </TooltipTrigger>
              )}
              {result && (
                <TooltipTrigger delay={400}>
                  <ActionButton isQuiet aria-label="Copy the result" onPress={() => void navigator.clipboard?.writeText(JSON.stringify(result.body, null, 2))}>
                    <Copy />
                  </ActionButton>
                  <Tooltip>Copy the result</Tooltip>
                </TooltipTrigger>
              )}
            </header>
            {result ? (
              <pre className="studio-gql__json">{typeof result.body === 'string' ? result.body : JSON.stringify(result.body, null, 2)}</pre>
            ) : (
              <p className="studio-gql__muted">Run the query to see what the endpoint answers.</p>
            )}
          </section>

          {schemaOpen && (
            <InspectorShell kind="Schema" subject={endpoint?.title ?? ''} path={endpoint?.url} onClose={() => setSchemaOpen(false)} className="studio-gql__schema" label="Schema">
              {schemaError ? <p className="studio-gql__error">{schemaError}</p> : !schema ? <LoadingState /> : <SchemaTree schema={schema} onInsert={insert} />}
            </InspectorShell>
          )}
        </main>
      )}

      <DialogContainer onDismiss={() => setSaving(false)}>
        {saving && endpoint?.conf && (
          <SaveDialog
            conf={endpoint.conf}
            initial={current?.name ?? ''}
            existing={persisted.map((p) => p.name)}
            onCancel={() => setSaving(false)}
            onSave={async (name) => {
              await client.persist(endpoint.conf!, name, text, persisted.some((p) => p.name === name));
              setSaving(false);
              ToastQueue.positive(`Query ${name} saved`, { timeout: 3000 });
              const list = await client.persisted(endpoint.conf!);
              setPersisted(list);
              setCurrent(list.find((p) => p.name === name) ?? null);
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title="Delete persisted query"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              client.deletePersisted(removing).then(
                () => {
                  ToastQueue.positive(`Query ${removing.name} deleted`, { timeout: 3000 });
                  if (current?.path === removing.path) setCurrent(null);
                  loadPersisted();
                },
                (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
              )
            }
          >
            {`You are going to delete ${removing.name}. Applications that call ${executeUrl(removing)} get an error from now on.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

function SaveDialog({ conf, initial, existing, onCancel, onSave }: { conf: string; initial: string; existing: string[]; onCancel(): void; onSave(name: string): Promise<void> }) {
  const [name, setName] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const valid = /^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(name);
  const replaces = existing.includes(name);
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await onSave(name);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog
      title="Save persisted query"
      subject={`In /conf/${conf}; applications run it with GET, which a cache can keep`}
      primaryLabel={replaces ? 'Replace' : 'Save'}
      onCancel={onCancel}
      onPrimary={() => void save()}
      primaryDisabled={!valid}
      busy={busy}
    >
      <div className="studio-gql-console__form">
        <TextField
          label="Name"
          value={name}
          onChange={setName}
          width="100%"
          autoFocus
          validationState={name && !valid ? 'invalid' : undefined}
          errorMessage="Letters, digits, - and _"
          description={valid ? `GET /graphql/execute.json/${conf}/${name}${replaces ? ' (replaces the stored one)' : ''}` : undefined}
        />
        {error && (
          <p className="studio-gql-console__error" role="alert">
            {error}
          </p>
        )}
      </div>
    </StudioDialog>
  );
}

/** How deep in braces a position is; comments (# to the end of the line) and strings do not count. */
function depthAt(value: string, pos: number): number {
  let depth = 0;
  let inString = false;
  for (let i = 0; i < pos && i < value.length; i++) {
    const c = value[i];
    if (inString) {
      if (c === '\\') i++;
      else if (c === '"') inString = false;
    } else if (c === '"') inString = true;
    else if (c === '#') {
      const nl = value.indexOf('\n', i);
      if (nl < 0 || nl >= pos) break;
      i = nl;
    } else if (c === '{') depth++;
    else if (c === '}') depth = Math.max(0, depth - 1);
  }
  return depth;
}

/**
 * A field from the Schema panel goes where it makes a query: at the caret when that is inside the braces of the query,
 * otherwise inside the query's own braces (before the last one), or in braces of its own when there are none. Before,
 * a click with the caret at the start put the field outside the query, which then did not parse.
 */
export function insertField(value: string, start: number, end: number, snippet: string): { text: string; caret: number } {
  if (depthAt(value, start) > 0) {
    return { text: `${value.slice(0, start)}${snippet}${value.slice(end)}`, caret: start + snippet.length };
  }
  // the last closing brace that is not in a comment
  let close = -1;
  for (let i = value.length - 1; i >= 0; i--) {
    if (value[i] !== '}') continue;
    const lineStart = value.lastIndexOf('\n', i) + 1;
    if (!value.slice(lineStart, i).includes('#')) {
      close = i;
      break;
    }
  }
  const lines = snippet
    .replace(/\n+$/, '')
    .split('\n')
    .map((l) => (l ? `  ${l}` : l))
    .join('\n');
  if (close < 0) {
    const sep = value.trim() ? `${value.replace(/\s+$/, '')}\n` : '';
    const text = `${sep}{\n${lines}\n}\n`;
    return { text, caret: text.length - 3 };
  }
  const before = value.slice(0, close).replace(/[ \t]+$/, '');
  const head = before.endsWith('\n') || before === '' ? before : `${before}\n`;
  const text = `${head}${lines}\n${value.slice(close)}`;
  return { text, caret: head.length + lines.length };
}

/** The queries the schema offers; a type opens to its fields; a click adds the name at the caret. */
function SchemaTree({ schema, onInsert }: { schema: GqlSchema; onInsert(snippet: string): void }) {
  const [filter, setFilter] = useState('');
  const [open, setOpen] = useState<Set<string>>(new Set());
  const f = filter.trim().toLowerCase();
  const queries = schema.queries.filter((q) => !f || q.name.toLowerCase().includes(f));
  const toggle = (id: string) =>
    setOpen((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  const row = (field: GqlField, id: string, depth: number) => {
    const fields = schema.types[baseType(field.type)];
    const expanded = open.has(id);
    return (
      <li key={id}>
        <div className="studio-gql__field" style={{ paddingLeft: 4 + depth * 14 }}>
          {fields ? (
            <button type="button" className="studio-gql__twisty" aria-label={expanded ? `Close ${field.name}` : `Open ${field.name}`} aria-expanded={expanded} onClick={() => toggle(id)}>
              {expanded ? <ChevronDown size="XS" /> : <ChevronRight size="XS" />}
            </button>
          ) : (
            <span className="studio-gql__twisty" />
          )}
          <button
            type="button"
            className="studio-gql__name"
            title={field.description ?? `Add ${field.name}`}
            onClick={() => onInsert(fields ? `${field.name} {\n  \n}\n` : `${field.name}\n`)}
          >
            {field.name}
            {field.args.length > 0 && <span className="studio-gql__args">({field.args.map((a) => a.name).join(', ')})</span>}
          </button>
          <span className="studio-gql__type">{field.type}</span>
        </div>
        {expanded && fields && depth < 4 && <ul>{fields.map((c) => row(c, `${id}.${c.name}`, depth + 1))}</ul>}
      </li>
    );
  };
  return (
    <div className="studio-gql__schematree">
      <SearchField aria-label="Filter the queries" placeholder="Filter" value={filter} onChange={setFilter} width="100%" />
      <ul>{queries.map((q) => row(q, q.name, 0))}</ul>
      <p className="studio-gql__muted">
        <Brackets size="XS" /> {queries.length} quer{queries.length === 1 ? 'y' : 'ies'}, {Object.keys(schema.types).length} types
      </p>
    </div>
  );
}
