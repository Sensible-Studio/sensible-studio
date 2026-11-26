import { Icon } from '@adobe/react-spectrum/Icon';
import type { ComponentProps } from 'react';

type Props = Omit<ComponentProps<typeof Icon>, 'children'>;

// Records and their models share one shape: a sheet with three fields. A record's fields have values;
// a model is the same sheet with the value slots left empty. Drawn on the 24 grid of the icon set.

/** A record: a sheet with its fields filled in. */
export function RecordIcon(props: Props) {
  return (
    <Icon {...props}>
      <svg viewBox="0 0 24 24" className="studio-icon" aria-hidden="true">
        <rect x="4" y="3" width="16" height="18" rx="2.5" />
        <path d="M8 8h.01" />
        <path d="M11.5 8H16" />
        <path d="M8 12h.01" />
        <path d="M11.5 12H16" />
        <path d="M8 16h.01" />
        <path d="M11.5 16h3" />
      </svg>
    </Icon>
  );
}

/** A record model: the same sheet with its fields still empty — outlines where a record puts its values. */
export function RecordModelIcon(props: Props) {
  return (
    <Icon {...props}>
      <svg viewBox="0 0 24 24" className="studio-icon" aria-hidden="true">
        <rect x="4" y="3" width="16" height="18" rx="2.5" />
        <path d="M8 8h.01" />
        <rect x="11" y="6.75" width="5.5" height="2.5" rx="0.75" strokeWidth="1.25" />
        <path d="M8 12h.01" />
        <rect x="11" y="10.75" width="5.5" height="2.5" rx="0.75" strokeWidth="1.25" />
        <path d="M8 16h.01" />
        <rect x="11" y="14.75" width="4" height="2.5" rx="0.75" strokeWidth="1.25" />
      </svg>
    </Icon>
  );
}

export function RecordCard({ title, model, description, kind = 'record' }: { title: string; model?: string | null; description?: string | null; kind?: 'record' | 'model' }) {
  return (
    <div className={`studio-recordcard studio-recordcard--${kind}`} aria-hidden>
      <div className="studio-recordcard__kind">
        {kind === 'model' ? <RecordModelIcon size="S" /> : <RecordIcon size="S" />}
        <span>{kind === 'model' ? 'Content Fragment Model' : model || 'Content Fragment'}</span>
      </div>
      <div className="studio-recordcard__title">{title}</div>
      {description ? <div className="studio-recordcard__desc">{description}</div> : null}
      <div className="studio-recordcard__fields">
        <span />
        <span />
        <span />
      </div>
    </div>
  );
}
