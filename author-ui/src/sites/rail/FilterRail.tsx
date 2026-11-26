import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  Picker,
  ProgressCircle,
  SearchField,
  Text,
  TextField,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Delete from '@spectrum-icons/workflow/Delete';
import SaveFloppy from '@spectrum-icons/workflow/SaveFloppy';
import Download from '@spectrum-icons/workflow/Download';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { StudioClient, SiteItem } from '../../editor/api/StudioClient';
import { HttpTagsClient, TAG_ROOT, type TagsClient } from '../../tags/api';
import { TagPicker } from '../../tags/TagPicker';
import { ListView } from '../views';
import {
  DEFAULT_PAGE_FACETS,
  emptyCriteria,
  isFiltering,
  resultsCsv,
  sameCriteria,
  tagIdOf,
  type FilterCriteria,
  type PageFacet,
  type SavedSearch,
  type SearchResults,
  type SiteRailClient,
} from './api';
import { StudioDialog } from '../../shell/StudioDialog';
import { PageFacets } from './PageFacets';

interface Props {
  rail: SiteRailClient;
  criteria: FilterCriteria;
  onCriteria(next: FilterCriteria): void;
  /** the results of the criteria (null: no filter set, the console shows its normal view) */
  onResults(results: SearchResults | null): void;
  tags?: TagsClient;
}

/**
 * The Filter rail: full text, then the filters of the search form (Search Forms: path, template, tags, modified
 * date, live copy and lock by default, filters on page properties when an admin added them). While any filter is
 * set the console lists the matching pages instead of its normal view.
 */
export function FilterRail({ rail, criteria, onCriteria, onResults, tags }: Props) {
  const [templates, setTemplates] = useState<Array<{ path: string; title: string }>>([]);
  const [picking, setPicking] = useState<string | null>(null);
  const [form, setForm] = useState<PageFacet[]>(DEFAULT_PAGE_FACETS);
  const tagsClient = useMemo(() => tags ?? new HttpTagsClient(), [tags]);
  const latest = useRef(0);
  const set = (patch: Partial<FilterCriteria>) => onCriteria({ ...criteria, ...patch });
  const [saved, setSaved] = useState<SavedSearch[]>([]);
  const [naming, setNaming] = useState(false);
  const [savedError, setSavedError] = useState<string | null>(null);
  const chosen = saved.find((s) => sameCriteria(s.criteria, criteria));

  useEffect(() => {
    rail.savedSearches().then(setSaved, () => setSaved([]));
  }, [rail]);

  // the filters as Search Forms lays them out; the default ones until it answers (or when it cannot)
  useEffect(() => {
    let live = true;
    rail.searchForm().then(
      (f) => live && setForm(f.facets),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [rail]);

  const save = async (name: string) => {
    const s = await rail.saveSearch(name, criteria);
    setSaved((list) => [...list.filter((x) => x.name !== s.name), s].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })));
  };

  const remove = async (name: string) => {
    setSavedError(null);
    try {
      await rail.deleteSearch(name);
      setSaved((list) => list.filter((x) => x.name !== name));
    } catch (e) {
      setSavedError(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    rail.templates(criteria.path).then(setTemplates, () => setTemplates([]));
  }, [rail, criteria.path]);

  useEffect(() => {
    if (!isFiltering(criteria)) {
      onResults(null);
      return;
    }
    // typing waits for a pause; only the answer to the latest criteria is shown
    const run = ++latest.current;
    const timer = window.setTimeout(() => {
      rail.search(criteria).then(
        (r) => run === latest.current && onResults(r),
        () => run === latest.current && onResults({ total: 0, truncated: false, items: [] }),
      );
    }, 250);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rail, criteria]);

  return (
    <div className="studio-filter">
      <div className="studio-filter__saved">
        <Picker
          label="Saved Searches"
          placeholder={saved.length ? 'Choose a search' : 'No saved searches'}
          items={saved}
          selectedKey={chosen?.name ?? null}
          onSelectionChange={(k) => {
            const s = saved.find((x) => x.name === k);
            if (s) onCriteria(s.criteria);
          }}
          isDisabled={!saved.length}
          width="100%"
        >
          {(s) => <Item key={s.name}>{s.name}</Item>}
        </Picker>
        <div className="studio-filter__savedactions">
          <ActionButton onPress={() => setNaming(true)} isDisabled={!isFiltering(criteria)}>
            <SaveFloppy />
            <Text>Save Search</Text>
          </ActionButton>
          {chosen && (
            <ActionButton isQuiet aria-label={`Delete saved search ${chosen.name}`} onPress={() => void remove(chosen.name)}>
              <Delete />
            </ActionButton>
          )}
        </div>
        {savedError && (
          <p className="studio-rail__error" role="alert">
            {savedError}
          </p>
        )}
      </div>
      <SearchField label="Full text" value={criteria.text} onChange={(text) => set({ text })} width="100%" />
      <PageFacets facets={form} criteria={criteria} onChange={set} templates={templates} onPickTag={(start) => setPicking(start ?? TAG_ROOT)} />
      <Button variant="secondary" onPress={() => onCriteria(emptyCriteria(criteria.path))} isDisabled={!isFiltering(criteria)}>
        Clear Filters
      </Button>
      <DialogContainer onDismiss={() => setNaming(false)}>
        {naming && <SaveSearchDialog initial={chosen?.name ?? criteria.text.trim()} taken={saved.map((s) => s.name)} onSave={save} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setPicking(null)}>
        {picking && (
          <TagPicker
            client={tagsClient}
            title="Filter by Tag"
            start={picking}
            onPick={(p) => {
              setPicking(null);
              const id = tagIdOf(p);
              if (!criteria.tags.includes(id)) set({ tags: [...criteria.tags, id] });
            }}
          />
        )}
      </DialogContainer>
    </div>
  );
}

