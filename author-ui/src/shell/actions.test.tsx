import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ActionsContext, ContextMenu, useContextMenu, useRegisterActions, type Action, type ActionSet } from './actions';

afterEach(cleanup);

const actions = (run: () => void = vi.fn()): Action[] => [
  { id: 'edit', label: 'Edit', shortcut: 'e', run },
  { id: 'delete', label: 'Delete', shortcut: '⌫', separator: true, run },
  { id: 'move', label: 'Move', disabled: true, run },
];

function List({ onRun }: { onRun(): void }) {
  const ctx = useContextMenu();
  return (
    <>
      <ul>
        <li onContextMenu={(e) => ctx.open(e, actions(onRun))}>Adventures</li>
      </ul>
      {ctx.menu && <ContextMenu at={ctx.menu.at} actions={ctx.menu.actions} onClose={ctx.close} />}
    </>
  );
}

describe('ContextMenu', () => {
  it('opens on right-click with the actions, runs one and closes', () => {
    const run = vi.fn();
    render(<List onRun={run} />);
    expect(screen.queryByRole('menu')).toBeNull();
    fireEvent.contextMenu(screen.getByText('Adventures'), { clientX: 40, clientY: 50 });
    const menu = screen.getByRole('menu');
    expect(menu.textContent).toContain('Edit');
    expect((screen.getByRole('menuitem', { name: /Move/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole('separator')).toBeTruthy();
    fireEvent.click(screen.getByRole('menuitem', { name: /Delete/ }));
    expect(run).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('closes with Escape', () => {
    render(<List onRun={vi.fn()} />);
    fireEvent.contextMenu(screen.getByText('Adventures'), { clientX: 40, clientY: 50 });
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
  });
});

describe('useRegisterActions', () => {
  function Console({ tab, set }: { tab: string; set: ActionSet | null }) {
    useRegisterActions(tab, set);
    return null;
  }
  it('publishes the set for its tab and withdraws it on unmount', () => {
    const register = vi.fn();
    const Host = () => {
      const [on, setOn] = useState(true);
      return (
        <ActionsContext.Provider value={{ current: null, register }}>
          {on && <Console tab="t1" set={{ subject: 'Adventures', actions: actions() }} />}
          <button type="button" onClick={() => setOn(false)}>
            unmount
          </button>
        </ActionsContext.Provider>
      );
    };
    render(<Host />);
    expect(register).toHaveBeenLastCalledWith('t1', expect.objectContaining({ subject: 'Adventures' }));
    fireEvent.click(screen.getByText('unmount'));
    expect(register).toHaveBeenLastCalledWith('t1', null);
  });
});
