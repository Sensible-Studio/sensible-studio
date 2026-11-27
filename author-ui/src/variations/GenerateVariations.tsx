import './variations.scss';
import {
  ActionButton,
  Button,
  ComboBox,
  DialogContainer,
  Item,
  NumberField,
  Picker,
  Slider,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Copy from '@spectrum-icons/workflow/Copy';
import Delete from '@spectrum-icons/workflow/Delete';
import MagicWand from '@spectrum-icons/workflow/MagicWand';
import Star from '@spectrum-icons/workflow/Star';
import StarOutline from '@spectrum-icons/workflow/StarOutline';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { useNavigate, useScreenTitle } from '../shell/ShellContext';
import { ExportToXfDialog } from './ExportToXf';
import { ToolBar } from '../shell/ToolCrumbs';
import {
  snippet,
  variationText,
  VARIATIONS_URL,
  type Generation,
  type PromptTemplate,
  type VariationsClient,
  type VariationsInput,
  type VariationsSetup,
} from './api';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

export const EMPTY_INPUT: VariationsInput = { template: 'hero', request: '', audience: '', tone: '', language: '', page: '', count: 4, creativity: 0.7 };

/** What keeps a request from being sent, in words. */
export function inputProblems(input: VariationsInput, maxCount = 10): string[] {
  const out: string[] = [];
  if (!input.request.trim()) out.push('Say what to write about.');
  if (!(input.count >= 1 && input.count <= maxCount)) out.push(`Between 1 and ${maxCount} variations.`);
  const page = (input.page ?? '').trim();
  if (page && (!page.startsWith('/content/') || page.includes('..'))) out.push('The page is a page under /content, such as /content/wknd/us/en.');
  return out;
}

type Tab = 'results' | 'recent' | 'favorites';

/**
 * AEM's Generate Variations: copy for a page written by a language model (the model of a translation provider),
 * in a few variations to choose from. A prompt template says what is written; the author says what it is about,
 * for whom and in which tone, and may name a page to take facts from. Generations are kept per person: Recent and
 * Favorites.
 */
export function GenerateVariations({ client }: { client: VariationsClient }) {
  const [setup, setSetup] = useState<VariationsSetup | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [input, setInput] = useState<VariationsInput>(EMPTY_INPUT);
  const [tried, setTried] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [current, setCurrent] = useState<Generation | null>(null);
  const [history, setHistory] = useState<Generation[] | null>(null);
  const [tab, setTab] = useState<Tab>('results');
  const [exporting, setExporting] = useState(false);
  const navigate = useNavigate();

  useScreenTitle('Generate Variations', 'Generate Variations | Sensible Studio');

  useEffect(() => {
    client.setup().then(
      (s) => {
        setSetup(s);
        setInput((i) => ({ ...i, provider: i.provider ?? s.providers[0]?.path }));
      },
      (e: unknown) => setError(message(e)),
    );
  }, [client]);
  const loadHistory = useCallback(() => {
    client.history().then(setHistory, () => setHistory([]));
  }, [client]);
  useEffect(loadHistory, [loadHistory]);

  const template = useMemo(() => setup?.templates.find((t) => t.id === input.template) ?? setup?.templates[0], [setup, input.template]);
  const problems = inputProblems(input, setup?.maxCount);
  const set = (patch: Partial<VariationsInput>) => setInput((i) => ({ ...i, ...patch }));

  const generate = async () => {
    setTried(true);
    if (problems.length) return;
    setBusy(true);
    setFailure(null);
    try {
      const g = await client.generate({ ...input, request: input.request.trim(), page: (input.page ?? '').trim() || undefined });
      setCurrent(g);
      setTab('results');
      loadHistory();
    } catch (e) {
      setFailure(message(e));
    } finally {
      setBusy(false);
    }
  };

  const open = async (id: string) => {
    try {
      const g = await client.generation(id);
      setCurrent(g);
      setTab('results');
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 6000 });
    }
  };

  const favorite = async (index: number, on: boolean) => {
    if (!current) return;
    try {
      const g = await client.favorite(current.id, index, on);
      setCurrent(g);
      loadHistory();
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 6000 });
    }
  };

  if (error) return <ErrorState title="Generate Variations could not start">{error}</ErrorState>;
  if (!setup || !template) return <LoadingState label="Loading" />;

  const noModel = setup.providers.length === 0;
  const shown = tab === 'favorites' ? (history ?? []).filter((h) => h.favorites.length > 0) : history ?? [];

  return (
    <div className="studio-variations">
      <ToolBar url={VARIATIONS_URL} title="Generate Variations" />
      <div className="studio-variations__body">
        <form
          className="studio-variations__prompt"
          aria-label="Prompt"
          onSubmit={(e) => {
            e.preventDefault();
            void generate();
          }}
        >
          <Picker
            label="Prompt template"
            selectedKey={template.id}
            onSelectionChange={(k) => k !== null && set({ template: String(k) })}
            items={setup.templates}
            width="100%"
          >
            {(t: PromptTemplate) => (
              <Item key={t.id} textValue={t.title}>
                <Text>{t.title}</Text>
                <Text slot="description">{t.description}</Text>
              </Item>
            )}
          </Picker>
          <TextArea
            label={template.id === 'custom' ? 'What to write' : 'What is it about'}
            isRequired
            value={input.request}
            onChange={(v) => set({ request: v })}
            description="The product, offer or topic, and what must be in it: names, facts, the link it leads to"
            validationState={tried && !input.request.trim() ? 'invalid' : undefined}
            width="100%"
            height="size-1600"
          />
          <TextField
            label="Audience"
            value={input.audience ?? ''}
            onChange={(v) => set({ audience: v })}
            description="Who reads it, e.g. young travellers on a budget"
            width="100%"
          />
          <div className="studio-variations__row">
            <ComboBox
              label="Tone of voice"
              allowsCustomValue
              inputValue={input.tone ?? ''}
              onInputChange={(v) => set({ tone: v })}
              defaultItems={setup.tones.map((t) => ({ id: t }))}
              width="100%"
            >
              {(t) => <Item key={t.id}>{t.id}</Item>}
            </ComboBox>
            <TextField
              label="Language"
              value={input.language ?? ''}
              onChange={(v) => set({ language: v })}
              placeholder="As the request"
              width="100%"
            />
          </div>
          <TextField
            label="Page to take facts from"
            value={input.page ?? ''}
            onChange={(v) => set({ page: v })}
            description="Optional: its texts are sent to the model with the request"
            placeholder="/content/wknd/us/en/adventures/..."
            width="100%"
          />
          <div className="studio-variations__row">
            <NumberField
              label="Variations"
              minValue={1}
              maxValue={setup.maxCount}
              value={input.count}
              onChange={(v) => set({ count: Number.isFinite(v) ? v : 4 })}
              width="100%"
            />
            <Slider
              label="Creativity"
              minValue={0}
              maxValue={1}
              step={0.1}
              value={input.creativity}
              onChange={(v) => set({ creativity: v })}
              getValueLabel={(v) => (v < 0.35 ? 'Precise' : v > 0.75 ? 'Free' : 'Balanced')}
              width="100%"
            />
          </div>
          {setup.providers.length > 1 ? (
            <Picker
              label="Model"
              selectedKey={input.provider ?? null}
              onSelectionChange={(k) => k !== null && set({ provider: String(k) })}
              items={setup.providers}
              width="100%"
            >
              {(p) => (
                <Item key={p.path} textValue={p.title}>
                  <Text>{p.title}</Text>
                  <Text slot="description">{p.engine}</Text>
                </Item>
              )}
            </Picker>
          ) : setup.providers.length === 1 ? (
            <p className="studio-variations__muted">{`Written by ${setup.providers[0].engine}, the model of the translation provider ${setup.providers[0].title}.`}</p>
          ) : null}
          {noModel && (
            <p className="studio-variations__problem" role="alert">
              No model to write with: add a translation provider of the AI connector under Translations › Providers.
            </p>
          )}
          {tried && problems.length > 0 && (
            <ul className="studio-variations__problem">
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}
          <Button type="submit" variant="accent" isPending={busy} isDisabled={noModel}>
            <MagicWand />
            <Text>Generate</Text>
          </Button>
        </form>

        <section className="studio-variations__results" aria-label="Variations">
          <div className="studio-variations__tabs" role="tablist" aria-label="Show">
            {(
              [
                ['results', 'Results'],
                ['recent', 'Recent'],
                ['favorites', 'Favorites'],
              ] as Array<[Tab, string]>
            ).map(([id, label]) => (
              <button key={id} type="button" role="tab" aria-selected={tab === id} className={tab === id ? 'is-selected' : undefined} onClick={() => setTab(id)}>
                {label}
              </button>
            ))}
          </div>
          {tab === 'results' ? (
            failure ? (
              <ErrorState title="No variations">{failure}</ErrorState>
            ) : busy ? (
              <LoadingState label="Writing" />
            ) : current ? (
              <Results
                generation={current}
                template={setup.templates.find((t) => t.id === current.template) ?? template}
                onFavorite={(i, on) => void favorite(i, on)}
                onReuse={() => setInput({ ...EMPTY_INPUT, ...current.input, provider: input.provider })}
                onExport={() => setExporting(true)}
              />
            ) : (
              <EmptyState title="Nothing generated yet">
                Choose what to write and say what it is about: the model writes a few variations to choose from. Check facts before you use
                them.
              </EmptyState>
            )
          ) : history === null ? (
            <LoadingState label="Loading" />
          ) : shown.length === 0 ? (
            <EmptyState title={tab === 'favorites' ? 'No favorites' : 'Nothing generated yet'}>
              {tab === 'favorites' ? 'Star the variations you like: they are kept here.' : 'What you generate is kept here, for you only.'}
            </EmptyState>
          ) : (
            <ul className="studio-variations__history">
              {shown.map((h) => (
                <li key={h.id}>
                  <button type="button" className="studio-variations__history-open" onClick={() => void open(h.id)}>
                    <strong>{setup.templates.find((t) => t.id === h.template)?.title ?? h.template}</strong>
                    <span>{snippet(h.input?.request ?? '')}</span>
                    <span className="studio-variations__muted">
                      {[h.created ? new Date(h.created).toLocaleString() : '', `${h.count} variation${h.count === 1 ? '' : 's'}`, h.favorites.length ? `${h.favorites.length} ★` : '']
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  </button>
                  <TooltipTrigger delay={400}>
                    <ActionButton
                      isQuiet
                      aria-label="Delete"
                      onPress={async () => {
                        try {
                          await client.remove(h.id);
                          if (current?.id === h.id) setCurrent(null);
                          loadHistory();
                        } catch (e) {
                          ToastQueue.negative(message(e), { timeout: 6000 });
                        }
                      }}
                    >
                      <Delete />
                    </ActionButton>
                    <Tooltip>Delete</Tooltip>
                  </TooltipTrigger>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
      <DialogContainer onDismiss={() => setExporting(false)}>
        {exporting && current && (
          <ExportToXfDialog
            client={client}
            generation={current}
            template={setup.templates.find((t) => t.id === current.template) ?? template}
            onCancel={() => setExporting(false)}
            onDone={(r) => {
              setExporting(false);
              ToastQueue.positive(`Experience fragment created with ${r.variations.length} variation${r.variations.length === 1 ? '' : 's'}`, {
                timeout: 8000,
                actionLabel: 'Open',
                onAction: () => navigate(`/editor.html${r.variations[0]}.html`),
                shouldCloseOnAction: true,
              });
            }}
          />
        )}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function Results({
  generation,
  template,
  onFavorite,
  onReuse,
  onExport,
}: {
  generation: Generation;
  template: PromptTemplate;
  onFavorite(index: number, on: boolean): void;
  onReuse(): void;
  onExport(): void;
}) {
  const favs = new Set(generation.favorites);
  return (
    <>
      <header className="studio-variations__result-head">
        <div>
          <strong>{template.title}</strong>
          <span className="studio-variations__muted">
            {[snippet(generation.input?.request ?? '', 60), generation.engine].filter(Boolean).join(' · ')}
          </span>
        </div>
        <span className="studio-variations__result-tools">
          <ActionButton isQuiet onPress={onReuse}>
            Use this prompt again
          </ActionButton>
          <ActionButton onPress={onExport}>Export to Experience Fragment</ActionButton>
        </span>
      </header>
      <p className="studio-variations__muted studio-variations__note">Written by a model: check names and facts before you publish.</p>
      <ol className="studio-variations__list">
        {(generation.variations ?? []).map((v, i) => (
          <li key={i} className="studio-variations__card" aria-label={`Variation ${i + 1}`}>
            <div className="studio-variations__card-head">
              <span className="studio-variations__number">{i + 1}</span>
              <span className="studio-variations__card-tools">
                <TooltipTrigger delay={400}>
                  <ActionButton
                    isQuiet
                    aria-label={`Copy variation ${i + 1}`}
                    onPress={async () => {
                      try {
                        await navigator.clipboard.writeText(variationText(v, template.fields));
                        ToastQueue.positive('Copied', { timeout: 2000 });
                      } catch {
                        ToastQueue.negative('The browser did not allow copying', { timeout: 4000 });
                      }
                    }}
                  >
                    <Copy />
                  </ActionButton>
                  <Tooltip>Copy</Tooltip>
                </TooltipTrigger>
                <TooltipTrigger delay={400}>
                  <ActionButton
                    isQuiet
                    aria-label={favs.has(i) ? `Unstar variation ${i + 1}` : `Star variation ${i + 1}`}
                    onPress={() => onFavorite(i, !favs.has(i))}
                    UNSAFE_className={favs.has(i) ? 'is-starred' : undefined}
                  >
                    {favs.has(i) ? <Star /> : <StarOutline />}
                  </ActionButton>
                  <Tooltip>{favs.has(i) ? 'Unstar' : 'Star: keep it in Favorites'}</Tooltip>
                </TooltipTrigger>
              </span>
            </div>
            <dl>
              {template.fields.map((f) =>
                (v[f.key] ?? '').trim() ? (
                  <div key={f.key} className={`studio-variations__field is-${f.key}`}>
                    {template.fields.length > 1 && <dt>{f.label}</dt>}
                    <dd>{v[f.key]}</dd>
                  </div>
                ) : null,
              )}
            </dl>
          </li>
        ))}
      </ol>
    </>
  );
}
