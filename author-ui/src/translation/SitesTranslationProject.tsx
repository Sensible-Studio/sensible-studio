import {
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  DialogContainer,
  Divider,
  Flex,
  Heading,
  InlineAlert,
  Item,
  Link,
  Picker,
  ProgressCircle,
  Text,
} from '@adobe/react-spectrum';
import { useEffect, useMemo, useState } from 'react';
import { languageName } from '../cf/languages';
import { HttpPageTranslationClient, type PageTranslationClient } from './pages';
import { HttpTranslationProjectClient, JOB_STATUS, type TranslationProject, type TranslationProjectClient } from './projects';
import { CreateProjectDialog, projectUrl } from './TranslationProjects';
import { StudioDialog } from '../shell/StudioDialog';

/** The language of a path: its nearest ancestor named with a language code below /content/<x> (…/language-masters/en/…). */
export function languageOf(path: string): string | null {
  const parts = path.split('/');
  for (let i = parts.length - 1; i >= 3; i--) if (languageName(parts[i])) return parts[i];
  return null;
}

/**
 * Sites / Assets → Translation Project for the selection (pages; assets and content fragments): a new translation
 * project with them (the console's Create dialog, the paths filled in), or added to an existing project in the same
 * source language.
 */
export function SitesTranslationProjectDialog({
  paths,
  onClose,
  client,
  pages,
}: {
  paths: string[];
  onClose(): void;
  client?: TranslationProjectClient;
  pages?: PageTranslationClient;
}) {
  const projects = useMemo(() => client ?? new HttpTranslationProjectClient(), [client]);
  const translation = useMemo(() => pages ?? new HttpPageTranslationClient(), [pages]);
  const [mode, setMode] = useState<'new' | 'existing'>('new');

  const [result, setResult] = useState<{ project: TranslationProject; created: boolean } | null>(null);

  return (
    <DialogContainer onDismiss={onClose}>
      {result ? (
        <StudioDialog size="S">
          <Heading>{result.created ? 'Translation project created' : 'Added to the translation project'}</Heading>
          <Divider />
          <Content>
            <Text>
              {`${paths.length === 1 ? '1 item' : `${paths.length} items`} ${result.created ? 'went into the new project' : 'went into'} “${result.project.title}”. Start its jobs from the project.`}
            </Text>
          </Content>
          <ButtonGroup>
            <Button variant="secondary" onPress={onClose}>
              Close
            </Button>
            <Button variant="accent" onPress={() => (window.location.href = projectUrl(result.project.name))}>
              Open project
            </Button>
          </ButtonGroup>
        </StudioDialog>
      ) : mode === 'new' ? (
        <CreateProjectDialog
          client={projects}
          pages={translation}
          initialPaths={paths.join('\n')}
          initialDeep={false}
          onClose={onClose}
          onCreated={(p) => setResult({ project: p, created: true })}
          onExisting={() => setMode('existing')}
        />
      ) : (
        <AddToProjectDialog client={projects} paths={paths} onClose={onClose} onNew={() => setMode('new')} onAdded={(p) => setResult({ project: p, created: false })} />
      )}
    </DialogContainer>
  );
}

export function AddToProjectDialog({
  client,
  paths,
  onClose,
  onNew,
  onAdded,
}: {
  client: TranslationProjectClient;
  paths: string[];
  onClose(): void;
  onNew(): void;
  onAdded(p: TranslationProject): void;
}) {
  const source = languageOf(paths[0] ?? '');
  const [projects, setProjects] = useState<TranslationProject[] | null>(null);
  const [chosen, setChosen] = useState<string | null>(null);
  const [deep, setDeep] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    client.list().then(
      (all) => {
        // the projects these pages can join: same source language, not every job closed
        const open = all.filter((p) => p.sourceLanguage === source && p.jobs.some((j) => j.status !== 'COMPLETE' && j.status !== 'CANCELLED'));
        setProjects(open);
        setChosen(open[0]?.name ?? null);
      },
      (e: unknown) => setFailure(e instanceof Error ? e.message : String(e)),
    );
  }, [client, source]);

  const add = async () => {
    if (!chosen) return;
    setBusy(true);
    setFailure(null);
    try {
      onAdded(await client.addItems(chosen, paths, deep));
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const describe = (p: TranslationProject) =>
    `${p.title} · ${p.jobs.map((j) => `${j.language}: ${JOB_STATUS[j.status].text.toLowerCase()}`).join(', ')}`;

  return (
    <StudioDialog>
      <Heading>Add to translation project</Heading>
      <Divider />
      <Content>
        <Flex direction="column" gap="size-150">
          <Text>
            <Link onPress={onNew}>Create a new project instead</Link>
          </Text>
          <Text>{paths.length === 1 ? paths[0] : `${paths.length} items`}</Text>
          {!projects && !failure && <ProgressCircle aria-label="Loading" isIndeterminate size="S" />}
          {projects && projects.length === 0 && (
            <InlineAlert variant="info">
              <Heading>No open project</Heading>
              <Content>{`There is no translation project from ${source ? (languageName(source) ?? source) : 'this language'} with a job still open.`}</Content>
            </InlineAlert>
          )}
          {projects && projects.length > 0 && (
            <Picker label="Project" items={projects} selectedKey={chosen} onSelectionChange={(k) => setChosen(String(k))} width="100%">
              {(p) => <Item key={p.name}>{describe(p)}</Item>}
            </Picker>
          )}
          <Checkbox isSelected={deep} onChange={setDeep}>
            Include the pages below
          </Checkbox>
          <Text UNSAFE_className="studio-cf__muted">Jobs already translated go back to work for what is added.</Text>
          {failure && (
            <InlineAlert variant="negative">
              <Heading>Not added</Heading>
              <Content>{failure}</Content>
            </InlineAlert>
          )}
        </Flex>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void add()} isPending={busy} isDisabled={!chosen}>
          Add
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
