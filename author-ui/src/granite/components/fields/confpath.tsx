import { useEffect, useState } from 'react';
import type { GraniteComponentProps } from '../../registry';
import { useField, useFormStore, useGranite } from '../../context';
import { scalarEntries } from '../../form/slingPost';
import { asString, str } from '../../node';
import { commonFieldProps, describedBy, FieldShell } from '../FieldShell';
import { PathInput } from './path';
import { pageOf } from './inherited';

interface ConfValue {
  inherit: boolean;
  path: string;
}

/**
 * `studio/granite/components/confpath` (AEM's cloud configuration browser): the page's `cq:conf`, or, with "Inherited
 * from …" checked, none of its own (the closest ancestor's applies; an own value is removed on save).
 */
export function ConfPathField({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { env } = useGranite();
  const common = commonFieldProps(node);
  const stored = asString(store.contentValue(common.name));
  const { id, value, setValue, error, disabled } = useField<ConfValue>(common.name, () => ({ inherit: stored === '', path: stored }), {
    label: common.label,
    entries: (v) => {
      const c = v as ConfValue;
      if (c.inherit || !c.path.trim()) return [{ name: `${common.name}@Delete`, value: '' }];
      return scalarEntries(common.name, c.path.trim());
    },
    values: (v) => [(v as ConfValue).inherit ? '' : (v as ConfValue).path],
  });
  const c = value ?? { inherit: stored === '', path: stored };
  const [inherited, setInherited] = useState<{ value: string; from: string } | null | undefined>(undefined);
  const page = pageOf(env.contentPath);
  const prop = common.name.replace(/^\.\//, '');
  useEffect(() => {
    if (!page || !env.inheritedValue) {
      setInherited(null);
      return;
    }
    let live = true;
    env.inheritedValue(page.slice(0, page.lastIndexOf('/')), prop).then(
      (v) => live && setInherited(v),
      () => live && setInherited(null),
    );
    return () => {
      live = false;
    };
  }, [env, page, prop]);

  return (
    <div className="studio-g-confpath">
      <div className="studio-g-checkrow">
        <label className="studio-g-checkbox" htmlFor={`${id}-inherit`}>
          <input
            id={`${id}-inherit`}
            type="checkbox"
            className="studio-g-checkbox__input"
            checked={c.inherit}
            disabled={disabled || common.disabled}
            onChange={(e) => setValue({ ...c, inherit: e.target.checked })}
          />
          <span className="studio-g-checkbox__box" aria-hidden="true" />
          <span className="studio-g-checkbox__text">
            {inherited ? `Inherited from ${inherited.from}` : inherited === null ? 'Inherited (no parent page sets one)' : 'Inherited'}
          </span>
        </label>
      </div>
      <FieldShell id={id} label={common.label} description={common.description} error={error}>
        <PathInput
          id={id}
          value={c.inherit ? (inherited?.value ?? '') : c.path}
          rootPath={str(node, 'rootPath') ?? '/conf'}
          placeholder="Select Configuration Path"
          disabled={c.inherit || disabled || common.disabled}
          describedBy={describedBy(id, common.description, error)}
          title={common.label}
          onChange={(p) => setValue({ inherit: false, path: p })}
        />
      </FieldShell>
    </div>
  );
}
