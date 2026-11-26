import { createContext, useContext, useEffect, useRef, type ReactNode } from 'react';

/** A tab of the page properties that is not part of the page component's dialog (Permissions, Live Copy, …). */
export interface ExtraTab {
  name: string;
  title: string;
  /** the dialog tab it follows (by node name); at the end when absent or not found */
  after?: string;
  /** the tab's action bar (quiet buttons, top left, like AEM) */
  actions?: ReactNode;
  content: ReactNode;
}

/** Changes a tab keeps until Save, outside the Granite form. */
export interface PendingChange {
  isDirty(): boolean;
  save(): Promise<void>;
}

export const ExtraTabsContext = createContext<ExtraTab[]>([]);
export const PendingChangesContext = createContext<(key: string, change: PendingChange | null) => void>(() => {});
/** Set below the page properties' own tabs: nested tab sets render as plain Granite tabs. */
export const NestedTabsContext = createContext(false);

/** Registers a tab's pending change for as long as the tab is mounted. */
export function usePendingChange(key: string, change: PendingChange) {
  const register = useContext(PendingChangesContext);
  const ref = useRef(change);
  ref.current = change;
  useEffect(() => {
    register(key, { isDirty: () => ref.current.isDirty(), save: () => ref.current.save() });
    return () => register(key, null);
  }, [key, register]);
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="studio-pp-section">
      <h3 className="studio-pp-section__title">{title}</h3>
      {children}
    </section>
  );
}
