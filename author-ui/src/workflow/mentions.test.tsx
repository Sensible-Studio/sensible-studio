import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { Mentions } from './InboxConsole';
import { notificationUrl, type NotificationList, type NotificationsClient, type StudioNotification } from '../shell/notifications';
import { assetsRoute } from '../assets/route';

function fakeNotifications(list: StudioNotification[]) {
  let items = [...list];
  const calls: string[] = [];
  const out = (): NotificationList => ({ notifications: items, unread: items.filter((n) => !n.read).length });
  const client: NotificationsClient & { calls: string[] } = {
    calls,
    list: async () => out(),
    markRead: async (ids) => {
      calls.push(`read ${ids.join(',') || 'all'}`);
      items = items.map((n) => (ids.length === 0 || ids.includes(n.id) ? { ...n, read: true } : n));
      return out();
    },
    remove: async (ids) => {
      calls.push(`remove ${ids.join(',')}`);
      items = items.filter((n) => !ids.includes(n.id));
      return out();
    },
  };
  return client;
}

const n = (id: string, read = false): StudioNotification => ({
  id,
  kind: 'mention',
  from: 'bob',
  path: '/content/dam/wknd/surf.jpg',
  comment: 'c1',
  text: `@anna is this crop right? (${id})`,
  created: '2026-11-24T10:00:00Z',
  read,
});

describe('mentions in the Inbox', () => {
  it('lists who mentioned the user where, opens the file and marks it read', async () => {
    const client = fakeNotifications([n('n2'), n('n1', true)]);
    const opened: string[] = [];
    render(
      <Provider theme={defaultTheme}>
        <Mentions client={client} onOpen={(u) => opened.push(u)} />
      </Provider>,
    );
    expect(await screen.findByRole('heading', { name: 'Mentions · 1 new' })).toBeTruthy();
    fireEvent.click(screen.getByText(/\(n2\)/));
    expect(opened).toEqual(['/assetdetails.html/content/dam/wknd/surf.jpg#timeline']);
    await waitFor(() => expect(client.calls).toContain('read n2'));
    await screen.findByRole('heading', { name: 'Mentions' });
    fireEvent.click(screen.getByRole('button', { name: /Remove: @anna is this crop right\? \(n1\)/ }));
    await waitFor(() => expect(screen.queryByText(/\(n1\)/)).toBeNull());
    // the file opens with its Timeline
    expect(assetsRoute({ pathname: '/assetdetails.html/content/dam/wknd/surf.jpg', hash: '#timeline' })).toEqual({
      kind: 'details',
      path: '/content/dam/wknd/surf.jpg',
      properties: false,
      timeline: true,
    });
    expect(assetsRoute({ pathname: '/assetdetails.html/content/dam/wknd/surf.jpg', hash: '#annotate' })).toMatchObject({ annotate: true });
    expect(notificationUrl(n('x'))).toContain('#timeline');
  });

  it('shows nothing when no one mentioned the user', async () => {
    const { container } = render(
      <Provider theme={defaultTheme}>
        <Mentions client={fakeNotifications([])} onOpen={() => {}} />
      </Provider>,
    );
    await new Promise((r) => setTimeout(r, 10));
    expect(container.querySelector('.studio-wf__mentions')).toBeNull();
  });
});
