import { useMemo, useSyncExternalStore } from 'react';
import type { GraniteComponentProps } from '../../registry';
import type { GraniteNode } from '../../types';
import { useField, useFormStore, useGranite } from '../../context';
import { multiEntries } from '../../form/slingPost';
import { asStringList, child, items, str } from '../../node';
import { commonFieldProps, describedBy, FieldShell, requiredError } from '../FieldShell';
import { ArrowGlyph, TrashGlyph } from '../glyphs';
import { useOptions } from './choice';

/**
 * `studio/granite/components/contentfragment/elementnames`: the Content Fragment component's element choice, as its
 * dialog script switches it with the Display Mode: one element (required) for "Single Text Element", an ordered list
 * of elements for "Multiple Elements" (none chosen: all of them), nothing for a Visual Content Fragment. The options
 * are the elements of the chosen fragment's model and follow the Content Fragment field. Stores `./elementNames`.
 */
export function CfElementNames({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { rootStore } = useGranite();
  useSyncExternalStore(rootStore.subscribe, rootStore.getVersion, rootStore.getVersion);
  const mode = String(rootStore.get('./displayMode') ?? 'multi');

  // the two variants of the dialog: a select (singleText) and a multifield of selects (multi)
  const single = useMemo(() => findField(node, (n) => str(n, 'sling:resourceType')?.endsWith('/form/select') ?? false), [node]);
  const multi = useMemo(() => findField(node, (n) => str(n, 'sling:resourceType')?.endsWith('/form/multifield') ?? false), [node]);
  const select = single ?? child(multi, 'field');
  const options = useOptions(select ?? node);
  const singleCommon = single ? commonFieldProps(single) : null;
  const name = singleCommon?.name || './elementNames';

  const { id, value, setValue, error } = useField<string[]>(
    name,
    () => {
      const stored = store.contentValue(name);
      return stored === undefined ? [] : asStringList(stored).filter(Boolean);
    },
    {
      label: singleCommon?.label ?? 'Element',
      entries: (v) => {
        const m = String(rootStore.get('./displayMode') ?? 'multi');
        const values = (Array.isArray(v) ? v : []).filter(Boolean);
        if (m === 'singleText') return values.length ? [{ name, value: values[0] }] : [];
        if (m === 'multi') return multiEntries(name, values);
        return [];
      },
      values: (v) => (Array.isArray(v) ? v : []),
      validate: (v) =>
        String(rootStore.get('./displayMode') ?? 'multi') === 'singleText' ? requiredError(!!singleCommon?.required, !(Array.isArray(v) && v[0])) : null,
    },
  );

  const list = options.options;
  const loading = options.status === 'loading';
  const none = !rootStore.get('./fragmentPath');
  const empty = str(select, 'emptyText') ?? 'Select';
  const optionList = (current: string) => (
    <>
      <option value="">{empty}</option>
      {list.map((o) => (
        <option key={o.value} value={o.value}>
          {o.text}
        </option>
      ))}
      {current && !list.some((o) => o.value === current) && <option value={current}>{current}</option>}
    </>
  );

  if (mode === 'singleText' && single) {
    const c = singleCommon!;
    return (
      <FieldShell id={id} label={c.label} required={c.required} description={c.description} error={error}>
        <div className="studio-g-select">
          <select
            id={id}
            className="studio-g-input studio-g-select__control"
            value={value[0] ?? ''}
            disabled={loading || none}
            aria-required={c.required || undefined}
            aria-describedby={describedBy(id, c.description, error)}
            onChange={(e) => setValue(e.target.value ? [e.target.value] : [])}
          >
            {optionList(value[0] ?? '')}
          </select>
        </div>
      </FieldShell>
    );
  }

  if (mode === 'multi' && multi) {
    const label = str(multi, 'fieldLabel') ?? 'Elements';
    const set = (i: number, v: string) => setValue(value.map((x, j) => (j === i ? v : x)));
    const move = (from: number, to: number) => {
      const next = [...value];
      const [it] = next.splice(from, 1);
      next.splice(to, 0, it);
      setValue(next);
    };
    return (
      <FieldShell id={id} label={label} description={str(multi, 'fieldDescription')}>
        <div className="studio-g-multifield" id={id}>
        {value.length === 0 && <div className="studio-g-multifield__empty">All elements</div>}
        <ol className="studio-g-multifield__items" aria-label={label}>
          {value.map((v, i) => (
            <li key={`${i}-${v}`} className="studio-g-multifield__item">
              <div className="studio-g-multifield__body">
                <select
                  className="studio-g-input studio-g-select__control"
                  aria-label={`Element ${i + 1}`}
                  value={v}
                  disabled={loading}
                  onChange={(e) => set(i, e.target.value)}
                >
                  {optionList(v)}
                </select>
              </div>
              <div className="studio-g-multifield__actions">
                <button type="button" aria-label="Move up" title="Move up" disabled={i === 0} onClick={() => move(i, i - 1)}>
                  <ArrowGlyph up />
                </button>
                <button type="button" aria-label="Move down" title="Move down" disabled={i === value.length - 1} onClick={() => move(i, i + 1)}>
                  <ArrowGlyph />
                </button>
                <button type="button" aria-label={`Remove element ${i + 1}`} title="Remove" onClick={() => setValue(value.filter((_, j) => j !== i))}>
                  <TrashGlyph />
                </button>
              </div>
            </li>
          ))}
        </ol>
        <button type="button" className="studio-g-btn studio-g-multifield__add" disabled={none} onClick={() => setValue([...value, list[0]?.value ?? ''])}>
          Add
        </button>
        </div>
      </FieldShell>
    );
  }
  return null;
}

function findField(node: GraniteNode, test: (n: GraniteNode) => boolean): GraniteNode | undefined {
  for (const c of items(node)) {
    if (test(c)) return c;
  }
  return undefined;
}
