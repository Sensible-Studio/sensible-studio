import { NAVIGATION, TOOLS, type IconName } from '../../start/navigation';
import { titleOf, urlOf, type Place } from '../places';
import type { ShellState } from '../tabs';
import { placeOf } from '../tabs';
import { VOCAB } from '../vocabulary';
import { iconOfPlace, iconOfUrl, readRecent } from '../recent';

/** One row of the palette: where it goes (a URL) or what it does (an action), and how it is found. */
export interface Command {
  id: string;
  /** The row's title. */
  title: string;
  /** A hint after the title: the group, the path, the kind. */
  hint?: string;
  /** Which section of the palette the row belongs to. */
  group: Group;
  icon?: IconName;
  /** A place to open, in the tab or a new one (⌘↩). */
  url?: string;
  /** Or something to do. */
  run?(): void;
  /** Extra words the row is found by (its path, its group…). */
  keywords?: string;
}

export type Group = 'selection' | 'tabs' | 'go' | 'recent' | 'pages' | 'media' | 'records' | 'actions';

export const GROUPS: Array<{ id: Group; title: string }> = [
  { id: 'selection', title: 'Selection' },
  { id: 'tabs', title: 'Open tabs' },
  { id: 'go', title: 'Go to' },
  { id: 'recent', title: 'Recent' },
  { id: 'pages', title: VOCAB.pages },
  { id: 'media', title: VOCAB.media },
  { id: 'records', title: VOCAB.records },
  { id: 'actions', title: 'Actions' },
];

/** The consoles of Home and of System: every place that has a tile. */
export function placeCommands(): Command[] {
  const out: Command[] = [];
  for (const tile of NAVIGATION) {
    if (tile.target.kind !== 'href') continue;
    out.push({ id: `go:${tile.id}`, title: tile.title, hint: tile.section === 'content' ? 'Content' : 'Structure', group: 'go', icon: tile.icon, url: tile.target.url });
  }
  for (const group of TOOLS) {
    for (const tool of group.tools) {
      out.push({ id: `go:${tool.url}`, title: tool.title, hint: `${VOCAB.system} › ${group.title}`, group: 'go', icon: tool.icon, url: tool.url, keywords: `${group.title} ${tool.description}` });
    }
  }
  return out;
}

/** The open tabs, and the places their histories passed through (most recent first), without the current one. */
export function tabCommands(state: ShellState, activate: (tab: string) => void): { tabs: Command[]; recent: Command[] } {
  const active = state.tabs.find((t) => t.id === state.active);
  const current = active ? urlOf(placeOf(active)) : null;
  const tabs: Command[] = [];
  const seen = new Set<string>();
  for (const tab of state.tabs) {
    const place = placeOf(tab);
    const url = urlOf(place);
    // Home is one row however many tabs sit on it, whatever group they are scrolled to
    const key = place.kind === 'start' ? '/studio/start.html' : url;
    if (url === current || seen.has(key)) continue;
    seen.add(key);
    tabs.push({ id: `tab:${tab.id}`, title: tab.title ?? titleOf(place), hint: url, group: 'tabs', run: () => activate(tab.id), keywords: url });
  }
  // the screens this browser showed lately (shell/recent), then the tabs' own histories
  const recent: Command[] = [];
  for (const v of readRecent()) {
    if (v.url === current || seen.has(v.url)) continue;
    seen.add(v.url);
    recent.push({ id: `recent:${v.url}`, title: v.title, hint: v.url, group: 'recent', icon: iconOfUrl(v.url), url: v.url, keywords: v.url });
    if (recent.length >= 8) break;
  }
  const entries: Place[] = [];
  for (const tab of state.tabs) for (let i = tab.entries.length - 1; i >= 0; i--) entries.push(tab.entries[i]);
  for (const place of entries) {
    if (recent.length >= 8) break;
    const url = urlOf(place);
    if (url === current || seen.has(url) || place.kind === 'start') continue;
    seen.add(url);
    recent.push({ id: `recent:${url}`, title: titleOf(place), hint: url, group: 'recent', icon: iconOfPlace(place), url, keywords: url });
  }
  return { tabs, recent };
}

// ------------------------------------------------------------------------------------------ matching

/** How well a command matches the words typed: 0 when it does not; the title counts more than the keywords. */
export function score(command: Command, query: string): number {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return 1;
  const title = command.title.toLowerCase();
  const rest = `${command.hint ?? ''} ${command.keywords ?? ''}`.toLowerCase();
  let total = 0;
  for (const w of words) {
    if (title === w) total += 100;
    else if (title.startsWith(w)) total += 60;
    else if (title.split(/[\s·›/-]+/).some((part) => part.startsWith(w))) total += 40;
    else if (title.includes(w)) total += 25;
    else if (rest.includes(w)) total += 10;
    else return 0;
  }
  return total;
}

export function rank(commands: Command[], query: string): Command[] {
  return commands
    .map((c, i) => ({ c, s: score(c, query), i }))
    .filter((x) => x.s > 0)
    .sort((a, b) => b.s - a.s || a.i - b.i)
    .map((x) => x.c);
}

// ------------------------------------------------------------------------------------------ content

export interface ContentHit {
  path: string;
  title: string;
  kind: 'page' | 'media' | 'record';
}

/** Pages by title, name or path; media and records by their indexed text. Both bounded, both quick. */
export async function searchContent(q: string, signal?: AbortSignal): Promise<Command[]> {
  const text = q.trim();
  if (text.length < 2) return [];
  const [pages, dam] = await Promise.all([
    fetch(`/bin/studio/sites.json?op=search&path=/content&q=${encodeURIComponent(text)}&limit=8`, { signal, credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : { hits: [] }))
      .catch(() => ({ hits: [] })) as Promise<{ hits: Array<{ path: string; title: string }> }>,
    fetch(`/bin/studio/dam.json?op=search&path=/content/dam&fulltext=${encodeURIComponent(text)}&limit=12`, { signal, credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : { items: [] }))
      .catch(() => ({ items: [] })) as Promise<{ items: Array<{ type: string; path: string; title: string; contentFragment?: boolean }> }>,
  ]);
  const out: Command[] = [];
  for (const h of pages.hits ?? []) {
    const blocks = h.path.startsWith('/content/experience-fragments/');
    out.push({ id: `page:${h.path}`, title: h.title, hint: h.path, group: 'pages', icon: blocks ? 'xf' : 'sites', url: `/sites.html${h.path}`, keywords: h.path });
  }
  let media = 0;
  let records = 0;
  for (const i of dam.items ?? []) {
    if (i.type !== 'asset') continue;
    if (i.contentFragment) {
      if (records++ >= 6) continue;
      out.push({ id: `record:${i.path}`, title: i.title, hint: i.path, group: 'records', icon: 'cf', url: `/editor.html${i.path}`, keywords: i.path });
    } else {
      if (media++ >= 6) continue;
      out.push({ id: `media:${i.path}`, title: i.title, hint: i.path, group: 'media', icon: 'assets', url: `/assetdetails.html${i.path}`, keywords: i.path });
    }
  }
  return out;
}
