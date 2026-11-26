import { placeOfUrl, samePlace, urlOf, type Place } from './places';

/**
 * The shell's tabs: each one is a stack of places with an index, like a browser tab. Pure state and a
 * reducer, so the rules (what Back does, where a closed tab's neighbour goes, what a reload must not
 * duplicate) are tested without a DOM. The shell wires it to `history` and `localStorage`.
 */
/** A stack of places with an index: a tab, or the second pane of a split tab. */
export interface Pane {
  entries: Place[];
  index: number;
  /** The title the view reported, when it knows better than `titleOf(place)`. */
  title?: string;
}

export interface Tab extends Pane {
  id: string;
  /**
   * Split view: a second pane beside the first, with a history of its own (Media on the left, the editor
   * on the right — the way to drag between two places). Addressed as `<tab id>#2` wherever an action
   * takes a tab id; `focus` says which pane Back, Forward, links and the URL belong to.
   */
  second?: Pane;
  focus?: 'first' | 'second';
}

export interface ShellState {
  tabs: Tab[];
  active: string;
  /** Recently closed tabs, newest last, for Reopen Closed Tab. */
  closed: Tab[];
}

export type ShellAction =
  /** Go to a place: pushed on the active tab, or in a new tab after it. The same place again is a no-op. */
  | { type: 'open'; place: Place; newTab?: boolean; at?: 'end'; tab?: string }
  /** Rewrite the current entry of a tab (filters, sort, the canonical URL of what is shown). */
  | { type: 'replace'; place: Place; tab?: string }
  | { type: 'back'; tab?: string }
  | { type: 'forward'; tab?: string }
  /** Jump to an entry of a tab (the browser's back/forward landing on a recorded entry). */
  | { type: 'goto'; tab: string; index: number }
  | { type: 'activate'; tab: string }
  | { type: 'close'; tab?: string }
  | { type: 'closeOthers'; tab: string }
  | { type: 'closeRight'; tab: string }
  /** A copy of a tab, with its whole history, right after it. */
  | { type: 'duplicate'; tab: string }
  | { type: 'reopen' }
  | { type: 'move'; tab: string; to: number }
  | { type: 'title'; tab: string; title: string | undefined }
  /** Split view: a second pane (at `place`, or a copy of the first pane's place) beside the first. */
  | { type: 'split'; tab?: string; place?: Place }
  /** Back to one pane: the first stays, or the second takes its place (`keep: 'second'`). */
  | { type: 'unsplit'; tab?: string; keep?: 'first' | 'second' }
  | { type: 'focus'; tab: string; pane: 'first' | 'second' };

const MAX_CLOSED = 10;
let counter = 0;
export const newId = () => `t${Date.now().toString(36)}${(counter++).toString(36)}`;

export const HOME: Place = { kind: 'start' };

export function newTab(place: Place = HOME): Tab {
  return { id: newId(), entries: [place], index: 0 };
}

export function initialState(place: Place = HOME): ShellState {
  const tab = newTab(place);
  return { tabs: [tab], active: tab.id, closed: [] };
}

export const SECOND = '#2';
/** The tab a pane id names: `t1#2` → `t1`. */
export const tabIdOf = (id: string) => (id.endsWith(SECOND) ? id.slice(0, -SECOND.length) : id);
export const isSecond = (id: string) => id.endsWith(SECOND);
export const paneId = (tab: Tab, pane: 'first' | 'second' = tab.focus ?? 'first') => (pane === 'second' && tab.second ? `${tab.id}${SECOND}` : tab.id);

export const tabOf = (state: ShellState, id: string = state.active): Tab => state.tabs.find((t) => t.id === tabIdOf(id)) ?? state.tabs[0];
/** The pane an id names: the tab itself, or its second pane (the tab when it has none). */
export const paneOf = (state: ShellState, id: string = focusedId(state)): Pane => {
  const tab = tabOf(state, id);
  return isSecond(id) && tab.second ? tab.second : tab;
};
/** The focused pane of the active tab: where Back, Forward, links and the URL go. */
export const focusedId = (state: ShellState): string => paneId(tabOf(state));
export const placeOf = (pane: Pane): Place => pane.entries[pane.index];
export const canBack = (pane: Pane) => pane.index > 0;
export const canForward = (pane: Pane) => pane.index < pane.entries.length - 1;

