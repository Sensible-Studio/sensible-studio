import { useId } from 'react';
import type { GraniteComponentProps, GraniteEnv } from '../../registry';
import type { GraniteNode } from '../../types';
import { useField, useFormStore, useGranite } from '../../context';
import { multiEntries, scalarEntries } from '../../form/slingPost';
import { asString, asStringList, isContentNode } from '../../node';
import { commonFieldProps, describedBy, FieldShell } from '../FieldShell';
import { initialScalar } from './text';

/**
 * The Style System as stored in a component policy:
 * `cq:styleGroups/<item>/{cq:styleGroupLabel, cq:styleGroupMultiple, cq:styles/<item>/{cq:styleId, cq:styleLabel, cq:styleClasses}}`.
 */
export interface StyleGroup {
  label: string;
  multiple: boolean;
  styles: Array<{ id: string; label: string; classes?: string }>;
}

/** Reads the style groups from `env.design` (the policy, see `designFromPolicy`). */
export function styleGroups(design: GraniteEnv['design']): StyleGroup[] {
  const groups = design?.['cq:styleGroups'];
  if (!isContentNode(groups)) return [];
  return Object.values(groups)
    .filter(isContentNode)
    .map((g) => {
      const styles = isContentNode(g['cq:styles']) ? Object.values(g['cq:styles']).filter(isContentNode) : [];
      return {
        label: asString(g['cq:styleGroupLabel']) || 'Styles',
        multiple: g['cq:styleGroupMultiple'] === true || g['cq:styleGroupMultiple'] === 'true',
        styles: styles
          .map((s) => ({
            id: asString(s['cq:styleId']),
            label: asString(s['cq:styleLabel']) || asString(s['cq:styleClasses']) || asString(s['cq:styleId']),
            classes: asString(s['cq:styleClasses']) || undefined,
          }))
          .filter((s) => s.id),
      };
    })
    .filter((g) => g.styles.length > 0);
}

/** `studio/granite/renderconditions/hasstyles`: the Styles tab only appears when the policy defines styles. */
export function hasStyles(_node: GraniteNode, env: GraniteEnv): boolean {
  return styleGroups(env.design).length > 0;
}

/**
 * `studio/granite/components/stylesystem/styleselector` — one control per style group (a select, or checkboxes
 * when the group allows combining), stored as the `cq:styleIds` String[] like AEM. IDs that the policy no
 * longer defines are kept, so saving never drops data silently.
 */
export function StyleSelector({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { env } = useGranite();
  const common = commonFieldProps(node);
  const name = common.name || './cq:styleIds';
  const groups = styleGroups(env.design);
  const known = new Set(groups.flatMap((g) => g.styles.map((s) => s.id)));
  const { id, value, setValue, error } = useField<string[]>(name, () => asStringList(store.contentValue(name)), {
    label: 'Styles',
    entries: (v) => multiEntries(name, (v as string[]) ?? []),
    values: (v) => (v as string[]) ?? [],
  });
  const selected = value ?? [];
  const unknown = selected.filter((s) => !known.has(s));

  const setGroup = (group: StyleGroup, ids: string[]) => {
    const groupIds = new Set(group.styles.map((s) => s.id));
    setValue([...selected.filter((s) => !groupIds.has(s)), ...ids]);
  };

  return (
    <FieldShell id={id} description={common.description} error={error}>
      <div className="studio-g-styles" id={id} aria-describedby={describedBy(id, common.description, error)}>
        {groups.length === 0 && <p className="studio-g-text">This component's configuration defines no styles.</p>}
        {groups.map((g) => (
          <StyleGroupControl key={g.label} group={g} selected={selected} onChange={(ids) => setGroup(g, ids)} />
        ))}
        {unknown.length > 0 && (
          <div className="studio-g-field__note">
            Kept style IDs not defined in the policy: <code>{unknown.join(', ')}</code>
          </div>
        )}
      </div>
    </FieldShell>
  );
}

function StyleGroupControl({ group, selected, onChange }: { group: StyleGroup; selected: string[]; onChange(ids: string[]): void }) {
  const id = useId();
  const current = group.styles.filter((s) => selected.includes(s.id)).map((s) => s.id);
  if (group.multiple) {
    return (
      <fieldset className="studio-g-styles__group">
        <legend className="studio-g-field__label">{group.label}</legend>
        {group.styles.map((s) => (
          <label key={s.id} className="studio-g-checkbox">
            <input
              type="checkbox"
              className="studio-g-checkbox__input"
              checked={current.includes(s.id)}
              onChange={(e) => onChange(e.target.checked ? [...current, s.id] : current.filter((x) => x !== s.id))}
            />
            <span className="studio-g-checkbox__box" aria-hidden="true" />
            <span className="studio-g-checkbox__text">{s.label}</span>
          </label>
        ))}
      </fieldset>
    );
  }
  return (
    <div className="studio-g-field">
      <label className="studio-g-field__label" htmlFor={id}>
        {group.label}
      </label>
      <select id={id} className="studio-g-input" value={current[0] ?? ''} onChange={(e) => onChange(e.target.value ? [e.target.value] : [])}>
        <option value="">None</option>
        {group.styles.map((s) => (
          <option key={s.id} value={s.id}>
            {s.label}
          </option>
        ))}
      </select>
    </div>
  );
}

/**
 * `studio/granite/components/stylesystem/styleid` — the ID of a style in a design dialog. Existing IDs are kept;
 * a new style gets a fresh one (AEM generates numeric, timestamp-like IDs too).
 */
export function StyleId({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  useField<string>(common.name, () => initialScalar(store, node) || newStyleId(), {
    entries: (v) => scalarEntries(common.name, asString(v)),
    values: (v) => [asString(v)],
  });
  return null;
}

let lastId = 0;
function newStyleId(): string {
  lastId = Math.max(Date.now(), lastId + 1);
  return String(lastId);
}
