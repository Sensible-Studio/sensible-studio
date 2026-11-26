import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { OAuthConsole } from './OAuthConsole';
import { relative, type OAuthAdminClient, type OAuthClient, type OAuthClientInput, type OAuthToken } from './oauth';

afterEach(cleanup);

const HOUR = 3_600_000;
const app: OAuthClient = { id: 'studio-abc', name: 'Site app', redirectUris: ['https://app.example/cb'], scopes: ['read', 'offline_access'], runAs: '' };
const token: OAuthToken = {
  id: 't1',
  kind: 'access',
  client: 'studio-abc',
  clientName: 'Site app',
  user: 'ana',
  scopes: ['read'],
  created: Date.now() - HOUR / 2,
  expires: Date.now() + HOUR / 2,
};

class Memory implements OAuthAdminClient {
  list: OAuthClient[];
  held: OAuthToken[];
  made: OAuthClientInput[] = [];
  revoked: string[] = [];
  removed: string[] = [];
  constructor(clients: OAuthClient[], tokens: OAuthToken[], private readonly admin = true) {
    this.list = clients;
    this.held = tokens;
  }
  async clients() {
    return { admin: this.admin, clients: this.admin ? this.list : [] };
  }
  async tokens() {
    return { admin: this.admin, user: 'ana', tokens: this.held };
  }
  async create(input: OAuthClientInput) {
    this.made.push(input);
    const client: OAuthClient = { id: 'studio-new', name: input.name, redirectUris: input.redirectUris.split('\n'), scopes: input.scopes, runAs: input.runAs, secret: 'the-secret-once' };
    this.list = [...this.list, { ...client, secret: undefined }];
    return { client, clients: this.list };
  }
  async update() {
    return { clients: this.list };
  }
  async newSecret() {
    return { secret: 'another-secret' };
  }
  async remove(id: string) {
    this.removed.push(id);
    this.list = this.list.filter((c) => c.id !== id);
    this.held = this.held.filter((t) => t.client !== id);
    return { clients: this.list };
  }
  async revoke(id: string) {
    this.revoked.push(id);
    this.held = this.held.filter((t) => t.id !== id);
    return { tokens: this.held };
  }
}

const mount = (client: Memory) =>
  render(
    <Provider theme={defaultTheme}>
      <OAuthConsole user="admin" client={client} />
    </Provider>,
  );

describe('Users › OAuth', () => {
  it('lists the clients with what they may ask for', async () => {
    mount(new Memory([app], [token]));
    expect(await screen.findByText('Site app')).toBeTruthy();
    expect(screen.getByText('https://app.example/cb')).toBeTruthy();
    expect(screen.getByText('read, offline_access')).toBeTruthy();
    expect(screen.getByText('the user who allows it')).toBeTruthy();
  });

  it('registers a client and shows its secret once', async () => {
    const client = new Memory([], []);
    mount(client);
    fireEvent.click(await screen.findByRole('button', { name: /New client/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: /^Name/ }), { target: { value: 'Mobile app' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Redirect addresses' }), { target: { value: 'https://m.example/cb' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(client.made).toEqual([{ name: 'Mobile app', redirectUris: 'https://m.example/cb', scopes: ['read'], runAs: '' }]));
    const secret = await screen.findByLabelText('Client secret');
    expect(secret.textContent).toBe('the-secret-once');
    // in the dialog with its secret, and in the list
    expect(screen.getAllByText('studio-new')).toHaveLength(2);
  });

  it('lists the tokens and revokes one after asking', async () => {
    const client = new Memory([app], [token]);
    mount(client);
    fireEvent.click(await screen.findByRole('radio', { name: /Tokens/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke the token of Site app for ana' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(client.revoked).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(client.revoked).toEqual(['t1']));
    expect(await screen.findByText('No tokens')).toBeTruthy();
  });

  it('shows somebody who is no administrator only the tokens that act as them', async () => {
    mount(new Memory([app], [token], false));
    expect(await screen.findByText('ana')).toBeTruthy();
    expect(screen.queryByRole('radio', { name: /Clients/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /New client/ })).toBeNull();
  });

  it('deleting a client ends its tokens', async () => {
    const client = new Memory([app], [token]);
    mount(client);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Site app' }));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(client.removed).toEqual(['studio-abc']));
    expect(await screen.findByText('No OAuth clients')).toBeTruthy();
  });

  it('says when in words', () => {
    const now = Date.UTC(2026, 10, 24, 12);
    expect(relative(now + 30 * 60_000, now)).toBe('in 30 minutes');
    expect(relative(now - 2 * HOUR, now)).toBe('2 hours ago');
    expect(relative(now + 29 * 24 * HOUR, now)).toBe('in 29 days');
  });
});
