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
  Item,
  Picker,
  ProgressCircle,
  Text,
  TextField,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { formatWhen } from '../msm/LiveCopyStatus';
import { ProductBar } from '../shell/ProductBar';
import type { DeliveryRecord, QueuedRequest, ReplicationAgent, ReplicationClient } from './api';
import { Segmented } from '../shell/Segmented';
import { LiveCaches } from './LiveCaches';
import { Unpublished } from './Unpublished';
import './replication.scss';
import { useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { KeyValueList, KeyValueRow, SettingsCard, SettingsGrid } from '../shell/Settings';
import { StudioDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: ReplicationClient;
  studio: StudioClient;
}

/**
 * The Replication console (`/replication.html`), AEM's Agents on author: each agent with its state,
 * target, queue and last delivery; Test, Enable/Disable, Edit, Clear queue, Retry now, Fetch now; the
 * queue or history of the selected agent; and, under them, the pages with unpublished changes.
 */
export function ReplicationConsole({ client, studio }: Props) {
  const tab = useTab();
  const [agents, setAgents] = useState<ReplicationAgent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [queue, setQueue] = useState<QueuedRequest[] | null>(null);
  const [history, setHistory] = useState<DeliveryRecord[] | null>(null);
  const [pane, setPane] = useState<'queue' | 'history'>('queue');
  const [editing, setEditing] = useState<ReplicationAgent | 'new' | null>(null);
  const [user, setUser] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    studio.currentUser().then(setUser, () => setUser(null));
  }, [studio]);

  const load = useCallback(() => {
    client.agents().then(
      (a) => {
        setAgents(a);
        setError(null);
        setSelected((s) => s ?? a[0]?.id ?? null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);

  useEffect(load, [load, tick]);
  useEffect(() => {
    const t = window.setInterval(() => setTick((n) => n + 1), 10000);
    return () => window.clearInterval(t);
  }, []);
  useEffect(() => {
    if (!selected) return;
    let live = true;
    client.queue(selected).then((q) => live && setQueue(q), () => live && setQueue([]));
    client.history(selected).then((h) => live && setHistory(h), () => live && setHistory([]));
    return () => {
      live = false;
    };
  }, [client, selected, tick, agents]);

  const act = async (work: () => Promise<ReplicationAgent[] | unknown>, done?: string) => {
    try {
      const r = await work();
      if (Array.isArray(r)) setAgents(r as ReplicationAgent[]);
      else load();
      if (done) ToastQueue.positive(done, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const test = async (a: ReplicationAgent) => {
    try {
      const r = await client.test(a.id);
      if (r.ok) ToastQueue.positive(`${a.title}: ${r.message}`, { timeout: 4000 });
      else ToastQueue.negative(`${a.title}: ${r.message}`, { timeout: 8000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const current = agents?.find((a) => a.id === selected) ?? null;

  return (
    <div className="studio-sites studio-replication">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/replication.html"
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={() => setTick((n) => n + 1)}>
              <Refresh />
            </ActionButton>
            <Tooltip>Refresh</Tooltip>
          </TooltipTrigger>
        }
        primary={
          <Button variant="accent" onPress={() => setEditing('new')}>
            <Add />
            <Text>New Agent</Text>
          </Button>
        }
      />
      <main className="studio-sites__content studio-replication__main">
        {error && <ErrorState title="Could not load publishing agents">{error}</ErrorState>}
        {!agents && !error && <LoadingState label="Loading agents" />}
        {agents && (
          <div className="studio-replication__layout">
            <SettingsGrid>
              {agents.length === 0 && <EmptyState title="No publishing agents">Create one that points at a publish instance's /bin/receive.</EmptyState>}
              {agents.map((a) => (
                <SettingsCard
                  key={a.id}
                  title={a.title}
                  description={a.description}
                  className={`studio-replication__agent${a.id === selected ? ' is-selected' : ''}`}
                  onClick={() => setSelected(a.id)}
                  status={<>{a.id}{a.tier === 'preview' ? ' · Preview tier' : ''} · {!a.enabled ? 'Disabled' : a.blocked ? 'Blocked' : a.queue ? 'Delivering' : 'Idle'}</>}
                  actions={<div className="studio-replication__actions" onClick={(e) => e.stopPropagation()}>
                    <Button variant="secondary" onPress={() => void test(a)}>Test Connection</Button>
                    <Button variant="secondary" onPress={() => void act(() => client.saveAgent({ id: a.id, enabled: !a.enabled }), a.enabled ? 'Agent disabled' : 'Agent enabled')}>{a.enabled ? 'Disable' : 'Enable'}</Button>
                    <Button variant="secondary" onPress={() => setEditing(a)}><Edit /><Text>Edit agent</Text></Button>
                    {a.serializationType === 'reverse' && <Button variant="secondary" onPress={() => void act(async () => {
                      const r = await client.poll(a.id);
                      if (!r.ok) throw new Error(r.message ?? 'Fetch failed');
                      ToastQueue.positive(`${r.installed ?? 0} item${r.installed === 1 ? '' : 's'} fetched${r.failed ? `, ${r.failed} failed` : ''}`, { timeout: 4000 });
                      return r.agents;
                    }, '')}>Fetch now</Button>}
                    {a.queue > 0 && a.serializationType !== 'reverse' && <><Button variant="secondary" onPress={() => void act(() => client.retry(a.id), 'Retrying now')}>Retry now</Button><Button variant="negative" onPress={() => void act(() => client.clearQueue(a.id), 'Queue cleared')}>Clear queue</Button></>}
                    <Button variant="negative" onPress={() => void act(() => client.removeAgent(a.id), 'Agent removed')}><Delete /><Text>Remove agent</Text></Button>
                  </div>}
                >
                  <KeyValueList>
                    <KeyValueRow label="Target">
                      <code>{a.transportUri ?? '—'}</code>
                      {a.transportUser && <span className="studio-replication__muted"> as {a.transportUser}</span>}
                    </KeyValueRow>
                    <KeyValueRow label="State">{!a.enabled ? 'Disabled' : !a.valid ? 'Invalid: no transport URI' : a.blocked ? `Blocked: ${a.lastError ?? 'delivery failed'} (retrying)` : a.queue ? `Delivering, ${a.queue} queued` : 'Idle'}</KeyValueRow>
                    <KeyValueRow label={a.serializationType === 'reverse' ? 'Outbox' : 'Queue'}>{a.serializationType === 'reverse' ? (a.outboxWaiting ? `${a.outboxWaiting} waiting` : 'empty') : (a.queue ? `${a.queue} requests` : 'empty')}</KeyValueRow>
                    <KeyValueRow label={a.serializationType === 'reverse' ? 'Last fetch' : 'Last delivery'}>
                      {a.lastAt ? (
                        <>
                          {a.lastAction} <code>{a.lastPath}</code> {formatWhen(a.lastAt)}
                          {a.lastError ? <span className="studio-replication__error"> · failed: {a.lastError}</span> : <span className="studio-replication__ok"> · ok</span>}
                        </>
                      ) : (
                        'none yet'
                      )}
                    </KeyValueRow>
                  </KeyValueList>
                </SettingsCard>
              ))}
            </SettingsGrid>
            <section className="studio-replication__queue" aria-label={pane === 'queue' ? 'Queue' : 'History'}>
              <div className="studio-replication__panehead">
                <Segmented<'queue' | 'history'>
                  label="Queue or history"
                  items={[
                    { key: 'queue', label: `Queue${queue?.length ? ` (${queue.length})` : ''}` },
                    { key: 'history', label: 'History' },
                  ]}
                  value={pane}
                  onChange={setPane}
                />
                {current && <span className="studio-replication__muted">{current.title}</span>}
              </div>
              {pane === 'history' ? (
                <History records={history} reverse={current?.serializationType === 'reverse'} />
              ) : queue === null ? (
                <ProgressCircle size="S" isIndeterminate aria-label="Loading queue" />
              ) : queue.length === 0 ? (
                <p className="studio-replication__muted">Nothing queued.</p>
              ) : (
                <table className="studio-table">
                  <thead>
                    <tr>
                      <th>Action</th>
                      <th>Path</th>
                      <th>By</th>
                      <th>Requested</th>
                      <th>Attempts</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {queue.map((j) => (
                      <tr key={j.id}>
                        <td>
                          {j.action}
                          {j.deep ? ' (tree)' : ''}
                        </td>
                        <td>
                          <code>{j.path}</code>
                        </td>
                        <td>{j.user}</td>
                        <td>{j.state === 'SCHEDULED' && j.scheduled ? `Scheduled for ${new Date(j.scheduled).toLocaleString()}` : formatWhen(j.requested)}</td>
                        <td>{j.state === 'SCHEDULED' ? '—' : j.retries}</td>
                        <td>
                          {j.state === 'SCHEDULED' && (
                            <ActionButton
                              isQuiet
                              aria-label="Cancel scheduled request"
                              onPress={() =>
                                client.unschedule(j.id).then(
                                  (jobs) => {
                                    setQueue(jobs);
                                    ToastQueue.positive('Scheduled request cancelled', { timeout: 3000 });
                                  },
                                  (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
                                )
                              }
                            >
                              <Delete />
                            </ActionButton>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
            <LiveCaches client={client} tick={tick} />
            <Unpublished client={client} tick={tick} />
          </div>
        )}
      </main>
      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && (
          <AgentForm
            agent={editing === 'new' ? null : editing}
            onSave={async (a) => {
              await act(() => client.saveAgent(a), 'Agent saved');
              setEditing(null);
            }}
            onCancel={() => setEditing(null)}
          />
        )}
      </DialogContainer>
    </div>
  );
}

function AgentForm({ agent, onSave, onCancel }: { agent: ReplicationAgent | null; onSave(a: { id: string; title: string; description: string; enabled: boolean; transportUri: string; transportUser: string; transportPassword?: string; retryDelay: number; serializationType: string; tier: 'publish' | 'preview'; publicUrl: string }): Promise<void>; onCancel(): void }) {
  const [id, setId] = useState(agent?.id ?? '');
  const [title, setTitle] = useState(agent?.title ?? '');
  const [description, setDescription] = useState(agent?.description ?? '');
  const [enabled, setEnabled] = useState(agent?.enabled ?? true);
  const [uri, setUri] = useState(agent?.transportUri ?? 'http://localhost:4503/bin/receive');
  const [userName, setUserName] = useState(agent?.transportUser ?? 'admin');
  const [password, setPassword] = useState('');
  const [retry, setRetry] = useState(String(agent?.retryDelay ?? 60000));
  const [kind, setKind] = useState(agent?.serializationType === 'flush' || agent?.serializationType === 'reverse' || agent?.serializationType === 'webhook' ? agent.serializationType : 'studio');
  const [tier, setTier] = useState<'publish' | 'preview'>(agent?.tier === 'preview' ? 'preview' : 'publish');
  const [publicUrl, setPublicUrl] = useState(agent?.publicUrl ?? '');
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    try {
      await onSave({ id: id.trim(), title: title.trim() || id.trim(), description, enabled, transportUri: uri.trim(), transportUser: userName.trim(), transportPassword: password || undefined, retryDelay: Number(retry) || 60000, serializationType: kind, tier: kind === 'studio' ? tier : 'publish', publicUrl: publicUrl.trim() });
    } finally {
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="M">
      <Heading>{agent ? `Agent: ${agent.title}` : 'New Publishing Agent'}</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <TextField label="Id" value={id} onChange={setId} isDisabled={!!agent} isRequired description="Letters, digits, - and _" />
          <TextField label="Name" value={title} onChange={setTitle} />
          <TextField label="Description" value={description} onChange={setDescription} />
          <Checkbox isSelected={enabled} onChange={setEnabled}>
            Enabled
          </Checkbox>
          <Picker label="Agent type" selectedKey={kind} onSelectionChange={(k) => setKind(String(k))}>
            <Item key="studio">Publish instance (delivers the content)</Item>
            <Item key="flush">Cache flush (tells a cache what was published)</Item>
            <Item key="reverse">Reverse (fetches what visitors created on a publish instance)</Item>
            <Item key="webhook">Webhook (tells a URL what was published)</Item>
          </Picker>
          <TextField
            label="Transport URI"
            value={uri}
            onChange={setUri}
            isRequired
            description={kind === 'flush' ? "The cache's invalidation URL, e.g. http://127.0.0.1:4520/dispatcher/invalidate.cache (headers CQ-Action, CQ-Handle, CQ-Path). For the cache in front of a publish instance, create it on that instance: it is then told once the content has arrived there" : kind === 'reverse' ? "The publish instance's /bin/outbox; fetched every 30 seconds" : kind === 'webhook' ? 'Receives a POST with JSON {event, action, path, agent, time} for every publish and unpublish: a CDN purge, a site rebuild, a search index' : "The publish instance's /bin/receive"}
          />
          {(
            <Picker isHidden={kind !== 'studio'} label="Tier" selectedKey={tier} onSelectionChange={(k) => setTier(k === 'preview' ? 'preview' : 'publish')} description={tier === 'preview' ? 'Gets only what is sent to Preview (Destination: Preview in the Publish dialog); Publish never goes there' : 'Gets what is published'}>
              <Item key="publish">Publish</Item>
              <Item key="preview">Preview</Item>
            </Picker>
          )}
          {(
            <TextField isHidden={kind !== 'studio'} label="Public URL" value={publicUrl} onChange={setPublicUrl} description="Where that instance's visitors see the content, e.g. http://localhost:4505 (empty: the transport URI's address)" />
          )}
          <TextField label="User" value={userName} onChange={setUserName} />
          <TextField label="Password" type="password" value={password} onChange={setPassword} description={agent ? 'Leave empty to keep the stored one' : undefined} />
          <TextField label="Retry delay (ms)" value={retry} onChange={setRetry} />
        </Form>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onCancel} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isPending={busy} isDisabled={!id.trim() || !uri.trim()}>
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/** What an agent delivered (or fetched), newest first, the way Health shows its runs. */
function History({ records, reverse }: { records: DeliveryRecord[] | null; reverse: boolean }) {
  if (records === null) return <ProgressCircle size="S" isIndeterminate aria-label="Loading history" />;
  if (records.length === 0) return <p className="studio-replication__muted">{reverse ? 'Nothing fetched yet.' : 'Nothing delivered yet.'}</p>;
  return (
    <table className="studio-replication__history studio-table">
      <thead>
        <tr>
          <th>When</th>
          <th>Action</th>
          <th>Path</th>
          <th>Result</th>
        </tr>
      </thead>
      <tbody>
        {records.map((r) => (
          <tr key={r.id} className={r.error ? 'is-failed' : undefined}>
            <td title={new Date(r.at).toLocaleString()}>{formatWhen(r.at)}</td>
            <td>{r.action}</td>
            <td>
              <code>{r.path}</code>
            </td>
            <td>{r.error ? <span className="studio-replication__error">failed: {r.error}</span> : <span className="studio-replication__ok">ok</span>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
