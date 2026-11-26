import { useSyncExternalStore } from 'react';
import type { GraniteComponentProps } from '../../registry';
import { useField, useFormStore, useGranite } from '../../context';
import { multiEntries } from '../../form/slingPost';
import { asStringList, str } from '../../node';
import { commonFieldProps, describedBy, FieldShell } from '../FieldShell';
import { panelItems } from './coreDataSources';

/**
 * `studio/granite/components/panelcontainer/expandeditems`: what the Accordion editor script makes of its two
 * "Expanded Items" selects. With "Single item expansion" it is one select (None or one item), otherwise a choice of
 * several; the options are the items of the Items tab, kept in step with it; with no items it is hidden and posts
 * nothing. Stores `./expandedItems` as a String[], like AEM.
 */
export function ExpandedItems({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { rootStore, env } = useGranite();
  const common = commonFieldProps(node);
  const name = common.name || './expandedItems';
  useSyncExternalStore(rootStore.subscribe, rootStore.getVersion, rootStore.getVersion);
  const single = rootStore.get('./singleExpansion') === true;
  const options = panelItems(rootStore, env);
  const hasItems = options.length > 0;

  const { id, value, setValue, disabled: ruleDisabled } = useField<string[]>(
    name,
    () => {
      const stored = store.contentValue(name);
      return stored === undefined ? [] : asStringList(stored).filter(Boolean);
    },
    {
      label: common.label,
      // like the script's disabled select: nothing is posted while there are no items
      entries: (v) => {
        const names = panelItems(rootStore, env).map((o) => o.value);
        if (!names.length) return [];
        // an item removed in the Items tab is no longer expanded
        const values = (Array.isArray(v) ? v : []).filter((x) => names.includes(x));
        return multiEntries(name, rootStore.get('./singleExpansion') === true ? values.slice(0, 1) : values);
      },
      values: (v) => (Array.isArray(v) ? v : []),
    },
  );
  if (!hasItems) return null;

  const disabled = common.disabled || ruleDisabled;
  const textOf = (v: string) => options.find((o) => o.value === v)?.text ?? v;
  const known = value.filter((v) => options.some((o) => o.value === v));

  if (single) {
    const label = str(node, 'studio:singleLabel') ?? 'Expanded Item';
    const description = str(node, 'studio:singleDescription') ?? common.description;
    return (
      <FieldShell id={id} label={label} description={description}>
        <div className="studio-g-select">
          <select
            id={id}
            className="studio-g-input studio-g-select__control"
            value={known[0] ?? ''}
            disabled={disabled}
            aria-describedby={describedBy(id, description, null)}
            onChange={(e) => setValue(e.target.value ? [e.target.value] : [])}
          >
            <option value="">None</option>
            {options.map((o) => (
              <option key={o.value} value={o.value}>
                {o.text}
              </option>
            ))}
          </select>
        </div>
      </FieldShell>
    );
  }

  return (
    <FieldShell id={id} label={common.label} description={common.description}>
      <div className="studio-g-select">
        <select
          id={id}
          className="studio-g-input studio-g-select__control"
          value=""
          disabled={disabled}
          aria-describedby={describedBy(id, common.description, null)}
          onChange={(e) => e.target.value && setValue([...known, e.target.value])}
        >
          <option value="">Select</option>
          {options
            .filter((o) => !known.includes(o.value))
            .map((o) => (
              <option key={o.value} value={o.value}>
                {o.text}
              </option>
            ))}
        </select>
      </div>
      {known.length > 0 && (
        <ul className="studio-g-taglist" aria-label={`${common.label ?? 'Expanded Items'} chosen`}>
          {known.map((v) => (
            <li key={v} className="studio-g-taglist__tag">
              <span>{textOf(v)}</span>
              <button type="button" aria-label={`Remove ${textOf(v)}`} disabled={disabled} onClick={() => setValue(known.filter((x) => x !== v))}>
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </FieldShell>
  );
}
