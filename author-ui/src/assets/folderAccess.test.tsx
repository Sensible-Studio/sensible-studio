import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FolderAccess } from './folderAccess';
import type { PagePermissions, PagePermissionsClient } from '../pageprops/api';

afterEach(cleanup);

function fake(initial: string[], supported = true) {
  let names = initial;
  const state = (): PagePermissions => ({ acl: [], cug: { supported, principals: names.map((n) => ({ principal: n, title: n, group: true, read: false, modify: false, delete: false, replicate: false, create: false })) } });
  const client: PagePermissionsClient = {
    load: vi.fn(async () => state()),
    effective: vi.fn(async () => []),
    save: vi.fn(async () => state()),
    remove: vi.fn(async () => state()),
    setCug: vi.fn(async (_page: string, principals: string[]) => {
      names = principals;
      return state();
    }),
    findPrincipals: vi.fn(async () => []),
  };
  return client;
}

const show = (client: PagePermissionsClient) =>
  render(
    <Provider theme={defaultTheme}>
      <FolderAccess path="/content/dam/wknd/members" client={client} />
    </Provider>,
  );

describe('who sees a Media folder on the published site', () => {
  it('lists the closed user group and removes a member at once', async () => {
    const client = fake(['wkndmembers', 'editors']);
    show(client);
    expect(await screen.findByText('wkndmembers')).toBeTruthy();
    expect(screen.getByText(/Only these users and groups see this folder/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove editors' }));
    await waitFor(() => expect(client.setCug).toHaveBeenCalledWith('/content/dam/wknd/members', ['wkndmembers']));
    expect(await screen.findByText('Saved.')).toBeTruthy();
    expect(screen.queryByText('editors')).toBeNull();
  });

  it('says a folder is open when nobody is named, and when closing is not possible', async () => {
    show(fake([]));
    expect(await screen.findByText(/Everyone who can see the site sees this folder/)).toBeTruthy();
    cleanup();
    show(fake([], false));
    expect(await screen.findByText('Folders cannot be closed to a group on this instance.')).toBeTruthy();
  });

  it('puts a member back when saving fails', async () => {
    const client = fake(['wkndmembers']);
    (client.setCug as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('You may not change who sees this folder'));
    show(client);
    fireEvent.click(await screen.findByRole('button', { name: 'Remove wkndmembers' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('wkndmembers')).toBeTruthy();
  });
});
