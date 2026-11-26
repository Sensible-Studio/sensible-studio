import { createContext, useContext, useEffect, useState, type MouseEvent, type ReactElement, type ReactNode } from 'react';
import './actions.scss';

/**
 * An action a console offers for its selection — Edit, Properties, Publish, Move, Delete. Declared once,
 * it shows in the action bar, in the right-click menu and in ⌘K, with the same label, icon and shortcut.
 */
export interface Action {
  id: string;
  label: string;
  icon?: ReactElement;
  /** As shown to the user: "e", "⌘C", "⌫". The console keeps handling the key itself. */
  shortcut?: string;
  disabled?: boolean;
  /** A separator before this action in a menu. */
  separator?: boolean;
  /** Takes something away (unpublish, delete): listed last, apart from the rest. */
  danger?: boolean;
  /**
   * Actions with the same group show as one button with a menu on the selection bar (AEM's "Create ▾",
   * "Restore ▾"); the right-click menu and ⌘K list them one by one.
   */
  group?: string;
  /** The icon of the group's button (Create ▾: +); else the first action's. */
  groupIcon?: ReactElement;
  run(): void;
}

/** What the console's selection is called in ⌘K: "Adventures", "3 pages". */
export interface ActionSet {
  subject: string;
  actions: Action[];
}

/** The active tab's actions, read by ⌘K. Set through `useRegisterActions` from the console. */
export const ActionsContext = createContext<{ current: ActionSet | null; register(tab: string, set: ActionSet | null): void } | null>(null);

/**
 * A console publishes the actions of its selection while it has one. `tab` is the tab's id (the shell's),
 * so the shell can keep one set per tab and show the active tab's.
 */
export function useRegisterActions(tab: string | undefined, set: ActionSet | null) {
  const ctx = useContext(ActionsContext);
  const register = ctx?.register;
  useEffect(() => {
    if (!tab || !register) return;
    register(tab, set);
    return () => register(tab, null);
    // the set is new on every render; its identity does not matter, its content is read when used
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, register, set?.subject, set?.actions.map((a) => `${a.id}:${a.disabled ? 0 : 1}`).join()]);
}

// ------------------------------------------------------------------------------------------ context menu

export interface MenuAt {
  x: number;
  y: number;
}

/**
 * The right-click menu of a console: the same actions as its bar, at the pointer. The console opens it
 * from an item's `onContextMenu` (selecting the item first when it was not) and renders `<ContextMenu>`.
 */
export function useContextMenu(): { menu: { at: MenuAt; actions: Action[] } | null; open(e: MouseEvent, actions: Action[]): void; close(): void } {
  const [menu, setMenu] = useState<{ at: MenuAt; actions: Action[] } | null>(null);
  return {
    menu,
    open: (e, actions) => {
      e.preventDefault();
      if (actions.length) setMenu({ at: { x: e.clientX, y: e.clientY }, actions });
    },
    close: () => setMenu(null),
  };
}

export function ContextMenu({ at, actions, onClose, label = 'Actions' }: { at: MenuAt; actions: Action[]; onClose(): void; label?: string }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('mousedown', onClose);
    window.addEventListener('keydown', onKey);
    window.addEventListener('blur', onClose);
    window.addEventListener('scroll', onClose, true);
    return () => {
      window.removeEventListener('mousedown', onClose);
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('blur', onClose);
      window.removeEventListener('scroll', onClose, true);
    };
  }, [onClose]);
  // keep the menu on screen: flip to the left / up when it would fall off the edge
  const width = 240;
  const height = 8 + actions.length * 30;
  const left = Math.min(at.x, window.innerWidth - width - 8);
  const top = Math.min(at.y, window.innerHeight - height - 8);
  return (
    <ul className="studio-ctxmenu" role="menu" aria-label={label} style={{ left, top }} onMouseDown={(e) => e.stopPropagation()} onContextMenu={(e) => e.preventDefault()}>
      {actions.map((a) => (
        <MenuRow key={a.id} action={a} onClose={onClose} />
      ))}
    </ul>
  );
}

function MenuRow({ action, onClose }: { action: Action; onClose(): void }) {
  const row: ReactNode = (
    <li role="none">
      <button
        type="button"
        role="menuitem"
        disabled={action.disabled}
        onClick={() => {
          onClose();
          action.run();
        }}
      >
        <span className="studio-ctxmenu__icon" aria-hidden>
          {action.icon}
        </span>
        <span className="studio-ctxmenu__label">{action.label}</span>
        {action.shortcut && <kbd className="studio-ctxmenu__key">{action.shortcut}</kbd>}
      </button>
    </li>
  );
  return action.separator ? (
    <>
      <li role="separator" className="studio-ctxmenu__sep" />
      {row}
    </>
  ) : (
    row
  );
}
