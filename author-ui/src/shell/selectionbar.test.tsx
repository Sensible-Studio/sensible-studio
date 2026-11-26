import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SelectionBar } from './SelectionBar';
import type { Action } from './actions';

afterEach(cleanup);

describe('Selection bar', () => {
  it('shows a group of actions as one button with a menu, like AEM’s Create ▾', async () => {
    (globalThis as { CSS?: { escape(v: string): string } }).CSS ??= { escape: (v) => v };
    const release = vi.fn();
    const actions: Action[] = [
      { id: 'edit', label: 'Edit', run: () => {} },
      { id: 'release', label: 'Release', group: 'Create', run: release },
      { id: 'flow', label: 'Start Flow', group: 'Create', run: () => {} },
      { id: 'delete', label: 'Delete', run: () => {} },
    ];
    render(
      <Provider theme={defaultTheme}>
        <SelectionBar count={1} actions={actions} onClear={() => {}} />
      </Provider>,
    );
    const bar = screen.getByRole('toolbar', { name: 'Selection actions' });
    expect(within(bar).queryByRole('button', { name: 'Release' })).toBeNull();
    fireEvent.click(within(bar).getByRole('button', { name: 'Create' }));
    const menu = await screen.findByRole('menu', { name: 'Create' });
    expect(within(menu).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Release', 'Start Flow']);
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Release' }));
    expect(release).toHaveBeenCalled();
    // the destructive action stays last
    const names = within(bar).getAllByRole('button').map((b) => b.getAttribute('aria-label') ?? b.textContent);
    expect(names.indexOf('Delete')).toBe(names.length - 1);
  });
});
