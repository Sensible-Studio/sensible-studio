import {
  ActionButton,
  ButtonGroup,
  Divider,
  DialogContainer,
  Button,
  Cell,
  Checkbox,
  Column,
  Content,
  Flex,
  Heading,
  InlineAlert,
  Item,
  Link,
  Picker,
  Row,
  StatusLight,
  TableBody,
  TableHeader,
  TableView,
  Text,
  TextArea,
  ToastContainer,
  ToastQueue,
} from '@adobe/react-spectrum';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import Globe from '@spectrum-icons/workflow/Globe';
import SaveFloppy from '@spectrum-icons/workflow/SaveFloppy';
import { useCallback, useEffect, useMemo, useState, type Key, useRef } from 'react';
import { languageName } from '../cf/languages';
import { ProductBar } from '../shell/ProductBar';
import {
  HttpReviewWorkflowClient,
  PageTranslationError,
  ReviewValidationError,
  type ReviewWorkflowClient,
  type PageReview,
  type PageTranslationClient,
  type BulkApproval,
  type QueueItem,
  type ReviewState,
} from './pages';
import './translation.scss';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { ToolCrumbs } from '../shell/ToolCrumbs';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { StudioDialog } from '../shell/StudioDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { EmptyState } from '../shell/FeedbackState';

export const reviewUrl = (copy?: string) => `/translation/review.html${copy ?? ''}`;
const editorUrl = (path: string, kind?: QueueItem['kind']) =>
  kind === 'content-fragment' ? `/editor.html${path}` : kind === 'asset' || path.startsWith('/content/dam/') ? `/assetdetails.html${path}` : `/editor.html${path}.html`;

const STATES: { key: ReviewState | 'all'; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'review', label: 'To review' },
  { key: 'changes', label: 'Changes requested' },
  { key: 'failed', label: 'With untranslated texts' },
  { key: 'outdated', label: 'Outdated' },
  { key: 'approved', label: 'Approved' },
];

export function stateLight(state: ReviewState): { text: string; variant: 'notice' | 'negative' | 'positive' | 'info' } {
  switch (state) {
    case 'failed':
      return { text: 'Texts not translated', variant: 'negative' };
    case 'changes':
      return { text: 'Changes requested', variant: 'negative' };
    case 'outdated':
      return { text: 'Outdated: the source changed', variant: 'info' };
    case 'approved':
      return { text: 'Approved', variant: 'positive' };
    default:
      return { text: 'To review', variant: 'notice' };
  }
}

const kindLabel = (k: QueueItem['kind']) =>
  k === 'content-fragment' ? 'Content Fragment' : k === 'experience-fragment' ? 'Experience Fragment' : k === 'asset' ? 'Asset' : 'Page';
const lang = (code?: string) => (code ? `${languageName(code) ?? code} (${code})` : '');

/**
 * Tools → Translation Review (`/translation/review.html`): everything Sensible Studio translated, its state and who did it;
 * a page or experience fragment opens its review (`/translation/review.html/<copy>`), a content fragment its editor
 * (Languages tab: Compare with source, Approve).
 */
