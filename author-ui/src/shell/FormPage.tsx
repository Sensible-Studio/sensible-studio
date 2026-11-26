import { Button, ProgressCircle } from '@adobe/react-spectrum';
import type { FormEvent, ReactNode } from 'react';
import { ScreenFrame } from './ScreenFrame';

export interface FormPageTab { id: string; label: string }

function MiddlePath({ value }: { value: string }) {
  const middle = Math.ceil(value.length / 2);
  return <code className="studio-form-page__path" title={value}><span>{value.slice(0, middle)}</span><span>{value.slice(middle)}</span></code>;
}

export function FormPage({ kind, title, path, dirty, busy, tabs, activeTab, onTabChange, onReset, onSave, onClose, canSave = true, requireDirty = true, primaryLabel = 'Save', formLabel, secondaryActions, children, aside, wide, full, error }: {
  kind: string; title: string; path?: string; dirty: boolean; busy?: boolean; tabs?: FormPageTab[]; activeTab?: string; onTabChange?(id: string): void;
  onReset(): void; onSave(): void | Promise<void>; onClose(): void; canSave?: boolean; requireDirty?: boolean; primaryLabel?: string | null; formLabel?: string; secondaryActions?: ReactNode; children: ReactNode; aside?: ReactNode; wide?: boolean; /** Edge to edge, for a body that is a workspace (a form beside a preview). */ full?: boolean; error?: string | null;
}) {
  const submit = (e: FormEvent) => { e.preventDefault(); if (canSave && !busy) void onSave(); };
  // nothing is stored yet for a thing being created: no path, and a title that says so
  const fresh = !path && /^Create\b/.test(title);
  return (
    <form className={`studio-form-page${wide ? ' is-wide' : ''}${full ? ' is-full' : ''}`} aria-label={formLabel ?? `${kind}: ${title}`} onSubmit={submit}>
      <ScreenFrame
        identity={<div className="studio-form-page__identity"><span>{kind}</span><h1>{title}</h1><em className={`studio-pill${dirty ? ' studio-pill--warning' : fresh ? '' : ' studio-pill--success'}`}>{dirty ? 'Unsaved changes' : fresh ? 'New' : 'Saved'}</em>{path && <MiddlePath value={path} />}</div>}
        actions={<>{secondaryActions}{busy && <ProgressCircle size="S" isIndeterminate aria-label="Saving" />}<Button variant="secondary" style="outline" onPress={onReset} isDisabled={busy || !dirty}>Cancel</Button></>}
        primary={primaryLabel ? <Button variant="accent" type="submit" isDisabled={!canSave || busy || (requireDirty && !dirty)}>{primaryLabel}</Button> : undefined}
        onClose={onClose}
        closeLabel={`Close ${kind.toLowerCase()}`}
      />
      {tabs?.length ? <nav className="studio-form-page__tabs" aria-label={`${kind} sections`}>{tabs.map((tab) => <button type="button" key={tab.id} aria-current={tab.id === activeTab ? 'page' : undefined} onClick={() => onTabChange?.(tab.id)}>{tab.label}</button>)}</nav> : null}
      {error && <div className="studio-form-page__error" role="alert">{error}</div>}
      <div className="studio-form-page__layout">{aside && <aside>{aside}</aside>}<main>{children}</main></div>
    </form>
  );
}

export function FormSection({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return <section className="studio-form-section"><header><h2>{title}</h2>{description && <p>{description}</p>}</header><div>{children}</div></section>;
}

export function WizardPage({ title, path, steps, step, busy, error, onBack, onClose, primaryLabel, onPrimary, primaryDisabled, children, className }: {
  title: string; path?: string; steps: string[]; step: number; busy?: boolean; error?: string | null; onBack?: () => void; onClose(): void;
  primaryLabel: string; onPrimary(): void; primaryDisabled?: boolean; children: ReactNode; className?: string;
}) {
  const kind = `New ${title.replace(/^Create\s+/i, '')}`;
  return (
    <div className={`studio-wizard${className ? ` ${className}` : ''}`} role="dialog" aria-modal="true" aria-label={title}>
      <ScreenFrame
        identity={<div className="studio-form-page__identity"><span>{kind}</span><h1>{title}</h1>{path && <MiddlePath value={path} />}</div>}
        actions={onBack ? <Button variant="secondary" onPress={onBack} isDisabled={busy}>Back</Button> : undefined}
        primary={<Button variant="accent" onPress={onPrimary} isDisabled={primaryDisabled || busy} isPending={busy}>{primaryLabel}</Button>}
        onClose={onClose}
        closeLabel={`Close ${title.toLowerCase()}`}
        windowControlsDisabled={busy}
      />
      {error && <div className="studio-form-page__error" role="alert">{error}</div>}
      <div className="studio-wizard__layout">
        <nav className="studio-wizard__rail" aria-label="Steps">
          <ol>
            {steps.map((label, index) => (
              <li key={label} className={index === step ? 'is-current' : index < step ? 'is-done' : undefined} aria-current={index === step ? 'step' : undefined}>
                <span>{index + 1}</span>{label}
              </li>
            ))}
          </ol>
        </nav>
        <main className="studio-wizard__body">{children}</main>
      </div>
    </div>
  );
}