/** Applies `fn` to the pane `id` names: the tab, or its second pane. */
function update(state: ShellState, id: string, fn: (p: Pane) => Pane): ShellState {
  const tabId = tabIdOf(id);
  return {
    ...state,
    tabs: state.tabs.map((t) => {
      if (t.id !== tabId) return t;
      if (isSecond(id)) return t.second ? { ...t, second: fn(t.second) } : t;
      const { id: _id, second, focus, ...pane } = t;
      return { ...t, ...fn(pane), id: t.id, second, focus };
    }),
  };
}

export function reducer(state: ShellState, action: ShellAction): ShellState {
  switch (action.type) {
    case 'open': {
      if (action.newTab) {
        const tab = newTab(action.place);
        const i = action.at === 'end' ? state.tabs.length : state.tabs.findIndex((t) => t.id === state.active) + 1;
        const tabs = [...state.tabs.slice(0, i), tab, ...state.tabs.slice(i)];
        return { ...state, tabs, active: tab.id };
      }
      const id = action.tab ?? focusedId(state);
      const current = paneOf(state, id);
      if (samePlace(placeOf(current), action.place)) return state;
      return update(state, id, (t) => ({ ...t, entries: [...t.entries.slice(0, t.index + 1), action.place], index: t.index + 1, title: undefined }));
    }
    case 'replace': {
      const id = action.tab ?? focusedId(state);
      return update(state, id, (t) => ({ ...t, entries: t.entries.map((p, i) => (i === t.index ? action.place : p)) }));
    }
    case 'back': {
      const id = action.tab ?? focusedId(state);
      return update(state, id, (t) => (canBack(t) ? { ...t, index: t.index - 1, title: undefined } : t));
    }
    case 'forward': {
      const id = action.tab ?? focusedId(state);
      return update(state, id, (t) => (canForward(t) ? { ...t, index: t.index + 1, title: undefined } : t));
    }
    case 'goto': {
      const tabId = tabIdOf(action.tab);
      if (!state.tabs.some((t) => t.id === tabId)) return state;
      const s = update(state, action.tab, (t) => ({ ...t, index: Math.max(0, Math.min(action.index, t.entries.length - 1)), title: undefined }));
      return { ...s, active: tabId, tabs: s.tabs.map((t) => (t.id === tabId && t.second ? { ...t, focus: isSecond(action.tab) ? 'second' : 'first' } : t)) };
    }
    case 'activate':
      return state.tabs.some((t) => t.id === action.tab) ? { ...state, active: action.tab } : state;
    case 'split': {
      const tab = tabOf(state, action.tab ?? state.active);
      if (tab.second) return state;
      const second: Pane = { entries: [action.place ?? placeOf(tab)], index: 0 };
      return { ...state, tabs: state.tabs.map((t) => (t.id === tab.id ? { ...t, second, focus: 'second' } : t)) };
    }
    case 'unsplit': {
      const tab = tabOf(state, action.tab ?? state.active);
      if (!tab.second) return state;
      const kept: Tab = action.keep === 'second' ? { id: tab.id, ...tab.second } : { id: tab.id, entries: tab.entries, index: tab.index, title: tab.title };
      return { ...state, tabs: state.tabs.map((t) => (t.id === tab.id ? kept : t)) };
    }
    case 'focus': {
      const tab = tabOf(state, action.tab);
      if (!tab.second || tab.focus === action.pane || (tab.focus === undefined && action.pane === 'first')) return state;
      return { ...state, tabs: state.tabs.map((t) => (t.id === tab.id ? { ...t, focus: action.pane } : t)) };
    }
    case 'close': {
      const id = tabIdOf(action.tab ?? state.active);
      const i = state.tabs.findIndex((t) => t.id === id);
      if (i < 0) return state;
      const closing = state.tabs[i];
      const closed = [...state.closed, closing].slice(-MAX_CLOSED);
      const tabs = state.tabs.filter((t) => t.id !== id);
      if (tabs.length === 0) {
        const home = newTab(HOME);
        return { tabs: [home], active: home.id, closed };
      }
      // the neighbour on the left takes over, like a browser; the right one when it was the first
      const active = state.active === id ? tabs[Math.max(0, i - 1)].id : state.active;
      return { tabs, active, closed };
    }
    case 'closeOthers': {
      const keep = state.tabs.find((t) => t.id === action.tab);
      if (!keep) return state;
      const closed = [...state.closed, ...state.tabs.filter((t) => t.id !== action.tab)].slice(-MAX_CLOSED);
      return { tabs: [keep], active: keep.id, closed };
    }
    case 'closeRight': {
      const i = state.tabs.findIndex((t) => t.id === action.tab);
      if (i < 0 || i === state.tabs.length - 1) return state;
      const closed = [...state.closed, ...state.tabs.slice(i + 1)].slice(-MAX_CLOSED);
      const tabs = state.tabs.slice(0, i + 1);
      return { tabs, active: tabs.some((t) => t.id === state.active) ? state.active : action.tab, closed };
    }
    case 'duplicate': {
      const i = state.tabs.findIndex((t) => t.id === action.tab);
      if (i < 0) return state;
      const copy = { ...state.tabs[i], id: newId() };
      const tabs = [...state.tabs];
      tabs.splice(i + 1, 0, copy);
      return { ...state, tabs, active: copy.id };
    }
    case 'reopen': {
      const tab = state.closed[state.closed.length - 1];
      if (!tab) return state;
      const reopened = { ...tab, id: newId() };
      return { tabs: [...state.tabs, reopened], active: reopened.id, closed: state.closed.slice(0, -1) };
    }
    case 'move': {
      const from = state.tabs.findIndex((t) => t.id === action.tab);
      if (from < 0) return state;
      const tabs = [...state.tabs];
      const [tab] = tabs.splice(from, 1);
      tabs.splice(Math.max(0, Math.min(action.to, tabs.length)), 0, tab);
      return { ...state, tabs };
    }
    case 'title':
      return update(state, action.tab, (t) => (t.title === action.title ? t : { ...t, title: action.title }));
    default:
      return state;
  }
}

