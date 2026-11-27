import { ActionButton, Item, Menu, MenuTrigger, Section, Text, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import ChevronLeft from '@spectrum-icons/workflow/ChevronLeft';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Close from '@spectrum-icons/workflow/Close';
import Home from '@spectrum-icons/workflow/Home';
import RailLeft from '@spectrum-icons/workflow/RailLeft';
import Search from '@spectrum-icons/workflow/Search';
import { useCallback, useEffect, useMemo, useReducer, useRef, useState, type Key, type MouseEvent } from 'react';
import { flushSync } from 'react-dom';
import type { StudioClient } from '../editor/api/StudioClient';
import { InboxBell } from '../workflow/InboxBell';
import { ActionsContext, type ActionSet } from './actions';
import { CommandPalette } from './palette/CommandPalette';
import { load, shellPlaceOf, ShellContext, TabContext, type NavigateOptions, type NavTarget, type ShellApi } from './ShellContext';
import { titleOf, urlOf, type Place } from './places';
import { VOCAB } from './vocabulary';
import { newTabPlace, readNewTab, storeNewTab, type NewTabPref } from './prefs';
import { readTheme, storeTheme, THEMES, type ThemeId } from '../theme/pref';
import { recordVisit } from './recent';
import { readSidebar, Sidebar, storeSidebar, takesSidebar } from './Sidebar';
import { boot, canBack, canForward, deserialize, focusedId, HOME, isSecond, paneId, paneOf, placeOf, reducer, serialize, STORAGE_KEY, tabIdOf, tabOf, type Pane, type ShellAction, type ShellState, type Tab } from './tabs';
import { KEEP_ALIVE, MAX_BACKGROUND_EDITORS, SHELL_KINDS, View } from './views';
import './shell.scss';

interface Props {
  client: StudioClient;
  /** The place the browser was opened at. */
  initial: Place;
}

/**
 * One window for all of Studio: one bar with back / forward / home, the tabs and the user, and the
 * tabs' views below it. Each tab is a stack of places; the browser's own
 * back and forward land on the entries the shell recorded; the tabs are saved and come back on reload.
 */
export function Shell({ client, initial }: Props) {
  const [state, dispatch] = useReducer(reducer, initial, (place) => boot(deserialize(readStorage()), place));
  const [user, setUser] = useState<string | null>(null);
  // the window's sidebar (pinned, the content tree, Media's folders) and where a new tab opens: per browser
  const [sidebar, setSidebar] = useState(readSidebar);
  const toggleSidebar = useCallback(() => {
    // the sidebar is the editors': elsewhere there is nothing to toggle (and no button)
    if (!takesSidebar(placeOf(paneOf(stateRef.current)))) return;
    setSidebar((open) => {
      storeSidebar(!open);
      return !open;
    });
  }, []);
  const [newTab, setNewTab] = useState<NewTabPref>(readNewTab);
  const [theme, setTheme] = useState<ThemeId>(readTheme);
  const chooseNewTab = (pref: NewTabPref) => {
    storeNewTab(pref);
    setNewTab(pref);
  };
  // Full screen: the active view alone, the bar with the tabs hidden. It is a mode of the window, not of one
  // screen: entered from any screen's top bar, it stays while the author moves on (Home, the path, a page
  // opened from a list, Close) and ends only with Exit full screen. Kept for this browser tab, so a move
  // that reloads the page keeps it too.
  const [full, setFull] = useState(readFull);
  const stateRef = useRef(state);
  stateRef.current = state;
  const enterFull = useCallback(() => {
    setFull(true);
    storeFull(true);
  }, []);

  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);

  // ------------------------------------------------------------------------------------------ persistence

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, serialize(state));
    } catch {
      /* private window: the tabs live for this page only */
    }
    // the screen shown is a recent one (Home itself is not), under the title its view reports
    const pane = paneOf(state);
    const place = placeOf(pane);
    if (place.kind !== 'start') recordVisit(urlOf(place), pane.title ?? titleOf(place));
  }, [state]);

  // ------------------------------------------------------------------------------------------ browser history

  // The URL is the active tab's place. Every move the shell makes is a browser entry carrying
  // {tab, index}, so the browser's back and forward land where the shell can follow; a move that
  // came *from* the browser (popstate) must not be recorded again.
  const fromPop = useRef(false);
  const recorded = useRef<{ tab: string; index: number; url: string } | null>(null);
  useEffect(() => {
    const tab = tabOf(state);
    const pane = paneOf(state);
    const place = placeOf(pane);
    const url = urlOf(place);
    const key = { tab: focusedId(state), index: pane.index, url };
    document.title = `${tabTitle(tab)} · Sensible Studio`;
    const last = recorded.current;
    recorded.current = key;
    if (fromPop.current) return;
    if (!last) window.history.replaceState({ studio: key }, '', url);
    else if (last.tab === key.tab && last.index === key.index) {
      if (last.url !== url) window.history.replaceState({ studio: key }, '', url);
    } else window.history.pushState({ studio: key }, '', url);
  }, [state]);

  useEffect(() => {
    const onPop = (e: PopStateEvent) => {
      const s = (e.state as { studio?: { tab: string; index: number } } | null)?.studio;
      fromPop.current = true;
      try {
        flushSync(() => {
          // the recorded entry, when the tab still has it at that index (the tabs may have changed
          // since — another visit, a restore); otherwise the URL the browser landed on
          const tab = s ? stateRef.current.tabs.find((t) => t.id === tabIdOf(s.tab)) : undefined;
          const pane = tab && s ? (isSecond(s.tab) ? tab.second : tab) : undefined;
          const entry = pane?.entries[s!.index];
          const here = `${window.location.pathname}${window.location.search}${window.location.hash}`;
          if (tab && entry && urlOf(entry) === here) dispatch({ type: 'goto', tab: tab.id, index: s!.index });
          else {
            const place = shellPlaceOf(window.location.href, SHELL_KINDS);
            if (place) dispatch({ type: 'open', place });
          }
        });
      } finally {
        fromPop.current = false;
      }
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  // ------------------------------------------------------------------------------------------ guards

  // A view with unsaved work guards its tab (TabApi.setGuard). `send` is `dispatch` for everything the
  // shell does on the user's behalf: when the action would move a guarded tab away from its place, the
  // guard is asked first. A view's own moves (setPlace, leave) go straight to `dispatch`.
  const guards = useRef(new Map<string, () => Promise<boolean>>());
  const send = useCallback((action: ShellAction) => {
    const id = leavingTab(action, stateRef.current);
    const guard = id ? guards.current.get(id) : undefined;
    if (!guard) {
      dispatch(action);
      return;
    }
    void guard().then((ok) => ok && dispatch(action));
  }, []);

  // ------------------------------------------------------------------------------------------ navigation

  const navigate = useCallback(
    (target: NavTarget, options?: NavigateOptions) => {
      const place = shellPlaceOf(target, SHELL_KINDS);
      if (!place) {
        load(target, options);
        return;
      }
      if (options?.replace) send({ type: 'replace', place, tab: options.tab });
      else send({ type: 'open', place, newTab: options?.newTab, tab: options?.tab });
    },
    [send],
  );

  const exitFull = useCallback(() => {
    setFull(false);
    storeFull(false);
  }, []);
  const api = useMemo<ShellApi>(() => ({ state, dispatch: send, kinds: SHELL_KINDS, navigate, full, enterFull, exitFull }), [state, send, navigate, full, enterFull, exitFull]);

  // ------------------------------------------------------------------------------------------ actions

  // the actions each console offers for its selection, one set per tab; ⌘K shows the active tab's
  const [actionSets, setActionSets] = useState<Record<string, ActionSet | null>>({});
  const registerActions = useCallback((tab: string, set: ActionSet | null) => {
    setActionSets((m) => (m[tab] === set ? m : { ...m, [tab]: set }));
  }, []);
  const actionsApi = useMemo(() => ({ current: actionSets[state.active] ?? null, register: registerActions }), [actionSets, state.active, registerActions]);

  // ------------------------------------------------------------------------------------------ keyboard

  // ⌘K / Ctrl+K: the command palette, from anywhere, even while typing
  const [palette, setPalette] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.code === 'KeyK') {
        e.preventDefault();
        setPalette((v) => !v);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  useEffect(() => {
    const typing = (t: EventTarget | null) => {
      const el = t as HTMLElement | null;
      return !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));
    };
    const onKey = (e: KeyboardEvent) => {
      // Alt, because ⌘T / ⌘W / ⌘1–9 belong to the browser and cannot be taken from it
      if (!e.altKey || e.metaKey || e.ctrlKey || typing(e.target)) return;
      const code = e.code;
      if (code === 'KeyT' && e.shiftKey) send({ type: 'reopen' });
      else if (code === 'KeyT') send({ type: 'open', place: newTabPlace(stateRef.current), newTab: true });
      else if (code === 'KeyB') toggleSidebar();
      else if (code === 'KeyW') send({ type: 'close' });
      else if (code === 'Backslash') {
        const tab = tabOf(stateRef.current);
        if (tab.second) send({ type: 'unsplit', keep: tab.focus === 'second' ? 'second' : 'first' });
        else dispatch({ type: 'split' });
      }
      else if (code === 'ArrowLeft') send({ type: 'back' });
      else if (code === 'ArrowRight') send({ type: 'forward' });
      else if (/^Digit[1-9]$/.test(code)) {
        const n = Number(code.slice(5));
        const tabs = stateRef.current.tabs;
        const tab = n === 9 ? tabs[tabs.length - 1] : tabs[n - 1];
        if (tab) dispatch({ type: 'activate', tab: tab.id });
      } else return;
      e.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [send, toggleSidebar]);

  // ------------------------------------------------------------------------------------------ which views stay mounted

  const active = tabOf(state);
  const focused = paneOf(state);
  const recent = useRef<string[]>([]); // tab ids, most recently active last
  recent.current = [...recent.current.filter((id) => id !== active.id && state.tabs.some((t) => t.id === id)), active.id];
  const mounted = (tab: Tab) => {
    if (tab.id === active.id) return true;
    const place = placeOf(tab);
    if (!KEEP_ALIVE.has(place.kind)) return false;
    if (place.kind !== 'editor') return true;
    const editors = recent.current.filter((id) => id !== active.id && placeOf(tabOf(state, id)).kind === 'editor');
    return editors.slice(-MAX_BACKGROUND_EDITORS).includes(tab.id);
  };

  // the user menu's choices for where a new tab opens (an array: a collection takes no empty children)
  const here = placeOf(focused);
  const hereTitle = focused.title ?? titleOf(here);
  const newTabItems = [
    <Item key="newtab:home">{VOCAB.home}</Item>,
    <Item key="newtab:current" textValue="Where I am">
      <Text>Where I am</Text>
      <Text slot="description">A copy of the tab you are in</Text>
    </Item>,
  ];
  if (newTab.kind === 'fixed') {
    newTabItems.push(
      <Item key="newtab:fixed" textValue={newTab.title}>
        <Text>Always: {newTab.title}</Text>
        <Text slot="description">{newTab.url}</Text>
      </Item>,
    );
  }
  if (here.kind !== 'start' && !(newTab.kind === 'fixed' && newTab.url === urlOf(here))) {
    newTabItems.push(
      <Item key="newtab:here" textValue="Always this screen">
        <Text>Always this screen</Text>
        <Text slot="description">{hereTitle}</Text>
      </Item>,
    );
  }

  const signOut = () => {
    window.location.href = `/system/sling/logout?resource=${encodeURIComponent(window.location.pathname)}`;
  };

  return (
    <ShellContext.Provider value={api}>
      <ActionsContext.Provider value={actionsApi}>
      <div className={`studio-shellroot${full ? ' studio-shellroot--full' : ''}`}>
        <header className="studio-shellbar" role="toolbar" aria-label="Navigation" hidden={full}>
          <a
            className="studio-shellbar__brand"
            href={urlOf(HOME)}
            title="Sensible Studio"
            onClick={(e) => {
              e.preventDefault();
              navigate(HOME, { newTab: e.metaKey || e.ctrlKey });
            }}
          >
            <span className="studio-shellbar__mark" aria-hidden>
              S
            </span>
          </a>
          <div className="studio-shellbar__nav">
            {takesSidebar(here) && (
              <TooltipTrigger delay={600}>
                <ActionButton isQuiet aria-label={sidebar ? 'Hide the sidebar' : 'Show the sidebar'} aria-pressed={sidebar} onPress={toggleSidebar}>
                  <RailLeft />
                </ActionButton>
                <Tooltip>Sidebar: find and open another page without leaving the editor (Alt+B)</Tooltip>
              </TooltipTrigger>
            )}
            <ActionButton isQuiet aria-label="Back" isDisabled={!canBack(focused)} onPress={() => send({ type: 'back' })}>
              <ChevronLeft />
            </ActionButton>
            <ActionButton isQuiet aria-label="Forward" isDisabled={!canForward(focused)} onPress={() => send({ type: 'forward' })}>
              <ChevronRight />
            </ActionButton>
            <ActionButton isQuiet aria-label="Home" onPress={() => navigate(HOME)}>
              <Home />
            </ActionButton>
            <TooltipTrigger delay={600}>
              <ActionButton isQuiet aria-label="Go to" onPress={() => setPalette(true)}>
                <Search />
              </ActionButton>
              <Tooltip>Go anywhere (⌘K)</Tooltip>
            </TooltipTrigger>
          </div>
          <TabStrip state={state} dispatch={send} newTab={() => newTabPlace(stateRef.current, newTab)} />
          <div className="studio-shellbar__actions">
            <InboxBell onOpen={(url) => navigate(url)} />
            <MenuTrigger align="end" closeOnSelect>
              <ActionButton isQuiet aria-label="User">
                <span className="studio-shellbar__avatar" aria-hidden>
                  {(user ?? '?').slice(0, 1).toUpperCase()}
                </span>
              </ActionButton>
              <Menu
                selectionMode="multiple"
                selectedKeys={[`newtab:${newTab.kind}`, `theme:${theme}`]}
                onAction={(k: Key) => {
                  const key = String(k);
                  if (key.startsWith('theme:')) {
                    const id = key.slice(6) as ThemeId;
                    storeTheme(id);
                    setTheme(id);
                  } else if (k === 'signout') signOut();
                  else if (k === 'newtab:home') chooseNewTab({ kind: 'home' });
                  else if (k === 'newtab:current') chooseNewTab({ kind: 'current' });
                  else if (k === 'newtab:here') chooseNewTab({ kind: 'fixed', url: urlOf(here), title: hereTitle });
                }}
              >
                <Section title="A new tab opens at">{newTabItems}</Section>
                <Section title="Theme">
                  {THEMES.map((t) => (
                    <Item key={`theme:${t.id}`} textValue={t.label}>
                      <Text>{t.label}</Text>
                      <Text slot="description">{t.description}</Text>
                    </Item>
                  ))}
                </Section>
                <Section title={user ? `Signed in as ${user}` : 'Not signed in'}>
                  <Item key="signout">Sign Out</Item>
                </Section>
              </Menu>
            </MenuTrigger>
          </div>
        </header>
        <div className={`studio-shellbody${sidebar && !full && takesSidebar(here) ? ' studio-shellbody--sidebar' : ''}`}>
        {sidebar && !full && takesSidebar(here) && <Sidebar client={client} place={placeOf(focused)} onNavigate={(pl) => navigate(pl)} />}
        <main className="studio-shell-content">
          {state.tabs.filter(mounted).map((tab) => {
            // a pane: the tab itself, or its second half; each has its own context, so a view inside it
            // navigates, guards and closes its own pane
            const pane = (which: 'first' | 'second', p: Pane) => {
              const id = paneId(tab, which);
              const place = placeOf(p);
              const focusedHere = (tab.focus ?? 'first') === which || !tab.second;
              return (
                <TabContext.Provider
                  key={id}
                  value={{
                    id,
                    place,
                    active: tab.id === active.id && focusedHere,
                    canBack: canBack(p),
                    close: () => (tab.second ? dispatch({ type: 'unsplit', tab: tab.id, keep: which === 'first' ? 'second' : 'first' }) : dispatch({ type: 'close', tab: tab.id })),
                    setPlace: (pl, o) => dispatch(o?.replace ? { type: 'replace', place: pl, tab: id } : { type: 'open', place: pl, tab: id }),
                    setTitle: (title) => dispatch({ type: 'title', tab: id, title }),
                    setGuard: (guard) => {
                      if (guard) guards.current.set(id, guard);
                      else guards.current.delete(id);
                    },
                    leave: (fallback) => {
                      if (canBack(p)) dispatch({ type: 'back', tab: id });
                      else {
                        const pl = shellPlaceOf(fallback, SHELL_KINDS);
                        if (pl) dispatch({ type: 'replace', place: pl, tab: id });
                        else load(fallback, { replace: true });
                      }
                    },
                  }}
                >
                  <div
                    className={`studio-pane${tab.second ? (focusedHere ? ' is-focused' : '') : ' studio-pane--only'}`}
                    onMouseDownCapture={() => tab.second && !focusedHere && dispatch({ type: 'focus', tab: tab.id, pane: which })}
                    onFocusCapture={() => tab.second && !focusedHere && dispatch({ type: 'focus', tab: tab.id, pane: which })}
                  >
                    <View key={place.kind} place={place} client={client} />
                  </div>
                </TabContext.Provider>
              );
            };
            return (
              <div key={tab.id} className={`studio-tabhost${tab.second ? ' studio-tabhost--split' : ''}`} hidden={tab.id !== active.id}>
                {pane('first', tab)}
                {tab.second && <SplitHandle />}
                {tab.second && pane('second', tab.second)}
              </div>
            );
          })}
        </main>
        </div>
        <CommandPalette open={palette} onClose={() => setPalette(false)} />
      </div>
      </ActionsContext.Provider>
    </ShellContext.Provider>
  );
}

/** The tab an action moves away from its current place, if any: the one whose guard must agree. */
function leavingTab(action: ShellAction, state: ShellState): string | null {
  switch (action.type) {
    case 'open':
      return action.newTab ? null : action.tab ?? focusedId(state);
    case 'replace':
    case 'back':
    case 'forward':
      return action.tab ?? focusedId(state);
    case 'close':
      return action.tab ?? state.active;
    case 'unsplit': {
      // the pane that goes away is the one not kept
      const tab = tabOf(state, action.tab ?? state.active);
      return action.keep === 'second' ? tab.id : paneId(tab, 'second');
    }
    // closing several tabs asks the first guarded one; good enough for a menu action
    case 'closeOthers':
      return state.tabs.find((t) => t.id !== action.tab)?.id ?? null;
    case 'closeRight': {
      const i = state.tabs.findIndex((t) => t.id === action.tab);
      return state.tabs[i + 1]?.id ?? null;
    }
    default:
      return null;
  }
}

function readStorage(key = STORAGE_KEY): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------------------------------ tab strip

function TabStrip({ state, dispatch, newTab }: { state: ShellApi['state']; dispatch: ShellApi['dispatch']; newTab(): Place }) {
  // dragging a tab onto another moves it there
  const [dragging, setDragging] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  // the tab's context menu (right-click): where it opened, and for which tab
  const [menu, setMenu] = useState<{ tab: string; x: number; y: number } | null>(null);
  const onAux = (e: MouseEvent, id: string) => {
    if (e.button === 1) {
      e.preventDefault();
      dispatch({ type: 'close', tab: id });
    }
  };
  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close();
    window.addEventListener('mousedown', close);
    window.addEventListener('keydown', onKey);
    window.addEventListener('blur', close);
    return () => {
      window.removeEventListener('mousedown', close);
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('blur', close);
    };
  }, [menu]);
  const act = (action: ShellAction) => {
    setMenu(null);
    dispatch(action);
  };
  const menuTab = menu ? state.tabs.find((t) => t.id === menu.tab) : undefined;
  const menuIndex = menuTab ? state.tabs.indexOf(menuTab) : -1;
  // with more tabs than fit, the strip scrolls: the tab being worked in is the one that must stay in sight
  const strip = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const active = strip.current?.querySelector<HTMLElement>('.studio-tab.is-active');
    if (active && typeof active.scrollIntoView === 'function') active.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [state.active, state.tabs.length]);
  return (
    <div className="studio-tabs" role="tablist" aria-label="Open tabs" ref={strip}>
      {menu && menuTab && (
        <ul className="studio-tabmenu" role="menu" style={{ left: menu.x, top: menu.y }} onMouseDown={(e) => e.stopPropagation()}>
          <li role="none">
            <button type="button" role="menuitem" onClick={() => act({ type: 'duplicate', tab: menuTab.id })}>
              Duplicate
            </button>
          </li>
          <li role="none">
            <button type="button" role="menuitem" onClick={() => act({ type: 'close', tab: menuTab.id })}>
              Close
            </button>
          </li>
          <li role="none">
            <button type="button" role="menuitem" disabled={state.tabs.length < 2} onClick={() => act({ type: 'closeOthers', tab: menuTab.id })}>
              Close Others
            </button>
          </li>
          <li role="none">
            <button type="button" role="menuitem" disabled={menuIndex === state.tabs.length - 1} onClick={() => act({ type: 'closeRight', tab: menuTab.id })}>
              Close to the Right
            </button>
          </li>
          <li role="separator" className="studio-tabmenu__sep" />
          <li role="none">
            {menuTab.second ? (
              <button type="button" role="menuitem" onClick={() => act({ type: 'unsplit', tab: menuTab.id, keep: menuTab.focus === 'second' ? 'second' : 'first' })}>
                Close Split
              </button>
            ) : (
              <button type="button" role="menuitem" onClick={() => act({ type: 'split', tab: menuTab.id })}>
                Split View
              </button>
            )}
          </li>
          <li role="none">
            <button type="button" role="menuitem" disabled={state.closed.length === 0} onClick={() => act({ type: 'reopen' })}>
              Reopen Closed Tab
            </button>
          </li>
        </ul>
      )}
      {state.tabs.map((tab) => {
        const place = placeOf(tab);
        const title = tabTitle(tab);
        const selected = tab.id === state.active;
        return (
          <div
            key={tab.id}
            role="tab"
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            className={`studio-tab${selected ? ' is-active' : ''}${dragging === tab.id ? ' is-dragging' : ''}${over === tab.id && dragging && dragging !== tab.id ? ' is-over' : ''}`}
            draggable={state.tabs.length > 1}
            onDragStart={(e) => {
              setDragging(tab.id);
              e.dataTransfer.effectAllowed = 'move';
              e.dataTransfer.setData('application/x-studio-tab', tab.id);
            }}
            onDragOver={(e) => {
              if (!dragging) return;
              e.preventDefault();
              setOver(tab.id);
            }}
            onDrop={(e) => {
              e.preventDefault();
              if (dragging && dragging !== tab.id) dispatch({ type: 'move', tab: dragging, to: state.tabs.findIndex((t) => t.id === tab.id) });
              setDragging(null);
              setOver(null);
            }}
            onDragEnd={() => {
              setDragging(null);
              setOver(null);
            }}
            title={`${title}\n${urlOf(place)}`}
            onClick={() => dispatch({ type: 'activate', tab: tab.id })}
            onAuxClick={(e) => onAux(e, tab.id)}
            onContextMenu={(e) => {
              e.preventDefault();
              setMenu({ tab: tab.id, x: e.clientX, y: e.clientY });
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') dispatch({ type: 'activate', tab: tab.id });
            }}
          >
            <span className="studio-tab__title">{title}</span>
            <button
              type="button"
              className="studio-tab__close"
              aria-label={`Close ${title}`}
              onClick={(e) => {
                e.stopPropagation();
                dispatch({ type: 'close', tab: tab.id });
              }}
            >
              <Close size="XS" />
            </button>
          </div>
        );
      })}
      <TooltipTrigger delay={600}>
        <ActionButton isQuiet aria-label="New tab" UNSAFE_className="studio-tabs__new" onPress={() => dispatch({ type: 'open', place: newTab(), newTab: true })}>
          <Add />
        </ActionButton>
        <Tooltip>New tab (Alt+T)</Tooltip>
      </TooltipTrigger>
    </div>
  );
}

