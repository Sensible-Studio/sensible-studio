import { useOptions } from './choice';
import type { GraniteComponentProps } from '../../registry';
import type { GraniteNode } from '../../types';
import { useField, useFieldHint, useFormStore } from '../../context';
import type { FormStore } from '../../form/FormStore';
import { scalarEntries } from '../../form/slingPost';
import { asString, num, str } from '../../node';
import { commonFieldProps, describedBy, FieldShell, requiredError } from '../FieldShell';

/**
 * Starting value of a single-value field: the stored content, else the dialog's `value` default.
 * Names carrying a Sling suffix (`./x@TypeHint`) are parameters, not properties, and always use the default.
 */
export function initialScalar(store: FormStore, node: GraniteNode): string {
  const name = str(node, 'name') ?? '';
  const fallback = str(node, 'value') ?? '';
  if (!name || name.includes('@')) return fallback;
  const stored = store.contentValue(name);
  return stored === undefined ? fallback : asString(stored);
}

function useScalarField(node: GraniteNode, typeHint?: string, extraValidate?: (v: string) => string | null) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const hint = typeHint ?? str(node, 'typeHint');
  const binding = useField<string>(common.name, () => initialScalar(store, node), {
    label: common.label,
    entries: (v) => scalarEntries(common.name, String(v ?? ''), hint),
    values: (v) => [String(v ?? '')],
    validate: (v) => requiredError(common.required, String(v ?? '').trim() === '') ?? extraValidate?.(String(v ?? '')) ?? null,
  });
  return { common, ...binding };
}

/** `granite/ui/components/coral/foundation/form/textfield` */
export function TextField({ node }: GraniteComponentProps) {
  const { common, id, value, setValue, error, disabled: ruleDisabled } = useScalarField(node);
  // While the value is inherited (rule-disabled), show what it inherits, as AEM's editor scripts do.
  const hint = useFieldHint(common.name);
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <input
        id={id}
        className="studio-g-input"
        type="text"
        value={ruleDisabled && hint !== undefined ? hint : (value ?? '')}
        placeholder={common.emptyText}
        maxLength={num(node, 'maxlength')}
        disabled={common.disabled || ruleDisabled}
        readOnly={common.readOnly}
        aria-required={common.required || undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, common.description, error)}
        onChange={(e) => setValue(e.target.value)}
      />
    </FieldShell>
  );
}

/** `granite/ui/components/coral/foundation/form/textarea` */
export function TextArea({ node }: GraniteComponentProps) {
  const { common, id, value, setValue, error, disabled: ruleDisabled } = useScalarField(node);
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <textarea
        id={id}
        className="studio-g-input studio-g-textarea"
        rows={num(node, 'rows') ?? 5}
        value={value ?? ''}
        placeholder={common.emptyText}
        maxLength={num(node, 'maxlength')}
        disabled={common.disabled || ruleDisabled}
        readOnly={common.readOnly}
        aria-required={common.required || undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, common.description, error)}
        onChange={(e) => setValue(e.target.value)}
      />
    </FieldShell>
  );
}

/** `granite/ui/components/coral/foundation/form/numberfield` */
export function NumberField({ node }: GraniteComponentProps) {
  const min = num(node, 'min');
  const max = num(node, 'max');
  const step = num(node, 'step');
  const validateRange = (v: string) => {
    if (v.trim() === '') return null;
    const n = Number(v);
    if (!Number.isFinite(n)) return 'Enter a number.';
    if (min !== undefined && n < min) return `Must be at least ${min}.`;
    if (max !== undefined && n > max) return `Must be at most ${max}.`;
    return null;
  };
  const { common, id, value, setValue, error, disabled: ruleDisabled } = useScalarField(node, undefined, validateRange);
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <input
        id={id}
        className="studio-g-input studio-g-input--number"
        type="number"
        inputMode="decimal"
        value={value ?? ''}
        min={min}
        max={max}
        step={step ?? 'any'}
        placeholder={common.emptyText}
        disabled={common.disabled || ruleDisabled}
        readOnly={common.readOnly}
        aria-required={common.required || undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, common.description, error)}
        onChange={(e) => setValue(e.target.value)}
      />
    </FieldShell>
  );
}

