import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  Menu,
  MenuTrigger,
  SearchField,
  Section,
  Text,
  TextArea,
  TextField,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronUp from '@spectrum-icons/workflow/ChevronUp';
import Delete from '@spectrum-icons/workflow/Delete';
import Download from '@spectrum-icons/workflow/Download';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { optionsToText, textToOptions } from '../assets/searchForms';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { useScreenTitle } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import {
  BUILT_IN_PAGE_FACETS,
  DEFAULT_PAGE_FACETS,
  emptyCriteria,
  PAGE_FACET_NAMES,
  type FilterCriteria,
  type PageFacet,
  type PageFacetType,
  type PageSearchForm,
  type SiteRailClient,
} from './rail/api';
import { PageFacets } from './rail/PageFacets';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** What each kind of filter of the Pages search is called in the editor. */
export const PAGE_FACET_KINDS: Record<PageFacetType, { name: string; hint: string }> = {
  path: { name: 'Path', hint: 'Where to search: the pages below a path' },
  template: { name: 'Template', hint: 'The template the pages are made from' },
  tags: { name: 'Tags', hint: 'Tags the pages have' },
  modified: { name: 'Modified', hint: 'Last 24 hours, week, month or year' },
  livecopy: { name: 'Linked copy', hint: 'Linked copies of a source, or not' },
  locked: { name: 'Locked', hint: 'Only the pages someone locked' },
  options: { name: 'Values of a property', hint: 'Tick one or more values of a page property, such as a region' },
  text: { name: 'Text in a property', hint: 'A text a page property contains, such as words in pageTitle' },
};

const PROPERTY = /^[A-Za-z][\w.-]{0,63}(:[A-Za-z][\w.-]{0,63})?$/;

export interface PageDraft extends PageFacet {
  key: number;
  optionsText?: string;
}

/** The filters as the server takes them. */
export function toPageFacets(drafts: PageDraft[]): PageFacet[] {
  return drafts.map(({ key: _k, optionsText, ...f }) => {
    const out: PageFacet = { type: f.type, label: f.label.trim() };
    if (f.description?.trim()) out.description = f.description.trim();
    if (f.type === 'tags' && f.root?.trim()) out.root = f.root.trim();
    if (f.type === 'options' || f.type === 'text') out.property = (f.property ?? '').trim();
    if (f.type === 'options') out.options = textToOptions(optionsText ?? '');
    return out;
  });
}

