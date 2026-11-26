import {
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
} from '@adobe/react-spectrum';
import Globe from '@spectrum-icons/workflow/Globe';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { languageName } from '../cf/languages';
import {
  copyState,
  PageTranslationError,
  type PageLanguages,
  type PageTranslateResult,
  type PageTranslationClient,
  type TranslationProvider,
} from './pages';
import './translation.scss';
import { StudioDialog } from '../shell/StudioDialog';

const editorUrl = (path: string) => `/editor.html${path}.html`;
const CLOUD_SERVICES = '/cloudservices/translation.html/conf';

/**
 * Sites → Language Copies of a page: its language, its copies in the site's other languages and their state
 * (Approve), and Translate: creates or updates copies, with the sub-pages if asked, one page at a time.
 */
export function PageLanguagesDialog({
  client,
  path,
  onClose,
  onChanged,
  file = false,
  more = [],
}: {
  client: PageTranslationClient;
  path: string;
  onClose(): void;
  onChanged?(): void;
  /** a file in Media (its title and description are translated): no pages below, nothing it uses */
  file?: boolean;
  /** further pages translated together with `path` (several pages selected): the languages are those of `path` */
  more?: string[];
}) {
  return (
    <DialogContainer onDismiss={onClose} type="modal">
      <LanguagesDialog client={client} path={path} onClose={onClose} onChanged={onChanged} file={file} more={more} />
    </DialogContainer>
  );
}

