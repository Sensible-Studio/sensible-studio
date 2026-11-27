import {
  ActionButton,
  Button,
  ButtonGroup,
  Checkbox,
  ComboBox,
  Content,
  DialogContainer,
  Divider,
  Heading,
  Item,
  Picker,
  Text,
  TextArea,
  ToastContainer,
  ToastQueue,
} from '@adobe/react-spectrum';
import { SelectionActions } from '../shell/SelectionBar';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import History from '@spectrum-icons/workflow/History';
import LinkOut from '@spectrum-icons/workflow/LinkOut';
import Refresh from '@spectrum-icons/workflow/Refresh';
import UsersShare from '@spectrum-icons/workflow/UsersShare';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { security } from '../security/api';
import { ProductBar } from '../shell/ProductBar';
import { formatTime, payloadUrl, stageLabel, type InboxEntry, type WorkflowClient } from './api';
import { HistoryDialog, instanceTitle, PayloadLink } from './shared';
import { inboxChanged } from './InboxBell';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { StudioDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { dueText, projectUrl, type MyTask } from '../projects/api';
import { httpNotifications, mentionParts, notificationUrl, NOTIFICATIONS_CHANGED, type NotificationsClient, type StudioNotification } from '../shell/notifications';

const TOOL = '/workflow/inbox.html';

/**
 * The Inbox (`/workflow/inbox.html`): the active steps assigned to the signed-in user or one of their groups.
 * Complete a step (choosing the next one where the workflow branches), Delegate it to another user or group, open
 * the payload or the workflow's history. Several steps (or all) can be selected and completed at once, along a
 * next step they all offer.
 */
export function InboxConsole({ client, user, notifications = httpNotifications }: { client: WorkflowClient; user: string | null; notifications?: NotificationsClient }) {
  const go = useNavigate();
  const tab = useTab();
  const [items, setItems] = useState<InboxEntry[] | null>(null);
  // the project tasks assigned to the user wait here too, beside the flow steps
  const [tasks, setTasks] = useState<MyTask[]>([]);
  useEffect(() => {
    let live = true;
    fetch('/bin/studio/projects.json?op=mytasks', { credentials: 'same-origin' })
      .then((r) => (r.ok ? (r.json() as Promise<{ tasks?: MyTask[] }>) : { tasks: [] }))
      .then((d) => live && setTasks(Array.isArray(d.tasks) ? d.tasks : []))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, []);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [completing, setCompleting] = useState<InboxEntry | null>(null);
  const [completingMany, setCompletingMany] = useState<InboxEntry[] | null>(null);
  const [delegating, setDelegating] = useState<InboxEntry | null>(null);
  const [history, setHistory] = useState<string | null>(null);

  useScreenTitle('Inbox', 'Inbox | Sensible Studio');

  const load = useCallback(() => {
    client.inbox().then(
      (list) => {
        setItems(list);
        setError(null);
        setSelected((s) => s.filter((id) => list.some((i) => i.id === id)));
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  const selection = (items ?? []).filter((i) => selected.includes(i.id));
  const single = selection.length === 1 ? selection[0] : null;
  const done = (message: string) => {
    ToastQueue.positive(message, { timeout: 3000 });
    inboxChanged();
    setSelected([]);
    window.setTimeout(load, 600);
  };

  return (
    <div className="studio-sites studio-wf">
      {!tab && <ProductBar user={user} />}
      {selection.length > 1 ? (
        <SelectionActions count={selection.length} onClear={() => setSelected([])}>
            <ActionButton isQuiet onPress={() => setCompletingMany(selection)}>
              <Checkmark />
              <Text>{`Complete (${selection.length})`}</Text>
            </ActionButton>
          </SelectionActions>
      ) : single ? (
        <SelectionActions count={1} onClear={() => setSelected([])}>
            <ActionButton isQuiet onPress={() => setCompleting(single)}>
              <Checkmark />
              <Text>Complete</Text>
            </ActionButton>
            <ActionButton isQuiet onPress={() => setDelegating(single)}>
              <UsersShare />
              <Text>Delegate</Text>
            </ActionButton>
            {single.workflow.payload && (
              <ActionButton isQuiet onPress={() => go(payloadUrl(single.workflow.payload!), { newTab: true })}>
                <LinkOut />
                <Text>Open content</Text>
              </ActionButton>
            )}
            <ActionButton isQuiet onPress={() => setHistory(single.workflow.id)}>
              <History />
              <Text>Flow History</Text>
            </ActionButton>
          </SelectionActions>
      ) : (
        <ToolBar
          url={TOOL}
          closable={false}
          end={
            <ActionButton isQuiet onPress={load} aria-label="Refresh">
              <Refresh />
            </ActionButton>
          }
        />
      )}

      <main className="studio-sites__content">
        <Mentions client={notifications} onOpen={(url) => go(url)} />
        {error ? (
          <ErrorState title="Could not load the inbox">{error}</ErrorState>
        ) : !items ? (
          <LoadingState />
        ) : items.length === 0 ? (
          tasks.length === 0 ? <EmptyState title="Inbox clear">Nothing waits for you.</EmptyState> : null
        ) : (
          <div className="studio-listview">
            <table className="studio-table">
              <thead>
                <tr>
                  <th className="studio-listview__check">
                    <Checkbox
                      aria-label="Select all"
                      isSelected={items.length > 0 && selected.length === items.length}
                      isIndeterminate={selected.length > 0 && selected.length < items.length}
                      onChange={(on) => setSelected(on ? items.map((i) => i.id) : [])}
                      isEmphasized
                    />
                  </th>
                  <th>Step</th>
                  <th>Flow</th>
                  <th>Stage</th>
                  <th>Content</th>
                  <th>Assignee</th>
                  <th>Initiator</th>
                  <th>Arrived</th>
                  <th>Due</th>
                </tr>
              </thead>
              <tbody>
                {items.map((i) => {
                  const isSel = selected.includes(i.id);
                  return (
                    <tr key={i.id} className={isSel ? 'is-selected' : undefined}>
                      <td className="studio-listview__check">
                        <Checkbox aria-label={`Select ${i.title ?? i.name}`} isSelected={isSel} onChange={() => setSelected((s) => (isSel ? s.filter((id) => id !== i.id) : [...s, i.id]))} isEmphasized />
                      </td>
                      <td>
                        <button type="button" className="studio-listview__title" onClick={() => setCompleting(i)}>
                          {i.title || i.nodeId}
                        </button>
                      </td>
                      <td className="studio-listview__muted">
                        {instanceTitle(i.workflow)}
                        {i.workflow.comment && <div className="studio-wf__sub">{i.workflow.comment}</div>}
                      </td>
                      <td className="studio-listview__muted">{stageLabel(i.stage ?? i.workflow.stage, i.workflow.stages)}</td>
                      <td>
                        <PayloadLink payload={i.workflow.payload} />
                      </td>
                      <td className="studio-listview__muted">
                        {i.assignee}
                        {i.delegatedFrom && <span className="studio-wf__delegated"> (from {i.delegatedFrom})</span>}
                      </td>
                      <td className="studio-listview__muted">{i.workflow.initiator}</td>
                      <td className="studio-listview__muted">{formatTime(i.startTime)}</td>
                      <td className={i.dueTime && new Date(i.dueTime).getTime() < Date.now() ? 'studio-wf__failure' : 'studio-listview__muted'}>
                        {formatTime(i.dueTime)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {tasks.length > 0 && (
          <section className="studio-listview" aria-label="Project tasks" style={{ marginTop: 16 }}>
            <h2 style={{ margin: 0, padding: '12px 10px 8px', fontSize: 14, fontWeight: 600 }}>Tasks from projects</h2>
            <table className="studio-table">
              <thead>
                <tr>
                  <th>Task</th>
                  <th>Project</th>
                  <th>Due</th>
                  <th>Priority</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((task) => (
                  <tr key={`${task.project}:${task.id}`}>
                    <td>
                      <a
                        href={projectUrl(task.project.split('/').pop() ?? task.project)}
                        onClick={(e) => {
                          e.preventDefault();
                          go(projectUrl(task.project.split('/').pop() ?? task.project));
                        }}
                      >
                        {task.title}
                      </a>
                      {task.description && <div className="studio-replication__muted">{task.description}</div>}
                    </td>
                    <td>{task.projectTitle}</td>
                    <td>{dueText(task.due)}</td>
                    <td>{task.priority}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </main>

      <DialogContainer onDismiss={() => setCompleting(null)}>
        {completing && (
          <CompleteDialog
            entry={completing}
            onClose={() => setCompleting(null)}
            onComplete={async (route, comment) => {
              await client.complete(completing.id, route, comment);
              setCompleting(null);
              done(`${completing.title ?? 'Step'} completed`);
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setCompletingMany(null)}>
        {completingMany && (
          <CompleteManyDialog
            entries={completingMany}
            onClose={() => setCompletingMany(null)}
            onComplete={async (routeName, comment) => {
              const failures: string[] = [];
              for (const e of completingMany) {
                const route = routeName ? e.routes.find((r) => r.name === routeName)?.id : undefined;
                try {
                  await client.complete(e.id, route, comment);
                } catch (err) {
                  failures.push(`${e.title ?? e.name} (${instanceTitle(e.workflow)}): ${err instanceof Error ? err.message : String(err)}`);
                }
              }
              setCompletingMany(null);
              const ok = completingMany.length - failures.length;
              if (failures.length) {
                ToastQueue.negative(`${ok} completed, ${failures.length} not: ${failures.join('; ')}`, { timeout: 10000 });
                inboxChanged();
                setSelected([]);
                window.setTimeout(load, 600);
              } else {
                done(`${ok} steps completed${routeName ? ` · ${routeName}` : ''}`);
              }
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDelegating(null)}>
        {delegating && (
          <DelegateDialog
            entry={delegating}
            onClose={() => setDelegating(null)}
            onDelegate={async (assignee) => {
              await client.delegate(delegating.id, assignee);
              setDelegating(null);
              done(`${delegating.title ?? 'Step'} delegated to ${assignee}`);
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setHistory(null)}>
        {history && <HistoryDialog client={client} id={history} onClose={() => setHistory(null)} />}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function CompleteDialog({ entry, onClose, onComplete }: { entry: InboxEntry; onClose(): void; onComplete(route: string | undefined, comment: string): Promise<void> }) {
  const routes = entry.routes;
  const [route, setRoute] = useState<string | null>(routes.find((r) => r.default)?.id ?? routes[0]?.id ?? null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  // what the last person wrote when they passed the flow on: the reason this step is here
  const said = [...(entry.workflow.history ?? [])].reverse().find((h) => h.id !== entry.id && !!h.comment?.trim()) ?? null;
  const submit = async () => {
    setBusy(true);
    setFailure(null);
    try {
      await onComplete(route ?? undefined, comment.trim());
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="M">
      <Heading>Complete step</Heading>
      <Divider />
      <Content>
        <p className="studio-wf__runmodel">{entry.title}</p>
        <p className="studio-listview__muted studio-wf__nextstep">Flow: {instanceTitle(entry.workflow)}</p>
        {said && (
          <p className="studio-wf__said">
            <strong>{said.completedBy ?? 'Someone'}</strong> wrote at “{said.title}”: {said.comment}
          </p>
        )}
        {routes.length === 1 && (
          <p className="studio-listview__muted studio-wf__nextstep">
            Next step: <strong>{routes[0].name}</strong>
          </p>
        )}
        <div className="studio-wf__form">
          {routes.length > 1 && (
            <Picker label="Next Step" isRequired selectedKey={route} onSelectionChange={(k) => setRoute(k === null ? null : String(k))} items={routes}>
              {(r) => <Item key={r.id}>{r.name}</Item>}
            </Picker>
          )}
          <TextArea label="Comment" value={comment} onChange={setComment} width="100%" />
        </div>
        {failure && (
          <p className="studio-wf__failure" role="alert">
            {failure}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isDisabled={busy || (routes.length > 1 && route === null)}>
          Complete
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/**
 * Completes several steps at once: the next step is chosen among the ones they all offer (by name, e.g. Approve);
 * steps that go on without a choice just complete.
 */
function CompleteManyDialog({
  entries,
  onClose,
  onComplete,
}: {
  entries: InboxEntry[];
  onClose(): void;
  onComplete(routeName: string | undefined, comment: string): Promise<void>;
}) {
  const branching = entries.filter((e) => e.routes.length > 1);
  const common = useMemo(() => {
    if (!branching.length) return [];
    return branching[0].routes.map((r) => r.name).filter((n) => branching.every((e) => e.routes.some((r) => r.name === n)));
  }, [branching]);
  const preferred = branching[0]?.routes.find((r) => r.default)?.name;
  const [route, setRoute] = useState<string | null>(common.includes(preferred ?? '') ? preferred! : (common[0] ?? null));
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const impossible = branching.length > 0 && common.length === 0;
  return (
    <StudioDialog size="M">
      <Heading>{`Complete ${entries.length} steps`}</Heading>
      <Divider />
      <Content>
        <ul aria-label="Work items" style={{ maxHeight: 200, overflowY: "auto", margin: "0 0 12px", paddingLeft: 18 }}>
          {entries.map((e) => (
            <li key={e.id}>
              {e.title} <span className="studio-listview__muted">· {instanceTitle(e.workflow)}</span>
            </li>
          ))}
        </ul>
        <div className="studio-wf__form">
          {impossible ? (
            <p className="studio-wf__failure" role="alert">
              These steps offer different next steps: complete them in groups that share one, or one by one.
            </p>
          ) : (
            common.length > 0 && (
              <Picker label="Next Step" isRequired selectedKey={route} onSelectionChange={(k) => setRoute(k === null ? null : String(k))}>
                {common.map((n) => (
                  <Item key={n}>{n}</Item>
                ))}
              </Picker>
            )
          )}
          <TextArea label="Comment" value={comment} onChange={setComment} width="100%" description="The same comment for every step" />
        </div>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={busy || impossible || (common.length > 0 && route === null)}
          onPress={async () => {
            setBusy(true);
            await onComplete(common.length ? (route ?? undefined) : undefined, comment.trim());
          }}
        >
          {`Complete ${entries.length}`}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

interface Principal {
  id: string;
  label: string;
}

function DelegateDialog({ entry, onClose, onDelegate }: { entry: InboxEntry; onClose(): void; onDelegate(assignee: string): Promise<void> }) {
  const [principals, setPrincipals] = useState<Principal[]>([]);
  const [value, setValue] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  useEffect(() => {
    Promise.all([security.groups(''), security.users('', false)]).then(
      ([groups, users]) =>
        setPrincipals([
          ...groups.map((g) => ({ id: g.id, label: `${g.name} (group)` })),
          ...users.filter((u) => !u.disabled).map((u) => ({ id: u.id, label: u.name === u.id ? u.id : `${u.name} (${u.id})` })),
        ]),
      () => setPrincipals([]),
    );
  }, []);
  const filtered = useMemo(
    () => principals.filter((p) => p.id !== entry.assignee && (!input || p.label.toLowerCase().includes(input.toLowerCase()))),
    [principals, input, entry.assignee],
  );
  const submit = async () => {
    if (value === null) return;
    setBusy(true);
    setFailure(null);
    try {
      await onDelegate(value);
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>Delegate</Heading>
      <Divider />
      <Content>
        <p className="studio-listview__muted">
          {entry.title} is assigned to <strong>{entry.assignee}</strong>.
        </p>
        <ComboBox
          label="Delegate to"
          isRequired
          items={filtered}
          selectedKey={value}
          onSelectionChange={(k) => setValue(k === null ? null : String(k))}
          inputValue={input}
          onInputChange={setInput}
          width="100%"
          autoFocus
        >
          {(p) => <Item key={p.id}>{p.label}</Item>}
        </ComboBox>
        {failure && (
          <p className="studio-wf__failure" role="alert">
            {failure}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isDisabled={busy || value === null}>
          Delegate
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/**
 * AEM's notifications in the Inbox: where someone @mentioned the user in a comment on a Media file. Opening one
 * marks it read; × removes it.
 */
export function Mentions({ client, onOpen }: { client: NotificationsClient; onOpen(url: string): void }) {
  const [list, setList] = useState<StudioNotification[] | null>(null);
  const [unread, setUnread] = useState(0);
  const load = useCallback(() => {
    client.list().then(
      (r) => {
        setList(r.notifications);
        setUnread(r.unread);
      },
      () => setList([]),
    );
  }, [client]);
  useEffect(() => {
    load();
    window.addEventListener(NOTIFICATIONS_CHANGED, load);
    return () => window.removeEventListener(NOTIFICATIONS_CHANGED, load);
  }, [load]);
  if (!list || list.length === 0) return null;
  const take = (r: { notifications: StudioNotification[]; unread: number }) => {
    setList(r.notifications);
    setUnread(r.unread);
  };
  const name = (p: string) => p.slice(p.lastIndexOf('/') + 1);
  return (
    <section className="studio-wf__mentions" aria-label="Mentions">
      <header>
        <h2>Mentions{unread ? ` · ${unread} new` : ''}</h2>
        {unread > 0 && (
          <ActionButton isQuiet onPress={() => void client.markRead([]).then(take)}>
            <Checkmark />
            <Text>Mark all read</Text>
          </ActionButton>
        )}
      </header>
      <ul>
        {list.map((n) => (
          <li key={n.id} className={n.read ? undefined : 'is-unread'}>
            <button
              type="button"
              className="studio-wf__mention"
              onClick={() => {
                if (!n.read) void client.markRead([n.id]).then(take);
                onOpen(notificationUrl(n));
              }}
            >
              <strong>{n.from ?? 'Someone'}</strong> mentioned you on <strong>{name(n.path)}</strong>
              <span className="studio-wf__mention-text">
                {mentionParts(n.text).map((p, i) => (p.mention ? <em key={i}>{p.text}</em> : <span key={i}>{p.text}</span>))}
              </span>
              <small>{n.created ? formatTime(n.created) : ''}</small>
            </button>
            <ActionButton isQuiet aria-label={`Remove: ${n.text}`} onPress={() => void client.remove([n.id]).then(take)}>
              ×
            </ActionButton>
          </li>
        ))}
      </ul>
    </section>
  );
}
