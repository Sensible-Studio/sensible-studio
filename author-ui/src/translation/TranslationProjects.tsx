import {
  ActionButton,
  Button,
  ButtonGroup,
  Cell,
  Checkbox,
  Column,
  Content,
  DialogContainer,
  Divider,
  Flex,
  Heading,
  InlineAlert,
  Item,
  Link,
  MenuTrigger,
  Menu,
  Picker,
  ProgressBar,
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
import { useCallback, useEffect, useRef, useState, type Key } from 'react';
import { languageName } from '../cf/languages';
import { ProductBar } from '../shell/ProductBar';
import { HttpReviewWorkflowClient, PageTranslationError, type PageTranslationClient, type ReviewWorkflowClient, type TranslationProvider } from './pages';
import {
  JOB_STATUS,
  jobStats,
  parsePaths,
  pendingItems,
  type ItemKind,
  type JobStatus,
  type TranslationJob,
  type TranslationProject,
  type TranslationProjectClient,
  type XliffImport,
} from './projects';
import { reviewUrl, stateLight } from './TranslationReview';
import './translation.scss';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { StudioDialog } from '../shell/StudioDialog';

export const projectUrl = (name?: string | null) => `/translation/projects.html${name ? `/${name}` : ''}`;

const lang = (code?: string) => (code ? `${languageName(code) ?? code} (${code})` : '');
const when = (t?: number) => (t ? new Date(t).toLocaleString() : '');
const KIND: Record<ItemKind, string> = {
  page: 'Page',
  'experience-fragment': 'Experience Fragment',
  'content-fragment': 'Content Fragment',
  asset: 'File',
};
const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Translation Projects (Translation → Translation Projects): like AEM's translation projects, a project holds what
 * to translate and one job per language. A job is started here and worked one item at a time (Stop, then Start
 * again resumes); when every item is done it is ready for review, then approved (the copies' texts feed the
 * translation memory) and completed. Update runs it again: only what changed in the sources is translated.
 */
export function TranslationProjects({
  client,
  pages,
  initialName,
  workflow = new HttpReviewWorkflowClient(),
}: {
  client: TranslationProjectClient;
  pages: PageTranslationClient;
  initialName: string | null;
  workflow?: ReviewWorkflowClient;
}) {
  const tab = useTab();
  const [name, setName] = useState<string | null>(initialName);
  const [user, setUser] = useState<string | null>(null);

  useEffect(() => {
    fetch('/system/sling/info.sessionInfo.json', { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : null))
      .then((i: { userID?: string } | null) => setUser(i?.userID ?? null), () => setUser(null));
  }, []);

  const tabRef = useRef(tab);
  tabRef.current = tab;
  const tabName = tab?.place.kind === 'translation-projects' ? tab.place.name : undefined;
  useEffect(() => {
    if (tabName !== undefined && tabName !== name) setName(tabName);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabName]);
  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      const m = window.location.pathname.match(/^\/translation\/projects\.html\/([^/]+)$/);
      setName(m ? decodeURIComponent(m[1]) : null);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const open = useCallback((n: string | null) => {
    if (tabRef.current) tabRef.current.setPlace({ kind: 'translation-projects', name: n });
    else window.history.pushState(null, '', projectUrl(n));
    setName(n);
  }, []);

  return (
    <div className="studio-cfc studio-trp">
      {!tab && <ProductBar user={user} />}
      {name ? (
        <ProjectView client={client} workflow={workflow} name={name} onBack={() => open(null)} />
      ) : (
        <ProjectList client={client} pages={pages} onOpen={open} />
      )}
      <ToastContainer />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ list

export function ProjectList({ client, pages, onOpen }: { client: TranslationProjectClient; pages: PageTranslationClient; onOpen(name: string): void }) {
  const [projects, setProjects] = useState<TranslationProject[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useScreenTitle(`${VOCAB.translations} · Jobs`, `Jobs | ${VOCAB.translations} | ${VOCAB.product}`);
  useEffect(() => {
    client.list().then(setProjects, (e: unknown) => setFailure(message(e)));
  }, [client]);

  return (
    <>
      <ToolBar
        url="/translation/projects.html"
        closable={false}
        primary={<Button variant="accent" onPress={() => setCreating(true)}>
          <Add />
          <Text>Create project</Text>
        </Button>}
      />
      <div className="studio-cfc__content">
        {failure ? (
          <ErrorState title="Translation projects could not be loaded">{failure}</ErrorState>
        ) : !projects ? (
          <LoadingState />
        ) : (
          <TableView
            aria-label="Translation projects"
            density="spacious"
            overflowMode="truncate"
            height="100%"
            onAction={(k: Key) => onOpen(String(k))}
            renderEmptyState={() => <EmptyState title="No translation projects">Create a project to translate pages, content fragments, and files into several languages and follow each language's job.</EmptyState>}
          >
            <TableHeader>
              <Column key="title" isRowHeader width="3fr">
                Project
              </Column>
              <Column key="languages" width="1.4fr">
                Languages
              </Column>
              <Column key="items" width="0.7fr">
                Items
              </Column>
              <Column key="jobs" width="2.6fr">
                Jobs
              </Column>
              <Column key="created" width="1.5fr">
                Created
              </Column>
            </TableHeader>
            <TableBody items={projects}>
              {(p) => (
                <Row key={p.name}>
                  <Cell textValue={p.title}>
                    <span className="studio-trr__title">
                      <span>{p.title}</span>
                      {p.description && <span className="studio-cf__muted">{p.description}</span>}
                      {p.dueDate && (
                        <span className={p.overdue ? 'studio-cf-error' : 'studio-cf__muted'}>{`Due ${new Date(p.dueDate).toLocaleDateString()}${p.overdue ? ' · late' : ''}`}</span>
                      )}
                    </span>
                  </Cell>
                  <Cell>{`${p.sourceLanguage} → ${p.languages.join(', ')}`}</Cell>
                  <Cell>{String(p.itemCount)}</Cell>
                  <Cell textValue={p.jobs.map((j) => `${j.language} ${JOB_STATUS[j.status].text}`).join(', ')}>
                    <span className="studio-trp__jobs">
                      {p.jobs.map((j) => (
                        <StatusLight key={j.language} variant={JOB_STATUS[j.status].variant}>
                          {`${j.language}: ${JOB_STATUS[j.status].text}${j.status === 'IN_PROGRESS' ? ` ${j.done}/${j.total}` : ''}`}
                        </StatusLight>
                      ))}
                    </span>
                  </Cell>
                  <Cell>{`${when(p.created)}${p.createdBy ? ` · ${p.createdBy}` : ''}`}</Cell>
                </Row>
              )}
            </TableBody>
          </TableView>
        )}
      </div>
      <DialogContainer onDismiss={() => setCreating(false)}>
        {creating && (
          <CreateProjectDialog
            client={client}
            pages={pages}
            onClose={() => setCreating(false)}
            onCreated={(p) => {
              setCreating(false);
              onOpen(p.name);
            }}
          />
        )}
      </DialogContainer>
    </>
  );
}

function useProviders(pages: PageTranslationClient, path: string | null) {
  const [providers, setProviders] = useState<TranslationProvider[] | null>(null);
  useEffect(() => {
    if (!path) {
      setProviders(null);
      return;
    }
    let live = true;
    const t = window.setTimeout(() => {
      pages.providers(path).then(
        (p) => live && setProviders(p),
        () => live && setProviders([]),
      );
    }, 300);
    return () => {
      live = false;
      window.clearTimeout(t);
    };
  }, [pages, path]);
  return providers;
}

export function CreateProjectDialog({
  client,
  pages,
  onClose,
  onCreated,
  initialPaths = '',
  initialDeep = true,
  onExisting,
}: {
  client: TranslationProjectClient;
  pages: PageTranslationClient;
  onClose(): void;
  onCreated(p: TranslationProject): void;
  initialPaths?: string;
  initialDeep?: boolean;
  /** Offers adding to an existing project instead (Sites). */
  onExisting?(): void;
}) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [paths, setPaths] = useState(initialPaths);
  const [deep, setDeep] = useState(initialDeep);
  const [references, setReferences] = useState(true);
  const [languages, setLanguages] = useState('');
  const [provider, setProvider] = useState<string | null>(null);
  const [due, setDue] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const list = parsePaths(paths);
  const providers = useProviders(pages, list[0] ?? null);
  const codes = [...new Set(languages.split(/[\s,]+/).map((s) => s.trim().toLowerCase().replace('-', '_')).filter(Boolean))];
  const badCodes = codes.filter((c) => !languageName(c));

  useEffect(() => {
    if (providers && !providers.some((p) => p.name === provider)) {
      setProvider((providers.find((x) => x.default && x.available) ?? providers.find((x) => x.available))?.name ?? null);
    }
  }, [providers, provider]);

  const create = async () => {
    setBusy(true);
    setFailure(null);
    try {
      const dueDate = due ? new Date(`${due}T17:00:00`).getTime() : undefined;
      onCreated(
        await client.create({
          title: title.trim(),
          description: description.trim() || undefined,
          paths: list,
          deep,
          includeReferences: references,
          languages: codes,
          provider: provider ?? undefined,
          dueDate: dueDate && !Number.isNaN(dueDate) ? dueDate : undefined,
        }),
      );
    } catch (e) {
      setFailure(message(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="L">
      <Heading>Create translation project</Heading>
      <Divider />
      <Content>
        <Flex direction="column" gap="size-150">
          {onExisting && (
            <Text>
              <Link onPress={onExisting}>Add to an existing project instead</Link>
            </Text>
          )}
          <TextField label="Title" value={title} onChange={setTitle} isRequired autoFocus width="100%" />
          <TextField label="Description" value={description} onChange={setDescription} width="100%" />
          <TextArea
            label="What to translate"
            description="One path per line: pages, experience fragments, content fragments or files, all in the same language"
            value={paths}
            onChange={setPaths}
            isRequired
            width="100%"
            height="size-1600"
          />
          <Flex gap="size-300" wrap>
            <Checkbox isSelected={deep} onChange={setDeep}>
              Include the pages below
            </Checkbox>
            <Checkbox isSelected={references} onChange={setReferences}>
              Include what pages use (experience fragments, content fragments, media)
            </Checkbox>
          </Flex>
          <TextField
            label="Target languages"
            description={codes.length && !badCodes.length ? codes.map((c) => languageName(c)).join(', ') : 'Language codes, e.g. ro, de, fr'}
            value={languages}
            onChange={setLanguages}
            validationState={badCodes.length ? 'invalid' : undefined}
            errorMessage={`Not a language code: ${badCodes.join(', ')}`}
            isRequired
            width="size-4600"
          />
          {providers && providers.length > 0 && (
            <Picker
              label="Translated by"
              items={providers}
              selectedKey={provider}
              onSelectionChange={(k) => setProvider(String(k))}
              disabledKeys={providers.filter((p) => !p.available).map((p) => p.name)}
              width="size-4600"
            >
              {(p) => <Item key={p.name}>{p.available ? p.label : `${p.label} (not set up)`}</Item>}
            </Picker>
          )}
          <TextField label="Due date" type="date" value={due} onChange={setDue} width="size-2400" />
          {failure && (
            <InlineAlert variant="negative">
              <Heading>The project was not created</Heading>
              <Content>{failure}</Content>
            </InlineAlert>
          )}
        </Flex>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void create()} isPending={busy} isDisabled={!title.trim() || !list.length || !codes.length || badCodes.length > 0}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

// ------------------------------------------------------------------------------------------------ one project

export function ProjectView({
  client,
  workflow,
  name,
  onBack,
}: {
  client: TranslationProjectClient;
  workflow: ReviewWorkflowClient;
  name: string;
  onBack(): void;
}) {
  const [project, setProject] = useState<TranslationProject | null>(null);
  useScreenTitle(project ? `${VOCAB.translationJob} · ${project.title}` : null, project ? `${project.title} | ${VOCAB.translations} | ${VOCAB.product}` : null);
  const [failure, setFailure] = useState<string | null>(null);
  const [running, setRunning] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [newLanguage, setNewLanguage] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirmDelete, setConfirmDelete] = useState(false);
  const stop = useRef(false);

  const load = useCallback(() => {
    client.get(name).then(
      (p) => {
        setProject(p);
      },
      (e: unknown) => setFailure(message(e)),
    );
  }, [client, name]);

  useEffect(load, [load]);

  // while a job is worked on the server, follow it
  const onServer = project?.jobs.some((j) => j.background) ?? false;
  useEffect(() => {
    if (!onServer) return;
    const t = window.setInterval(() => {
      client.get(name).then(setProject, () => {});
    }, 3000);
    return () => window.clearInterval(t);
  }, [client, name, onServer]);

  const run = async (job: TranslationJob) => {
    if (!project) return;
    stop.current = false;
    setRunning(job.language);
    let current = project;
    try {
      for (const id of pendingItems(current, job)) {
        if (stop.current) break;
        const outcome = await client.translateItem(name, job.language, id);
        current = outcome.project;
        setProject(current);
        if (outcome.status === 'FAILED') {
          const item = current.items?.find((i) => i.id === id);
          ToastQueue.negative(`${item?.title ?? item?.path ?? id}: ${outcome.error ?? 'not translated'}`, { timeout: 6000 });
        }
      }
      const after = current.jobs.find((j) => j.language === job.language);
      if (after?.status === 'READY_FOR_REVIEW') ToastQueue.positive(`${lang(job.language)}: translated, ready for review`, { timeout: 5000 });
    } catch (e) {
      const code = e instanceof PageTranslationError && e.code ? ` (${e.code})` : '';
      ToastQueue.negative(`${lang(job.language)}: stopped${code}. ${message(e)}`, { timeout: 10000 });
      load();
    } finally {
      setRunning(null);
    }
  };

  const status = async (job: TranslationJob, s: 'APPROVED' | 'COMPLETE' | 'CANCELLED' | 'DRAFT') => {
    try {
      const p = await client.setStatus(name, job.language, s);
      setProject(p);
      const after = p.jobs.find((j) => j.language === job.language);
      if (after?.error) ToastQueue.negative(after.error, { timeout: 10000 });
      else if (s === 'APPROVED') ToastQueue.positive(`${lang(job.language)}: approved; its texts are in the translation memory`, { timeout: 5000 });
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    }
  };

  const background = async (job: TranslationJob, start: boolean) => {
    try {
      setProject(start ? await client.runInBackground(name, job.language) : await client.stopBackground(name, job.language));
      if (start) ToastQueue.info(`${lang(job.language)}: translating on the server; you can close this page`, { timeout: 5000 });
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    }
  };

  const [report, setReport] = useState<(XliffImport & { language: string }) | null>(null);
  const importXliff = async (job: TranslationJob, file: File) => {
    try {
      const r = await client.importXliff(name, job.language, file);
      setProject(r.project);
      setReport({ ...r, language: job.language });
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 10000 });
    }
  };

  const requestReview = async (job: TranslationJob) => {
    const copies = (job.copies ?? []).map((c) => c.path);
    if (!copies.length) return;
    try {
      await workflow.requestReview(copies);
      ToastQueue.positive(`Review requested for ${copies.length} translation(s): the reviewers find them in their Inbox`, { timeout: 5000 });
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
    }
  };

  const change = async (action: () => Promise<TranslationProject>) => {
    try {
      setProject(await action());
      return true;
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 8000 });
      return false;
    }
  };

  if (failure) return <ErrorState title="The project could not be loaded" action={{ label: 'All projects', onPress: onBack }}>{failure}</ErrorState>;
  if (!project) return <LoadingState label="Loading translation project" />;

  const items = project.items ?? [];
  return (
    <>
      <ToolBar
        url="/translation/projects.html"
        extra={[{ path: `project:${name}`, title: project.title }]}
        onGo={() => onBack()}
        onClose={onBack}
        closeLabel="Close translation project"
        end={<Flex gap="size-100" wrap>
          <Button variant="secondary" onPress={() => setAdding(true)} isDisabled={running !== null}>
            <Add />
            <Text>Add content</Text>
          </Button>
          <Button variant="negative" style="outline" onPress={() => setConfirmDelete(true)} isDisabled={running !== null}>
            <Delete />
            <Text>Delete project</Text>
          </Button>
        </Flex>}
      />
      <div className="studio-trp__body">
        <section className="studio-trp__summary" aria-label="Project">
          <h2>{project.title}</h2>
          {project.description && <p>{project.description}</p>}
          <DueDate project={project} onChange={(due) => void change(() => client.setDueDate(name, due))} />
          <p className="studio-cf__muted">
            {`${lang(project.sourceLanguage)} → ${project.languages.map(lang).join(', ')}`}
            {` · ${project.provider ? `Translated by ${project.provider.split('/').pop()}` : 'Default translation connector'}`}
            {project.includeReferences ? ' · with what pages use' : ''}
            {project.createdBy ? ` · created by ${project.createdBy} ${when(project.created)}` : ''}
          </p>
        </section>

        <section className="studio-trp__jobs-grid" aria-label="Jobs">
          {project.jobs.map((job) => (
            <JobCard
              key={job.language}
              project={project}
              job={job}
              running={running === job.language}
              busy={running !== null}
              onStart={() => void run(job)}
              onStop={() => {
                stop.current = true;
              }}
              onStatus={(s) => void status(job, s)}
              onBackground={(start) => void background(job, start)}
              xliffUrl={(version) => client.xliffUrl(name, job.language, version)}
              onImport={(file) => void importXliff(job, file)}
              onRequestReview={() => void requestReview(job)}
            />
          ))}
          <div className="studio-trp__add-language">
            <TextField label="Add a language" value={newLanguage} onChange={setNewLanguage} width="size-1600" description={languageName(newLanguage.trim()) ?? undefined} />
            <ActionButton
              isDisabled={!languageName(newLanguage.trim().toLowerCase()) || running !== null}
              onPress={() => void change(() => client.addLanguage(name, newLanguage.trim().toLowerCase())).then((ok) => ok && setNewLanguage(''))}
            >
              <Add />
              <Text>Add job</Text>
            </ActionButton>
          </div>
        </section>

        <section aria-label="Content" className="studio-trp__items">
          <Flex alignItems="center" justifyContent="space-between">
            <h3>{`Content (${items.length})`}</h3>
            {selected.size > 0 && (
              <ActionButton
                isDisabled={running !== null}
                onPress={async () => {
                  for (const id of selected) if (!(await change(() => client.removeItem(name, id)))) break;
                  setSelected(new Set());
                }}
              >
                <Delete />
                <Text>{`Remove (${selected.size})`}</Text>
              </ActionButton>
            )}
          </Flex>
          <TableView
            aria-label="Content"
            selectionMode="multiple"
            selectedKeys={selected}
            onSelectionChange={(keys) => setSelected(keys === 'all' ? new Set(items.map((i) => i.id)) : new Set([...keys].map(String)))}
            density="compact"
            overflowMode="truncate"
            maxHeight="size-6000"
          >
            <TableHeader>
              <Column key="title" isRowHeader width="2fr">
                Title
              </Column>
              <Column key="path" width="3fr">
                Path
              </Column>
              <Column key="kind" width="1fr">
                Type
              </Column>
            </TableHeader>
            <TableBody items={items}>
              {(i) => (
                <Row key={i.id}>
                  <Cell>{i.exists === false ? `${i.title ?? i.path} (missing)` : (i.title ?? i.path)}</Cell>
                  <Cell>{i.path}</Cell>
                  <Cell>{KIND[i.kind] ?? i.kind}</Cell>
                </Row>
              )}
            </TableBody>
          </TableView>
        </section>
      </div>

      <DialogContainer onDismiss={() => setAdding(false)}>
        {adding && (
          <AddContentDialog
            onClose={() => setAdding(false)}
            onAdd={async (paths, deep) => {
              if (await change(() => client.addItems(name, paths, deep))) setAdding(false);
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setReport(null)}>
        {report && (
          <StudioDialog>
            <Heading>{`XLIFF imported · ${lang(report.language)}`}</Heading>
            <Divider />
            <Content>
              <Flex direction="column" gap="size-100">
                <Text>{`${report.saved} text${report.saved === 1 ? '' : 's'} saved in ${report.files} cop${report.files === 1 ? 'y' : 'ies'}, ${report.unchanged} unchanged. Saved texts count as reviewed.`}</Text>
                {report.errors.length > 0 && (
                  <>
                    <Text>{`${report.errors.length} not saved:`}</Text>
                    <ul aria-label="Not saved" className="studio-trp__list">
                      {report.errors.map((e, i) => (
                        <li key={i}>
                          <span>{`${e.path}${e.label ? ` · ${e.label}` : e.id ? ` · ${e.id}` : ''}`}</span>
                          <span className="studio-cf-error">{e.message}</span>
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </Flex>
            </Content>
            <ButtonGroup>
              <Button variant="accent" onPress={() => setReport(null)}>
                Close
              </Button>
            </ButtonGroup>
          </StudioDialog>
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setConfirmDelete(false)}>
        {confirmDelete && (
          <StudioDialog size="S">
            <Heading>Delete the project?</Heading>
            <Divider />
            <Content>
              <Text>The project and its jobs are deleted. The language versions it made stay as they are.</Text>
            </Content>
            <ButtonGroup>
              <Button variant="secondary" onPress={() => setConfirmDelete(false)}>
                Cancel
              </Button>
              <Button
                variant="negative"
                onPress={async () => {
                  try {
                    await client.remove(name);
                    setConfirmDelete(false);
                    onBack();
                  } catch (e) {
                    ToastQueue.negative(message(e), { timeout: 8000 });
                  }
                }}
              >
                Delete
              </Button>
            </ButtonGroup>
          </StudioDialog>
        )}
      </DialogContainer>
    </>
  );
}

function JobCard({
  project,
  job,
  running,
  busy,
  onStart,
  onStop,
  onStatus,
  onRequestReview,
  onBackground,
  xliffUrl,
  onImport,
}: {
  project: TranslationProject;
  job: TranslationJob;
  running: boolean;
  busy: boolean;
  onStart(): void;
  onStop(): void;
  onStatus(s: 'APPROVED' | 'COMPLETE' | 'CANCELLED' | 'DRAFT'): void;
  onRequestReview(): void;
  onBackground(start: boolean): void;
  xliffUrl(version: '1.2' | '2.0'): string;
  onImport(file: File): void;
}) {
  const go = useNavigate();
  const fileInput = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const st = JOB_STATUS[job.status];
  const pending = pendingItems(project, job).length;
  const is = (...s: JobStatus[]) => s.includes(job.status);
  const review = job.review ?? {};
  const byId = new Map((project.items ?? []).map((i) => [i.id, i]));
  return (
    <article className="studio-trp__job" aria-label={`${lang(job.language)} job`}>
      <header className="studio-trp__job-head">
        <h3>{lang(job.language)}</h3>
        <StatusLight variant={st.variant}>{running ? 'Translating…' : job.background ? 'On the server…' : st.text}</StatusLight>
      </header>
      <ProgressBar
        label={`${job.done} of ${job.total} items`}
        value={job.total ? (100 * job.done) / job.total : 0}
        valueLabel={job.failedItems ? `${job.failedItems} failed` : undefined}
        width="100%"
        size="S"
      />
      <p className="studio-cf__muted">
        {job.copyCount ? `${job.copyCount} language version${job.copyCount === 1 ? '' : 's'}` : 'No copies yet'}
        {Object.entries(review)
          .filter(([, n]) => n)
          .map(([k, n]) => ` · ${n} ${stateLight(k as never).text.toLowerCase()}`)
          .join('')}
      </p>
      {jobStats(job) && <p className="studio-cf__muted">{jobStats(job)}</p>}
      {job.started && <p className="studio-cf__muted">{`Started ${when(job.started)}${job.startedBy ? ` by ${job.startedBy}` : ''}`}</p>}
      {job.approvedAt && <p className="studio-cf__muted">{`Approved ${when(job.approvedAt)}${job.approvedBy ? ` by ${job.approvedBy}` : ''}`}</p>}
      {job.background && (
        <p className="studio-cf__muted">
          {`${job.background === 'QUEUED' ? 'Waiting to run on the server' : job.background === 'STOPPING' ? 'Stopping after the current item' : 'Translating on the server'}${job.backgroundBy ? ` for ${job.backgroundBy}` : ''}`}
        </p>
      )}
      {job.error && <p className="studio-cf-error">{job.error}</p>}
      <Flex gap="size-100" wrap marginTop="size-100">
        {running ? (
          <Button variant="secondary" onPress={onStop}>
            Stop
          </Button>
        ) : job.background ? (
          <Button variant="secondary" onPress={() => onBackground(false)} isDisabled={job.background === 'STOPPING'}>
            Stop
          </Button>
        ) : (
          is('DRAFT', 'IN_PROGRESS') && (
            <>
              <Button variant="accent" onPress={onStart} isDisabled={busy || pending === 0}>
                {is('IN_PROGRESS') ? `Resume (${pending} to do)` : 'Start'}
              </Button>
              <Button variant="secondary" onPress={() => onBackground(true)} isDisabled={busy || pending === 0}>
                Run on the server
              </Button>
            </>
          )
        )}
        {is('READY_FOR_REVIEW') && (
          <>
            <Button variant="secondary" onPress={onRequestReview} isDisabled={busy || !job.copyCount}>
              Request review
            </Button>
            <Button variant="accent" onPress={() => onStatus('APPROVED')} isDisabled={busy}>
              Approve
            </Button>
          </>
        )}
        {is('READY_FOR_REVIEW', 'APPROVED') && (
          <Button variant="secondary" onPress={() => onStatus('COMPLETE')} isDisabled={busy}>
            Complete
          </Button>
        )}
        {is('READY_FOR_REVIEW', 'APPROVED', 'COMPLETE', 'CANCELLED') && (
          <Button variant="secondary" style="outline" onPress={() => onStatus('DRAFT')} isDisabled={busy}>
            Update
          </Button>
        )}
        {!running && !job.background && is('DRAFT', 'IN_PROGRESS', 'READY_FOR_REVIEW', 'APPROVED') && (
          <Button variant="negative" style="outline" onPress={() => onStatus('CANCELLED')} isDisabled={busy}>
            Cancel job
          </Button>
        )}
      </Flex>
      {job.copyCount > 0 && !is('CANCELLED') && (
        <Flex gap="size-100" wrap marginTop="size-100">
          <MenuTrigger>
            <Button variant="secondary" style="outline" isDisabled={running || !!job.background}>
              Export XLIFF
            </Button>
            <Menu
              aria-label="XLIFF version"
              onAction={(k) => {
                go(xliffUrl(k === '2.0' ? '2.0' : '1.2'));
              }}
            >
              <Item key="1.2">XLIFF 1.2</Item>
              <Item key="2.0">XLIFF 2.0</Item>
            </Menu>
          </MenuTrigger>
          {!is('COMPLETE') && (
            <Button variant="secondary" style="outline" onPress={() => fileInput.current?.click()} isDisabled={busy || !!job.background}>
              Import XLIFF
            </Button>
          )}
          <input
            ref={fileInput}
            type="file"
            accept=".xlf,.xliff,.xml,application/xml"
            aria-label={`XLIFF file for ${lang(job.language)}`}
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              e.target.value = '';
              if (f) onImport(f);
            }}
          />
        </Flex>
      )}
      {(job.items?.some((i) => i.status !== 'DRAFT') || (job.copies?.length ?? 0) > 0) && (
        <>
          <ActionButton isQuiet onPress={() => setOpen(!open)} marginTop="size-100" aria-expanded={open}>
            {open ? 'Hide details' : 'Details'}
          </ActionButton>
          {open && (
            <div className="studio-trp__details">
              <ul aria-label="Items" className="studio-trp__list">
                {(job.items ?? []).map((i) => {
                  const item = byId.get(i.id);
                  return (
                    <li key={i.id}>
                      <span>{item?.title ?? item?.path ?? i.id}</span>
                      <span className={i.status === 'FAILED' ? 'studio-cf-error' : 'studio-cf__muted'}>
                        {i.status === 'DRAFT'
                          ? 'To do'
                          : i.status === 'FAILED'
                            ? `Failed: ${i.error ?? ''}`
                            : i.status === 'SKIPPED'
                              ? `Skipped${i.error ? `: ${i.error}` : ''}`
                              : `${i.translated ?? 0} translated, ${i.unchanged ?? 0} unchanged${i.failedTexts ? `, ${i.failedTexts} not translated` : ''}`}
                      </span>
                    </li>
                  );
                })}
              </ul>
              {(job.copies?.length ?? 0) > 0 && (
                <ul aria-label="Copies" className="studio-trp__list">
                  {(job.copies ?? []).map((c) => (
                    <li key={c.path}>
                      {c.kind === 'content-fragment' ? <Link href={`/editor.html${c.path}`}>{c.title ?? c.path}</Link> : <Link href={reviewUrl(c.path)}>{c.title ?? c.path}</Link>}
                      <span className="studio-cf__muted">{`${c.kind ? `${KIND[c.kind]} · ` : ''}${c.state ? stateLight(c.state).text : 'not recorded'}`}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </>
      )}
    </article>
  );
}

function AddContentDialog({ onClose, onAdd }: { onClose(): void; onAdd(paths: string[], deep: boolean): Promise<void> }) {
  const [paths, setPaths] = useState('');
  const [deep, setDeep] = useState(false);
  const [busy, setBusy] = useState(false);
  const list = parsePaths(paths);
  return (
    <StudioDialog>
      <Heading>Add content</Heading>
      <Divider />
      <Content>
        <Flex direction="column" gap="size-150">
          <TextArea
            label="Paths"
            description="One per line: pages, experience fragments, content fragments or files in the project's language"
            value={paths}
            onChange={setPaths}
            autoFocus
            width="100%"
            height="size-1600"
          />
          <Checkbox isSelected={deep} onChange={setDeep}>
            Include the pages below
          </Checkbox>
        </Flex>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isPending={busy}
          isDisabled={!list.length}
          onPress={async () => {
            setBusy(true);
            try {
              await onAdd(list, deep);
            } finally {
              setBusy(false);
            }
          }}
        >
          Add
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/** The project's due date: shown (late in red), changed or removed; the owner's Inbox has a reminder. */
function DueDate({ project, onChange }: { project: TranslationProject; onChange(due: number | null): void }) {
  const [editing, setEditing] = useState(false);
  const iso = (t?: number) => (t ? new Date(t - new Date(t).getTimezoneOffset() * 60000).toISOString().slice(0, 10) : '');
  const [value, setValue] = useState(iso(project.dueDate));
  if (editing) {
    return (
      <Flex gap="size-100" alignItems="end" wrap>
        <TextField label="Due date" type="date" value={value} onChange={setValue} width="size-2400" />
        <Button
          variant="accent"
          isDisabled={!value}
          onPress={() => {
            setEditing(false);
            onChange(new Date(`${value}T17:00:00`).getTime());
          }}
        >
          Save
        </Button>
        {project.dueDate && (
          <Button
            variant="secondary"
            style="outline"
            onPress={() => {
              setEditing(false);
              onChange(null);
            }}
          >
            Remove
          </Button>
        )}
        <Button variant="secondary" style="outline" onPress={() => setEditing(false)}>
          Cancel
        </Button>
      </Flex>
    );
  }
  return (
    <p className={project.overdue ? 'studio-cf-error' : 'studio-cf__muted'}>
      {project.dueDate ? `Due ${new Date(project.dueDate).toLocaleDateString()}${project.overdue ? ' · late' : ''}` : 'No due date'}
      {project.dueReminder ? ' · reminder in the Inbox' : ''}{' '}
      <Link
        onPress={() => {
          setValue(iso(project.dueDate));
          setEditing(true);
        }}
      >
        {project.dueDate ? 'Change' : 'Set a due date'}
      </Link>
    </p>
  );
}
