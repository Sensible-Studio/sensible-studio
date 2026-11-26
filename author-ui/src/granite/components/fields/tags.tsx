import { useEffect, useState } from 'react';
import type { GraniteComponentProps } from '../../registry';
import { useField, useFormStore, useGranite } from '../../context';
import { multiEntries, scalarEntries } from '../../form/slingPost';
import { asString, asStringList, bool, str } from '../../node';
import { commonFieldProps, describedBy, FieldShell, requiredError } from '../FieldShell';
import { FolderGlyph } from '../glyphs';

/** Display paths of tag IDs, shared by every tag field of the page. */
const titles = new Map<string, string>();

/**
 * `cq/gui/components/coral/common/form/tagfield`: the tags as chips titled like AEM (`Namespace : Parent / Tag`), a text
 * input to add a tag ID, and a folder button that browses the tag tree.
 */
export function TagField({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { env } = useGranite();
  const common = commonFieldProps(node);
  const multiple = bool(node, 'multiple', true);
  const [draft, setDraft] = useState('');
  const [picking, setPicking] = useState(false);
  const [, rerender] = useState(0);
  const { id, value, setValue, error, disabled: ruleDisabled } = useField<string[]>(
    common.name,
    () => asStringList(common.name ? store.contentValue(common.name) : undefined),
    {
      label: common.label,
      entries: (v) => {
        const list = (v as string[]) ?? [];
        return multiple ? multiEntries(common.name, list) : scalarEntries(common.name, asString(list[0] ?? ''));
      },
      values: (v) => (v as string[]) ?? [],
      validate: (v) => requiredError(common.required, ((v as string[]) ?? []).length === 0),
    },
  );
  const tags = value ?? [];
  const disabled = common.disabled || common.readOnly || ruleDisabled;

  useEffect(() => {
    if (!env.tagTitle) return;
    let live = true;
    const missing = tags.filter((t) => !titles.has(t));
    Promise.all(missing.map((t) => env.tagTitle!(t).then((title) => titles.set(t, title), () => titles.set(t, t)))).then(
      () => live && missing.length > 0 && rerender((n) => n + 1),
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [env, tags.join('|')]);

  const add = (tag: string) => {
    const t = tag.trim();
    if (!t) return;
    setValue(multiple ? (tags.includes(t) ? tags : [...tags, t]) : [t]);
    setDraft('');
  };
  const Picker = env.TagPicker;

  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <div className="studio-g-tagfield">
        <div className="studio-g-path">
          <input
            id={id}
            className="studio-g-input studio-g-path__input"
            value={draft}
            disabled={disabled}
            placeholder={str(node, 'emptyText') ?? ''}
            aria-describedby={describedBy(id, common.description, error)}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                add(draft);
              }
            }}
            onBlur={() => add(draft)}
          />
          {Picker && (
            <button type="button" className="studio-g-path__browse" disabled={disabled} aria-label="Browse tags" title="Browse tags" onClick={() => setPicking(true)}>
              <FolderGlyph />
            </button>
          )}
        </div>
        {tags.length > 0 && (
          <ul className="studio-g-taglist" aria-label={`${common.label ?? 'Tags'} chosen`}>
            {tags.map((t) => (
              <li key={t} className="studio-g-taglist__tag" title={t}>
                <span>{titles.get(t) ?? t}</span>
                <button type="button" aria-label={`Remove ${titles.get(t) ?? t}`} disabled={disabled} onClick={() => setValue(tags.filter((x) => x !== t))}>
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {picking && Picker && (
        <Picker
          onCancel={() => setPicking(false)}
          onPick={(tagId) => {
            setPicking(false);
            add(tagId);
          }}
        />
      )}
    </FieldShell>
  );
}
