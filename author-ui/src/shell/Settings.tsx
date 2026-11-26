import type { ReactNode } from 'react';

export function SettingsGrid({ children }: { children: ReactNode }) {
  return <div className="studio-settings-grid">{children}</div>;
}

export function SettingsCard({ title, description, status, actions, children, className = '', onClick }: { title: string; description?: string; status?: ReactNode; actions?: ReactNode; children?: ReactNode; className?: string; onClick?: () => void }) {
  return (
    <section className={`studio-settings-card ${className}`} onClick={onClick}>
      <header>
        <div><h2>{title}</h2>{description && <p>{description}</p>}</div>
        {status && <div className="studio-settings-card__status">{status}</div>}
      </header>
      {children}
      {actions && <footer>{actions}</footer>}
    </section>
  );
}

export function KeyValueList({ children }: { children: ReactNode }) {
  return <dl className="studio-facts">{children}</dl>;
}

export function KeyValueRow({ label, children }: { label: string; children: ReactNode }) {
  return <div><dt>{label}</dt><dd>{children}</dd></div>;
}
