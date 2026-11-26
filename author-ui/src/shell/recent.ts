import { useEffect, useState } from 'react';
import type { IconName } from '../start/navigation';
import { placeOfUrl, type Place } from './places';

/**
 * Recent and pinned screens, per browser (`localStorage`): what Home shows above the tiles and ⌘K
 * lists. Every screen the shell shows is a visit (the shell records it on each move; the view's own
 * title replaces the place's once it is known); a pin is a visit the author wants to keep at hand.
 * Both are URLs, as the saved tabs are, so a change of the Place type breaks nothing.
 */
export interface Visit {
  url: string;
  title: string;
  /** epoch millis of the last visit */
  at: number;
}

export interface Pin {
  url: string;
  title: string;
}

export const RECENT_KEY = 'studio.recent';
export const PINNED_KEY = 'studio.pinned';
export const RECENT_MAX = 40;
const EVENT = 'studio:recent';

function read<T>(key: string): T[] {
  try {
    const v = JSON.parse(window.localStorage.getItem(key) ?? '[]') as unknown;
    return Array.isArray(v) ? (v as T[]).filter((x) => x && typeof (x as { url?: unknown }).url === 'string') : [];
  } catch {
    return [];
  }
}

function write(key: string, value: unknown) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* private window: nothing is kept */
  }
  window.dispatchEvent(new Event(EVENT));
}

export const readRecent = () => read<Visit>(RECENT_KEY).sort((a, b) => b.at - a.at);
export const readPinned = () => read<Pin>(PINNED_KEY);

/** A screen was shown (or its title became known): it goes to the top of the recent list. */
export function recordVisit(url: string, title: string, now = Date.now()) {
  const rest = read<Visit>(RECENT_KEY).filter((v) => v.url !== url);
  write(RECENT_KEY, [{ url, title, at: now }, ...rest].slice(0, RECENT_MAX));
}

export function forgetVisit(url: string) {
  write(
    RECENT_KEY,
    read<Visit>(RECENT_KEY).filter((v) => v.url !== url),
  );
}

export function clearRecent() {
  write(RECENT_KEY, []);
}

export const isPinned = (url: string) => read<Pin>(PINNED_KEY).some((p) => p.url === url);

export function pin(url: string, title: string) {
  if (isPinned(url)) return;
  write(PINNED_KEY, [...read<Pin>(PINNED_KEY), { url, title }]);
}

export function unpin(url: string) {
  write(
    PINNED_KEY,
    read<Pin>(PINNED_KEY).filter((p) => p.url !== url),
  );
}

export function togglePin(url: string, title: string) {
  if (isPinned(url)) unpin(url);
  else pin(url, title);
}

/** The pins in a new order (drag, or move up / down). */
export function reorderPins(urls: string[]) {
  const pins = read<Pin>(PINNED_KEY);
  const byUrl = new Map(pins.map((p) => [p.url, p]));
  const ordered = urls.map((u) => byUrl.get(u)).filter((p): p is Pin => !!p);
  for (const p of pins) if (!urls.includes(p.url)) ordered.push(p);
  write(PINNED_KEY, ordered);
}

/** Re-renders on every change, in this window or another one of the same browser. */
function useStore<T>(reader: () => T[]): T[] {
  const [value, setValue] = useState<T[]>(reader);
  useEffect(() => {
    const update = () => setValue(reader());
    window.addEventListener(EVENT, update);
    window.addEventListener('storage', update);
    return () => {
      window.removeEventListener(EVENT, update);
      window.removeEventListener('storage', update);
    };
  }, [reader]);
  return value;
}

export const useRecent = () => useStore(readRecent);
export const usePinned = () => useStore(readPinned);

/** The icon of a screen, from the kind of place its URL names. */
export function iconOfUrl(url: string): IconName {
  return iconOfPlace(placeOfUrl(url));
}

export function iconOfPlace(place: Place): IconName {
  switch (place.kind) {
    case 'sites':
      return place.path.startsWith('/content/experience-fragments') ? 'xf' : place.path.startsWith('/content/forms') ? 'forms' : 'sites';
    case 'editor':
      return place.path.startsWith('/content/experience-fragments') ? 'xf' : place.path.startsWith('/content/forms') ? 'forms' : place.path.startsWith('/conf/') ? 'templates' : 'sites';
    case 'page-properties':
      return 'properties';
    case 'assets':
      return place.route.kind === 'browse' || place.route.kind === 'details' || place.route.kind === 'edit' ? 'assets' : place.route.kind === 'collection' || place.route.kind === 'collections' ? 'collection' : place.route.kind === 'profiles' ? 'processing' : place.route.kind === 'metadataprofiles' ? 'presets' : place.route.kind === 'folderschemas' || place.route.kind === 'schemas' ? 'properties' : place.route.kind === 'smarttags' ? 'autotags' : place.route.kind === 'reports' || place.route.kind === 'report' ? 'graph' : 'assets';
    case 'cf':
    case 'cf-editor':
      return 'cf';
    case 'cf-models':
    case 'cf-model-editor':
      return 'models';
    case 'templates':
      return 'templates';
    case 'workflow':
      return 'workflow';
    case 'replication':
      return 'live';
    case 'launches':
      return 'release';
    case 'linkchecker':
      return 'link';
    case 'health':
      return 'health';
    case 'jobs':
      return 'jobs';
    case 'activity':
      return 'requests';
    case 'siteconfig':
      return 'config';
    case 'projects':
      return 'projects';
    case 'form-submissions':
      return 'forms';
    case 'mail':
      return 'mail';
    case 'components':
    case 'component':
      return 'components';
    case 'blueprints':
      return 'sites';
    case 'graphql':
      return 'code';
    case 'graphiql':
      return 'query';
    case 'variations':
      return 'variations';
    case 'contexthub':
      return 'segments';
    case 'audiences':
      return 'audience';
    case 'activities':
      return 'segments';
    case 'tags':
      return 'tags';
    case 'security':
      return 'users';
    case 'translation-configs':
    case 'translation-review':
    case 'translation-rules':
    case 'translation-rule':
    case 'translation-projects':
      return 'translate';
    default:
      return 'tools';
  }
}
