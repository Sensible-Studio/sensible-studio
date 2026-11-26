import { ActionButton } from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import type { ReactNode } from 'react';

export function InspectorShell({ kind, subject, path, pills, onClose, children, label = 'Inspector', className }: { kind: string; subject: string; path?: string; pills?: ReactNode; onClose(): void; children: ReactNode; label?: string; className?: string }) {
  return (
    <aside className={`studio-inspector-shell${className ? ` ${className}` : ''}`} aria-label={label}>
      <header>
        <div className="studio-inspector-shell__top"><span>{kind}</span><ActionButton isQuiet aria-label="Close inspector" onPress={onClose}><Close /></ActionButton></div>
        <h2>{subject}</h2>
        {path && <p title={path}>{path}</p>}
        {pills && <div className="studio-inspector-shell__pills">{pills}</div>}
      </header>
      <div className="studio-inspector-shell__body">{children}</div>
    </aside>
  );
}

export function InspectorSection({ title, children }: { title: string; children: ReactNode }) {
  return <section className="studio-inspector-shell__section"><h3>{title}</h3>{children}</section>;
}
