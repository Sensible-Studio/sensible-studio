import { ActionButton, Button, ButtonGroup, Checkbox, Content, DialogContainer, DialogTrigger, Divider, Heading, Item, Picker, Text, TextArea, TextField, ToastQueue, Tooltip, TooltipTrigger, useDialogContainer } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Close from '@spectrum-icons/workflow/Close';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import { useCallback, useEffect, useState, type Key, type ReactNode } from 'react';
import { PathCrumbs } from '../shell/PathCrumbs';
import { ScreenFrame } from '../shell/ScreenFrame';
import { Segmented } from '../shell/Segmented';
import { Link, useNavigate, useScreenTitle } from '../shell/ShellContext';
import '../sites/sites.scss';
import { dueText, pathOfName, PROJECTS_URL, projectUrl, type Fields, type Priority, type Project, type ProjectsClient, type ProjectSummary, type Role, type Task } from './api';
import './projects.scss';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

/**
 * Projects (Home › Projects): a piece of work with a team, a due date, the content it is about and its
 * tasks. The list shows every project as a card (active first, the soonest due first) with how its tasks
 * stand; a project opens to its tasks (tick one to finish it), its team, its content and its details.
 * The tasks assigned to you also show in Home's Inbox.
 */
interface Props {
  client: ProjectsClient;
  /** the project shown, or null for the list */
  name: string | null;
}

export function ProjectsConsole({ client, name }: Props) {
  return name ? <ProjectView key={name} client={client} name={name} /> : <ProjectList client={client} />;
}

function Bar({ crumbs, end }: { crumbs: Array<{ path: string; title: string }>; end?: ReactNode }) {
  const navigate = useNavigate();
  return (
    <ScreenFrame
      identity={<PathCrumbs crumbs={crumbs} onGo={(p) => navigate(p)} />}
      actions={end}
      onClose={() => navigate('/studio/start.html')}
      closeLabel="Close projects"
    />
  );
}

