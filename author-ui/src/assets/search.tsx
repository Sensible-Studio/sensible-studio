import { Checkbox, Radio, RadioGroup, TextField } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { SearchFacet, SearchFacetType, SearchQuery } from './api';
import { COLORS_PROPERTY, ColorFacet } from './colors';
import { AssetTagsField } from './tagsField';

const TYPES: Array<[string, string]> = [
  ['image', 'Images'],
  ['document', 'Documents'],
  ['pdf', 'PDF'],
  ['video', 'Video'],
  ['audio', 'Audio'],
  ['archive', 'Archives'],
  ['fragment', 'Content Fragments'],
];

const MODIFIED: Array<[string, string]> = [
  ['', 'Any time'],
  ['1d', 'Last 24 hours'],
  ['7d', 'Last week'],
  ['30d', 'Last month'],
  ['365d', 'Last year'],
];

const SIZES: Array<[string, string]> = [
  ['', 'Any size'],
  ['small', 'Less than 1 MB'],
  ['medium', '1 to 10 MB'],
  ['large', 'More than 10 MB'],
];

const ORIENTATIONS: Array<[string, string]> = [
  ['', 'Any'],
  ['landscape', 'Landscape'],
  ['portrait', 'Portrait'],
  ['square', 'Square'],
];

/** The default search form: the filters the Media search shows when no form was saved (as the server's). */
export const DEFAULT_FACETS: SearchFacet[] = [
  { type: 'types', label: 'File type' },
  { type: 'tags', label: 'Tags' },
  { type: 'modified', label: 'Modified' },
  { type: 'size', label: 'File size' },
  { type: 'orientation', label: 'Orientation' },
  { type: 'colors', label: 'Color' },
];

export const BUILT_IN_FACETS: SearchFacetType[] = ['types', 'tags', 'modified', 'size', 'orientation', 'colors'];

/** True when the query filters at all (an empty search lists everything below the folder). */
export function isFiltering(q: SearchQuery): boolean {
  return !!(
    q.fulltext?.trim() ||
    q.types?.length ||
    q.tags?.length ||
    q.modified ||
    q.size ||
    q.orientation ||
    Object.values(q.meta ?? {}).some((v) => v.length) ||
    Object.values(q.metaText ?? {}).some((v) => v.trim())
  );
}

/** A text filter: sent when the field is left or Enter is pressed, not at every key. */
function TextFacet({ facet, value, onCommit }: { facet: SearchFacet; value: string; onCommit(v: string): void }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  const commit = () => draft.trim() !== value && onCommit(draft.trim());
  return (
    <TextField
      label={facet.label}
      description={facet.description}
      value={draft}
      onChange={setDraft}
      onBlur={commit}
      onKeyDown={(e) => e.key === 'Enter' && commit()}
      width="100%"
    />
  );
}

