import { useEffect, useMemo, useState } from 'react';
import type { ComponentChoice, GraniteComponentProps } from '../../registry';
import type { ContentNode, PostEntry } from '../../types';
import { useField, useFormStore, useGranite } from '../../context';
import { asString } from '../../node';
import { commonFieldProps, describedBy, FieldShell } from '../FieldShell';
import { ArrowGlyph, TrashGlyph } from '../glyphs';

/** One child component of the container, as the Items tab lists it. */
export interface ChildItem {
  name: string;
  resourceType: string;
  title: string;
  /** Added in this dialog: created on submit. */
  added?: boolean;
}

export interface ChildrenValue {
  items: ChildItem[];
  /** Names of existing children removed in this dialog. */
  deleted: string[];
}

/** The children of the container content that are components (have a `sling:resourceType`), in order. */
export function childItems(content: ContentNode): ChildItem[] {
  const out: ChildItem[] = [];
  for (const [name, value] of Object.entries(content)) {
    if (!value || typeof value !== 'object' || Array.isArray(value) || name.includes(':')) continue;
    const child = value as ContentNode;
    const resourceType = asString(child['sling:resourceType']);
    if (!resourceType) continue;
    out.push({ name, resourceType, title: asString(child['cq:panelTitle']) });
  }
  return out;
}

/** Sling POST entries of the Items tab, the same parameters AEM's children editor posts. */
export function childrenEntries(value: ChildrenValue): PostEntry[] {
  const out: PostEntry[] = [];
  for (const item of value.items) {
    out.push({ name: `./${item.name}/cq:panelTitle`, value: item.title });
    if (item.added) out.push({ name: `./${item.name}/sling:resourceType`, value: item.resourceType });
  }
  out.push({ name: 'itemOrder', value: value.items.map((i) => i.name).join(',') });
  out.push({ name: 'deletedItems', value: value.deleted.join(',') });
  return out;
}

/** A child name that is not used yet: `item_<timestamp>`, like AEM. */
export function newItemName(taken: Iterable<string>, now = Date.now()): string {
  const used = new Set(taken);
  let n = now;
  while (used.has(`item_${n}`)) n++;
  return `item_${n}`;
}

function initials(title: string): string {
  const words = title.split(/[\s-]+/).filter(Boolean);
  return (words.length > 1 ? words[0][0] + words[1][0] : title.slice(0, 2)).replace(/^./, (c) => c.toUpperCase());
}

/**
 * `core/wcm/components/commons/editor/dialog/childreneditor/v1/childreneditor`: the Items tab of Carousel, Tabs and
 * Accordion. Lists the container's child components with their panel titles; items can be renamed, reordered,
 * removed and added (from the components the container's policy allows). Removal and order are posted as
 * `deletedItems` / `itemOrder` and applied by Sensible Studio after the Sling POST, as in AEM.
 */
