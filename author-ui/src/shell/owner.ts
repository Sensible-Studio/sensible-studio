import { PINNED_KEY, RECENT_KEY } from './recent';
import { STORAGE_KEY } from './tabs';

/**
 * The open tabs, Recent and Pinned are kept in the browser, so they were the browser's and not the person's:
 * someone signing in after a colleague on the same browser found the colleague's tabs open and their recent
 * pages listed. The browser remembers whose they are; when somebody else signs in they are put away.
 * What stays is how the screens look (theme, views, panel widths): that is the machine's.
 */
export const OWNER_KEY = 'studio.user';
const PERSONAL = [STORAGE_KEY, RECENT_KEY, PINNED_KEY];

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** Notes who is signed in; true when what was kept belonged to somebody else and was removed. */
export function takeOver(store: Store, user: string | null | undefined): boolean {
  if (!user || user === 'anonymous') return false;
  const before = store.getItem(OWNER_KEY);
  if (before === user) return false;
  store.setItem(OWNER_KEY, user);
  // Nobody noted yet (the first start after this change): what is kept is taken to be this person's.
  if (!before) return false;
  PERSONAL.forEach((k) => store.removeItem(k));
  return true;
}

/** Asks who is signed in, once at start; when the browser's tabs were somebody else's, starts again at Home. */
export function watchOwner(win: Window = window): void {
  void win
    .fetch('/libs/granite/security/currentuser.json', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
    .then((r) => (r.ok ? (r.json() as Promise<{ authorizableId?: string }>) : null))
    .then((u) => {
      try {
        if (takeOver(win.localStorage, u?.authorizableId)) win.location.replace('/studio/start.html');
      } catch {
        /* a browser without storage keeps nothing to hand over */
      }
    })
    .catch(() => undefined);
}
