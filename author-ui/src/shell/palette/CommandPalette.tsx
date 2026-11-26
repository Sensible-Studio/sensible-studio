import Search from '@spectrum-icons/workflow/Search';
import { useContext, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { ActionsContext } from '../actions';
import { NavIcon } from '../../start/icons';
import { useShell } from '../ShellContext';
import { paneOf, placeOf, tabOf } from '../tabs';
import { titleOf, urlOf } from '../places';
import { isPinned, togglePin, usePinned } from '../recent';
import { VOCAB } from '../vocabulary';
import { GROUPS, placeCommands, rank, searchContent, tabCommands, type Command, type Group } from './commands';
import './palette.scss';

/**
 * ⌘K: one field that goes anywhere. Typing finds the consoles of Home and System, the open tabs and the
 * places recently visited at once, and, after a moment, pages, media and records by name. ↩ opens in
 * this tab, ⌘↩ in a new one, Esc closes.
 */
export function CommandPalette({ open, onClose }: { open: boolean; onClose(): void }) {
  const shell = useShell();
  const selection = useContext(ActionsContext)?.current ?? null;
  const [query, setQuery] = useState('');
  const [content, setContent] = useState<Command[]>([]);
  const [busy, setBusy] = useState(false);
  const [cursor, setCursor] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    setQuery('');
    setContent([]);
    setCursor(0);
    const t = window.setTimeout(() => input.current?.focus(), 0);
    return () => window.clearTimeout(t);
  }, [open]);

  // content: after a pause in typing, and only the last answer counts
  useEffect(() => {
    if (!open) return;
    const text = query.trim();
    if (text.length < 2) {
      setContent([]);
      setBusy(false);
      return;
    }
    const ctl = new AbortController();
    setBusy(true);
    const t = window.setTimeout(() => {
      searchContent(text, ctl.signal).then(
        (hits) => {
          if (!ctl.signal.aborted) {
            setContent(hits);
            setBusy(false);
          }
        },
        () => !ctl.signal.aborted && setBusy(false),
      );
    }, 180);
    return () => {
      ctl.abort();
      window.clearTimeout(t);
    };
  }, [open, query]);

  const activate = (tab: string) => shell?.dispatch({ type: 'activate', tab });
  const pinned = usePinned();
  const rows = useMemo(() => {
    if (!shell) return [];
    const { tabs, recent } = tabCommands(shell.state, activate);
    const here = placeOf(paneOf(shell.state));
    const hereUrl = urlOf(here);
    const hereTitle = paneOf(shell.state).title ?? titleOf(here);
    const actions: Command[] = [
      ...(here.kind !== 'start'
        ? [{ id: 'act:pin', title: isPinned(hereUrl) ? 'Unpin this screen' : 'Pin this screen', hint: `${hereTitle} · pinned screens are on ${VOCAB.home}`, group: 'actions' as const, run: () => togglePin(hereUrl, hereTitle) }]
        : []),
      { id: 'act:newtab', title: 'New tab', hint: 'Alt+T', group: 'actions', run: () => shell.dispatch({ type: 'open', place: { kind: 'start' }, newTab: true }) },
      { id: 'act:reopen', title: 'Reopen closed tab', hint: 'Alt+Shift+T', group: 'actions', run: () => shell.dispatch({ type: 'reopen' }) },
      { id: 'act:close', title: 'Close tab', hint: 'Alt+W', group: 'actions', run: () => shell.dispatch({ type: 'close' }) },
      ...(tabOf(shell.state).second
        ? [{ id: 'act:unsplit', title: 'Close split view', hint: 'Alt+\\', group: 'actions' as const, run: () => shell.dispatch({ type: 'unsplit', keep: tabOf(shell.state).focus === 'second' ? 'second' : 'first' }) }]
        : [{ id: 'act:split', title: 'Split view', hint: 'Alt+\\ · two places side by side, to drag between them', group: 'actions' as const, run: () => shell.dispatch({ type: 'split' }) }]),
      { id: 'act:signout', title: 'Sign out', group: 'actions', run: () => (window.location.href = `/system/sling/logout?resource=${encodeURIComponent(window.location.pathname)}`) },
    ];
    // the console's actions for what is selected: first, named after the selection
    const sel: Command[] = (selection?.actions ?? [])
      .filter((a) => !a.disabled)
      .map((a) => ({ id: `sel:${a.id}`, title: a.label, hint: `${selection!.subject}${a.shortcut ? ` · ${a.shortcut}` : ''}`, group: 'selection', run: a.run, keywords: selection!.subject }));
    const text = query.trim();
    const all = text ? rank([...sel, ...tabs, ...placeCommands(), ...recent, ...actions], text) : [...sel, ...tabs, ...recent, ...placeCommands()];
    return [...all, ...content];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shell?.state, selection, query, content, pinned]);

  // grouped, in the groups' order, the ranking kept inside each group
  const sections = useMemo(() => {
    const by = new Map<Group, Command[]>();
    for (const r of rows) by.set(r.group, [...(by.get(r.group) ?? []), r]);
    return GROUPS.filter((g) => by.has(g.id)).map((g) => ({ ...g, rows: by.get(g.id)! }));
  }, [rows]);
  const flat = useMemo(() => sections.flatMap((s) => s.rows), [sections]);
  useEffect(() => setCursor(0), [query]);
  useEffect(() => {
    if (cursor >= flat.length) setCursor(Math.max(0, flat.length - 1));
  }, [flat, cursor]);
  useEffect(() => {
    list.current?.querySelector<HTMLElement>('[aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' });
  }, [cursor]);

  const pick = (c: Command, newTab: boolean) => {
    onClose();
    if (c.run) c.run();
    else if (c.url) shell?.navigate(c.url, { newTab });
  };

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown') setCursor((i) => Math.min(flat.length - 1, i + 1));
    else if (e.key === 'ArrowUp') setCursor((i) => Math.max(0, i - 1));
    else if (e.key === 'Enter') {
      const c = flat[cursor];
      if (c) pick(c, e.metaKey || e.ctrlKey);
    } else if (e.key === 'Escape') onClose();
    else return;
    e.preventDefault();
  };

  if (!open) return null;
  return (
    <div className="studio-palette__backdrop" onMouseDown={onClose} role="presentation">
      <div className="studio-palette" role="dialog" aria-label="Go to" onMouseDown={(e) => e.stopPropagation()}>
        <div className="studio-palette__field">
          <Search size="S" />
          <input
            ref={input}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKey}
            placeholder="Go to a console, a page, a tool… or type what you want to do"
            aria-label="Go to"
            aria-controls="studio-palette-list"
            aria-activedescendant={flat[cursor] ? `studio-palette-${flat[cursor].id}` : undefined}
            role="combobox"
            aria-expanded
            autoComplete="off"
            spellCheck={false}
          />
          {busy && <span className="studio-palette__busy" aria-label="Searching" />}
        </div>
        <div className="studio-palette__list" id="studio-palette-list" role="listbox" ref={list}>
          {sections.map((s) => (
            <div key={s.id} className="studio-palette__group" role="group" aria-label={s.title}>
              <div className="studio-palette__heading">{s.title}</div>
              {s.rows.map((c) => {
                const i = flat.indexOf(c);
                return (
                  <div
                    key={c.id}
                    id={`studio-palette-${c.id}`}
                    role="option"
                    aria-selected={i === cursor}
                    className={`studio-palette__row${i === cursor ? ' is-current' : ''}`}
                    onMouseMove={() => i !== cursor && setCursor(i)}
                    onClick={(e) => pick(c, e.metaKey || e.ctrlKey)}
                  >
                    <span className="studio-palette__icon" aria-hidden>
                      {c.icon ? <NavIcon name={c.icon} size="S" /> : null}
                    </span>
                    <span className="studio-palette__title">{c.title}</span>
                    {c.hint && <span className="studio-palette__hint">{c.hint}</span>}
                  </div>
                );
              })}
            </div>
          ))}
          {flat.length === 0 && <div className="studio-palette__empty">{busy ? 'Searching…' : 'Nothing matches.'}</div>}
        </div>
        <div className="studio-palette__foot">
          <span>
            <kbd>↑</kbd>
            <kbd>↓</kbd> choose
          </span>
          <span>
            <kbd>↩</kbd> open
          </span>
          <span>
            <kbd>⌘</kbd>
            <kbd>↩</kbd> in a new tab
          </span>
          <span>
            <kbd>esc</kbd> close
          </span>
        </div>
      </div>
    </div>
  );
}