export function TranslationReview({
  client,
  initialPath,
  workflow = new HttpReviewWorkflowClient(),
}: {
  client: PageTranslationClient;
  initialPath: string | null;
  workflow?: ReviewWorkflowClient;
}) {
  const tab = useTab();
  const [path, setPath] = useState<string | null>(initialPath);
  const [user, setUser] = useState<string | null>(null);

  useEffect(() => {
    fetch('/system/sling/info.sessionInfo.json', { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : null))
      .then((i: { userID?: string } | null) => setUser(i?.userID ?? null), () => setUser(null));
  }, []);

  const tabRef = useRef(tab);
  tabRef.current = tab;
  const tabPath = tab?.place.kind === 'translation-review' ? tab.place.path : undefined;
  useEffect(() => {
    if (tabPath !== undefined && tabPath !== path) setPath(tabPath);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabPath]);
  useEffect(() => {
    if (tab) return; // the shell owns the history
    const onPop = () => {
      const m = window.location.pathname.match(/^\/translation\/review\.html(\/content\/.+)$/);
      setPath(m ? decodeURI(m[1]) : null);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!tab]);

  const open = useCallback((p: string | null) => {
    if (tabRef.current) tabRef.current.setPlace({ kind: 'translation-review', path: p });
    else window.history.pushState(null, '', reviewUrl(p ?? undefined));
    setPath(p);
  }, []);

  return (
    <div className="studio-cfc studio-trr">
      {!tab && <ProductBar user={user} />}
      {path ? (
        <PageReviewView client={client} workflow={workflow} path={path} onBack={() => open(null)} />
      ) : (
        <ReviewQueue client={client} workflow={workflow} onOpen={open} />
      )}
      <ToastContainer />
    </div>
  );
}

function ReviewQueue({ client, workflow, onOpen }: { client: PageTranslationClient; workflow: ReviewWorkflowClient; onOpen(path: string): void }) {
  const go = useNavigate();
  const [items, setItems] = useState<QueueItem[] | null>(null);
  const [running, setRunning] = useState<Record<string, { id: string; assignee?: string }>>({});
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [requesting, setRequesting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [state, setState] = useState<ReviewState | 'all'>('review');
  const [language, setLanguage] = useState<string>('all');
  const [kind, setKind] = useState<QueueItem['kind'] | 'all'>('all');

  const [confirming, setConfirming] = useState(false);
  const [approving, setApproving] = useState(false);
  const [outcome, setOutcome] = useState<BulkApproval | null>(null);

  const reload = useCallback(() => {
    client.queue().then(setItems, (e: unknown) => setFailure(e instanceof Error ? e.message : String(e)));
  }, [client]);

  useScreenTitle(`${VOCAB.translations} · Review`, `Review | ${VOCAB.translations} | ${VOCAB.product}`);
  useEffect(() => {
    reload();
    workflow.running().then(setRunning, () => setRunning({}));
  }, [reload, workflow]);

  const approveSelected = async () => {
    setApproving(true);
    try {
      const r = await client.approveAll([...selected]);
      setConfirming(false);
      setSelected(new Set());
      reload();
      // reviews asked for the approved copies are decided now
      const asked = r.approved.filter((p) => running[p]);
      if (asked.length && (await workflow.close(asked).catch(() => 0))) setRunning(await workflow.running());
      if (r.skipped.length || r.failed.length) setOutcome(r);
      else ToastQueue.positive(`${r.approved.length} translation(s) approved; their texts are in the translation memory`, { timeout: 5000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setApproving(false);
    }
  };

  const requestReview = async () => {
    setRequesting(true);
    try {
      const paths = [...selected].filter((p) => !running[p]);
      await workflow.requestReview(paths);
      ToastQueue.positive(`Review requested for ${paths.length} translation(s): the reviewers find it in their Inbox`, { timeout: 5000 });
      setSelected(new Set());
      setRunning(await workflow.running());
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setRequesting(false);
    }
  };

  const languages = useMemo(() => [...new Set((items ?? []).map((i) => i.targetLanguage))].sort(), [items]);
  // new objects when the running reviews change, so the table renders them
  const rows = (items ?? [])
    .filter((i) => (state === 'all' || i.state === state) && (language === 'all' || i.targetLanguage === language) && (kind === 'all' || i.kind === kind))
    .map((i) => ({ ...i, inReview: running[i.path] }));
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const i of items ?? []) c[i.state] = (c[i.state] ?? 0) + 1;
    return c;
  }, [items]);

  return (
    <>
      <ScreenFrame
        identity={<ToolCrumbs url="/translation/review.html" standaloneControls={false} />}
        tools={<Flex gap="size-150" alignItems="end" wrap>
          <Picker aria-label="State" isQuiet selectedKey={state} onSelectionChange={(k) => setState(k as ReviewState | 'all')} width="size-2000">
            {STATES.map((s) => (
              <Item key={s.key}>{s.key === 'all' ? `All (${items?.length ?? 0})` : `${s.label} (${counts[s.key] ?? 0})`}</Item>
            ))}
          </Picker>
          <Picker aria-label="Language" isQuiet selectedKey={language} onSelectionChange={(k) => setLanguage(String(k))} width="size-1600">
            {[{ code: 'all', name: 'All' }, ...languages.map((c) => ({ code: c, name: lang(c) }))].map((l) => (
              <Item key={l.code}>{l.code === 'all' ? 'All languages' : l.name}</Item>
            ))}
          </Picker>
          {rows.length > 0 && (
            <ActionButton onPress={() => setSelected(new Set(rows.map((r) => r.path)))} isDisabled={rows.every((r) => selected.has(r.path))}>
              <Text>{`Select all (${rows.length})`}</Text>
            </ActionButton>
          )}
          <Picker aria-label="Type" isQuiet selectedKey={kind} onSelectionChange={(k) => setKind(k as QueueItem['kind'] | 'all')} width="size-1600">
            <Item key="all">All types</Item>
            <Item key="page">Pages</Item>
            <Item key="content-fragment">Content Fragments</Item>
            <Item key="experience-fragment">Experience Fragments</Item>
            <Item key="asset">Media</Item>
          </Picker>
        </Flex>}
      />
      {selected.size > 0 && (
        <div className="studio-cfc__selection" role="toolbar" aria-label="Selection">
          <Text>{selected.size} selected</Text>
          <ActionButton isQuiet onPress={() => setConfirming(true)} isDisabled={approving}>
            <Checkmark />
            <Text>{`Approve (${selected.size})`}</Text>
          </ActionButton>
          <ActionButton isQuiet onPress={() => void requestReview()} isDisabled={requesting || [...selected].every((p) => running[p])}>
            <Checkmark />
            <Text>Request review</Text>
          </ActionButton>
          <ActionButton isQuiet onPress={() => setSelected(new Set())} marginStart="auto">
            Clear
          </ActionButton>
        </div>
      )}
      <div className="studio-cfc__content">
        {failure ? (
          <ErrorState title="Could not load the translations">{failure}</ErrorState>
        ) : !items ? (
          <LoadingState />
        ) : (
          <TableView
            aria-label="Translations"
            selectionMode="multiple"
            selectionStyle="checkbox"
            selectedKeys={selected}
            onSelectionChange={(keys) => setSelected(keys === 'all' ? new Set(rows.map((r) => r.path)) : new Set([...keys].map(String)))}
            density="spacious"
            overflowMode="truncate"
            height="100%"
            onAction={(k: Key) => {
              const item = rows.find((r) => r.path === String(k));
              if (!item) return;
              if (item.kind === 'content-fragment') go(editorUrl(item.path, item.kind));
              else onOpen(item.path);
            }}
            renderEmptyState={() =>
              // nothing under this choice is not nothing at all: say where the rest is
              (items?.length ?? 0) > 0 ? (
                <EmptyState title="Nothing under this choice">
                  {items!.length} translation{items!.length === 1 ? '' : 's'} in other states or languages: choose All to see {items!.length === 1 ? 'it' : 'them'}.
                </EmptyState>
              ) : (
                <EmptyState title="Nothing here">Translations made in Pages (language versions) or the Content Fragments console show up here.</EmptyState>
              )
            }
          >
            <TableHeader>
              <Column key="title" isRowHeader width="3fr">
                Translation
              </Column>
              <Column key="kind" width="1fr">
                Type
              </Column>
              <Column key="languages" width="1.4fr">
                Languages
              </Column>
              <Column key="state" width="1.6fr">
                State
              </Column>
              <Column key="when" width="1.4fr">
                Translated
              </Column>
            </TableHeader>
            <TableBody items={rows}>
              {(i) => {
                const st = stateLight(i.state);
                return (
                  <Row key={i.path}>
                    <Cell textValue={i.title}>
                      <span className="studio-trr__title">
                        <span>{i.title}</span>
                        <span className="studio-tr-page__mono">{i.path}</span>
                      </span>
                    </Cell>
                    <Cell>{kindLabel(i.kind)}</Cell>
                    <Cell>{`${i.sourceLanguage} → ${i.targetLanguage}`}</Cell>
                    <Cell textValue={st.text}>
                      <span className="studio-trr__title">
                        <StatusLight variant={st.variant}>{st.text}</StatusLight>
                        {i.inReview && <span className="studio-cf__muted">In review{i.inReview.assignee ? ` · ${i.inReview.assignee}` : ''}</span>}
                      </span>
                    </Cell>
                    <Cell>
                      {i.translatedAt ? new Date(i.translatedAt).toLocaleString() : ''}
                      {i.translatedBy ? ` · ${i.translatedBy}` : ''}
                    </Cell>
                  </Row>
                );
              }}
            </TableBody>
          </TableView>
        )}
      </div>
      <DialogContainer onDismiss={() => setConfirming(false)}>
        {confirming && (
          <StudioDialog size="S">
            <Heading>{`Approve ${selected.size} translation${selected.size === 1 ? '' : 's'}?`}</Heading>
            <Divider />
            <Content>
              <Text>
                Their texts go into the translation memory as approved. Translations with texts not translated, whose source changed since, or
                approved already are left as they are.
              </Text>
            </Content>
            <ButtonGroup>
              <Button variant="secondary" onPress={() => setConfirming(false)}>
                Cancel
              </Button>
              <Button variant="accent" onPress={() => void approveSelected()} isPending={approving}>
                Approve
              </Button>
            </ButtonGroup>
          </StudioDialog>
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setOutcome(null)}>
        {outcome && (
          <StudioDialog>
            <Heading>{`${outcome.approved.length} approved`}</Heading>
            <Divider />
            <Content>
              <ul aria-label="Not approved" className="studio-trp__list">
                {[...outcome.skipped.map((s) => ({ path: s.path, why: s.reason })), ...outcome.failed.map((f) => ({ path: f.path, why: f.error }))].map((e) => (
                  <li key={e.path}>
                    <span>{items?.find((i) => i.path === e.path)?.title ?? e.path}</span>
                    <span className="studio-cf__muted">{e.why}</span>
                  </li>
                ))}
              </ul>
            </Content>
            <ButtonGroup>
              <Button variant="accent" onPress={() => setOutcome(null)}>
                Close
              </Button>
            </ButtonGroup>
          </StudioDialog>
        )}
      </DialogContainer>
    </>
  );
}

/** One page copy: every text next to its source, editable; Save, Translate again (chosen texts), Approve. */
export function PageReviewView({
  client,
  workflow = new HttpReviewWorkflowClient(),
  path,
  onBack,
}: {
  client: PageTranslationClient;
  workflow?: ReviewWorkflowClient;
  path: string;
  onBack(): void;
}) {
  const [inReview, setInReview] = useState<{ id: string; assignee?: string } | null>(null);
  const [review, setReview] = useState<PageReview | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<null | 'save' | 'retranslate' | 'approve'>(null);
  const [onlyAttention, setOnlyAttention] = useState(false);

  useScreenTitle(`${VOCAB.translations} · Review`, `Review | ${VOCAB.translations} | ${VOCAB.product}`);
  useEffect(() => {
    client.review(path).then(setReview, (e: unknown) => setFailure(e instanceof Error ? e.message : String(e)));
    workflow.running().then((r) => setInReview(r[path] ?? null), () => setInReview(null));
  }, [client, workflow, path]);

  const requestReview = async () => {
    try {
      await workflow.requestReview([path]);
      setInReview((await workflow.running())[path] ?? { id: '' });
      ToastQueue.positive('Review requested: the reviewers find it in their Inbox', { timeout: 4000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
  };

  const dirty = Object.keys(edits).length;
  // approved, its source unchanged and nothing edited since: there is nothing left to approve
  const approvedAsIs = !!review && review.translation.status === 'APPROVED' && !review.translation.outdated && dirty === 0;
  const segments = (review?.segments ?? []).filter((s) => !onlyAttention || s.failed || s.changed || edits[s.id] !== undefined || errors[s.id]);
  const rec = review?.translation;

  const save = async (): Promise<boolean> => {
    if (!dirty) return true;
    setBusy('save');
    setErrors({});
    try {
      const r = await client.saveReview(path, edits);
      setReview(r);
      setEdits({});
      ToastQueue.positive(`${dirty} text${dirty === 1 ? '' : 's'} saved`, { timeout: 2500 });
      return true;
    } catch (e) {
      if (e instanceof ReviewValidationError) setErrors(e.errors);
      else ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
      return false;
    } finally {
      setBusy(null);
    }
  };

  const retranslate = async () => {
    setBusy('retranslate');
    try {
      const r = await client.retranslate(path, [...selected]);
      setReview(r);
      setSelected(new Set());
      setEdits((e) => Object.fromEntries(Object.entries(e).filter(([id]) => !selected.has(id))));
      ToastQueue.positive(`${r.retranslated ?? 0} text${r.retranslated === 1 ? '' : 's'} translated again`, { timeout: 3000 });
    } catch (e) {
      const code = e instanceof PageTranslationError ? e.code : undefined;
      ToastQueue.negative(`${e instanceof Error ? e.message : String(e)}${code === 'MISSING_CREDENTIALS' ? ' (Tools → Cloud Services → Translation)' : ''}`, {
        timeout: 10000,
      });
    } finally {
      setBusy(null);
    }
  };

  const approve = async () => {
    if (!(await save())) return;
    setBusy('approve');
    try {
      await client.approve(path);
      setReview(await client.review(path));
      // a review asked for this copy is decided now: close it, so it does not wait in an Inbox
      const closed = inReview ? await workflow.close([path]).catch(() => 0) : 0;
      if (closed) setInReview(null);
      ToastQueue.positive(`Approved: its texts are now in the translation memory${closed ? '; its review is closed' : ''}`, { timeout: 4000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <ScreenFrame
        identity={<ToolCrumbs url="/translation/review.html" extra={[{ path: `review:${path}`, title: review?.title ?? path }]} onGo={() => onBack()} standaloneControls={false} />}
        tools={review ? (
          <Flex gap="size-100" alignItems="center" wrap>
            <Checkbox isSelected={onlyAttention} onChange={setOnlyAttention}>
              Only texts needing attention
            </Checkbox>
            <ActionButton onPress={() => void requestReview()} isDisabled={!!inReview || busy !== null}>
              <Text>{inReview ? `In review${inReview.assignee ? ` (${inReview.assignee})` : ''}` : 'Request review'}</Text>
            </ActionButton>
            <ActionButton onPress={() => void retranslate()} isDisabled={!selected.size || !review.canEdit || busy !== null}>
              <Globe />
              <Text>{selected.size ? `Translate again (${selected.size})` : 'Translate again'}</Text>
            </ActionButton>
            <ActionButton onPress={() => void save()} isDisabled={!dirty || !review.canEdit || busy !== null}>
              <SaveFloppy />
              <Text>{dirty ? `Save (${dirty})` : 'Save'}</Text>
            </ActionButton>
          </Flex>
        ) : undefined}
        primary={review ? <Button variant="accent" onPress={() => void approve()} isPending={busy === 'approve'} isDisabled={!review.canEdit || busy !== null || approvedAsIs}><Checkmark /><Text>{approvedAsIs ? 'Approved' : 'Approve'}</Text></Button> : undefined}
        onClose={onBack}
        closeLabel="Close translation review"
      />
      <div className="studio-cfc__content studio-trr__body">
        {failure ? (
          <ErrorState title="Could not load the review">{failure}</ErrorState>
        ) : !review || !rec ? (
          <LoadingState />
        ) : (
          <div className="studio-trr__review">
            <div className="studio-trr__summary" role="region" aria-label="Translation">
              <Text>
                <strong>{lang(rec.sourceLanguage)}</strong> → <strong>{lang(rec.targetLanguage)}</strong> · source{' '}
                <Link href={editorUrl(rec.sourcePath)}>{review.sourceTitle ?? rec.sourcePath}</Link> · copy{' '}
                <Link href={editorUrl(path)}>{review.title}</Link>
              </Text>
              <StatusLight variant={rec.status === 'CHANGES' ? 'negative' : rec.outdated ? 'info' : rec.status === 'APPROVED' ? 'positive' : 'notice'}>
                {rec.status === 'CHANGES'
                  ? 'Changes requested'
                  : rec.outdated
                    ? 'Outdated: the source changed'
                    : rec.status === 'APPROVED'
                      ? `Approved${rec.approvedBy ? ` by ${rec.approvedBy}` : ''}`
                      : 'To review'}
              </StatusLight>
              {rec.attribution && <Text UNSAFE_className="studio-cf__muted">{rec.attribution}</Text>}
              {rec.status === 'CHANGES' && (
                <InlineAlert variant="negative" UNSAFE_className="studio-trr__changes">
                  <Heading>Changes requested{rec.changesRequestedBy ? ` by ${rec.changesRequestedBy}` : ''}</Heading>
                  <Content>{rec.reviewComment ?? 'No comment.'} Correct the texts and save: the translation is then to review again.</Content>
                </InlineAlert>
              )}
              {!review.canEdit && (
                <InlineAlert variant="info">
                  <Heading>Read only</Heading>
                  <Content>You may not change this page.</Content>
                </InlineAlert>
              )}
            </div>
            <ol className="studio-trr__segments" aria-label="Texts">
              {segments.map((s) => {
                const value = edits[s.id] ?? s.translation ?? '';
                return (
                  <li key={s.id} className={`studio-trr__segment${s.failed ? ' is-failed' : ''}${s.changed ? ' is-changed' : ''}`}>
                    <div className="studio-trr__segment-head">
                      <Checkbox
                        aria-label={`Select ${s.label}`}
                        isSelected={selected.has(s.id)}
                        onChange={(on) =>
                          setSelected((sel) => {
                            const n = new Set(sel);
                            if (on) n.add(s.id);
                            else n.delete(s.id);
                            return n;
                          })
                        }
                      >
                        <span className="studio-trr__label">{s.label}</span>
                      </Checkbox>
                      {s.failed && <StatusLight variant="negative">Not translated</StatusLight>}
                      {s.changed && <StatusLight variant="info">Source changed</StatusLight>}
                      {edits[s.id] !== undefined && <StatusLight variant="notice">Edited</StatusLight>}
                      {s.html && <span className="studio-cf__muted">HTML</span>}
                    </div>
                    <div className="studio-trr__pair">
                      <div className="studio-trr__source" aria-label={`Source of ${s.label}`}>
                        {s.source}
                      </div>
                      <TextArea
                        aria-label={`Translation of ${s.label}`}
                        value={value}
                        onChange={(v) => {
                          setEdits((e) => {
                            const n = { ...e };
                            if (v === (s.translation ?? '')) delete n[s.id];
                            else n[s.id] = v;
                            return n;
                          });
                          setErrors((e) => {
                            const n = { ...e };
                            delete n[s.id];
                            return n;
                          });
                        }}
                        isReadOnly={!review.canEdit}
                        width="100%"
                        validationState={errors[s.id] ? 'invalid' : undefined}
                        errorMessage={errors[s.id]}
                        UNSAFE_className={s.html ? 'studio-cf-mono' : undefined}
                      />
                    </div>
                  </li>
                );
              })}
            </ol>
          </div>
        )}
      </div>
    </>
  );
}