/** Names the current filters to keep them as a saved search. */
function SaveSearchDialog({ initial, taken, onSave }: { initial: string; taken: string[]; onSave(name: string): Promise<void> }) {
  const container = useDialogContainer();
  const [name, setName] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const trimmed = name.trim();
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await onSave(trimmed);
      container.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog title="Save Search" size="S" onCancel={container.dismiss} primaryLabel="Save" onPrimary={() => void submit()} primaryDisabled={!trimmed} busy={busy}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (trimmed && !busy) void submit();
          }}
        >
          <TextField label="Name" value={name} onChange={setName} maxLength={100} autoFocus width="100%" />
        </form>
        {taken.includes(trimmed) && <p className="studio-rail__muted">Replaces the saved search of that name.</p>}
        {error && (
          <p className="studio-rail__error" role="alert">
            {error}
          </p>
        )}
    </StudioDialog>
  );
}

interface ResultsProps {
  client: StudioClient;
  results: SearchResults | null;
  selected: string[];
  onOpen(item: SiteItem): void;
  onToggle(item: SiteItem): void;
}

/** The console's content while filtering: the matching pages as a list, with a CSV report. */
export function FilterResults({ client, results, selected, onOpen, onToggle }: ResultsProps) {
  const download = () => {
    if (!results) return;
    const url = URL.createObjectURL(new Blob([resultsCsv(results.items)], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'sites-report.csv';
    a.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return (
    <div className="studio-filter__results">
      <div className="studio-filter__resultsbar">
        <strong>Search Results</strong>
        <span className="studio-rail__muted">{results ? `${results.items.length} of ${results.total}${results.truncated ? '+' : ''}` : ''}</span>
        <ActionButton isQuiet onPress={download} isDisabled={!results?.items.length}>
          <Download />
          <Text>CSV Report</Text>
        </ActionButton>
      </div>
      {!results ? (
        <div className="studio-sites__loading">
          <ProgressCircle isIndeterminate aria-label="Searching" />
        </div>
      ) : results.items.length === 0 ? (
        <p className="studio-sites__empty">No page matches the filters.</p>
      ) : (
        <ListView client={client} items={results.items} selected={selected} onOpen={onOpen} onToggle={onToggle} />
      )}
    </div>
  );
}
