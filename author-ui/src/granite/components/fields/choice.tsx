import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import type { GraniteComponentProps, SelectOption } from '../../registry';
import type { GraniteNode } from '../../types';
import { useField, useFormStore, useGranite } from '../../context';
import { checkboxEntries, inferCheckboxTypeHint, multiEntries, scalarEntries } from '../../form/slingPost';
import { asString, asStringList, bool, child, items, prop, resourceTypeOf, str } from '../../node';
import { commonFieldProps, describedBy, FieldInfo, FieldShell, requiredError } from '../FieldShell';

// ------------------------------------------------------------------------------------------------ checkbox

function useCheckbox(node: GraniteNode) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const rawValue = prop(node, 'value');
  const rawUnchecked = prop(node, 'uncheckedValue');
  const value = rawValue === undefined || rawValue === null ? 'true' : String(rawValue);
  const uncheckedValue = rawUnchecked === undefined || rawUnchecked === null ? undefined : String(rawUnchecked);
  const typeHint = str(node, 'typeHint') ?? inferCheckboxTypeHint(rawValue, rawUnchecked);
  const deleteHint = bool(node, 'deleteHint', true);

  const binding = useField<boolean>(
    common.name,
    () => {
      const stored = common.name ? store.contentValue(common.name) : undefined;
      if (stored === undefined) return bool(node, 'checked');
      return asString(stored) === value;
    },
    {
      label: common.label ?? str(node, 'text'),
      entries: (v) => checkboxEntries(common.name, v === true, { value, uncheckedValue, deleteHint, typeHint }),
      values: (v) => (v === true ? [value] : uncheckedValue !== undefined ? [uncheckedValue] : []),
      validate: (v) => requiredError(common.required, v !== true),
    },
  );
  return { common, ...binding };
}

/** `granite/ui/components/coral/foundation/form/checkbox` */
export function Checkbox({ node }: GraniteComponentProps) {
  const { common, id, value, setValue, error, disabled: ruleDisabled } = useCheckbox(node);
  const text = str(node, 'text') || common.label || '';
  return (
    <FieldShell id={id} noLabel description={common.description} error={error}>
      <div className="studio-g-checkrow">
        <label className="studio-g-checkbox" htmlFor={id}>
          <input
            id={id}
            type="checkbox"
            className="studio-g-checkbox__input"
            checked={value === true}
            disabled={common.disabled || common.readOnly || ruleDisabled}
            aria-describedby={describedBy(id, common.description, error)}
            aria-label={text ? undefined : common.description || common.name}
            onChange={(e) => setValue(e.target.checked)}
          />
          <span className="studio-g-checkbox__box" aria-hidden="true" />
          {text && <span className="studio-g-checkbox__text">{text}</span>}
        </label>
        {common.description && <FieldInfo id={id} description={common.description} />}
      </div>
    </FieldShell>
  );
}

/** `granite/ui/components/coral/foundation/form/switch` — a checkbox with on/off labels. */
export function Switch({ node }: GraniteComponentProps) {
  const { common, id, value, setValue, error, disabled: ruleDisabled } = useCheckbox(node);
  const onText = str(node, 'onText', 'On');
  const offText = str(node, 'offText', 'Off');
  return (
    <FieldShell id={id} label={common.label} description={common.description} error={error}>
      <label className="studio-g-switch" htmlFor={id}>
        <input
          id={id}
          type="checkbox"
          role="switch"
          className="studio-g-switch__input"
          checked={value === true}
          disabled={common.disabled || common.readOnly || ruleDisabled}
          onChange={(e) => setValue(e.target.checked)}
        />
        <span className="studio-g-switch__track" aria-hidden="true" />
        <span className="studio-g-switch__text">{value ? onText : offText}</span>
      </label>
    </FieldShell>
  );
}

// ------------------------------------------------------------------------------------------------ options

export function staticOptions(node: GraniteNode): Array<SelectOption & { selected: boolean }> {
  return items(node).map((item) => ({
    value: str(item, 'value') ?? str(item, 'text') ?? item.name,
    text: str(item, 'text') ?? str(item, 'value') ?? item.name,
    disabled: bool(item, 'disabled'),
    selected: bool(item, 'selected') || bool(item, 'checked'),
  }));
}

type OptionsState =
  | { status: 'static'; options: SelectOption[] }
  | { status: 'loading'; options: SelectOption[] }
  | { status: 'loaded'; options: SelectOption[] }
  | { status: 'unsupported'; options: SelectOption[]; dataSource: string }
  | { status: 'error'; options: SelectOption[]; message: string };

const isPromise = <T,>(v: T | Promise<T>): v is Promise<T> => !!v && typeof (v as Promise<T>).then === 'function';

/**
 * Static `items`, or options from a registered datasource implementation. With `studio:keepItems` (set by Sensible Studio's
 * client library emulations) the static items come first, like a select an editor script adds options to.
 */