function LanguagesDialog({
  client,
  path,
  onClose,
  onChanged,
  file,
  more,
}: {
  client: PageTranslationClient;
  path: string;
  onClose(): void;
  onChanged?(): void;
  file: boolean;
  more: string[];
}) {
  const several = more.length > 0;
  const [info, setInfo] = useState<PageLanguages | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [providers, setProviders] = useState<TranslationProvider[] | null>(null);
  const [provider, setProvider] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [extra, setExtra] = useState('');
  const [deep, setDeep] = useState(false);
  const [references, setReferences] = useState(true);
  const [force, setForce] = useState(false);
  const [progress, setProgress] = useState<{ done: number; total: number; page?: string } | null>(null);
  const [results, setResults] = useState<PageTranslateResult[]>([]);
  const [problem, setProblem] = useState<{ title: string; detail: string; configure?: boolean } | null>(null);

  const load = useCallback(() => {
    client.languages(path).then(setInfo, (e: unknown) => setFailure(e instanceof Error ? e.message : String(e)));
  }, [client, path]);

  useEffect(() => {
    load();
    client.providers(path).then(
      (p) => {
        setProviders(p);
        setProvider((p.find((x) => x.default && x.available) ?? p.find((x) => x.available) ?? p[0])?.name ?? null);
      },
      () => setProviders([]),
    );
  }, [client, path, load]);

  const extraCode = extra.trim().toLowerCase().replace('-', '_');
  const extraName = extraCode ? languageName(extraCode) : null;
  const targets = useMemo(() => {
    const all = new Set(selected);
    if (extraName && extraCode !== info?.language) all.add(extraCode);
    return [...all];
  }, [selected, extraCode, extraName, info]);
  const chosen = providers?.find((p) => p.name === provider);
  const running = progress !== null && progress.done < progress.total;
  const translatable = (info?.copies ?? []).filter((c) => !c.liveCopy);

  const run = async () => {
    setProblem(null);
    setResults([]);
    let pages: string[];
    try {
      pages = [];
      for (const p of [path, ...more]) for (const x of deep ? await client.pages(p, true) : [p]) if (!pages.includes(x)) pages.push(x);
    } catch (e) {
      setProblem({ title: 'Could not list the pages', detail: e instanceof Error ? e.message : String(e) });
      return;
    }
    const all: PageTranslateResult[] = [];
    const referencesDone = new Set<string>();
    setProgress({ done: 0, total: pages.length, page: pages[0] });
    for (let i = 0; i < pages.length; i++) {
      setProgress({ done: i, total: pages.length, page: pages[i] });
      try {
        const r = await client.translate({
          path: pages[i],
          languages: targets,
          provider: provider ?? undefined,
          force,
          batch: pages,
          includeReferences: references,
          skipReferences: [...referencesDone],
        });
        for (const x of r) if (x.reference) referencesDone.add(x.source);
        all.push(...r);
        setResults([...all]);
      } catch (e) {
        const code = e instanceof PageTranslationError ? e.code : undefined;
        setProblem({
          title: code === 'MISSING_CREDENTIALS' ? 'The connector is not set up' : `Stopped at ${pages[i]}`,
          detail: e instanceof Error ? e.message : String(e),
          configure: code === 'MISSING_CREDENTIALS',
        });
        setProgress(null);
        load();
        onChanged?.();
        return;
      }
    }
    setProgress({ done: pages.length, total: pages.length });
    const translated = all.filter((r) => r.status === 'TRANSLATED').length;
    const skipped = all.length - translated;
    const failed = all.filter((r) => r.failed?.length).length;
    const noun = file ? 'file' : 'page';
    if (failed || skipped) ToastQueue.negative(`${translated} ${noun} cop${translated === 1 ? 'y' : 'ies'} translated, ${failed} only partly, ${skipped} skipped`, { timeout: 8000 });
    else ToastQueue.positive(`${translated} ${noun} cop${translated === 1 ? 'y' : 'ies'} translated`, { timeout: 4000 });
    load();
    onChanged?.();
  };

  const approve = async (copy: string) => {
    try {
      await client.approve(copy);
      ToastQueue.positive('Translation approved', { timeout: 2500 });
      load();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
  };

  const own = info?.translation;

  return (
    <StudioDialog size="L">
      <Heading>Language versions{several ? `: ${more.length + 1} pages` : info ? `: ${info.title}` : ''}</Heading>
      <Divider />
      <Content>
        {failure ? (
          <InlineAlert variant="negative">
            <Heading>Could not load the languages</Heading>
            <Content>{failure}</Content>
          </InlineAlert>
        ) : !info ? (
          <ProgressCircle aria-label="Loading" isIndeterminate />
        ) : !info.languageRoot ? (
          <InlineAlert variant="info">
            <Heading>Not in a language root</Heading>
            <Content>
              Language copies live next to a language root, as in AEM: /content/&lt;site&gt;/language-masters/en/… →
              /content/&lt;site&gt;/language-masters/fr/…
            </Content>
          </InlineAlert>
        ) : (
          <Flex direction="column" gap="size-200">
            {several && <Text>The selected pages are translated together, into the languages of the first one.</Text>}
            <Text>
              <strong>{info.title}</strong> is in <strong>{languageName(info.language ?? '') ?? info.language}</strong> ({info.language}) ·{' '}
              <span className="studio-tr-page__mono">{info.languageRoot}</span>
            </Text>
            {!several && info.liveCopy && (
              <InlineAlert variant="info">
                <Heading>A linked copy</Heading>
                <Content>Its content comes from its source. Translate the source (e.g. the language master), then sync it.</Content>
              </InlineAlert>
            )}
            {!several && own && (
              <div className="studio-tr-page__own" role="region" aria-label="This translation">
                <Text>
                  Translated from <Link href={editorUrl(own.sourcePath)}>{own.sourcePath}</Link>
                </Text>
                <StatusLight variant={own.outdated ? 'notice' : own.status === 'APPROVED' ? 'positive' : 'notice'}>
                  {own.outdated ? 'Outdated: the source changed' : own.status === 'APPROVED' ? 'Approved' : 'Translated, to review'}
                </StatusLight>
                {own.attribution && <Text UNSAFE_className="studio-cf__muted">{own.attribution}</Text>}
                {own.status !== 'APPROVED' && (
                  <Button variant="secondary" onPress={() => void approve(path)}>
                    Approve
                  </Button>
                )}
              </div>
            )}

            {!several && (info.copies ?? []).length > 0 && (
              <ul className="studio-tr-page__copies" aria-label="Language versions">
                {(info.copies ?? []).map((c) => {
                  const state = copyState(c);
                  return (
                    <li key={c.language}>
                      <span className="studio-tr-page__lang">{`${c.name} (${c.language})`}</span>
                      <StatusLight variant={state.variant}>{state.text}</StatusLight>
                      <span className="studio-tr-page__actions">
                        {c.exists && <Link href={editorUrl(c.path)}>Open</Link>}
                        {c.translation && c.translation.status !== 'APPROVED' && !c.translation.outdated && (
                          <Button variant="secondary" isQuiet onPress={() => void approve(c.path)}>
                            Approve
                          </Button>
                        )}
                      </span>
                    </li>
                  );
                })}
              </ul>
            )}

            <Divider size="S" />
            <Heading level={3} UNSAFE_className="studio-tr-page__heading">
              Translate
            </Heading>
            {translatable.length > 0 && (
              <CheckboxGroup aria-label="Languages" value={selected} onChange={setSelected} orientation="horizontal">
                {translatable.map((c) => (
                  <Checkbox key={c.language} value={c.language}>
                    {`${c.name} (${c.language})`}
                  </Checkbox>
                ))}
              </CheckboxGroup>
            )}
            <TextField
              label={translatable.length ? 'Another language' : 'Language'}
              placeholder="Code, e.g. de, es, pt_br"
              value={extra}
              onChange={setExtra}
              width="size-3600"
              validationState={extra.trim() && !extraName ? 'invalid' : undefined}
              errorMessage="Not a language code"
              description={extraName ? `${extraName}: a new language root is created next to ${info.language}` : undefined}
            />
            {providers && (
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
            {chosen && !chosen.available && <Text UNSAFE_className="studio-cf-error">{chosen.problem}</Text>}
            {!file && (
              <>
                <Checkbox isSelected={deep} onChange={setDeep}>
                  Include the pages below
                </Checkbox>
                <Checkbox isSelected={references} onChange={setReferences}>
                  Also translate the experience fragments, content fragments and assets (titles, descriptions) they use
                </Checkbox>
              </>
            )}
            <Checkbox isSelected={force} onChange={setForce}>
              Translate everything again (also unchanged texts and copies made by people)
            </Checkbox>
            <Text UNSAFE_className="studio-cf__muted">
              Existing copies keep their texts; only changed and missing texts are translated, after a version of the copy is saved.
              Linked copies are never written.
            </Text>
            {problem && (
              <InlineAlert variant="negative">
                <Heading>{problem.title}</Heading>
                <Content>
                  {problem.detail}
                  {problem.configure && (
                    <>
                      {' '}
                      Set up a connector in{' '}
                      <Link href={CLOUD_SERVICES} target="_blank">
                        System → Cloud Services → Translation
                      </Link>
                      , or choose another one.
                    </>
                  )}
                </Content>
              </InlineAlert>
            )}
            {progress && (
              <ProgressBar
                label={progress.done < progress.total ? `Translating ${progress.page ?? ''}` : 'Done'}
                value={progress.total ? (progress.done / progress.total) * 100 : 0}
                width="100%"
              />
            )}
            {results.length > 0 && (
              <ul className="studio-tr-page__results" aria-label="Results">
                {results.map((r) => (
                  <li key={`${r.path}`}>
                    {r.status === 'SKIPPED' ? (
                      <>
                        <span className="studio-tr-page__mono">{r.path}</span>
                        <span className="studio-cf-error"> · skipped: {r.error}</span>
                      </>
                    ) : (
                      <>
                        <Link href={r.path.startsWith('/content/dam/') ? `/editor.html${r.path}` : editorUrl(r.path)}>{r.path}</Link>
                        <span className="studio-cf__muted">
                          {' '}
                          {r.reference ? '· used by the page ' : ''}· {r.created ? 'created' : 'updated'} · {r.translated ?? 0} translated
                          {r.unchanged ? ` · ${r.unchanged} unchanged` : ''}
                          {r.kept ? ` · ${r.kept} kept` : ''}
                          {r.fromMemory ? ` · ${r.fromMemory} from memory` : ''}
                        </span>
                        {r.failed && r.failed.length > 0 && <span className="studio-cf-error"> · {r.failed.length} not translated</span>}
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Flex>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={running}>
          Close
        </Button>
        {info?.languageRoot && (
          <Button variant="accent" onPress={() => void run()} isPending={running} isDisabled={!targets.length || !chosen?.available || running}>
            <Globe />
            <Text>Translate</Text>
          </Button>
        )}
      </ButtonGroup>
    </StudioDialog>
  );
}