/** What keeps the form from being saved, in words. */
export function pageFacetProblems(drafts: PageDraft[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  drafts.forEach((f, i) => {
    const name = f.label.trim() || `Filter ${i + 1}`;
    if (!f.label.trim()) out.push(`Filter ${i + 1} needs a label.`);
    if (f.type === 'options' || f.type === 'text') {
      const p = (f.property ?? '').trim();
      if (!PROPERTY.test(p)) out.push(`${name}: “${p}” is not a page property (use names such as pageTitle or cq:productId).`);
      else if (seen.has(`${f.type} ${p}`)) out.push(`Two filters of the same kind on ${p}.`);
      seen.add(`${f.type} ${p}`);
    }
    if (f.type === 'options') {
      const n = textToOptions(f.optionsText ?? '').length;
      if (n === 0) out.push(`${name}: give the values to choose, one per line.`);
      if (n > 50) out.push(`${name}: at most 50 values.`);
    }
    if (f.type === 'tags' && f.root?.trim() && !/^[\w.-]+:([\w.-]+(\/[\w.-]+)*)?$/.test(f.root.trim()))
      out.push(`${name}: start at a tag ID, such as wknd: or wknd:activity.`);
  });
  if (drafts.length > 30) out.push('At most 30 filters.');
  return out;
}

let keys = 0;
const draftsOf = (facets: PageFacet[]): PageDraft[] =>
  facets.map((f) => ({ ...f, key: ++keys, ...(f.type === 'options' ? { optionsText: optionsToText(f.options) } : {}) }));

const isDefault = (facets: PageFacet[]) => JSON.stringify(facets) === JSON.stringify(DEFAULT_PAGE_FACETS);

/**
 * AEM's Search Forms › "Sites Admin Search Rail": the filters of the Pages console's Filter rail, in order. The six
 * built in can be renamed, moved or removed; filters on a page property can be added. Reset to default goes back to
 * the six. The full-text field is always there.
 */
export function PagesSearchFormPage({ rail, switcher, url }: { rail: SiteRailClient; switcher?: ReactNode; url: string }) {
  const [form, setForm] = useState<PageSearchForm | null>(null);
  const [drafts, setDrafts] = useState<PageDraft[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [preview, setPreview] = useState<FilterCriteria>(() => emptyCriteria('/content'));
  const [templates, setTemplates] = useState<Array<{ path: string; title: string }>>([]);
  const [focus, setFocus] = useState<number | null>(null);

  useScreenTitle('Search Forms', 'Search Forms | Sensible Studio');
  const take = useCallback((f: PageSearchForm) => {
    setForm(f);
    setDrafts(draftsOf(f.facets));
    setPreview(emptyCriteria('/content'));
  }, []);
  const load = useCallback(() => {
    setError(null);
    rail.searchForm().then(take, (e: unknown) => setError(message(e)));
  }, [rail, take]);
  useEffect(load, [load]);
  useEffect(() => {
    rail.templates('/content').then(setTemplates, () => setTemplates([]));
  }, [rail]);

  const problems = pageFacetProblems(drafts);
  const facets = useMemo(() => toPageFacets(drafts), [drafts]);
  const dirty = !!form && JSON.stringify(facets) !== JSON.stringify(form.facets);
  const missing = BUILT_IN_PAGE_FACETS.filter((t) => !drafts.some((d) => d.type === t));
  const menu = [
    { title: 'On a page property', items: (['options', 'text'] as PageFacetType[]).map((id) => ({ id })) },
    ...(missing.length ? [{ title: 'Built in', items: missing.map((id) => ({ id })) }] : []),
  ];
  const canEdit = form?.canEdit !== false;

  const change = (key: number, patch: Partial<PageDraft>) => setDrafts((ds) => ds.map((d) => (d.key === key ? { ...d, ...patch } : d)));
  const move = (i: number, by: number) =>
    setDrafts((ds) => {
      const next = [...ds];
      const [d] = next.splice(i, 1);
      next.splice(i + by, 0, d);
      return next;
    });
  const add = (type: PageFacetType) => {
    const key = ++keys;
    const label = BUILT_IN_PAGE_FACETS.includes(type) ? PAGE_FACET_NAMES[type] : '';
    setDrafts((ds) => [...ds, { key, type, label, ...(type === 'options' ? { property: '', optionsText: '' } : type === 'text' ? { property: '' } : {}) }]);
    setFocus(key);
  };

  const save = async () => {
    setBusy(true);
    setSaveError(null);
    try {
      take(await rail.saveSearchForm(facets));
      ToastQueue.positive('Search form saved. The Filter rail of Pages shows these filters now.', { timeout: 4000 });
    } catch (e) {
      setSaveError(message(e));
    } finally {
      setBusy(false);
    }
  };

  if (error) return <ErrorState title="Could not read the search form">{error}</ErrorState>;
  if (!form) return <LoadingState label="Loading the search form" />;

  return (
    <div className="studio-assets__profiles studio-searchforms">
      <ToolBar
        url={url}
        title="Search Forms"
        end={
          <>
            <Button variant="secondary" style="outline" isDisabled={!dirty || busy} onPress={() => take(form)}>
              Cancel
            </Button>
            <TooltipTrigger delay={400}>
              <Button variant="secondary" style="outline" isDisabled={!canEdit || busy || (!form.custom && isDefault(facets))} onPress={() => setResetting(true)}>
                Reset to default
              </Button>
              <Tooltip>Path, Template, Tags, Modified, Linked copy, Locked</Tooltip>
            </TooltipTrigger>
          </>
        }
        primary={
          <Button variant="accent" isDisabled={!canEdit || !dirty || problems.length > 0 || busy} isPending={busy} onPress={() => void save()}>
            Save
          </Button>
        }
      />
      <div className="studio-searchforms__body">
        <main className="studio-searchforms__editor">
          {switcher}
          <header className="studio-searchforms__head">
            <h1>Pages search</h1>
            <p className="studio-assets__muted">
              The filters of the Filter rail in Pages, in this order, under the full-text field.{' '}
              {form.custom
                ? `Changed${form.modifiedBy ? ` by ${form.modifiedBy}` : ''}${form.modified ? ` on ${new Date(form.modified).toLocaleString()}` : ''}.`
                : 'This is the default form.'}
              {!canEdit && ' Only admin can change it.'}
            </p>
          </header>
          {drafts.length === 0 && <p className="studio-searchforms__empty">No filters: the rail shows only the full-text field. Add one below.</p>}
          <ol className="studio-searchforms__list">
            {drafts.map((d, i) => (
              <PageFacetEditor
                key={d.key}
                draft={d}
                index={i}
                count={drafts.length}
                autoFocus={focus === d.key}
                rail={rail}
                onChange={(patch) => change(d.key, patch)}
                onMove={(by) => move(i, by)}
                onRemove={() => setDrafts((ds) => ds.filter((x) => x.key !== d.key))}
              />
            ))}
          </ol>
          <MenuTrigger>
            <ActionButton isDisabled={!canEdit}>
              <Add />
              <Text>Add filter</Text>
            </ActionButton>
            <Menu items={menu} onAction={(k) => add(k as PageFacetType)}>
              {(sec) => (
                <Section key={sec.title} title={sec.title} items={sec.items}>
                  {(it) => (
                    <Item key={it.id} textValue={PAGE_FACET_KINDS[it.id].name}>
                      <Text>{PAGE_FACET_KINDS[it.id].name}</Text>
                      <Text slot="description">{PAGE_FACET_KINDS[it.id].hint}</Text>
                    </Item>
                  )}
                </Section>
              )}
            </Menu>
          </MenuTrigger>
          {(problems.length > 0 || saveError) && (
            <ul className="studio-assets__dialog-error" role="alert">
              {saveError && <li>{saveError}</li>}
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}
        </main>
        <aside className="studio-searchforms__preview" aria-label="Preview">
          <h2>Preview</h2>
          <p className="studio-assets__muted">The Filter rail as Pages shows it. Try the filters here: nothing is searched.</p>
          <div className="studio-filter">
            <SearchField label="Full text" value={preview.text} onChange={(text) => setPreview((p) => ({ ...p, text }))} width="100%" />
            <PageFacets
              facets={facets}
              criteria={preview}
              onChange={(patch) => setPreview((p) => ({ ...p, ...patch }))}
              templates={templates}
              onPickTag={() => undefined}
            />
          </div>
        </aside>
      </div>
      <DialogContainer onDismiss={() => setResetting(false)}>
        {resetting && (
          <StudioConfirmDialog
            title="Reset to default"
            variant="destructive"
            primaryActionLabel="Reset"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              rail.resetSearchForm().then(
                (f) => {
                  take(f);
                  ToastQueue.positive('The Filter rail of Pages shows the default filters again.', { timeout: 4000 });
                },
                (e: unknown) => setSaveError(message(e)),
              )
            }
          >
            The Filter rail of Pages shows the default filters again: Path, Template, Tags, Modified, Linked copy and Locked. The filters
            added here are removed.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function PageFacetEditor({
  draft,
  index,
  count,
  autoFocus,
  rail,
  onChange,
  onMove,
  onRemove,
}: {
  draft: PageDraft;
  index: number;
  count: number;
  autoFocus: boolean;
  rail: SiteRailClient;
  onChange(patch: Partial<PageDraft>): void;
  onMove(by: number): void;
  onRemove(): void;
}) {
  const kind = PAGE_FACET_KINDS[draft.type];
  const [reading, setReading] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const ref = useRef<HTMLLIElement>(null);
  useEffect(() => {
    if (autoFocus) ref.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  }, [autoFocus]);
  const property = (draft.property ?? '').trim();
  const custom = draft.type === 'options' || draft.type === 'text';

  const readValues = async () => {
    setReading(true);
    setNote(null);
    try {
      const r = await rail.propertyValues('/content', property);
      if (r.values.length === 0) {
        setNote(`No page has ${property}.`);
        return;
      }
      const have = textToOptions(draft.optionsText ?? '');
      const known = new Set(have.map((o) => o.value));
      const added = r.values.filter((v) => !known.has(v.value)).slice(0, Math.max(0, 50 - have.length));
      onChange({ optionsText: [draft.optionsText?.trim(), ...added.map((v) => v.value)].filter(Boolean).join('\n') });
      setNote(
        added.length === 0
          ? 'Every value the pages have is listed already.'
          : `${added.length} value${added.length === 1 ? '' : 's'} added, the most used first${r.more ? '; there are more' : ''}.`,
      );
    } catch (e) {
      setNote(message(e));
    } finally {
      setReading(false);
    }
  };

  return (
    <li ref={ref} className="studio-searchforms__facet" aria-label={`Filter ${index + 1}: ${draft.label || kind.name}`}>
      <div className="studio-searchforms__facet-head">
        <span className="studio-searchforms__kind">{kind.name}</span>
        <span className="studio-searchforms__tools">
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label={`Move ${draft.label || kind.name} up`} isDisabled={index === 0} onPress={() => onMove(-1)}>
              <ChevronUp />
            </ActionButton>
            <Tooltip>Move up</Tooltip>
          </TooltipTrigger>
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label={`Move ${draft.label || kind.name} down`} isDisabled={index === count - 1} onPress={() => onMove(1)}>
              <ChevronDown />
            </ActionButton>
            <Tooltip>Move down</Tooltip>
          </TooltipTrigger>
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label={`Remove ${draft.label || kind.name}`} onPress={onRemove}>
              <Delete />
            </ActionButton>
            <Tooltip>Remove</Tooltip>
          </TooltipTrigger>
        </span>
      </div>
      <div className="studio-searchforms__fields">
        {custom && (
          <TextField
            label="Property"
            isRequired
            value={draft.property ?? ''}
            onChange={(v) => onChange({ property: v })}
            description="Of the page (its jcr:content), such as pageTitle or cq:productId"
            autoFocus={autoFocus}
            width="100%"
          />
        )}
        <TextField label="Label" isRequired value={draft.label} onChange={(v) => onChange({ label: v })} autoFocus={autoFocus && !custom} width="100%" />
        <TextField label="Description" value={draft.description ?? ''} onChange={(v) => onChange({ description: v })} width="100%" />
        {draft.type === 'tags' && (
          <TextField
            label="Start at tag"
            value={draft.root ?? ''}
            onChange={(v) => onChange({ root: v })}
            description="The tag picker opens there, such as wknd: (all tags when empty)"
            width="100%"
          />
        )}
        {draft.type === 'options' && (
          <div className="studio-searchforms__options">
            <TextArea
              label="Values"
              isRequired
              value={draft.optionsText ?? ''}
              onChange={(v) => onChange({ optionsText: v })}
              description="One per line; value = Label shows another text, such as eu = Europe"
              width="100%"
              height="size-1600"
            />
            <div className="studio-searchforms__read">
              <ActionButton isDisabled={!PROPERTY.test(property) || reading} onPress={() => void readValues()}>
                <Download />
                <Text>{reading ? 'Reading…' : 'Read the values from the pages'}</Text>
              </ActionButton>
              {note && <Text UNSAFE_className="studio-assets__muted">{note}</Text>}
            </div>
          </div>
        )}
      </div>
    </li>
  );
}
