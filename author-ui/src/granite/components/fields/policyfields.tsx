import { useEffect, useMemo, useState } from 'react';
import type { ComponentChoice, GraniteComponentProps } from '../../registry';
import type { ContentNode, PostEntry } from '../../types';
import { useField, useFormStore, useGranite } from '../../context';
import { asString, asStringList, isContentNode, str } from '../../node';

/** Components with the group they are listed under; `group:<name>` entries in a policy stand for whole groups. */
function useAllComponents(): { components: ComponentChoice[] | null; error: string | null } {
  const { env } = useGranite();
  const [components, setComponents] = useState<ComponentChoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    if (!env.allComponents) {
      setComponents([]);
      return;
    }
    env.allComponents().then(
      (c) => live && setComponents(c),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [env]);
  return { components, error };
}

const GROUP = 'group:';

/** What a component is called in a policy: its path (`/apps/wknd/components/title`), like AEM writes it. */
const entryOf = (c: ComponentChoice) => c.path ?? c.resourceType;

/** The `components` entries that select `c` (by path, resource type or its group). */
function selects(entries: Set<string>, c: ComponentChoice): boolean {
  return (
    entries.has(entryOf(c)) ||
    entries.has(c.resourceType) ||
    entries.has(`/apps/${c.resourceType}`) ||
    entries.has(`/libs/${c.resourceType}`) ||
    (!!c.group && entries.has(`${GROUP}${c.group}`))
  );
}

/**
 * `studio/granite/components/allowedcomponents`: the components a layout container accepts (its policy's
 * `components`), as AEM's policy dialog shows them: groups that can be ticked as a whole (stored as `group:<name>`)
 * and opened to tick single components (stored as their path), with a filter.
 */
