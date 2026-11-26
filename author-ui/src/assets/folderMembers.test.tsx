import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FolderMembersSection, type FolderMembers, type FolderMembersClient, type FolderRole } from './folderMembers';
import type { PagePermissionsClient } from '../pageprops/api';

afterEach(cleanup);

function fake(initial: Partial<FolderMembers>) {
  let state: FolderMembers = { members: [], private: false, canManage: true, readable: true, ...initial };
  const client: FolderMembersClient = {
    load: vi.fn(async () => state),
    save: vi.fn(async (_path: string, members: Array<{ id: string; role: FolderRole }>, isPrivate: boolean) => {
      state = {
        ...state,
        private: isPrivate,
        members: members.map((m) => ({ id: m.id, role: m.role, title: m.id, group: m.id.endsWith('s') })),
      };
      return state;
    }),
  };
  return client;
}

const principals = {
  findPrincipals: vi.fn(async () => []),
} as unknown as PagePermissionsClient;

const show = (client: FolderMembersClient) =>
  render(
    <Provider theme={defaultTheme}>
      <FolderMembersSection path="/content/dam/brand" client={client} principals={principals} />
    </Provider>,
  );

describe('the members of a Media folder', () => {
  it('change a role, remove a member and make the folder private, at once', async () => {
    const client = fake({
      members: [
        { id: 'ana', title: 'Ana Pop', group: false, role: 'owner' },
        { id: 'designers', title: 'designers', group: true, role: 'editor' },
      ],
    });
    show(client);
    expect(await screen.findByText('Ana Pop')).toBeTruthy();
    expect(screen.getByText('(group)')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Role of designers'), { target: { value: 'viewer' } });
    await waitFor(() =>
      expect(client.save).toHaveBeenLastCalledWith(
        '/content/dam/brand',
        [
          { id: 'ana', role: 'owner' },
          { id: 'designers', role: 'viewer' },
        ],
        false,
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Remove designers' }));
    await waitFor(() => expect(client.save).toHaveBeenLastCalledWith('/content/dam/brand', [{ id: 'ana', role: 'owner' }], false));
    fireEvent.click(screen.getByLabelText('Make folder private'));
    await waitFor(() => expect(client.save).toHaveBeenLastCalledWith('/content/dam/brand', [{ id: 'ana', role: 'owner' }], true));
    expect(await screen.findByText(/Only the members and administrators see this folder/)).toBeTruthy();
  });

  it('show the members without changing them to someone who is not an owner', async () => {
    show(fake({ canManage: false, members: [{ id: 'ana', title: 'Ana Pop', group: false, role: 'owner' }] }));
    expect(await screen.findByText('Ana Pop')).toBeTruthy();
    expect(screen.getByText('Owner')).toBeTruthy();
    expect(screen.queryByLabelText('Role of Ana Pop')).toBeNull();
    expect((screen.getByLabelText('Make folder private') as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(/Only the folder's owners and administrators change its members/)).toBeTruthy();
  });

  it('give the error back and keep the members as they were', async () => {
    const client = fake({ members: [{ id: 'bob', title: 'bob', group: false, role: 'viewer' }] });
    (client.save as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('a private folder needs an owner'));
    show(client);
    await screen.findByText('bob');
    fireEvent.click(screen.getByLabelText('Make folder private'));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('a private folder needs an owner')).toBeTruthy();
    expect((screen.getByLabelText('Make folder private') as HTMLInputElement).checked).toBe(false);
  });
});
