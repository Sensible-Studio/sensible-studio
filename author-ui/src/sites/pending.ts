import { useSyncExternalStore } from 'react';

/**
 * The paths that wait in a publishing queue, for the lists: a page whose request has not been delivered
 * (the live site is down, a target is held) reads "Published" by its own record, and the list said so
 * with a green dot. One question for all rows; asked again while something waits, so the mark goes
 * away by itself when the request is delivered.
 */
let paths: ReadonlySet<string> = new Set();
let timer: number | undefined;
let asked = 0;
const listeners = new Set<() => void>();

const same = (a: ReadonlySet<string>, b: ReadonlySet<string>) => a.size === b.size && [...a].every((p) => b.has(p));

export function refreshPending(): void {
  asked = Date.now();
  void fetch('/bin/studio/replication.json?op=pending', { credentials: 'same-origin' })
    .then((r) => (r.ok ? (r.json() as Promise<{ paths?: string[] }>) : null))
    .then((d) => {
      const next = new Set(d?.paths ?? []);
      if (!same(next, paths)) {
        paths = next;
        listeners.forEach((l) => l());
      }
      window.clearTimeout(timer);
      if (next.size > 0 && listeners.size > 0) timer = window.setTimeout(refreshPending, 10000);
    })
    .catch(() => undefined);
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (Date.now() - asked > 3000) refreshPending();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) window.clearTimeout(timer);
  };
}

export function usePendingPaths(): ReadonlySet<string> {
  return useSyncExternalStore(subscribe, () => paths, () => paths);
}