/** The filters rail of a search, as the search form lays it out (the default form unless one was saved). */
export function SearchRail({
  query,
  scopeTitle,
  onChange,
  facets = DEFAULT_FACETS,
  scope,
}: {
  query: SearchQuery;
  scopeTitle: string;
  onChange(q: SearchQuery): void;
  facets?: SearchFacet[];
  /** where the search runs now (Assets Omnisearch's default unless chosen); absent at the top of Media */
  scope?: 'folder' | 'all';
}) {
  const set = (patch: Partial<SearchQuery>) => {
    const next: SearchQuery = { ...query, ...patch };
    for (const k of Object.keys(next) as Array<keyof SearchQuery>) {
      const v = next[k];
      if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0) || (typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0))
        delete next[k];
    }
    onChange(next);
  };
  const setMeta = (property: string, values: string[]) => {
    const meta = { ...(query.meta ?? {}) };
    if (values.length) meta[property] = values;
    else delete meta[property];
    set({ meta });
  };
  const setMetaText = (property: string, text: string) => {
    const metaText = { ...(query.metaText ?? {}) };
    if (text) metaText[property] = text;
    else delete metaText[property];
    set({ metaText });
  };
  const types = new Set(query.types ?? []);

  const facet = (f: SearchFacet, i: number) => {
    const key = `${f.type}:${f.property ?? ''}:${i}`;
    switch (f.type) {
      case 'types':
        return (
          <fieldset key={key}>
            <legend>{f.label}</legend>
            {f.description && <p className="studio-assets__muted">{f.description}</p>}
            {TYPES.map(([k, label]) => (
              <Checkbox
                key={k}
                isSelected={types.has(k)}
                onChange={(on) => {
                  const next = new Set(types);
                  if (on) next.add(k);
                  else next.delete(k);
                  set({ types: [...next] });
                }}
              >
                {label}
              </Checkbox>
            ))}
          </fieldset>
        );
      case 'tags':
        return <AssetTagsField key={key} label={f.label} description={f.description} root={f.root} values={query.tags ?? []} onChange={(tags) => set({ tags })} />;
      case 'modified':
        return (
          <RadioGroup key={key} label={f.label} description={f.description} value={query.modified ?? ''} onChange={(v) => set({ modified: v || undefined })}>
            {MODIFIED.map(([k, label]) => (
              <Radio key={k} value={k}>
                {label}
              </Radio>
            ))}
          </RadioGroup>
        );
      case 'size':
        return (
          <RadioGroup key={key} label={f.label} description={f.description} value={query.size ?? ''} onChange={(v) => set({ size: (v || undefined) as SearchQuery['size'] })}>
            {SIZES.map(([k, label]) => (
              <Radio key={k} value={k}>
                {label}
              </Radio>
            ))}
          </RadioGroup>
        );
      case 'orientation':
        return (
          <RadioGroup
            key={key}
            label={f.label}
            description={f.description}
            value={query.orientation ?? ''}
            onChange={(v) => set({ orientation: (v || undefined) as SearchQuery['orientation'] })}
          >
            {ORIENTATIONS.map(([k, label]) => (
              <Radio key={k} value={k}>
                {label}
              </Radio>
            ))}
          </RadioGroup>
        );
      case 'colors':
        return (
          <ColorFacet
            key={key}
            label={f.label}
            description={f.description}
            values={query.meta?.[COLORS_PROPERTY] ?? []}
            onChange={(v) => setMeta(COLORS_PROPERTY, v)}
          />
        );
      case 'options': {
        const property = f.property ?? '';
        const chosen = new Set(query.meta?.[property] ?? []);
        return (
          <fieldset key={key}>
            <legend>{f.label}</legend>
            {f.description && <p className="studio-assets__muted">{f.description}</p>}
            {(f.options ?? []).map((o) => (
              <Checkbox
                key={o.value}
                isSelected={chosen.has(o.value)}
                onChange={(on) => {
                  const next = new Set(chosen);
                  if (on) next.add(o.value);
                  else next.delete(o.value);
                  setMeta(property, [...next]);
                }}
              >
                {o.label || o.value}
              </Checkbox>
            ))}
          </fieldset>
        );
      }
      case 'text':
        return <TextFacet key={key} facet={f} value={query.metaText?.[f.property ?? ''] ?? ''} onCommit={(v) => setMetaText(f.property ?? '', v)} />;
      default:
        return null;
    }
  };

  return (
    <div className="studio-assets__search-rail">
      {scope ? (
        <RadioGroup label="Search in" value={scope} onChange={(v) => set({ scope: v as 'folder' | 'all' })}>
          <Radio value="folder">{scopeTitle} and its subfolders</Radio>
          <Radio value="all">All of Media</Radio>
        </RadioGroup>
      ) : (
        <p className="studio-assets__muted">
          Searching in <strong>{scopeTitle}</strong> and its subfolders.
        </p>
      )}
      <fieldset>
        <legend>How to read the text</legend>
        <Checkbox isSelected={!!query.look} onChange={(on) => set({ look: on || undefined, ...(on ? {} : { weak: undefined, translate: undefined }) })}>
          As what the picture shows
        </Checkbox>
        {query.look && (
          <Checkbox isSelected={!!query.weak} onChange={(on) => set({ weak: on || undefined })}>
            Also weak matches
          </Checkbox>
        )}
        {query.look && (
          <Checkbox isSelected={!!query.translate} onChange={(on) => set({ translate: on || undefined })}>
            Not in English: translate it first
          </Checkbox>
        )}
        <p className="studio-assets__muted">
          {query.look
            ? 'Describe the picture, e.g. "a red tent on a mountain". Any language works when the model for it is installed (Tools > Auto tags); otherwise descriptions are read in English, and one in another language is translated first when you say so (it needs a translation provider). The best fitting pictures come first, each with how well it fits; the filters below do not apply.'
            : "Finds pictures by what is in them, whatever they are called or tagged."}
        </p>
      </fieldset>
      {facets.map(facet)}
    </div>
  );
}