/** `granite/ui/components/coral/foundation/form/hidden` — posted, never shown. */
export function Hidden({ node }: GraniteComponentProps) {
  useScalarField(node);
  return null;
}

/** `granite/ui/components/coral/foundation/form/colorfield` */
export function ColorField({ node }: GraniteComponentProps) {
  const { common, id, value, setValue, error, disabled: ruleDisabled } = useScalarField(node);
  // swatches: the colors of a datasource (Core's `allowedcolorswatches`, from the policy) or static `items`
  const swatches = useOptions(node).options;
  const swatchOnly = str(node, 'variant') === 'swatch';
  const off = common.disabled || common.readOnly || ruleDisabled;
  const hex = /^#[0-9a-fA-F]{6}$/.test(value ?? '') ? value : '#000000';
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      {!swatchOnly && (
        <div className="studio-g-color">
          <input
            type="color"
            aria-label={`${common.label ?? 'Color'} picker`}
            className="studio-g-color__swatch"
            value={hex}
            disabled={off}
            onChange={(e) => setValue(e.target.value)}
          />
          <input
            id={id}
            className="studio-g-input"
            type="text"
            value={value ?? ''}
            placeholder={common.emptyText ?? '#rrggbb'}
            disabled={common.disabled || ruleDisabled}
            readOnly={common.readOnly}
            aria-describedby={describedBy(id, common.description, error)}
            onChange={(e) => setValue(e.target.value)}
          />
        </div>
      )}
      {(swatchOnly || swatches.length > 0) && (
        <div className="studio-g-swatches" role="radiogroup" id={swatchOnly ? id : undefined} aria-label={`${common.label ?? 'Color'} swatches`}>
          {swatches.map((s) => {
            const selected = (value ?? '').toLowerCase() === s.value.toLowerCase();
            return (
              <button
                key={s.value || 'none'}
                type="button"
                role="radio"
                aria-checked={selected}
                aria-label={s.value ? s.text : 'No color'}
                title={s.value ? s.text : 'No color'}
                className={`studio-g-swatches__swatch${s.value ? '' : ' is-none'}${selected ? ' is-selected' : ''}`}
                style={s.value ? { background: s.value } : undefined}
                disabled={off}
                onClick={() => setValue(s.value)}
              />
            );
          })}
        </div>
      )}
    </FieldShell>
  );
}

/**
 * `granite/ui/components/coral/foundation/form/datepicker`
 * Stores ISO 8601 values and posts them as JCR Dates (`@TypeHint=Date`), like AEM's on/off time fields.
 */
export function DatePicker({ node }: GraniteComponentProps) {
  const type = str(node, 'type', 'date');
  const inputType = type === 'datetime' ? 'datetime-local' : type === 'time' ? 'time' : 'date';
  const { common, id, value, setValue, error, disabled: ruleDisabled } = useScalarField(node, type === 'time' ? undefined : 'Date');
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <input
        id={id}
        className="studio-g-input"
        type={inputType}
        value={toInputValue(value ?? '', inputType)}
        disabled={common.disabled || ruleDisabled}
        readOnly={common.readOnly}
        aria-required={common.required || undefined}
        aria-describedby={describedBy(id, common.description, error)}
        onChange={(e) => setValue(fromInputValue(e.target.value, inputType))}
      />
    </FieldShell>
  );
}

function toInputValue(iso: string, inputType: string): string {
  if (!iso) return '';
  if (inputType === 'time') return iso.slice(0, 5);
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return inputType === 'date' ? date : `${date}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromInputValue(v: string, inputType: string): string {
  if (!v) return '';
  if (inputType === 'time') return v;
  const d = new Date(inputType === 'date' ? `${v}T00:00` : v);
  return Number.isNaN(d.getTime()) ? '' : d.toISOString();
}
