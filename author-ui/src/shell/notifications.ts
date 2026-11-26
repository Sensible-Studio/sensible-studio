/**
 * Notifications of the signed-in user: @mentions in comments and annotations on Media files (AEM's
 * Notifications). Kept by studio-dam (`/bin/studio/dam.json?op=notifications`); the bell counts the unread ones and
 * the Inbox lists them.
 */
export interface StudioNotification {
  id: string;
  kind: 'mention';
  /** who wrote the comment */
  from: string | null;
  /** the file the comment is on */
  path: string;
  /** the comment's id */
  comment?: string | null;
  text: string;
  created: string | null;
  read: boolean;
}

export interface NotificationList {
  notifications: StudioNotification[];
  unread: number;
}

export interface NotificationsClient {
  list(): Promise<NotificationList>;
  /** ids empty: all of them */
  markRead(ids: string[]): Promise<NotificationList>;
  remove(ids: string[]): Promise<NotificationList>;
}

/** Dispatched on window when the notifications may have changed (read, removed). */
export const NOTIFICATIONS_CHANGED = 'studio:notifications-changed';

const API = '/bin/studio/dam.json';

async function post(op: string, ids: string[]): Promise<NotificationList> {
  const body = new URLSearchParams({ op, _charset_: 'utf-8' });
  for (const id of ids) body.append('id', id);
  const res = await fetch(API, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'X-Studio-CSRF': '1', 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body,
  });
  const json = (await res.json().catch(() => ({}))) as NotificationList & { error?: string };
  if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));
  return json;
}

export const httpNotifications: NotificationsClient = {
  async list() {
    const res = await fetch(`${API}?op=notifications`, { credentials: 'same-origin' });
    if (!res.ok) return { notifications: [], unread: 0 };
    const json = (await res.json()) as Partial<NotificationList>;
    return { notifications: json.notifications ?? [], unread: json.unread ?? 0 };
  },
  markRead: (ids) => post('readNotifications', ids),
  remove: (ids) => post('removeNotifications', ids),
};

/** Where the comment of a notification is: the file's page with its Timeline open. */
export function notificationUrl(n: StudioNotification): string {
  return `/assetdetails.html${encodeURI(n.path)}#timeline`;
}

/** The parts of a comment's text: plain text and @mentions, so a mention can be shown apart. */
export function mentionParts(text: string): Array<{ text: string; mention?: string }> {
  const out: Array<{ text: string; mention?: string }> = [];
  const re = /(^|[^\w@])@([A-Za-z0-9][A-Za-z0-9._+@-]{0,99}[A-Za-z0-9_])/g;
  let last = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    const start = m.index + m[1].length;
    if (start > last) out.push({ text: text.slice(last, start) });
    out.push({ text: `@${m[2]}`, mention: m[2] });
    last = start + 1 + m[2].length;
  }
  if (last < text.length) out.push({ text: text.slice(last) });
  return out;
}
