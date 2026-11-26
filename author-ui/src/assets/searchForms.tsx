import './searchForms.scss';
import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  Menu,
  MenuTrigger,
  Picker,
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
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { useScreenTitle } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { DAM_ROOT, type DamClient, type SearchFacet, type SearchFacetType, type SearchForm, type SearchQuery } from './api';
import { routeUrl, type AssetsRoute } from './route';
import { BUILT_IN_FACETS, DEFAULT_FACETS, SearchRail } from './search';
import { HttpSiteRailClient, type SiteRailClient } from '../sites/rail/api';
import { PagesSearchFormPage } from '../sites/searchForm';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** What each kind of filter is called in the editor. */
export const FACET_KINDS: Record<SearchFacetType, { name: string; hint: string }> = {
  types: { name: 'File type', hint: 'Images, documents, PDF, video, audio, archives, content fragments' },
  tags: { name: 'Tags', hint: 'Tags the files have, or tags below them' },
  modified: { name: 'Modified', hint: 'Last 24 hours, week, month or year' },
  size: { name: 'File size', hint: 'Less than 1 MB, 1 to 10 MB, more than 10 MB' },
  orientation: { name: 'Orientation', hint: 'Landscape, portrait or square pictures' },
  colors: { name: 'Color', hint: 'The colors a picture is mostly made of (color tags)' },
  options: { name: 'Values of a property', hint: 'Tick one or more values of a metadata property, such as dam:status' },
  text: { name: 'Text in a property', hint: 'A text a metadata property contains, such as a name in dc:creator' },
};

const PROPERTY = /^[A-Za-z][\w.-]{0,63}(:[A-Za-z][\w.-]{0,63})?$/;

/** A filter being edited: the values of an options filter are text, one per line (`value` or `value = Label`). */
export interface DraftFacet extends SearchFacet {
  key: number;
  optionsText?: string;
}

export function optionsToText(options: SearchFacet['options']): string {
  return (options ?? []).map((o) => (o.label && o.label !== o.value ? `${o.value} = ${o.label}` : o.value)).join('\n');
}

export function textToOptions(text: string): Array<{ value: string; label: string }> {
  const seen = new Set<string>();
  const out: Array<{ value: string; label: string }> = [];
  for (const line of text.split('\n')) {
    const i = line.indexOf('=');
    const value = (i > 0 ? line.slice(0, i) : line).trim();
    const label = (i > 0 ? line.slice(i + 1) : '').trim();
    if (!value || seen.has(value)) continue;
    seen.add(value);
    out.push({ value, label: label || value });
  }
  return out;
}

/** The filters as the server takes them. */
export function toFacets(drafts: DraftFacet[]): SearchFacet[] {
  return drafts.map(({ key: _k, optionsText, ...f }) => {
    const out: SearchFacet = { type: f.type, label: f.label.trim() };
    if (f.description?.trim()) out.description = f.description.trim();
    if (f.type === 'tags' && f.root?.trim()) out.root = f.root.trim();
    if (f.type === 'options' || f.type === 'text') out.property = (f.property ?? '').trim();
    if (f.type === 'options') out.options = textToOptions(optionsText ?? '');
    return out;
  });
}