/** A tab's name: the view's title, and the second pane's after it when the tab is split. */
function tabTitle(tab: Tab): string {
  const first = tab.title ?? titleOf(placeOf(tab));
  if (!tab.second) return first;
  return `${first} ⫽ ${tab.second.title ?? titleOf(placeOf(tab.second))}`;
}

/** The bar between two panes: drag it to give one pane more room (the split is remembered for the session only). */
function SplitHandle() {
  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const handle = e.currentTarget;
    const host = handle.parentElement;
    if (!host) return;
    const first = host.firstElementChild as HTMLElement | null;
    if (!first) return;
    e.preventDefault();
    handle.setPointerCapture(e.pointerId);
    const rect = host.getBoundingClientRect();
    const move = (ev: PointerEvent) => {
      const ratio = Math.max(0.2, Math.min(0.8, (ev.clientX - rect.left) / rect.width));
      first.style.flexBasis = `${ratio * 100}%`;
      (host.lastElementChild as HTMLElement).style.flexBasis = `${(1 - ratio) * 100}%`;
    };
    const up = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
  };
  return <div className="studio-splithandle" role="separator" aria-orientation="vertical" aria-label="Resize the panes" onPointerDown={onPointerDown} />;
}

const FULL_KEY = 'studio.full';

function readFull(): boolean {
  try {
    return window.sessionStorage.getItem(FULL_KEY) === '1';
  } catch {
    return false;
  }
}

function storeFull(on: boolean) {
  try {
    if (on) window.sessionStorage.setItem(FULL_KEY, '1');
    else window.sessionStorage.removeItem(FULL_KEY);
  } catch {
    /* a private window: full screen lasts until the page reloads */
  }
}
