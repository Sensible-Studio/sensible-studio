import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { DialogContainer, Provider, defaultTheme } from '@adobe/react-spectrum';
import { PublishDialog } from './PublishDialog';
import type { ReplicationAgent, ReplicationClient, ReplicationStatusInfo, UnpublishedReference } from './api';

/** Just what the Publish dialog asks: the agents, the references, activate and deactivate. */
function fakeClient(agents: Partial<ReplicationAgent>[]) {
  const calls: string[] = [];
  const client = {
    calls,
    async agents() {
      return agents as ReplicationAgent[];
    },
    async references(path: string, tier?: 'preview'): Promise<UnpublishedReference[]> {
      calls.push(`references ${path} ${tier ?? 'publish'}`);
      return tier
        ? [{ path: '/content/dam/surf.jpg', type: 'asset', title: 'Surf', status: 'never' }]
        : [];
    },
    async activate(paths: string[], options: { tier?: string; at?: string } = {}): Promise<ReplicationStatusInfo[]> {
      calls.push(`activate ${paths.join(',')} ${options.tier ?? 'publish'}${options.at ? ' later' : ''}`);
      return [];
    },
    async deactivate(paths: string[], options: { tier?: string } = {}): Promise<ReplicationStatusInfo[]> {
      calls.push(`deactivate ${paths.join(',')} ${options.tier ?? 'publish'}`);
      return [];
    },
  };
  return client as unknown as ReplicationClient & { calls: string[] };
}

const show = (client: ReplicationClient, action: 'activate' | 'deactivate' = 'activate') =>
  render(
    <Provider theme={defaultTheme}>
      <DialogContainer onDismiss={() => {}}>
        <PublishDialog client={client} paths={['/content/site/en']} title="En" action={action} onDone={() => {}} />
      </DialogContainer>
    </Provider>,
  );

describe('publish to preview', () => {
  afterEach(cleanup);

  it('offers the destination only when there is a preview tier', async () => {
    show(fakeClient([{ id: 'publish', enabled: true, tier: 'publish' }]));
    await screen.findByText(/Everything it uses is already published/);
    expect(screen.queryByRole('radiogroup', { name: 'Destination' })).toBeNull();
  });

  it('sends the page and what it lacks there to the preview tier, now', async () => {
    const client = fakeClient([
      { id: 'publish', enabled: true, tier: 'publish' },
      { id: 'preview', enabled: true, tier: 'preview' },
    ]);
    show(client);
    fireEvent.click(await screen.findByRole('radio', { name: 'Preview' }));
    // what the preview tier lacks, not the publish tier
    expect(await screen.findByText('Surf')).toBeTruthy();
    expect(client.calls).toContain('references /content/site/en preview');
    expect(screen.getByText(/the published site does not change/)).toBeTruthy();
    // no Later for the preview tier
    expect(screen.queryByRole('radio', { name: 'Later' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Send to Preview' }));
    await waitFor(() => expect(client.calls).toContain('activate /content/site/en preview'));
    expect(client.calls).toContain('activate /content/dam/surf.jpg preview');
  });

  it('removes from the preview tier only', async () => {
    const client = fakeClient([{ id: 'preview', enabled: true, tier: 'preview' }]);
    show(client, 'deactivate');
    fireEvent.click(await screen.findByRole('radio', { name: 'Preview' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove from Preview' }));
    await waitFor(() => expect(client.calls).toContain('deactivate /content/site/en preview'));
  });
});
