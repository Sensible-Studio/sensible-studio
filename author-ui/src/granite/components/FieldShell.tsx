import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import type { GraniteNode } from '../types';
import { bool, str } from '../node';

/** The attributes every Granite form field shares. */
export interface CommonFieldProps {
  name: string;
  label?: string;
  description?: string;
  emptyText?: string;
  required: boolean;
  disabled: boolean;
  readOnly: boolean;
}

export function commonFieldProps(node: GraniteNode): CommonFieldProps {
  return {
    name: str(node, 'name') ?? '',
    label: str(node, 'fieldLabel'),
    description: str(node, 'fieldDescription'),
    emptyText: str(node, 'emptyText'),
    required: bool(node, 'required'),
    disabled: bool(node, 'disabled'),
    readOnly: bool(node, 'readOnly'),
  };
}

export function requiredError(required: boolean, empty: boolean): string | null {
  return required && empty ? 'This field is required.' : null;
}

interface FieldShellProps {
  id: string;
  label?: string;
  required?: boolean;
  description?: string;
  error?: string | null;
  /**
   * For checkboxes the label sits next to the control, so the shell renders none; the control renders its own
   * {@link FieldInfo} next to its text.
   */
  noLabel?: boolean;
  children: ReactNode;
}

/**
 * Label, help and error message around a control, laid out like a Coral form field: the help text
 * (`fieldDescription`) is an info icon at the end of the label row that shows it as a tooltip.
 */
export function FieldShell({ id, label, required, description, error, noLabel, children }: FieldShellProps) {
  const head = !noLabel && (label || description);
  return (
    <div className={`studio-g-field${error ? ' studio-g-field--invalid' : ''}`}>
      {head && (
        <div className="studio-g-field__head">
          {label && (
            <label className="studio-g-field__label" htmlFor={id}>
              {label}
              {required && (
                <span className="studio-g-field__required" aria-hidden="true">
                  {' '}
                  *
                </span>
              )}
            </label>
          )}
          {description && <FieldInfo id={id} description={description} />}
        </div>
      )}
      {children}
      {error && (
        <div className="studio-g-field__error" id={`${id}-error`} role="alert">
          {error}
        </div>
      )}
    </div>
  );
}

/**
 * The (i) icon of a field with help text. The text itself is always in the DOM (visually hidden, referenced by
 * the control's `aria-describedby`); hovering or focusing the icon shows it as a tooltip.
 */
export function FieldInfo({ id, description }: { id: string; description: string }) {
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  return (
    <span className="studio-g-info">
      <button
        ref={ref}
        type="button"
        className="studio-g-info__icon"
        aria-label="Help"
        aria-describedby={`${id}-desc`}
        onMouseEnter={() => setOpen(true)}
        onMouseLeave={() => setOpen(false)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => e.key === 'Escape' && setOpen(false)}
      >
        <InfoGlyph />
      </button>
      <span className="studio-g-visually-hidden" id={`${id}-desc`}>
        {description}
      </span>
      {open && ref.current && <Tooltip anchor={ref.current} text={description} />}
    </span>
  );
}

function Tooltip({ anchor, text }: { anchor: HTMLElement; text: string }) {
  const tip = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number; below: boolean } | null>(null);
  useLayoutEffect(() => {
    const a = anchor.getBoundingClientRect();
    const t = tip.current?.getBoundingClientRect();
    const width = t?.width ?? 280;
    const height = t?.height ?? 40;
    const left = Math.max(8, Math.min(window.innerWidth - width - 8, a.left + a.width / 2 - width / 2));
    const below = a.top - height - 8 < 8;
    setPos({ left, top: below ? a.bottom + 8 : a.top - height - 8, below });
  }, [anchor, text]);
  return createPortal(
    <div
      ref={tip}
      className={`studio-g-tooltip${pos?.below ? ' is-below' : ''}`}
      role="tooltip"
      aria-hidden="true"
      style={pos ? { top: pos.top, left: pos.left } : { top: -9999, left: -9999 }}
    >
      {text}
    </div>,
    document.body,
  );
}

function InfoGlyph() {
  return (
    <svg viewBox="0 0 18 18" width="14" height="14" aria-hidden="true" focusable="false">
      <circle cx="9" cy="9" r="8" fill="currentColor" />
      <rect x="8" y="7.5" width="2" height="6" rx="1" fill="var(--studio-bg)" />
      <circle cx="9" cy="5.2" r="1.2" fill="var(--studio-bg)" />
    </svg>
  );
}

/** aria attributes linking a control to its help text and error. */
export function describedBy(id: string, description?: string, error?: string | null): string | undefined {
  const ids = [description ? `${id}-desc` : null, error ? `${id}-error` : null].filter(Boolean);
  return ids.length ? ids.join(' ') : undefined;
}