export function AllowedComponentsField({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const name = str(node, 'name') ?? './components';
  const { components, error } = useAllComponents();
  const { value, setValue } = useField<string[]>(name, () => asStringList(store.contentValue(name)), {
    label: 'Allowed Components',
    entries: (v) => {
      const list = v as string[];
      const out: PostEntry[] = [{ name: `${name}@Delete`, value: '' }];
      for (const e of list) out.push({ name, value: e });
      if (list.length) out.push({ name: `${name}@TypeHint`, value: 'String[]' });
      return out;
    },
    isDirty: () => {
      const now = (store.get<string[]>(name) ?? []).join('\n');
      return now !== asStringList(store.contentValue(name)).join('\n');
    },
  });
  const entries = useMemo(() => new Set(value ?? []), [value]);
  const [filter, setFilter] = useState('');
  const [open, setOpen] = useState<Set<string>>(new Set());

  const groups = useMemo(() => {
    const m = new Map<string, ComponentChoice[]>();
    for (const c of components ?? []) {
      const g = c.group || 'General';
      if (!m.has(g)) m.set(g, []);
      m.get(g)!.push(c);
    }
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [components]);

  const q = filter.trim().toLowerCase();
  const visible = groups
    .map(([g, list]) => [g, q ? list.filter((c) => c.title.toLowerCase().includes(q) || g.toLowerCase().includes(q)) : list] as const)
    .filter(([, list]) => list.length > 0);

  /** Rewrites the entries of one group: the whole group when all its components are on, else single paths. */
  const write = (group: string, on: Set<string>) => {
    const list = groups.find(([g]) => g === group)?.[1] ?? [];
    const next = new Set(entries);
    next.delete(`${GROUP}${group}`);
    for (const c of list) {
      next.delete(entryOf(c));
      next.delete(c.resourceType);
      next.delete(`/apps/${c.resourceType}`);
      next.delete(`/libs/${c.resourceType}`);
    }
    if (list.length && list.every((c) => on.has(c.resourceType))) next.add(`${GROUP}${group}`);
    else for (const c of list) if (on.has(c.resourceType)) next.add(entryOf(c));
    setValue([...next]);
  };

  const onIn = (group: string) => new Set((groups.find(([g]) => g === group)?.[1] ?? []).filter((c) => selects(entries, c)).map((c) => c.resourceType));

  if (error) return <p className="studio-g-policy__note">The components could not be listed: {error}</p>;
  if (!components) return <p className="studio-g-policy__note">Loading components…</p>;

  return (
    <div className="studio-g-allowed">
      <input
        className="studio-g-input studio-g-allowed__filter"
        type="search"
        placeholder="Search"
        aria-label="Search components"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
      />
      <ul className="studio-g-allowed__groups" aria-label="Allowed Components">
        {visible.map(([group, list]) => {
          const all = groups.find(([g]) => g === group)?.[1] ?? [];
          const on = onIn(group);
          const checked = all.length > 0 && on.size === all.length;
          const mixed = on.size > 0 && !checked;
          const expanded = open.has(group) || !!q;
          return (
            <li key={group} className="studio-g-allowed__group">
              <div className="studio-g-allowed__row">
                <button
                  type="button"
                  className={`studio-g-allowed__twisty${expanded ? ' is-open' : ''}`}
                  aria-label={`${expanded ? 'Collapse' : 'Expand'} ${group}`}
                  aria-expanded={expanded}
                  onClick={() => setOpen((s) => {
                    const n = new Set(s);
                    if (n.has(group)) n.delete(group);
                    else n.add(group);
                    return n;
                  })}
                />
                <label className="studio-g-checkbox">
                  <input
                    type="checkbox"
                    className="studio-g-checkbox__input"
                    checked={checked}
                    ref={(el) => {
                      if (el) el.indeterminate = mixed;
                    }}
                    onChange={(e) => write(group, e.target.checked ? new Set(all.map((c) => c.resourceType)) : new Set())}
                  />
                  <span className="studio-g-checkbox__box" aria-hidden="true" />
                  <span className="studio-g-allowed__group-name">{group}</span>
                </label>
                <span className="studio-g-allowed__count">
                  {on.size}/{all.length}
                </span>
              </div>
              {expanded && (
                <ul className="studio-g-allowed__items">
                  {list.map((c) => (
                    <li key={c.resourceType}>
                      <label className="studio-g-checkbox">
                        <input
                          type="checkbox"
                          className="studio-g-checkbox__input"
                          checked={on.has(c.resourceType)}
                          onChange={(e) => {
                            const next = new Set(on);
                            if (e.target.checked) next.add(c.resourceType);
                            else next.delete(c.resourceType);
                            write(group, next);
                          }}
                        />
                        <span className="studio-g-checkbox__box" aria-hidden="true" />
                        <span>{c.title}</span>
                        <span className="studio-g-allowed__type">{c.resourceType}</span>
                      </label>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

interface Mapping {
  /** node name (`mapping_<n>`), kept for existing mappings */
  key: string;
  resourceType: string;
  droptarget: string;
  assetGroup: string;
  mimeTypes: string;
}

const ASSET_GROUPS = ['media', 'content', 'documents', 'products', 'manuscripts'];

function readMappings(node: unknown): Mapping[] {
  if (!isContentNode(node)) return [];
  return Object.entries(node)
    .filter(([, v]) => isContentNode(v))
    .map(([k, v]) => {
      const m = v as ContentNode;
      return {
        key: k,
        resourceType: asString(m.resourceType),
        droptarget: asString(m.droptarget),
        assetGroup: asString(m.assetGroup),
        mimeTypes: asStringList(m.assetMimetype).join(', '),
      };
    });
}

/**
 * `studio/granite/components/assetmappings`: the container policy's `cq:authoring/assetToComponentMapping`, what an
 * asset dropped into the container becomes (Image for `image/*`, …), as AEM's "Default Components" tab edits it.
 */
export function AssetMappingsField({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const name = str(node, 'name') ?? './cq:authoring/assetToComponentMapping';
  const { components } = useAllComponents();
  const stored = useMemo(() => JSON.stringify(readMappings(store.contentValue(name))), [store, name]);
  const { value, setValue } = useField<Mapping[]>(name, () => readMappings(store.contentValue(name)), {
    label: 'Default Components',
    entries: (v) => {
      const list = (v as Mapping[]).filter((m) => m.resourceType);
      const out: PostEntry[] = [{ name: `${name}@Delete`, value: '' }];
      if (!list.length) return out;
      out.push({ name: `${name}/jcr:primaryType`, value: 'nt:unstructured' });
      for (const m of list) {
        const base = `${name}/${m.key}`;
        out.push({ name: `${base}/jcr:primaryType`, value: 'nt:unstructured' });
        out.push({ name: `${base}/resourceType`, value: m.resourceType });
        if (m.droptarget) out.push({ name: `${base}/droptarget`, value: m.droptarget });
        if (m.assetGroup) out.push({ name: `${base}/assetGroup`, value: m.assetGroup });
        const mimes = m.mimeTypes.split(',').map((s) => s.trim()).filter(Boolean);
        for (const t of mimes) out.push({ name: `${base}/assetMimetype`, value: t });
        if (mimes.length > 1) out.push({ name: `${base}/assetMimetype@TypeHint`, value: 'String[]' });
      }
      return out;
    },
    isDirty: () => JSON.stringify(store.get<Mapping[]>(name) ?? []) !== stored,
  });
  const list = value ?? [];
  const update = (i: number, patch: Partial<Mapping>) => setValue(list.map((m, j) => (j === i ? { ...m, ...patch } : m)));
  const choices = components ?? [];

  return (
    <div className="studio-g-mappings">
      {list.length === 0 && <p className="studio-g-policy__note">No default components: files dropped into the container are not turned into components.</p>}
      {list.map((m, i) => (
        <fieldset key={m.key} className="studio-g-mappings__item">
          <legend className="studio-g-visually-hidden">Mapping {i + 1}</legend>
          <label className="studio-g-mappings__field">
            <span className="studio-g-label">Component</span>
            <select className="studio-g-input" value={m.resourceType} onChange={(e) => update(i, { resourceType: e.target.value })}>
              <option value="">Choose a component</option>
              {m.resourceType && !choices.some((c) => c.resourceType === m.resourceType) && <option value={m.resourceType}>{m.resourceType}</option>}
              {choices.map((c) => (
                <option key={c.resourceType} value={c.resourceType}>
                  {c.title} - {c.group}
                </option>
              ))}
            </select>
          </label>
          <label className="studio-g-mappings__field">
            <span className="studio-g-label">Drop target</span>
            <input className="studio-g-input" value={m.droptarget} onChange={(e) => update(i, { droptarget: e.target.value })} />
          </label>
          <label className="studio-g-mappings__field">
            <span className="studio-g-label">Media group</span>
            <select className="studio-g-input" value={m.assetGroup} onChange={(e) => update(i, { assetGroup: e.target.value })}>
              <option value="" />
              {[...new Set([...ASSET_GROUPS, ...(m.assetGroup ? [m.assetGroup] : [])])].map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
            </select>
          </label>
          <label className="studio-g-mappings__field">
            <span className="studio-g-label">Mime types</span>
            <input className="studio-g-input" value={m.mimeTypes} placeholder="image/*" onChange={(e) => update(i, { mimeTypes: e.target.value })} />
          </label>
          <button type="button" className="studio-g-linkbtn studio-g-mappings__remove" onClick={() => setValue(list.filter((_, j) => j !== i))}>
            Delete
          </button>
        </fieldset>
      ))}
      <button
        type="button"
        className="studio-g-button"
        onClick={() => setValue([...list, { key: `mapping_${Date.now()}`, resourceType: '', droptarget: '', assetGroup: 'media', mimeTypes: '' }])}
      >
        Add mapping
      </button>
    </div>
  );
}
