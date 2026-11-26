import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { security, type PermLevel, type PermRow } from './api';
import { PermissionTree } from './PermissionTree';

const COLUMNS = ['read', 'modify', 'create', 'delete', 'readAcl', 'editAcl', 'replicate'] as const;
const row = (path: string, can: Partial<Record<string, boolean>>, set: PermRow['set'] = {}, hasChildren = true): PermRow => ({
  path,
  name: path === '/' ? '/' : path.slice(path.lastIndexOf('/') + 1),
  type: 'nt:folder',
  hasChildren,
  can,
  set,
});
const LEVELS: Record<string, PermLevel> = {
  '/': { principal: 'authors', columns: [...COLUMNS], node: row('/', {}), children: [row('/content', { read: true }, { read: 'allow' }), row('/apps', {})], more: false },
  '/content': { principal: 'authors', columns: [...COLUMNS], node: row('/content', { read: true }, { read: 'allow' }), children: [row('/content/wknd', { read: true }, {}, false)], more: false },
};

describe('permission tree', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('shows what can be done where, marks what is set on the node, and saves the changes', async () => {
    vi.spyOn(security, 'tree').mockImplementation(async (_p, path) => LEVELS[path]);
    const set = vi.spyOn(security, 'setPermissions').mockResolvedValue(row('/content/wknd', { read: true, modify: true }, { modify: 'allow' }));
    render(
      <Provider theme={defaultTheme}>
        <PermissionTree principal="authors" name="Authors" />
      </Provider>,
    );
    const read = (await screen.findByRole('checkbox', { name: 'Read on /content/wknd' })) as HTMLInputElement;
    expect(read.checked).toBe(true);
    expect(screen.getByRole('checkbox', { name: 'Read on /content' }).closest('td')?.className).toContain('is-set-allow');
    expect(screen.getByRole('checkbox', { name: 'Read on /content/wknd' }).closest('td')?.className).not.toContain('is-set');

    fireEvent.click(screen.getByRole('checkbox', { name: 'Modify on /content/wknd' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Read on /apps' }));
    expect(screen.getByText('2 changes')).toBeTruthy();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(set).toHaveBeenCalledTimes(2));
    expect(set).toHaveBeenCalledWith('authors', '/content/wknd', { modify: 'allow' });
    expect(set).toHaveBeenCalledWith('authors', '/apps', { read: 'allow' });
  });

  it('puts a node back to what it inherits', async () => {
    vi.spyOn(security, 'tree').mockImplementation(async (_p, path) => LEVELS[path]);
    const set = vi.spyOn(security, 'setPermissions').mockResolvedValue(row('/content', { read: false }));
    render(
      <Provider theme={defaultTheme}>
        <PermissionTree principal="authors" name="Authors" />
      </Provider>,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Inherit on /content' }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    expect(set).toHaveBeenCalledWith('authors', '/content', { read: 'inherit' });
  });
});