// ------------------------------------------------------------------------------------------ persistence

/** What is saved: URLs, not places, so a change in the Place type never breaks a saved session. */
interface Saved {
  v: 1;
  tabs: Array<{ id: string; urls: string[]; index: number; second?: { urls: string[]; index: number }; focus?: 'first' | 'second' }>;
  active: string;
}

export const STORAGE_KEY = 'studio.shell';

export function serialize(state: ShellState): string {
  const saved: Saved = {
    v: 1,
    tabs: state.tabs.map((t) => ({
      id: t.id,
      urls: t.entries.map(urlOf),
      index: t.index,
      ...(t.second ? { second: { urls: t.second.entries.map(urlOf), index: t.second.index }, focus: t.focus } : {}),
    })),
    active: state.active,
  };
  return JSON.stringify(saved);
}

export function deserialize(json: string | null): ShellState | null {
  if (!json) return null;
  try {
    const saved = JSON.parse(json) as Partial<Saved>;
    if (saved.v !== 1 || !Array.isArray(saved.tabs) || saved.tabs.length === 0) return null;
    const tabs: Tab[] = saved.tabs
      .filter((t) => t && Array.isArray(t.urls) && t.urls.length > 0 && typeof t.id === 'string')
      .map((t) => {
        const entries = t.urls.map((u) => placeOfUrl(String(u)));
        const tab: Tab = { id: t.id, entries, index: Math.max(0, Math.min(Number(t.index) || 0, entries.length - 1)) };
        if (t.second && Array.isArray(t.second.urls) && t.second.urls.length > 0) {
          const e2 = t.second.urls.map((u) => placeOfUrl(String(u)));
          tab.second = { entries: e2, index: Math.max(0, Math.min(Number(t.second.index) || 0, e2.length - 1)) };
          tab.focus = t.focus === 'second' ? 'second' : 'first';
        }
        return tab;
      });
    if (tabs.length === 0) return null;
    const active = tabs.some((t) => t.id === saved.active) ? String(saved.active) : tabs[0].id;
    return { tabs, active, closed: [] };
  } catch {
    return null;
  }
}

/**
 * The state the shell boots with: the saved tabs, with the URL the browser was opened at shown — in the
 * tab that already shows it, else in a new tab. A reload of the active tab's URL therefore opens nothing new.
 */
export function boot(saved: ShellState | null, place: Place): ShellState {
  if (!saved) return initialState(place);
  const active = tabOf(saved);
  if (samePlace(placeOf(paneOf(saved)), place)) return saved;
  if (active.second && samePlace(placeOf(active.second), place)) return { ...saved, tabs: saved.tabs.map((t) => (t.id === active.id ? { ...t, focus: 'second' } : t)) };
  const showing = saved.tabs.find((t) => samePlace(placeOf(t), place) || (t.second && samePlace(placeOf(t.second), place)));
  if (showing) return { ...saved, active: showing.id, tabs: saved.tabs.map((t) => (t.id === showing.id && t.second ? { ...t, focus: samePlace(placeOf(t), place) ? 'first' : 'second' } : t)) };
  return reducer(saved, { type: 'open', place, newTab: true, at: 'end' });
}
