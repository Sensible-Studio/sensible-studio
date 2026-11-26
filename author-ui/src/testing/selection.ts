import { fireEvent, screen, within } from '@testing-library/react';

/**
 * Actions of a selection, wherever the selection bar puts them: a few are buttons, the rest are items
 * under More (shell/SelectionBar.tsx). Tests ask for the action, not for its position.
 */
const matcher = (name: string | RegExp) => (typeof name === 'string' ? new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`) : name);

async function locate(name: string | RegExp): Promise<HTMLElement | null> {
  // jsdom has no CSS.escape, which Spectrum's menu uses for its item ids
  (globalThis as { CSS?: { escape(v: string): string } }).CSS ??= { escape: (v) => v };
  const bar = within(screen.getByRole('toolbar', { name: 'Selection actions' }));
  const direct = bar.queryByRole('button', { name: matcher(name) });
  if (direct) return direct;
  // More, then the groups (Create ▾, Restore ▾)
  const menus = bar.getAllByRole('button').filter((b) => b.getAttribute('aria-haspopup') && b.getAttribute('aria-label') !== 'Clear selection');
  for (const trigger of menus) {
    closeMenu();
    fireEvent.click(trigger);
    const menu = await screen.findByRole('menu');
    const item = within(menu).queryByRole('menuitem', { name: matcher(name) });
    if (item) return item;
  }
  closeMenu();
  return null;
}

function closeMenu() {
  const menu = screen.queryByRole('menu');
  if (menu) fireEvent.keyDown(menu, { key: 'Escape' });
}

/** Whether the selection offers the action (as a button or under More). */
export async function hasAction(name: string | RegExp): Promise<boolean> {
  const found = !!(await locate(name));
  closeMenu();
  return found;
}

/** Runs an action of the selection. */
export async function pressAction(name: string | RegExp): Promise<void> {
  const el = await locate(name);
  if (!el) throw new Error(`The selection does not offer ${String(name)}`);
  fireEvent.click(el);
}