export function useOptions(node: GraniteNode): OptionsState {
  const { registry, env, rootStore } = useGranite();
  const ds = child(node, 'datasource');
  const dsType = ds ? resourceTypeOf(ds) : '';
  const impl = ds ? registry.dataSource(dsType) : undefined;
  const live = !!impl && registry.isLiveDataSource(dsType);
  const keep = ds ? bool(ds, 'studio:keepItems') : false;
  const version = useSyncExternalStore(live ? rootStore.subscribe : noopSubscribe, rootStore.getVersion, rootStore.getVersion);
  const withStatic = (options: SelectOption[]) => (keep ? [...staticOptions(node), ...options] : options);

  const [state, setState] = useState<OptionsState>(() => {
    if (!ds) return { status: 'static', options: staticOptions(node) };
    if (!impl) return { status: 'unsupported', options: staticOptions(node), dataSource: dsType };
    if (live) return { status: 'loaded', options: [] };
    try {
      const result = impl(ds, env, rootStore);
      if (!isPromise(result)) return { status: 'loaded', options: withStatic(result) };
    } catch (e) {
      return { status: 'error', options: [], message: String(e) };
    }
    return { status: 'loading', options: [] };
  });
  useEffect(() => {
    if (!ds || !impl || live || state.status !== 'loading') return;
    let cancelled = false;
    Promise.resolve(impl(ds, env, rootStore))
      .then((options) => !cancelled && setState({ status: 'loaded', options: withStatic(options) }))
      .catch((e: unknown) => !cancelled && setState({ status: 'error', options: [], message: String(e) }));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dsType]);

  // live: recomputed when what the options depend on changes (every change of the dialog, without a key)
  const liveKey = live && ds ? (registry.dataSourceKey(dsType, ds, rootStore) ?? `v${version}`) : '';
  const liveResult = useMemo(() => {
    if (!live || !ds || !impl) return null;
    try {
      return impl(ds, env, rootStore);
    } catch (e) {
      const failed = Promise.reject(e);
      failed.catch(() => undefined); // handled by the effect below
      return failed;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, liveKey, dsType]);
  const [loadedLive, setLoadedLive] = useState<{ key: string; state: OptionsState } | null>(null);
  useEffect(() => {
    if (!liveResult || !isPromise(liveResult)) return;
    let cancelled = false;
    liveResult.then(
      (options) => !cancelled && setLoadedLive({ key: liveKey, state: { status: 'loaded', options: withStatic(options) } }),
      (e: unknown) => !cancelled && setLoadedLive({ key: liveKey, state: { status: 'error', options: [], message: String(e) } }),
    );
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveResult]);
  if (liveResult) {
    if (!isPromise(liveResult)) return { status: 'loaded', options: withStatic(liveResult) };
    // keep showing the previous options while the next ones load (the field stays usable)
    if (loadedLive?.key === liveKey) return loadedLive.state;
    return loadedLive ? { ...loadedLive.state, status: 'loading' } as OptionsState : { status: 'loading', options: [] };
  }
  return state;
}

const noopSubscribe = () => () => {};

function optionNote(state: OptionsState): string | null {
  if (state.status === 'unsupported') return `Options come from the datasource "${state.dataSource}", which Sensible Studio does not provide yet.`;
  if (state.status === 'error') return `Could not load options: ${state.message}`;
  return null;
}

/** Makes sure the stored value is selectable even when it is not among the known options. */
function withCurrent(options: SelectOption[], current: string[]): SelectOption[] {
  const known = new Set(options.map((o) => o.value));
  const extra = current.filter((v) => v !== '' && !known.has(v)).map((v) => ({ value: v, text: v }));
  return [...options, ...extra];
}

/** `granite/ui/components/coral/foundation/form/select` */
export function Select({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const multiple = bool(node, 'multiple');
  const emptyOption = bool(node, 'emptyOption');
  const state = useOptions(node);
  // the options known while the field initialises: static items, or a synchronous datasource's
  const declared = state.status === 'loaded' ? state.options : staticOptions(node);

  const { id, value, setValue, error, disabled: ruleDisabled } = useField<string | string[]>(
    common.name,
    () => {
      const stored = common.name ? store.contentValue(common.name) : undefined;
      if (multiple) {
        if (stored !== undefined) return asStringList(stored);
        return declared.filter((o) => o.selected).map((o) => o.value);
      }
      if (stored !== undefined) return asString(stored);
      const selected = declared.find((o) => o.selected);
      if (selected) return selected.value;
      if (emptyOption || declared.length === 0) return '';
      return declared[0].value; // a native single select always has a selection, Coral's too
    },
    {
      label: common.label,
      entries: (v) =>
        multiple
          ? multiEntries(common.name, Array.isArray(v) ? v : [])
          : scalarEntries(common.name, asString(v), str(node, 'typeHint')),
      values: (v) => (Array.isArray(v) ? v : [asString(v)]),
      validate: (v) => requiredError(common.required, Array.isArray(v) ? v.length === 0 : asString(v) === ''),
    },
  );

  // options loaded after the field initialised (an asynchronous datasource): the default they mark, else the first
  // one, as Coral's select shows it, when the content has no value
  const loadedOptions = state.status === 'loaded' ? state.options : null;
  useEffect(() => {
    if (!loadedOptions || multiple || emptyOption || !common.name || store.contentValue(common.name) !== undefined) return;
    if (asString(store.get(common.name)) !== '') return;
    const pick = loadedOptions.find((o) => o.selected) ?? loadedOptions[0];
    if (pick && pick.value !== '') store.resetInitial(common.name, pick.value);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadedOptions]);

  const current = Array.isArray(value) ? value : [value ?? ''];
  const options = withCurrent(state.options, current);
  const note = optionNote(state);

  const isDisabled = common.disabled || common.readOnly || ruleDisabled || state.status === 'loading';
  if (multiple) {
    // like Coral's multiple select: a drop-down to add a choice, the choices as removable tags below it
    const chosen = current.filter((v) => v !== '');
    const textOf = (v: string) => options.find((o) => o.value === v)?.text ?? v;
    return (
      <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
        <div className="studio-g-select">
          <select
            id={id}
            className="studio-g-input studio-g-select__control"
            value=""
            disabled={isDisabled}
            aria-required={common.required || undefined}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy(id, common.description, error)}
            onChange={(e) => e.target.value && setValue([...chosen, e.target.value])}
          >
            <option value="">{common.emptyText ?? 'Select'}</option>
            {options
              .filter((o) => !chosen.includes(o.value))
              .map((o) => (
                <option key={o.value} value={o.value} disabled={o.disabled}>
                  {o.text}
                </option>
              ))}
          </select>
        </div>
        {chosen.length > 0 && (
          <ul className="studio-g-taglist" aria-label={`${common.label ?? common.name} chosen`}>
            {chosen.map((v) => (
              <li key={v} className="studio-g-taglist__tag">
                <span>{textOf(v)}</span>
                <button type="button" aria-label={`Remove ${textOf(v)}`} disabled={isDisabled} onClick={() => setValue(chosen.filter((x) => x !== v))}>
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}
        {note && <div className="studio-g-field__note">{note}</div>}
      </FieldShell>
    );
  }

  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <div className="studio-g-select">
        <select
          id={id}
          className="studio-g-input studio-g-select__control"
          multiple={multiple}
          value={multiple ? current : current[0]}
          disabled={common.disabled || common.readOnly || ruleDisabled || state.status === 'loading'}
          aria-required={common.required || undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, common.description, error)}
          onChange={(e) => {
            if (multiple) setValue([...e.target.selectedOptions].map((o) => o.value));
            else setValue(e.target.value);
          }}
        >
          {!multiple && (emptyOption || options.length === 0) && <option value="">{common.emptyText ?? ''}</option>}
          {options.map((o) => (
            <option key={o.value} value={o.value} disabled={o.disabled}>
              {o.text}
            </option>
          ))}
        </select>
      </div>
      {note && <div className="studio-g-field__note">{note}</div>}
    </FieldShell>
  );
}

/** `granite/ui/components/coral/foundation/form/radiogroup` */
export function RadioGroup({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const options = staticOptions(node);
  const vertical = bool(node, 'vertical', true);
  const { id, value, setValue, error, disabled: ruleDisabled } = useField<string>(
    common.name,
    () => {
      const stored = common.name ? store.contentValue(common.name) : undefined;
      if (stored !== undefined) return asString(stored);
      return options.find((o) => o.selected)?.value ?? '';
    },
    {
      label: common.label,
      entries: (v) => scalarEntries(common.name, asString(v), str(node, 'typeHint')),
      values: (v) => [asString(v)],
      validate: (v) => requiredError(common.required, asString(v) === ''),
    },
  );
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <div
        className={`studio-g-radios${vertical ? '' : ' studio-g-radios--horizontal'}`}
        role="radiogroup"
        id={id}
        aria-label={common.label}
        aria-describedby={describedBy(id, common.description, error)}
      >
        {options.map((o) => (
          <label key={o.value} className="studio-g-radio">
            <input
              type="radio"
              name={id}
              value={o.value}
              checked={value === o.value}
              disabled={common.disabled || common.readOnly || ruleDisabled || o.disabled}
              onChange={() => setValue(o.value)}
            />
            <span className="studio-g-radio__dot" aria-hidden="true" />
            <span>{o.text}</span>
          </label>
        ))}
      </div>
    </FieldShell>
  );
}
