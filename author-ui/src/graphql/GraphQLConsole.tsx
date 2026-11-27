import './graphql.scss';
import { ActionButton, Button, DialogContainer, Item, Picker, Text, TextField, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Copy from '@spectrum-icons/workflow/Copy';
import Delete from '@spectrum-icons/workflow/Delete';
import Code from '@spectrum-icons/workflow/Code';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useMemo, useState, type Key } from 'react';
import { ErrorState, LoadingState, EmptyState } from '../shell/FeedbackState';
import { KeyValueList, KeyValueRow, SettingsCard, SettingsGrid } from '../shell/Settings';
import { useNavigate, useScreenTitle } from '../shell/ShellContext';
import { StudioConfirmDialog, StudioDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { VOCAB } from '../shell/vocabulary';
import { nameOfConf, type GqlConf, type GqlEndpoint, type GraphQLClient, type PersistedQuery } from './api';

export const GRAPHQL_URL = '/cfm/graphql.html';
export const GRAPHIQL_URL = '/cfm/graphiql.html';

/** The query editor on an endpoint (and a persisted query of it). */
export const editorUrl = (endpoint: GqlEndpoint | null, query?: string) => {
  const p = new URLSearchParams();
  if (endpoint) p.set('endpoint', endpoint.conf ?? 'global');
  if (query) p.set('query', query);
  const q = p.toString();
  return q ? `${GRAPHIQL_URL}?${q}` : GRAPHIQL_URL;
};

/**
 * AEM's Tools › General › GraphQL: the endpoints records are queried at, one per configuration plus the
 * global one, with their persisted queries. Create adds an endpoint to a configuration that has none.
 */
export function GraphQLConsole({ client }: { client: GraphQLClient }) {
  const navigate = useNavigate();
  const [endpoints, setEndpoints] = useState<GqlEndpoint[] | null>(null);
  const [confs, setConfs] = useState<GqlConf[]>([]);
  const [persisted, setPersisted] = useState<PersistedQuery[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<GqlEndpoint | null>(null);

  useScreenTitle('GraphQL', `GraphQL | ${VOCAB.product}`);

  const load = useCallback(() => {
    setError(null);
    Promise.all([client.endpoints(), client.confs(), client.persisted()]).then(
      ([e, c, p]) => {
        setEndpoints(e);
        setConfs(c);
        setPersisted(p);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  // the global endpoint is /conf/global's: that one is never free
  const free = useMemo(() => confs.filter((c) => !endpoints?.some((e) => (e.conf ?? 'global') === nameOfConf(c.path))), [confs, endpoints]);
  // configurations that have persisted queries and no endpoint: the queries run, and nothing here showed them
  const orphans = useMemo(() => {
    const names = [...new Set(persisted.map((q) => q.conf))];
    return names.filter((n) => n && !endpoints?.some((e) => e.conf === n));
  }, [persisted, endpoints]);
  const createFor = (conf: string, title: string) =>
    client.createEndpoint(conf, title).then(
      () => {
        ToastQueue.positive(`Endpoint ${title} created`, { timeout: 3000 });
        load();
      },
      (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
    );
  const confTitle = (conf: string | null) => (conf ? confs.find((c) => nameOfConf(c.path) === conf)?.title ?? conf : 'All configurations');
  const copy = (text: string) =>
    navigator.clipboard?.writeText(`${window.location.origin}${text}`).then(
      () => ToastQueue.positive('Address copied', { timeout: 2000 }),
      () => ToastQueue.negative('The browser did not allow copying', { timeout: 4000 }),
    );

  return (
    <div className="studio-sites studio-gql-console">
      <ToolBar
        url={GRAPHQL_URL}
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Read the endpoints again</Tooltip>
          </TooltipTrigger>
        }
        primary={
          <Button variant="accent" onPress={() => setCreating(true)} isDisabled={!endpoints || free.length === 0}>
            <Add />
            <Text>Create endpoint</Text>
          </Button>
        }
      />
      <main className="studio-sites__content studio-gql-console__body">
        {error ? (
          <ErrorState title="Could not read the GraphQL endpoints">{error}</ErrorState>
        ) : !endpoints ? (
          <LoadingState />
        ) : endpoints.length === 0 ? (
          <EmptyState title="No endpoint">Create one for a configuration whose content fragment models should be queried.</EmptyState>
        ) : (
          <SettingsGrid>
            {endpoints.map((e) => {
              const queries = e.conf ? persisted.filter((q) => q.conf === e.conf) : [];
              return (
                <SettingsCard
                  key={e.url}
                  title={e.title}
                  description={e.conf ? `The content fragments of ${confTitle(e.conf)}` : 'The content fragments of every configuration'}
                  actions={
                    <>
                      <Button variant="secondary" style="outline" onPress={() => navigate(editorUrl(e))}>
                        <Code />
                        <Text>Open in Query Editor</Text>
                      </Button>
                      {e.conf && (
                        <ActionButton isQuiet onPress={() => setRemoving(e)}>
                          <Delete />
                          <Text>Delete</Text>
                        </ActionButton>
                      )}
                    </>
                  }
                >
                  <KeyValueList>
                    <KeyValueRow label="Configuration">{e.conf ? `/conf/${e.conf}` : 'global'}</KeyValueRow>
                    <KeyValueRow label="Address">
                      <span className="studio-gql-console__url">
                        <code>{e.url}</code>
                        <ActionButton isQuiet aria-label={`Copy the address of ${e.title}`} onPress={() => void copy(e.url)}>
                          <Copy />
                        </ActionButton>
                      </span>
                    </KeyValueRow>
                    {e.conf && (
                      <KeyValueRow label="Persisted queries">
                        {queries.length === 0 ? (
                          'none'
                        ) : (
                          <span className="studio-gql-console__queries">
                            {queries.map((q) => (
                              <a
                                key={q.path}
                                href={editorUrl(e, q.name)}
                                onClick={(ev) => {
                                  ev.preventDefault();
                                  navigate(editorUrl(e, q.name));
                                }}
                              >
                                {q.name}
                              </a>
                            ))}
                          </span>
                        )}
                      </KeyValueRow>
                    )}
                  </KeyValueList>
                </SettingsCard>
              );
            })}
            {orphans.map((conf) => {
              const queries = persisted.filter((q) => q.conf === conf);
              const title = `${confTitle(conf)} Endpoint`;
              return (
                <SettingsCard
                  key={`orphan:${conf}`}
                  title={confTitle(conf)}
                  description={`No endpoint. Its persisted ${queries.length === 1 ? 'query still runs' : 'queries still run'} at /graphql/execute.json, but cannot be opened or tried here until the configuration has an endpoint.`}
                  actions={
                    <Button variant="secondary" style="outline" onPress={() => void createFor(conf, title)}>
                      <Add />
                      <Text>Create endpoint</Text>
                    </Button>
                  }
                >
                  <KeyValueList>
                    <KeyValueRow label="Configuration">{`/conf/${conf}`}</KeyValueRow>
                    <KeyValueRow label="Persisted queries">
                      <span className="studio-gql-console__queries">
                        {queries.map((q) => (
                          <span key={q.path}>{q.name}</span>
                        ))}
                      </span>
                    </KeyValueRow>
                  </KeyValueList>
                </SettingsCard>
              );
            })}
          </SettingsGrid>
        )}
      </main>

      <DialogContainer onDismiss={() => setCreating(false)}>
        {creating && (
          <CreateEndpointDialog
            confs={free}
            onCancel={() => setCreating(false)}
            onCreate={async (conf, title) => {
              await client.createEndpoint(conf, title);
              setCreating(false);
              ToastQueue.positive(`Endpoint ${title} created`, { timeout: 3000 });
              load();
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title="Delete endpoint"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              client.deleteEndpoint(removing).then(
                () => {
                  ToastQueue.positive(`Endpoint ${removing.title} deleted`, { timeout: 3000 });
                  load();
                },
                (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
              )
            }
          >
            {`You are going to delete ${removing.title}. Applications that query ${removing.url} get an error from now on; the persisted queries of ${removing.conf} stay and still run at /graphql/execute.json.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

function CreateEndpointDialog({ confs, onCancel, onCreate }: { confs: GqlConf[]; onCancel(): void; onCreate(conf: string, title: string): Promise<void> }) {
  const [conf, setConf] = useState<string>(nameOfConf(confs[0]?.path ?? ''));
  const chosen = confs.find((c) => nameOfConf(c.path) === conf);
  const [title, setTitle] = useState(chosen ? `${chosen.title} Endpoint` : '');
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pick = (k: Key) => {
    const name = String(k);
    setConf(name);
    const c = confs.find((x) => nameOfConf(x.path) === name);
    if (!touched && c) setTitle(`${c.title} Endpoint`);
  };
  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      await onCreate(conf, title.trim());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog title="Create endpoint" subject="Queries for the content fragment models of one configuration" primaryLabel="Create" onCancel={onCancel} onPrimary={() => void create()} primaryDisabled={!conf || !title.trim()} busy={busy}>
      <div className="studio-gql-console__form">
        <Picker label="Configuration" selectedKey={conf} onSelectionChange={(k) => k != null && pick(k)} width="100%">
          {confs.map((c) => (
            <Item key={nameOfConf(c.path)} textValue={c.title}>
              {`${c.title} · ${c.models} model${c.models === 1 ? '' : 's'}`}
            </Item>
          ))}
        </Picker>
        <TextField
          label="Title"
          value={title}
          onChange={(v) => {
            setTitle(v);
            setTouched(true);
          }}
          width="100%"
        />
        <p className="studio-gql-console__hint">
          Address: <code>{`/content/cq:graphql/${conf}/endpoint.json`}</code>
        </p>
        {error && (
          <p className="studio-gql-console__error" role="alert">
            {error}
          </p>
        )}
      </div>
    </StudioDialog>
  );
}
