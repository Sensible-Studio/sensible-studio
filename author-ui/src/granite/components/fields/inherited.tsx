import { useEffect, useState } from 'react';
import type { GraniteComponentProps } from '../../registry';
import { useField, useFormStore, useGranite } from '../../context';
import { checkboxEntries, scalarEntries } from '../../form/slingPost';
import { asString, str } from '../../node';
import { describedBy } from '../FieldShell';

/** The page of a `…/jcr:content` path. */
export function pageOf(contentPath: string | undefined): string | null {
  if (!contentPath) return null;
  const i = contentPath.indexOf('/jcr:content');
  return i > 0 ? contentPath.slice(0, i) : contentPath;
}

/**
 * `core/wcm/components/commons/editor/dialog/inherited/v1/inherited` (Core Components' Brand Slug): the value of
 * `prop` comes from the closest ancestor page that sets it, unless "Override" is checked, which stores the page's own
 * value (`./<prop>` and `./<prop>_override`).
 */
export function InheritedField({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { env } = useGranite();
  const prop = str(node, 'prop') ?? '';
  const heading = str(node, 'heading') ?? prop;
  const name = `./${prop}`;
  const overrideName = `./${prop}_override`;
  const override = useField<boolean>(overrideName, () => asString(store.contentValue(overrideName)) === 'true', {
    label: `${heading} override`,
    entries: (v) => checkboxEntries(overrideName, v === true, { value: 'true', deleteHint: true }),
    values: (v) => (v === true ? ['true'] : []),
  });
  const field = useField<string>(name, () => asString(store.contentValue(name)), {
    label: heading,
    entries: (v) => (store.get<boolean>(overrideName) ? scalarEntries(name, asString(v)) : []),
    values: (v) => [asString(v)],
  });
  const [inherited, setInherited] = useState<string | null>(null);
  const page = pageOf(env.contentPath);
  useEffect(() => {
    if (!page || !env.inheritedValue || !prop) return;
    let live = true;
    const parent = page.slice(0, page.lastIndexOf('/'));
    env.inheritedValue(parent, prop).then(
      (v) => live && setInherited(v?.value ?? ''),
      () => live && setInherited(''),
    );
    return () => {
      live = false;
    };
  }, [env, page, prop]);
  const on = override.value === true;
  return (
    <div className="studio-g-field studio-g-inherited">
      <div className="studio-g-field__head">
        <span className="studio-g-field__label" id={`${field.id}-heading`}>
          {heading}
        </span>
      </div>
      <div className="studio-g-inherited__row">
        <label className="studio-g-checkbox" htmlFor={override.id}>
          <input
            id={override.id}
            type="checkbox"
            className="studio-g-checkbox__input"
            checked={on}
            disabled={override.disabled}
            onChange={(e) => override.setValue(e.target.checked)}
          />
          <span className="studio-g-checkbox__box" aria-hidden="true" />
          <span className="studio-g-checkbox__text">Override:</span>
        </label>
        <input
          id={field.id}
          className="studio-g-input studio-g-inherited__input"
          aria-labelledby={`${field.id}-heading`}
          aria-describedby={describedBy(field.id)}
          disabled={!on || field.disabled}
          value={on ? (field.value ?? '') : (inherited ?? '')}
          placeholder={on ? 'Override Value' : inherited ? '' : 'Override Value'}
          onChange={(e) => field.setValue(e.target.value)}
        />
      </div>
    </div>
  );
}
