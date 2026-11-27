import { ToastQueue } from '@adobe/react-spectrum';

/**
 * A session ends after a while without use; the server then answers every request with the sign-in page, which a
 * screen would show as stale lists, empty answers or "invalid JSON". This notices it once and says what happened,
 * with the way back in: the sign-in page returns to the address the person was at.
 */
export function signedOut(response: Pick<Response, 'redirected' | 'url' | 'status'>, origin: string): boolean {
  if (response.status === 401) return true;
  if (!response.url) return false;
  let url: URL;
  try {
    url = new URL(response.url, origin);
  } catch {
    return false;
  }
  return url.origin === origin && /\/login\.html$/.test(url.pathname) && (response.redirected || url.searchParams.has('resource'));
}

export function signInUrl(loc: Pick<Location, 'pathname' | 'search' | 'hash'>): string {
  return `/libs/granite/core/content/login.html?resource=${encodeURIComponent(loc.pathname + loc.search + loc.hash)}`;
}

let installed = false;
let told = false;

export function installSessionWatch(win: Window = window): void {
  if (installed || typeof win.fetch !== 'function') return;
  installed = true;
  const original = win.fetch.bind(win);
  win.fetch = async (...args: Parameters<typeof fetch>) => {
    const response = await original(...args);
    try {
      if (!told && signedOut(response, win.location.origin)) {
        told = true;
        ToastQueue.negative('You were signed out after a while without use. What you see may be out of date.', {
          actionLabel: 'Sign in',
          onAction: () => win.location.assign(signInUrl(win.location)),
          shouldCloseOnAction: true,
          onClose: () => {
            // said again if it is still so the next time something is asked of the server
            win.setTimeout(() => (told = false), 30000);
          },
        });
      }
    } catch {
      // telling is a courtesy: never in the way of the request itself
    }
    return response;
  };
}
