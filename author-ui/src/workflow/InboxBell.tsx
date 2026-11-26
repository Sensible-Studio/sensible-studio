import { ActionButton, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Bell from '@spectrum-icons/workflow/Bell';
import { useEffect, useState } from 'react';
import { workflowUrl } from './api';
import { httpNotifications, NOTIFICATIONS_CHANGED } from '../shell/notifications';

const POLL_MS = 60_000;

/** Dispatched on window when the inbox may have changed (a workflow started, a step completed or delegated). */
export const INBOX_CHANGED = 'studio:inbox-changed';

export function inboxChanged() {
  window.dispatchEvent(new Event(INBOX_CHANGED));
}

/** The bell in the product bar: how many workflow steps wait in the signed-in user's inbox; opens the inbox (in the shell through `onOpen`, without a page load). */
export function InboxBell({ onOpen }: { onOpen?(url: string): void } = {}) {
  const [count, setCount] = useState<number | null>(null);
  const [mentions, setMentions] = useState(0);
  useEffect(() => {
    let live = true;
    const load = () =>
      fetch('/bin/studio/workflow.json?op=inbox', { credentials: 'same-origin' })
        .then((r) => (r.ok ? (r.json() as Promise<unknown[]>) : null))
        .then((list) => live && setCount(Array.isArray(list) ? list.length : null))
        .catch(() => live && setCount(null));
    // @mentions in comments on Media files, not read yet
    const loadMentions = () =>
      httpNotifications.list().then(
        (r) => live && setMentions(r.unread),
        () => undefined,
      );
    void load();
    void loadMentions();
    const timer = window.setInterval(() => {
      void load();
      void loadMentions();
    }, POLL_MS);
    // the engine works in jobs: read again a moment after a change
    const later = () => window.setTimeout(load, 1500);
    window.addEventListener(INBOX_CHANGED, later);
    window.addEventListener(NOTIFICATIONS_CHANGED, loadMentions);
    return () => {
      live = false;
      window.clearInterval(timer);
      window.removeEventListener(INBOX_CHANGED, later);
      window.removeEventListener(NOTIFICATIONS_CHANGED, loadMentions);
    };
  }, []);
  const total = (count ?? 0) + mentions;
  const parts = [count ? `${count} step${count === 1 ? '' : 's'} waiting` : '', mentions ? `${mentions} new mention${mentions === 1 ? '' : 's'}` : ''].filter(Boolean);
  const label = parts.length ? `Inbox: ${parts.join(', ')}` : 'Inbox';
  return (
    <TooltipTrigger>
      <ActionButton isQuiet aria-label={label} onPress={() => (onOpen ? onOpen(workflowUrl.inbox()) : (window.location.href = workflowUrl.inbox()))} UNSAFE_className="studio-shell__bell">
        <Bell />
        {!!total && <span className="studio-shell__badge">{total > 99 ? '99+' : total}</span>}
      </ActionButton>
      <Tooltip>{label}</Tooltip>
    </TooltipTrigger>
  );
}