function ProjectList({ client }: { client: ProjectsClient }) {
  useScreenTitle('Projects', 'Projects | Sensible Studio');
  const navigate = useNavigate();
  const [data, setData] = useState<{ projects: ProjectSummary[]; canCreate: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [show, setShow] = useState<'active' | 'all'>('active');
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    client.list().then(
      (d) => {
        setData(d);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  const projects = (data?.projects ?? []).filter((p) => show === 'all' || p.active);
  const inactive = (data?.projects ?? []).filter((p) => !p.active).length;
  return (
    <div className="studio-sites studio-projects">
      <Bar
        crumbs={[{ path: PROJECTS_URL, title: 'Projects' }]}
        end={
          <>
            {inactive > 0 && (
              <Segmented<'active' | 'all'>
                label="Which projects"
                items={[
                  { key: 'active', label: 'Active' },
                  { key: 'all', label: `All (${data?.projects.length ?? 0})` },
                ]}
                value={show}
                onChange={setShow}
              />
            )}
            <Button variant="accent" isDisabled={!data?.canCreate} onPress={() => setCreating(true)}>
              <Add />
              <Text>Create project</Text>
            </Button>
          </>
        }
      />
      <main className="studio-projects__main">
        {error && <ErrorState title="Projects could not be loaded">{error}</ErrorState>}
        {!error && !data && <LoadingState label="Loading projects" />}
        {data && !data.canCreate && <p className="studio-projects__muted">You cannot make a project: projects are kept at /content/projects, which is missing or which your account may not write.</p>}
        {data && projects.length === 0 && data.canCreate && <EmptyState title={`No ${show === 'active' && inactive ? 'active ' : ''}projects yet`}>Create one for a piece of work that has a team, a date and things to do.</EmptyState>}
        <ul className="studio-projects__cards">
          {projects.map((p) => (
            <li key={p.path}>
              <Link to={projectUrl(p.name)} className={`studio-projects__card${p.active ? '' : ' is-inactive'}`}>
                <span className="studio-projects__cardtitle">{p.title}</span>
                {p.description && <span className="studio-projects__carddesc">{p.description}</span>}
                <Progress open={p.open} done={p.done} />
                <span className="studio-projects__cardmeta">
                  {p.open + p.done === 0 ? 'No tasks' : `${p.done} of ${p.open + p.done} done`}
                  {p.overdue > 0 && <span className="studio-projects__late"> · {p.overdue} late</span>}
                  {p.due && <span className={dueText(p.due).includes('late') && p.active ? 'studio-projects__late' : undefined}> · {dueText(p.due)}</span>}
                </span>
                <span className="studio-projects__cardmeta">
                  {p.members.length} {p.members.length === 1 ? 'person' : 'people'}
                  {p.myRole ? ` · you: ${p.myRole}` : ''}
                  {!p.active ? ' · inactive' : ''}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </main>
      <DialogContainer onDismiss={() => setCreating(false)}>
        {creating && (
          <InfoDialog
            heading="Create a project"
            confirm="Create"
            onSave={async (fields) => {
              const p = await client.create(fields);
              navigate(projectUrl(p.name));
            }}
          />
        )}
      </DialogContainer>
    </div>
  );
}

function Progress({ open, done }: { open: number; done: number }) {
  const total = open + done;
  return (
    <span className="studio-projects__progress" role="img" aria-label={total ? `${done} of ${total} tasks done` : 'No tasks'}>
      <span style={{ width: total ? `${Math.round((done / total) * 100)}%` : '0%' }} />
    </span>
  );
}

function InfoDialog({ heading, confirm, project, onSave }: { heading: string; confirm: string; project?: ProjectSummary; onSave(fields: Fields): Promise<void> }) {
  const dialog = useDialogContainer();
  const [title, setTitle] = useState(project?.title ?? '');
  const [description, setDescription] = useState(project?.description ?? '');
  const [due, setDue] = useState(project?.due ?? '');
  const [active, setActive] = useState(project?.active ?? true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const save = () => {
    setBusy(true);
    onSave({ title, description, due, ...(project ? { active: String(active) } : {}) }).then(
      () => dialog.dismiss(),
      (e: unknown) => {
        setBusy(false);
        setError(e instanceof Error ? e.message : String(e));
      },
    );
  };
  return (
    <StudioDialog size="M">
      <Heading>{heading}</Heading>
      <Divider />
      <Content>
        <form
          className="studio-projects__form"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <TextField label="Title" isRequired autoFocus value={title} onChange={setTitle} width="100%" />
          <TextArea label="What it is about" value={description} onChange={setDescription} width="100%" />
          <TextField label="Due" type="date" value={due} onChange={setDue} description="Optional" />
          {project && (
            <Checkbox isSelected={active} onChange={setActive}>
              Active (an inactive project keeps its history and leaves the lists)
            </Checkbox>
          )}
          {error && <p className="studio-projects__error">{error}</p>}
        </form>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" isDisabled={busy || !title.trim()} onPress={save}>
          {confirm}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

const ROLE_LABEL: Record<Role, string> = { owner: 'Owner', editor: 'Editor', observer: 'Observer' };
const PRIORITY_LABEL: Record<Priority, string> = { high: 'High', medium: 'Medium', low: 'Low' };

function ProjectView({ client, name }: { client: ProjectsClient; name: string }) {
  const navigate = useNavigate();
  const path = pathOfName(name);
  const [project, setProject] = useState<Project | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [task, setTask] = useState<Task | 'new' | null>(null);
  useScreenTitle(project ? `Projects · ${project.title}` : 'Projects', `${project?.title ?? 'Projects'} | Sensible Studio`);

  useEffect(() => {
    client.get(path).then(setProject, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, path]);

  const change = (op: string, fields?: Fields, done?: string) =>
    client.change(path, op, fields).then(
      (p) => {
        setProject(p);
        if (done) ToastQueue.positive(done, { timeout: 2500 });
      },
      (e: unknown) => {
        ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
      },
    );

  return (
    <div className="studio-sites studio-projects">
      <Bar
        crumbs={[
          { path: PROJECTS_URL, title: 'Projects' },
          { path: projectUrl(name), title: project?.title ?? name },
        ]}
        end={
          project && (
            <>
              <ActionButton isQuiet onPress={() => setEditing(true)}>
                <Edit />
                <Text>Details</Text>
              </ActionButton>
              <DialogTrigger>
                <ActionButton isQuiet aria-label="Delete the project">
                  <Delete />
                </ActionButton>
                <StudioConfirmDialog
                  title={`Delete ${project.title}?`}
                  variant="destructive"
                  primaryActionLabel="Delete"
                  cancelLabel="Cancel"
                  onPrimaryAction={() => client.remove(path).then(() => navigate(PROJECTS_URL), (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }))}
                >
                  The project, its team and its {project.tasks.length} task{project.tasks.length === 1 ? '' : 's'} go. The content it names stays as it is. To keep the history, make it inactive in Details instead.
                </StudioConfirmDialog>
              </DialogTrigger>
            </>
          )
        }
      />
      <main className="studio-projects__main">
        {error && <ErrorState title="Project could not be loaded">{error}</ErrorState>}
        {!error && !project && <LoadingState label="Loading project" />}
        {project && (
          <div className="studio-projects__detail">
            <header className="studio-projects__head">
              <h1>{project.title}</h1>
              {project.description && <p>{project.description}</p>}
              <p className="studio-projects__muted">
                {project.active ? 'Active' : 'Inactive'}
                {project.due ? ` · ${dueText(project.due)} (${new Date(`${project.due}T00:00:00`).toLocaleDateString(undefined, { dateStyle: 'medium' })})` : ''}
                {project.owner ? ` · started by ${project.owner}` : ''}
              </p>
              <Progress open={project.open} done={project.done} />
            </header>

            <section className="studio-projects__panel studio-projects__tasks" aria-label="Tasks">
              <div className="studio-projects__panelhead">
                <h2>
                  Tasks <span className="studio-projects__muted">{project.open} open{project.done ? ` · ${project.done} done` : ''}</span>
                </h2>
                <Button variant="secondary" onPress={() => setTask('new')}>
                  <Add />
                  <Text>Add a task</Text>
                </Button>
              </div>
              {project.tasks.length === 0 && <p className="studio-projects__muted">Nothing to do yet.</p>}
              <ul className="studio-projects__tasklist">
                {project.tasks.map((t) => (
                  <li key={t.id} className={t.status === 'done' ? 'is-done' : undefined}>
                    <Checkbox aria-label={`${t.title}: done`} isSelected={t.status === 'done'} onChange={(on) => void change('task', { id: t.id, status: on ? 'done' : 'open' })} />
                    <button type="button" className="studio-projects__tasktitle" onClick={() => setTask(t)} title="Open the task">
                      <span>{t.title}</span>
                      <span className="studio-projects__muted">
                        {[t.assignee ? `for ${t.assignee}` : 'nobody yet', t.status === 'done' ? (t.doneBy ? `done by ${t.doneBy}` : 'done') : dueText(t.due), t.priority !== 'medium' && t.status !== 'done' ? `${PRIORITY_LABEL[t.priority].toLowerCase()} priority` : '']
                          .filter(Boolean)
                          .map((s, i) => (
                            <span key={i} className={s.includes('late') ? 'studio-projects__late' : undefined}>
                              {i > 0 ? ' · ' : ''}
                              {s}
                            </span>
                          ))}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>

            <div className="studio-projects__columns">
              <section className="studio-projects__panel" aria-label="Team">
                <div className="studio-projects__panelhead">
                  <h2>Team</h2>
                </div>
                <ul className="studio-projects__rows">
                  {project.members.map((m) => (
                    <li key={m.user}>
                      <span className="studio-projects__who">{m.user}</span>
                      <Picker aria-label={`Role of ${m.user}`} isQuiet width="size-1600" selectedKey={m.role} onSelectionChange={(k: Key | null) => k && void change('member', { user: m.user, role: String(k) })}>
                        {(Object.keys(ROLE_LABEL) as Role[]).map((r) => (
                          <Item key={r}>{ROLE_LABEL[r]}</Item>
                        ))}
                      </Picker>
                      <TooltipTrigger delay={400}>
                        <ActionButton isQuiet aria-label={`Remove ${m.user} from the team`} onPress={() => void change('removeMember', { user: m.user })}>
                          <Close size="S" />
                        </ActionButton>
                        <Tooltip>Remove from the team</Tooltip>
                      </TooltipTrigger>
                    </li>
                  ))}
                </ul>
                <AddRow label="Add a person" placeholder="User or group id" button="Add" onAdd={(user) => change('member', { user, role: 'editor' })} />
              </section>

              <section className="studio-projects__panel" aria-label="Content">
                <div className="studio-projects__panelhead">
                  <h2>Content</h2>
                </div>
                {project.links.length === 0 && <p className="studio-projects__muted">The pages and media this project is about.</p>}
                <ul className="studio-projects__rows">
                  {project.links.map((l) => (
                    <li key={l.path}>
                      <span className="studio-projects__link">
                        {l.exists ? <Link to={l.kind === 'media' ? `/assets.html${l.path}` : `/sites.html${l.path}`}>{l.title}</Link> : <span>{l.title} (gone)</span>}
                        <code>{l.path}</code>
                      </span>
                      <TooltipTrigger delay={400}>
                        <ActionButton isQuiet aria-label={`Remove ${l.title} from the project`} onPress={() => void change('unlink', { target: l.path })}>
                          <Close size="S" />
                        </ActionButton>
                        <Tooltip>Remove from the project (the content stays)</Tooltip>
                      </TooltipTrigger>
                    </li>
                  ))}
                </ul>
                <AddRow label="Add content" placeholder="/content/…" button="Add" onAdd={(target) => change('link', { target })} />
              </section>
            </div>
          </div>
        )}
      </main>
      <DialogContainer onDismiss={() => setEditing(false)}>
        {editing && project && (
          <InfoDialog
            heading="Project details"
            confirm="Save"
            project={project}
            onSave={async (fields) => {
              setProject(await client.change(path, 'save', fields));
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setTask(null)}>
        {task && project && (
          <TaskDialog
            task={task === 'new' ? null : task}
            members={project.members.map((m) => m.user)}
            onSave={async (fields) => {
              setProject(await client.change(path, 'task', fields));
            }}
            onRemove={task === 'new' ? undefined : async () => setProject(await client.change(path, 'removeTask', { id: task.id }))}
          />
        )}
      </DialogContainer>
    </div>
  );
}

function AddRow({ label, placeholder, button, onAdd }: { label: string; placeholder: string; button: string; onAdd(value: string): Promise<unknown> }) {
  const [value, setValue] = useState('');
  const add = () => {
    if (!value.trim()) return;
    void onAdd(value.trim()).then(() => setValue(''));
  };
  return (
    <form
      className="studio-projects__add"
      onSubmit={(e) => {
        e.preventDefault();
        add();
      }}
    >
      <TextField aria-label={label} placeholder={placeholder} flex value={value} onChange={setValue} />
      <Button variant="secondary" isDisabled={!value.trim()} onPress={add}>
        {button}
      </Button>
    </form>
  );
}

function TaskDialog({ task, members, onSave, onRemove }: { task: Task | null; members: string[]; onSave(fields: Fields): Promise<void>; onRemove?(): Promise<void> }) {
  const dialog = useDialogContainer();
  const [title, setTitle] = useState(task?.title ?? '');
  const [description, setDescription] = useState(task?.description ?? '');
  const [assignee, setAssignee] = useState(task?.assignee ?? '');
  const [due, setDue] = useState(task?.due ?? '');
  const [priority, setPriority] = useState<Priority>(task?.priority ?? 'medium');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = (work: Promise<void>) => {
    setBusy(true);
    work.then(
      () => dialog.dismiss(),
      (e: unknown) => {
        setBusy(false);
        setError(e instanceof Error ? e.message : String(e));
      },
    );
  };
  const save = () => run(onSave({ ...(task ? { id: task.id } : {}), title, description, assignee, due, priority }));
  return (
    <StudioDialog size="M">
      <Heading>{task ? 'Task' : 'Add a task'}</Heading>
      <Divider />
      <Content>
        <form
          className="studio-projects__form"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <TextField label="What is to be done" isRequired autoFocus value={title} onChange={setTitle} width="100%" />
          <TextArea label="Notes" value={description} onChange={setDescription} width="100%" />
          <Picker label="For" selectedKey={assignee || '-'} onSelectionChange={(k: Key | null) => setAssignee(k === '-' || k === null ? '' : String(k))}>
            {[<Item key="-">Nobody yet</Item>, ...[...new Set([...members, ...(assignee ? [assignee] : [])])].map((m) => <Item key={m}>{m}</Item>)]}
          </Picker>
          <TextField label="Due" type="date" value={due} onChange={setDue} />
          <Picker label="Priority" selectedKey={priority} onSelectionChange={(k: Key | null) => k && setPriority(String(k) as Priority)}>
            {(Object.keys(PRIORITY_LABEL) as Priority[]).map((p) => (
              <Item key={p}>{PRIORITY_LABEL[p]}</Item>
            ))}
          </Picker>
          {error && <p className="studio-projects__error">{error}</p>}
        </form>
      </Content>
      <ButtonGroup>
        {onRemove && (
          <Button variant="negative" isDisabled={busy} onPress={() => run(onRemove())}>
            Remove
          </Button>
        )}
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" isDisabled={busy || !title.trim()} onPress={save}>
          {task ? 'Save' : 'Add'}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
