import { ActionButton, Checkbox, Item, Picker, Text, TextField } from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import Label from '@spectrum-icons/workflow/Label';
import type { FilterCriteria, PageFacet } from './api';

const MODIFIED = [
  { id: '0', label: 'Any time' },
  { id: '1', label: 'Last 24 hours' },
  { id: '7', label: 'Last week' },
  { id: '30', label: 'Last month' },
  { id: '365', label: 'Last year' },
];

const LIVE_COPY = [
  { id: 'any', label: 'Any' },
  { id: 'livecopy', label: 'Linked copies' },
  { id: 'notlivecopy', label: 'Not linked copies' },
];

/** `wknd:` → `/content/cq:tags/wknd`, `wknd:activity` → `/content/cq:tags/wknd/activity`. */
export function tagPathOfId(id: string): string {
  const colon = id.indexOf(':');
  const ns = colon < 0 ? id : id.slice(0, colon);
  const rest = colon < 0 ? '' : id.slice(colon + 1);
  return `/content/cq:tags/${ns}${rest ? `/${rest}` : ''}`;
}

interface Props {
  facets: PageFacet[];
  criteria: FilterCriteria;
  onChange(patch: Partial<FilterCriteria>): void;
  /** the templates used below the path, for the Template filter */
  templates: Array<{ path: string; title: string }>;
  /** opens the tag picker, at the facet's tag when it has one */
  onPickTag(start: string | undefined): void;
}

/**
 * The filters of the Filter rail as a search form lays them out (Search Forms): the built-in ones and filters on a
 * page property, in the form's order, each with its label and description.
 */
export function PageFacets({ facets, criteria, onChange, templates, onPickTag }: Props) {
  const setMeta = (property: string, values: string[]) => {
    const meta = { ...(criteria.meta ?? {}) };
    if (values.length) meta[property] = values;
    else delete meta[property];
    onChange({ meta });
  };
  const setMetaText = (property: string, text: string) => {
    const metaText = { ...(criteria.metaText ?? {}) };
    if (text) metaText[property] = text;
    else delete metaText[property];
    onChange({ metaText });
  };

  const facet = (f: PageFacet, i: number) => {
    const key = `${f.type}:${f.property ?? ''}:${i}`;
    switch (f.type) {
      case 'path':
        return (
          <TextField
            key={key}
            label={f.label}
            description={f.description}
            value={criteria.path}
            onChange={(p) => onChange({ path: p || '/content' })}
            width="100%"
            UNSAFE_className="studio-filter__path"
          />
        );
      case 'template':
        return (
          <Picker
            key={key}
            label={f.label}
            description={f.description}
            items={[{ path: '', title: 'Any template' }, ...templates]}
            selectedKey={criteria.template ?? ''}
            onSelectionChange={(k) => onChange({ template: k ? String(k) : null })}
            width="100%"
          >
            {(t) => <Item key={t.path}>{t.title}</Item>}
          </Picker>
        );
      case 'tags':
        return (
          <div key={key} className="studio-filter__tags">
            <span className="studio-filter__label">{f.label}</span>
            {f.description && <span className="studio-filter__description">{f.description}</span>}
            <ul aria-label={f.label}>
              {criteria.tags.map((t) => (
                <li key={t} className="studio-filter__tag">
                  <Label size="XS" />
                  <span>{t}</span>
                  <button type="button" aria-label={`Remove tag ${t}`} onClick={() => onChange({ tags: criteria.tags.filter((x) => x !== t) })}>
                    <Close size="XS" />
                  </button>
                </li>
              ))}
            </ul>
            <ActionButton onPress={() => onPickTag(f.root ? tagPathOfId(f.root) : undefined)}>
              <Label />
              <Text>Add Tag</Text>
            </ActionButton>
          </div>
        );
      case 'modified':
        return (
          <Picker
            key={key}
            label={f.label}
            description={f.description}
            items={MODIFIED}
            selectedKey={String(criteria.modifiedDays)}
            onSelectionChange={(k) => onChange({ modifiedDays: Number(k) || 0 })}
            width="100%"
          >
            {(o) => <Item key={o.id}>{o.label}</Item>}
          </Picker>
        );
      case 'livecopy':
        return (
          <Picker
            key={key}
            label={f.label}
            description={f.description}
            items={LIVE_COPY}
            selectedKey={criteria.liveCopy || 'any'}
            onSelectionChange={(k) => onChange({ liveCopy: k === 'any' ? '' : (String(k) as FilterCriteria['liveCopy']) })}
            width="100%"
          >
            {(o) => <Item key={o.id}>{o.label}</Item>}
          </Picker>
        );
      case 'locked':
        return (
          <div key={key} className="studio-filter__check">
            <Checkbox isSelected={criteria.locked} onChange={(locked) => onChange({ locked })}>
              {f.label}
            </Checkbox>
            {f.description && <span className="studio-filter__description">{f.description}</span>}
          </div>
        );
      case 'options': {
        const property = f.property ?? '';
        const chosen = new Set(criteria.meta?.[property] ?? []);
        return (
          <fieldset key={key} className="studio-filter__options">
            <legend className="studio-filter__label">{f.label}</legend>
            {f.description && <span className="studio-filter__description">{f.description}</span>}
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
        return (
          <TextField
            key={key}
            label={f.label}
            description={f.description}
            value={criteria.metaText?.[f.property ?? ''] ?? ''}
            onChange={(v) => setMetaText(f.property ?? '', v)}
            width="100%"
          />
        );
      default:
        return null;
    }
  };

  return <>{facets.map(facet)}</>;
}
