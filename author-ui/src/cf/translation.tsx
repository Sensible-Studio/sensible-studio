import {
  ActionButton,
  Button,
  ButtonGroup,
  Checkbox,
  CheckboxGroup,
  Content,
  DialogContainer,
  Divider,
  Flex,
  Heading,
  InlineAlert,
  Item,
  Link,
  Picker,
  ProgressBar,
  ProgressCircle,
  StatusLight,
  Text,
  TextField,
  ToastQueue,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Globe from '@spectrum-icons/workflow/Globe';
import { useEffect, useMemo, useState } from 'react';
import {
  CfServiceError,
  cfEditorUrl,
  type CfClient,
  type CfFragment,
  type CfLanguages,
  type CfModel,
  type CfTranslateResult,
  type CfTranslationInfo,
  type CfTranslationProvider,
} from './api';
import { languageName, languageRoot } from './languages';
import { plain } from './versions';
import { StudioDialog } from '../shell/StudioDialog';

const STATUS: Record<string, { label: string; variant: 'positive' | 'notice' | 'negative' | 'info' | 'neutral' }> = {
  TRANSLATED: { label: 'Translated, to review', variant: 'notice' },
  APPROVED: { label: 'Approved', variant: 'positive' },
  ERROR_UPDATE: { label: 'Partly translated', variant: 'negative' },
};

export function TranslationStatus({ info }: { info: CfTranslationInfo }) {
  const s = STATUS[info.status ?? ''] ?? { label: info.status ?? 'Not translated', variant: 'neutral' as const };
  const outdated = info.outdated?.length ?? 0;
  return (
    <Flex direction="column" gap="size-50">
      <StatusLight variant={s.variant} UNSAFE_className="studio-cf-tr__status">
        {s.label}
      </StatusLight>
      {outdated > 0 && (
        <Text UNSAFE_className="studio-cf-tr__outdated">
          {outdated} source text{outdated === 1 ? '' : 's'} changed since
        </Text>
      )}
    </Flex>
  );
}

/** What a connector failure means for the user. */
function problemOf(e: unknown): { title: string; detail: string; configure?: boolean } {
  if (e instanceof CfServiceError && e.code === 'MISSING_CREDENTIALS') {
    return { title: 'The AI connector is not set up', detail: e.message, configure: true };
  }
  if (e instanceof CfServiceError && e.code === 'SERVICE_NOT_AVAILABLE') {
    return { title: 'The translation service is busy', detail: `${e.message} Try again in a moment.` };
  }
  return { title: 'Translation failed', detail: e instanceof Error ? e.message : String(e) };
}

// ------------------------------------------------------------------------------------------- translate dialog

/**
 * Translates fragments into languages: creates the language copies (…/fr/… next to …/en/…) or updates them
 * (only texts changed since). One fragment at a time, with progress.
 */
export function TranslateDialog({
  client,
  paths,
  onDone,
}: {
  client: CfClient;
  paths: string[];
  onDone?(results: CfTranslateResult[]): void;
}) {
  const dialog = useDialogContainer();
  const [languages, setLanguages] = useState<CfLanguages | null>(null);
  const [providers, setProviders] = useState<CfTranslationProvider[] | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [extra, setExtra] = useState('');
  const [provider, setProvider] = useState<string | null>(null);
  const [includeReferences, setIncludeReferences] = useState(true);
  const [force, setForce] = useState(false);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [results, setResults] = useState<CfTranslateResult[]>([]);
  const [problem, setProblem] = useState<ReturnType<typeof problemOf> | null>(null);
  const source = languageRoot(paths[0] ?? '');

  useEffect(() => {
    if (!paths[0]) return;
    client.languages(paths[0]).then(setLanguages, () => setLanguages({ path: paths[0], languageRoot: null }));
    client.translationProviders(paths[0]).then((p) => {
      setProviders(p);
      const preferred = p.find((x) => x.default && x.available) ?? p.find((x) => x.available) ?? p[0];
      setProvider(preferred?.name ?? null);
    }, () => setProviders([]));
  }, [client, paths]);

  const extraCode = extra.trim().toLowerCase().replace('-', '_');
  const extraName = extraCode ? languageName(extraCode) : null;
  const targets = useMemo(() => {
    const all = new Set(selected);
    if (extraName && extraCode !== source?.language) all.add(extraCode);
    return [...all];
  }, [selected, extraCode, extraName, source]);
  const chosen = providers?.find((p) => p.name === provider);
  const running = progress !== null && progress.done < progress.total;
  const finished = progress !== null && progress.done === progress.total;

  const run = async () => {
    setProblem(null);
    setResults([]);
    setProgress({ done: 0, total: paths.length });
    const all: CfTranslateResult[] = [];
    for (let i = 0; i < paths.length; i++) {
      try {
        const r = await client.translate({ paths: [paths[i]], languages: targets, provider: provider ?? undefined, includeReferences, force });
        all.push(...r);
        setResults([...all]);
      } catch (e) {
        setProblem(problemOf(e));
        setProgress(null);
        return;
      }
      setProgress({ done: i + 1, total: paths.length });
    }
    const failed = all.filter((r) => r.failed.length).length;
    if (failed) ToastQueue.negative(`${failed} language cop${failed === 1 ? 'y' : 'ies'} only partly translated`, { timeout: 6000 });
    else ToastQueue.positive(`${all.length} language cop${all.length === 1 ? 'y' : 'ies'} translated`, { timeout: 3000 });
    onDone?.(all);
  };

  const others = (languages?.languages ?? []).filter((l) => l.code !== source?.language);

  return (
    <StudioDialog size="M">
      <Heading>
        Translate {paths.length === 1 ? 'fragment' : `${paths.length} fragments`}
      </Heading>
      <Divider />
      <Content>
        {!source ? (
          <InlineAlert variant="negative">
            <Heading>Not in a language folder</Heading>
            <Content>Language versions live next to a language folder, e.g. /content/dam/site/en/… → /content/dam/site/fr/…</Content>
          </InlineAlert>
        ) : !languages || !providers ? (
          <ProgressCircle aria-label="Loading languages" isIndeterminate />
        ) : (
          <Flex direction="column" gap="size-200">
            <Text>
              From <strong>{languageName(source.language) ?? source.language}</strong> ({source.language}) into:
            </Text>
            {others.length > 0 && (
              <CheckboxGroup aria-label="Languages" value={selected} onChange={setSelected} orientation="horizontal">
                {others.map((l) => (
                  <Checkbox key={l.code} value={l.code}>
                    {`${l.name} (${l.code})`}
                  </Checkbox>
                ))}
              </CheckboxGroup>
            )}
            <TextField
              label={others.length ? 'Another language' : 'Language'}
              placeholder="Code, e.g. de, es, pt_br"
              value={extra}
              onChange={setExtra}
              width="size-3600"
              validationState={extra.trim() && !extraName ? 'invalid' : undefined}
              errorMessage="Not a language code"
              description={extraName ? `${extraName}: a new language folder is created` : undefined}
            />
            <Picker
              label="Translated by"
              items={providers}
              selectedKey={provider}
              onSelectionChange={(k) => setProvider(String(k))}
              disabledKeys={providers.filter((p) => !p.available).map((p) => p.name)}
              width="size-3600"
            >
              {(p) => <Item key={p.name}>{p.available ? p.label : `${p.label} (not set up)`}</Item>}
            </Picker>
            {chosen && !chosen.available && <Text UNSAFE_className="studio-cf-error">{chosen.problem}</Text>}
            {providers
              .filter((p) => p.cloudConfig && !p.available && p.name !== chosen?.name)
              .map((p) => (
                <Text key={p.name} UNSAFE_className="studio-cf__muted">
                  {p.label} is not set up: {p.problem}{' '}
                  <Link href={`/cloudservices/translation.html${p.conf ?? '/conf'}`} target="_blank">
                    Cloud Services → Translation
                  </Link>
                </Text>
              ))}
            {chosen?.cloudConfig && (
              <Text UNSAFE_className="studio-cf__muted">
                Configured in{' '}
                <Link href={`/cloudservices/translation.html${chosen.conf ?? '/conf'}`} target="_blank">
                  Cloud Services → Translation
                </Link>
              </Text>
            )}
            <Checkbox isSelected={includeReferences} onChange={setIncludeReferences}>
              Also translate the content fragments they reference
            </Checkbox>
            <Checkbox isSelected={force} onChange={setForce}>
              Translate everything again (also texts unchanged since the last translation)
            </Checkbox>
            {problem && (
              <InlineAlert variant="negative">
                <Heading>{problem.title}</Heading>
                <Content>
                  {!problem.configure && problem.detail}
                  {problem.configure && (
                    <>
                      There is no API key for the AI connector. Add a translation configuration in{' '}
                      <Link href="/cloudservices/translation.html/conf" target="_blank">
                        System → Cloud Services → Translation
                      </Link>{' '}
                      that refers to a secret of this instance, or choose another connector.
                    </>
                  )}
                </Content>
              </InlineAlert>
            )}
            {progress && (
              <ProgressBar
                label={finished ? 'Done' : 'Translating…'}
                value={progress.total ? (progress.done / progress.total) * 100 : 0}
                width="100%"
              />
            )}
            {results.length > 0 && (
              <ul className="studio-cf-tr__results" aria-label="Results">
                {results.map((r) => (
                  <li key={`${r.path}`}>
                    <Link href={cfEditorUrl(r.path)}>{r.path.slice(r.path.lastIndexOf('/') + 1)}</Link>
                    <span className="studio-cf__muted">
                      {' '}
                      {r.language} · {r.created ? 'created' : 'updated'} · {r.translated} translated
                      {r.unchanged ? ` · ${r.unchanged} unchanged` : ''}
                    </span>
                    {r.failed.length > 0 && <span className="studio-cf-error"> · {r.failed.length} not translated</span>}
                  </li>
                ))}
              </ul>
            )}
          </Flex>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          {finished ? 'Close' : 'Cancel'}
        </Button>
        {!finished && (
          <Button
            variant="accent"
            onPress={() => void run()}
            isPending={running}
            isDisabled={!source || !targets.length || !chosen?.available || running}
          >
            <Globe />
            <Text>Translate</Text>
          </Button>
        )}
      </ButtonGroup>
    </StudioDialog>
  );
}

// ------------------------------------------------------------------------------------------- languages panel

/**
 * The editor's Languages tab. For a language copy: its source, translation status, update and approve, and the
 * translatable texts next to the source's. For a source: its language copies and their status, and Translate.
 */
export function LanguagesPanel({
  client,
  fragment,
  model,
  dirty,
  onOpen,
  onReload,
}: {
  client: CfClient;
  fragment: CfFragment;
  model: CfModel;
  dirty: boolean;
  onOpen(url: string): void;
  onReload(): Promise<void>;
}) {
  const [info, setInfo] = useState<CfLanguages | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<null | 'translate' | 'compare'>(null);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let cancelled = false;
    client.languages(fragment.path).then(
      (l) => !cancelled && setInfo(l),
      (e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      cancelled = true;
    };
  }, [client, fragment.path, fragment.modified, version]);

  if (error) return <Text UNSAFE_className="studio-cf__muted">Languages are not available: {error}</Text>;
  if (!info) return <ProgressCircle aria-label="Loading languages" isIndeterminate size="S" />;
  if (!info.languageRoot) {
    return (
      <Text UNSAFE_className="studio-cf__muted">
        This content fragment is not in a language folder. Language versions live next to one, e.g. /content/dam/site/en/… → /content/dam/site/fr/…
      </Text>
    );
  }

  const t = info.translation;
  const update = async () => {
    if (!t?.sourcePath || !t.targetLanguage) return;
    setBusy(true);
    try {
      const [r] = await client.translate({ paths: [t.sourcePath], languages: [t.targetLanguage], provider: t.provider ?? undefined });
      await onReload();
      ToastQueue.positive(r ? `Updated: ${r.translated} text${r.translated === 1 ? '' : 's'} translated` : 'Up to date', { timeout: 3000 });
      setVersion((n) => n + 1);
    } catch (e) {
      const p = problemOf(e);
      ToastQueue.negative(`${p.title}: ${p.detail}`, { timeout: 8000 });
    } finally {
      setBusy(false);
    }
  };
  const approve = async () => {
    setBusy(true);
    try {
      setInfo(await client.approveTranslation(fragment.path));
      ToastQueue.positive('Translation approved', { timeout: 2500 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Flex direction="column" gap="size-200" UNSAFE_className="studio-cf-tr">
      {t ? (
        <section className="studio-cf-tr__box" aria-label="Translation">
          <span className="studio-cf-label">Language version of</span>
          {t.sourcePath ? (
            <Link isQuiet onPress={() => onOpen(cfEditorUrl(t.sourcePath!))}>
              {t.sourcePath}
            </Link>
          ) : (
            <Text>unknown source</Text>
          )}
          <Text UNSAFE_className="studio-cf__muted">
            {languageName(t.sourceLanguage ?? '') ?? t.sourceLanguage} → {languageName(t.targetLanguage ?? '') ?? t.targetLanguage}
          </Text>
          <TranslationStatus info={t} />
          {t.attribution && (
            <Text UNSAFE_className="studio-cf__muted">
              {t.attribution}
              {t.translatedAt ? ` · ${new Date(t.translatedAt).toLocaleString()}` : ''}
              {t.translatedBy ? ` · ${t.translatedBy}` : ''}
            </Text>
          )}
          {t.status === 'APPROVED' && t.approvedBy && (
            <Text UNSAFE_className="studio-cf__muted">
              Approved by {t.approvedBy}
              {t.approvedAt ? ` · ${new Date(t.approvedAt).toLocaleString()}` : ''}
            </Text>
          )}
          {(t.failed?.length ?? 0) > 0 && (
            <Text UNSAFE_className="studio-cf-error">
              Not translated: {t.failed!.map((f) => f.split(':')[0]).join(', ')}
            </Text>
          )}
          {t.sourceMissing && <Text UNSAFE_className="studio-cf-error">The source content fragment no longer exists.</Text>}
          <Flex gap="size-100" wrap marginTop="size-100">
            <Button variant="secondary" style="outline" onPress={() => setDialog('compare')} isDisabled={!t.sourcePath || t.sourceMissing}>
              Compare with source
            </Button>
            <Button variant="secondary" style="outline" onPress={() => void update()} isPending={busy} isDisabled={dirty || !t.sourcePath || t.sourceMissing}>
              {(t.outdated?.length ?? 0) > 0 ? 'Update translation' : 'Translate again'}
            </Button>
            {t.status !== 'APPROVED' && (
              <Button variant="accent" onPress={() => void approve()} isDisabled={dirty || busy}>
                Approve
              </Button>
            )}
          </Flex>
          {dirty && <Text UNSAFE_className="studio-cf__muted">Save your changes first.</Text>}
        </section>
      ) : (
        <Flex direction="column" gap="size-100">
          <Flex alignItems="center" justifyContent="space-between">
            <Text>
              <strong>{languageName(info.language ?? '') ?? info.language}</strong> ({info.language})
            </Text>
            <Button variant="secondary" style="outline" onPress={() => setDialog('translate')} isDisabled={dirty}>
              <Globe />
              <Text>Translate…</Text>
            </Button>
          </Flex>
          {dirty && <Text UNSAFE_className="studio-cf__muted">Save your changes first: the saved content fragment is translated.</Text>}
          <span className="studio-cf-label">Language versions</span>
          {(info.copies ?? []).length === 0 ? (
            <Text UNSAFE_className="studio-cf__muted">None yet.</Text>
          ) : (
            <ul className="studio-cf-tr__copies" aria-label="Language versions">
              {info.copies!.map((c) => (
                <li key={c.path}>
                  <Link isQuiet onPress={() => onOpen(cfEditorUrl(c.path))}>
                    {`${languageName(c.language ?? '') ?? c.language} (${c.language})`}
                  </Link>
                  <TranslationStatus info={c} />
                </li>
              ))}
            </ul>
          )}
        </Flex>
      )}

      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog === 'translate' && <TranslateDialog client={client} paths={[fragment.path]} onDone={() => setVersion((n) => n + 1)} />}
        {dialog === 'compare' && t?.sourcePath && <CompareSourceDialog client={client} sourcePath={t.sourcePath} copy={fragment} model={model} />}
      </DialogContainer>
    </Flex>
  );
}

function CompareSourceDialog({ client, sourcePath, copy, model }: { client: CfClient; sourcePath: string; copy: CfFragment; model: CfModel }) {
  const dialog = useDialogContainer();
  const [source, setSource] = useState<CfFragment | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    client.fragment(sourcePath).then(setSource, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, sourcePath]);
  const rows = source
    ? [
        { name: '_title', label: 'Title (content fragment)', a: source.title, b: copy.title },
        ...model.fields
          .filter((f) => f.translatable && f.kind !== 'tab')
          .map((f) => ({ name: f.name, label: f.label, a: plain(source.values.master?.[f.name]), b: plain(copy.values.master?.[f.name]) })),
      ]
    : [];
  return (
    <StudioDialog size="L">
      <Heading>Translation next to the source</Heading>
      <Divider />
      <Content>
        {error ? (
          <Text>{error}</Text>
        ) : !source ? (
          <ProgressCircle aria-label="Loading source" isIndeterminate />
        ) : (
          <table className="studio-cf-diff studio-table" aria-label="Translatable texts">
            <thead>
              <tr>
                <th scope="col">Element</th>
                <th scope="col">Source ({languageRoot(sourcePath)?.language})</th>
                <th scope="col">This copy ({languageRoot(copy.path)?.language})</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.name}>
                  <th scope="row">{r.label}</th>
                  <td className="studio-cf-diff__src">{r.a || <em>empty</em>}</td>
                  <td className="studio-cf-diff__src">{r.b || <em>empty</em>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Close
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/** A small "Translate" action for consoles. */
export function TranslateAction({ onPress, isDisabled }: { onPress(): void; isDisabled?: boolean }) {
  return (
    <ActionButton isQuiet onPress={onPress} isDisabled={isDisabled}>
      <Globe />
      <Text>Translate</Text>
    </ActionButton>
  );
}
