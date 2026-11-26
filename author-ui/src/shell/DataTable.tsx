import type { ReactNode } from 'react';

export function DataTableFrame({ children, label }: { children: ReactNode; label?: string }) {
  return <div className="studio-data-table" role="region" aria-label={label}>{children}</div>;
}