/** What keeps the form from being saved, in words. */
export function facetProblems(drafts: DraftFacet[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  drafts.forEach((f, i) => {
    const name = f.label.trim() || `Filter ${i + 1}`;
    if (!f.label.trim()) out.push(`Filter ${i + 1} needs a label.`);
    if (f.type === 'options' || f.type === 'text') {
      const p = (f.property ?? '').trim();
      if (!PROPERTY.test(p)) out.push(`${name}: “${p}” is not a metadata property (use names such as dc:creator).`);
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
const draftsOf = (facets: SearchFacet[]): DraftFacet[] =>
  facets.map((f) => ({ ...f, key: ++keys, ...(f.type === 'options' ? { optionsText: optionsToText(f.options) } : {}) }));

const isDefault = (facets: SearchFacet[]) => JSON.stringify(facets) === JSON.stringify(DEFAULT_FACETS);

/**
 * AEM's Tools › General › Search Forms, for the search of Media (AEM's "Assets Admin Search Rail"): the filters the
 * search shows, in order. The five built in can be renamed, moved or removed; filters on a metadata property can be
 * added. Reset to default goes back to the five.
 */
export function SearchFormsPage({
  client,
  navigate,
  form: which,
  siteRail,
}: {
  client: DamClient;
  navigate(route: AssetsRoute): void;
  /** 'sites': the Filter rail of Pages */
  form?: 'sites';
  siteRail?: SiteRailClient;
}) {
  const rail = useMemo(() => siteRail ?? new HttpSiteRailClient(), [siteRail]);
  const switcher = <FormSwitch value={which ?? 'assets'} onChange={(f) => navigate(f === 'sites' ? { kind: 'searchforms', form: 'sites' } : { kind: 'searchforms' })} />;
  if (which === 'sites') return <PagesSearchFormPage rail={rail} switcher={switcher} url={routeUrl({ kind: 'searchforms', form: 'sites' })} />;
  return <MediaSearchFormPage client={client} switcher={switcher} />;
}

/** AEM's list of search forms, as a choice above the editor: Media's (Assets Admin Search Rail) or Pages' (Sites Admin Search Rail). */
function FormSwitch({ value, onChange }: { value: 'assets' | 'sites'; onChange(form: 'assets' | 'sites'): void }) {
  return (
    <Picker
      label="Search form"
      labelPosition="side"
      selectedKey={value}
      onSelectionChange={(k) => k !== value && onChange(k as 'assets' | 'sites')}
      UNSAFE_className="studio-searchforms__switch"
    >
      <Item key="assets">Media search</Item>
      <Item key="sites">Pages search</Item>
    </Picker>
  );
}

function MediaSearchFormPage({ client, switcher }: { client: DamClient; switcher: ReactNode }) {
  const [form, setForm] = useState<SearchForm | null>(null);
  const [drafts, setDrafts] = useState<DraftFacet[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [preview, setPreview] = useState<SearchQuery>({});
  const [focus, setFocus] = useState<number | null>(null);

  useScreenTitle('Search Forms', 'Search Forms | Sensible Studio');
  const take = useCallback((f: SearchForm) => {
    setForm(f);
    setDrafts(draftsOf(f.facets));
    setPreview({});
  }, []);
  const load = useCallback(() => {
    setError(null);
    client.searchForm().then(take, (e: unknown) => setError(message(e)));
  }, [client, take]);
  useEffect(load, [load]);

  const problems = facetProblems(drafts);
  const facets = useMemo(() => toFacets(drafts), [drafts]);
  const dirty = !!form && JSON.stringify(facets) !== JSON.stringify(form.facets);
  const missing = BUILT_IN_FACETS.filter((t) => !drafts.some((d) => d.type === t));
  const menu = [
    { title: 'On a metadata property', items: (['options', 'text'] as SearchFacetType[]).map((id) => ({ id })) },
    ...(missing.length ? [{ title: 'Built in', items: missing.map((id) => ({ id })) }] : []),
  ];

  const change = (key: number, patch: Partial<DraftFacet>) => setDrafts((ds) => ds.map((d) => (d.key === key ? { ...d, ...patch } : d)));
  const move = (i: number, by: number) =>
    setDrafts((ds) => {
      const next = [...ds];
      const [d] = next.splice(i, 1);
      next.splice(i + by, 0, d);
      return next;
    });
  const add = (type: SearchFacetType) => {
    const key = ++keys;
    const label = BUILT_IN_FACETS.includes(type) ? FACET_KINDS[type].name : '';
    setDrafts((ds) => [...ds, { key, type, label, ...(type === 'options' ? { property: '', optionsText: '' } : type === 'text' ? { property: '' } : {}) }]);
    setFocus(key);
  };

  const save = async () => {
    setBusy(true);
    setSaveError(null);
    try {
      take(await client.saveSearchForm(facets));
      ToastQueue.positive('Search form saved. Searches in Media show these filters now.', { timeout: 4000 });
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
        url={routeUrl({ kind: 'searchforms' })}
        end={
          <>
            <Button variant="secondary" style="outline" isDisabled={!dirty || busy} onPress={() => take(form)}>
              Cancel
            </Button>
            <TooltipTrigger delay={400}>
              <Button variant="secondary" style="outline" isDisabled={busy || (!form.custom && isDefault(facets))} onPress={() => setResetting(true)}>
                Reset to default
              </Button>
              <Tooltip>File type, Tags, Modified, File size, Orientation, Color</Tooltip>
            </TooltipTrigger>
          </>
        }
        primary={
          <Button variant="accent" isDisabled={!dirty || problems.length > 0 || busy} isPending={busy} onPress={() => void save()}>
            Save
          </Button>
        }
      />
      <div className="studio-searchforms__body">
        <main className="studio-searchforms__editor">
          {switcher}
          <header className="studio-searchforms__head">
            <h1>Media search</h1>
            <p className="studio-assets__muted">
              The filters a search in Media shows beside its results, in this order.{' '}
              {form.custom
                ? `Changed${form.modifiedBy ? ` by ${form.modifiedBy}` : ''}${form.modified ? ` on ${new Date(form.modified).toLocaleString()}` : ''}.`
                : 'This is the default form.'}
            </p>
          </header>
          {drafts.length === 0 && <p className="studio-searchforms__empty">No filters: the search shows only the search field. Add one below.</p>}
          <ol className="studio-searchforms__list">
            {drafts.map((d, i) => (
              <FacetEditor
                key={d.key}
                draft={d}
                index={i}
                count={drafts.length}
                autoFocus={focus === d.key}
                client={client}
                onChange={(patch) => change(d.key, patch)}
                onMove={(by) => move(i, by)}
                onRemove={() => setDrafts((ds) => ds.filter((x) => x.key !== d.key))}
              />
            ))}
          </ol>
          <MenuTrigger>
            <ActionButton>
              <Add />
              <Text>Add filter</Text>
            </ActionButton>
            <Menu items={menu} onAction={(k) => add(k as SearchFacetType)}>
              {(sec) => (
                <Section key={sec.title} title={sec.title} items={sec.items}>
                  {(it) => (
                    <Item key={it.id} textValue={FACET_KINDS[it.id].name}>
                      <Text>{FACET_KINDS[it.id].name}</Text>
                      <Text slot="description">{FACET_KINDS[it.id].hint}</Text>
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
          <p className="studio-assets__muted">The filters as a search shows them. Try them here: nothing is searched.</p>
          <SearchRail facets={facets} query={preview} scopeTitle="Media" onChange={setPreview} />
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
              client.resetSearchForm().then(
                (f) => {
                  take(f);
                  ToastQueue.positive('The search shows the default filters again.', { timeout: 4000 });
                },
                (e: unknown) => setSaveError(message(e)),
              )
            }
          >
            Searches in Media show the default filters again: File type, Tags, Modified, File size, Orientation and Color. The filters added
            here are removed.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function FacetEditor({
  draft,
  index,
  count,
  autoFocus,
  client,
  onChange,
  onMove,
  onRemove,
}: {
  draft: DraftFacet;
  index: number;
  count: number;
  autoFocus: boolean;
  client: DamClient;
  onChange(patch: Partial<DraftFacet>): void;
  onMove(by: number): void;
  onRemove(): void;
}) {
  const kind = FACET_KINDS[draft.type];
  const [reading, setReading] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const ref = useRef<HTMLLIElement>(null);
  useEffect(() => {
    if (autoFocus) ref.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
  }, [autoFocus]);
  const property = (draft.property ?? '').trim();

  const readValues = async () => {
    setReading(true);
    setNote(null);
    try {
      const r = await client.metadataValues(DAM_ROOT, property);
      if (r.values.length === 0) {
        setNote(`No file in Media has ${property}.`);
        return;
      }
      const have = textToOptions(draft.optionsText ?? '');
      const known = new Set(have.map((o) => o.value));
      const added = r.values.filter((v) => !known.has(v.value)).slice(0, Math.max(0, 50 - have.length));
      onChange({ optionsText: [draft.optionsText?.trim(), ...added.map((v) => v.value)].filter(Boolean).join('\n') });
      setNote(
        added.length === 0
          ? 'Every value the files have is listed already.'
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
        {(draft.type === 'options' || draft.type === 'text') && (
          <TextField
            label="Property"
            isRequired
            value={draft.property ?? ''}
            onChange={(v) => onChange({ property: v })}
            description="In the files' metadata, such as dc:creator or dam:status"
            autoFocus={autoFocus}
            width="100%"
          />
        )}
        <TextField
          label="Label"
          isRequired
          value={draft.label}
          onChange={(v) => onChange({ label: v })}
          autoFocus={autoFocus && draft.type !== 'options' && draft.type !== 'text'}
          width="100%"
        />
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
              description="One per line; value = Label shows another text, such as approved = Approved"
              width="100%"
              height="size-1600"
            />
            <div className="studio-searchforms__read">
              <ActionButton isDisabled={!PROPERTY.test(property) || reading} onPress={() => void readValues()}>
                <Download />
                <Text>{reading ? 'Reading…' : 'Read the values from the files'}</Text>
              </ActionButton>
              {note && <Text UNSAFE_className="studio-assets__muted">{note}</Text>}
            </div>
          </div>
        )}
      </div>
    </li>
  );
}