export function ChildrenEditor({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { env } = useGranite();
  const common = commonFieldProps(node);
  const { id, value, setValue, disabled: ruleDisabled } = useField<ChildrenValue>(
    'itemOrder',
    () => ({ items: childItems(store.content), deleted: [] }),
    {
      label: common.label ?? 'Items',
      entries: (v) => childrenEntries(v as ChildrenValue),
    },
  );
  const disabled = common.disabled || ruleDisabled;
  const [choosing, setChoosing] = useState(false);
  const items = value.items;

  const update = (next: ChildItem[], deleted = value.deleted) => setValue({ items: next, deleted });
  const move = (from: number, to: number) => {
    const next = [...items];
    const [it] = next.splice(from, 1);
    next.splice(to, 0, it);
    update(next);
  };
  const remove = (item: ChildItem) =>
    update(
      items.filter((x) => x !== item),
      item.added ? value.deleted : [...value.deleted, item.name],
    );
  const add = (c: ComponentChoice) => {
    setChoosing(false);
    const name = newItemName([...items.map((i) => i.name), ...value.deleted]);
    update([...items, { name, resourceType: c.resourceType, title: '', added: true }]);
  };

  return (
    <FieldShell id={id} label={common.label} description={common.description}>
      <div className="studio-g-children" id={id} aria-describedby={describedBy(id, common.description, null)}>
        {items.length === 0 && <div className="studio-g-children__empty">No items</div>}
        <ol className="studio-g-children__items">
          {items.map((item, i) => {
            const info = env.componentInfo?.(item.resourceType);
            const typeTitle = info?.title ?? item.resourceType.slice(item.resourceType.lastIndexOf('/') + 1);
            return (
              <li key={item.name} className="studio-g-children__item">
                <span className="studio-g-children__badge" title={typeTitle} aria-hidden="true">
                  {initials(typeTitle)}
                </span>
                <input
                  className="studio-g-input studio-g-children__title"
                  type="text"
                  value={item.title}
                  placeholder={typeTitle}
                  aria-label={`Title of item ${i + 1} (${typeTitle})`}
                  disabled={disabled}
                  onChange={(e) => update(items.map((x) => (x === item ? { ...x, title: e.target.value } : x)))}
                />
                <div className="studio-g-children__actions">
                  <button type="button" aria-label="Move up" title="Move up" disabled={disabled || i === 0} onClick={() => move(i, i - 1)}>
                    <ArrowGlyph up />
                  </button>
                  <button
                    type="button"
                    aria-label="Move down"
                    title="Move down"
                    disabled={disabled || i === items.length - 1}
                    onClick={() => move(i, i + 1)}
                  >
                    <ArrowGlyph />
                  </button>
                  <button type="button" aria-label={`Remove ${item.title || typeTitle}`} title="Remove" disabled={disabled} onClick={() => remove(item)}>
                    <TrashGlyph />
                  </button>
                </div>
              </li>
            );
          })}
        </ol>
        <button type="button" className="studio-g-btn studio-g-children__add" disabled={disabled || !env.allowedComponents} onClick={() => setChoosing(true)}>
          Add
        </button>
      </div>
      {choosing && <ComponentChooser onPick={add} onCancel={() => setChoosing(false)} />}
    </FieldShell>
  );
}

/** "Insert New Component" for a container: the components its policy allows, grouped, with a filter. */
function ComponentChooser({ onPick, onCancel }: { onPick(c: ComponentChoice): void; onCancel(): void }) {
  const { env } = useGranite();
  const [choices, setChoices] = useState<ComponentChoice[] | null>(null);
  const [filter, setFilter] = useState('');
  useEffect(() => {
    let live = true;
    Promise.resolve(env.allowedComponents?.(env.contentPath ?? '') ?? []).then(
      (list) => live && setChoices(list),
      () => live && setChoices([]),
    );
    return () => {
      live = false;
    };
  }, [env]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onCancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  const groups = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const map = new Map<string, ComponentChoice[]>();
    for (const c of choices ?? []) {
      if (q && !c.title.toLowerCase().includes(q) && !(c.group ?? '').toLowerCase().includes(q)) continue;
      const g = c.group ?? 'Other';
      map.set(g, [...(map.get(g) ?? []), c]);
    }
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([g, list]) => [g, list.sort((a, b) => a.title.localeCompare(b.title))] as const);
  }, [choices, filter]);

  return (
    <div className="studio-g-picker studio-g-chooser" role="dialog" aria-modal="true" aria-label="Insert New Component">
      <div className="studio-g-picker__backdrop" onClick={onCancel} />
      <div className="studio-g-picker__window">
        <header className="studio-g-picker__header">
          <h2 className="studio-g-chooser__heading">Insert New Component</h2>
          <input
            className="studio-g-input"
            type="search"
            placeholder="Enter Keyword"
            aria-label="Filter components"
            value={filter}
            autoFocus
            onChange={(e) => setFilter(e.target.value)}
          />
        </header>
        <div className="studio-g-chooser__list">
          {choices === null && <p className="studio-g-picker__empty">Loading…</p>}
          {choices?.length === 0 && <p className="studio-g-picker__empty">This container does not allow any component.</p>}
          {choices && choices.length > 0 && groups.length === 0 && <p className="studio-g-picker__empty">No component matches.</p>}
          {groups.map(([group, list]) => (
            <section key={group}>
              <h3 className="studio-g-chooser__group">{group}</h3>
              <ul role="listbox" aria-label={group}>
                {list.map((c) => (
                  <li key={c.resourceType} role="option" aria-selected={false}>
                    <button type="button" className="studio-g-chooser__item" onClick={() => onPick(c)}>
                      <span className="studio-g-children__badge" aria-hidden="true">
                        {initials(c.title)}
                      </span>
                      {c.title}
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
        <footer className="studio-g-picker__footer">
          <button type="button" className="studio-g-btn" onClick={onCancel}>
            Cancel
          </button>
        </footer>
      </div>
    </div>
  );
}
