import { ActionButton, Item, Keyboard, Menu, MenuTrigger, Section, Text } from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import More from '@spectrum-icons/workflow/More';
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type Key, type ReactNode, type RefObject } from 'react';
import type { Action } from './actions';

interface Props {
  count: number;
  /** Every action of the selection, as the right-click menu and ⌘K list them. */
  actions: Action[];
  /** Ids of the actions that come first, in this order; the first one present is the filled one. The rest follow in the console's order, destructive ones last. */
  leading?: string[];
  onClear(): void;
  /** Before the clear button (a rail selector). */
  start?: ReactNode;
  /** After the actions (an inspector toggle). */
  end?: ReactNode;
}

/**
 * The bar a console shows while it has a selection (DESIGN.md, "Selection bar"): a clear button, the word
 * Selection and the count on the left, on a neutral surface; the actions after it, like AEM's: as many as the
 * width holds, each a button with its icon and name, the first one filled. What does not fit goes to More,
 * destructive actions last. `leading` only orders the first ones; the list itself stays the console's.
 */
export function SelectionBar({ count, actions, leading, onClear, start, end }: Props) {
  const isDanger = (a: Action) => a.danger ?? /^(delete|remove|unpublish|deactivate)/.test(a.id);
  const first = leading ? leading.flatMap((id) => actions.filter((a) => a.id === id)) : [];
  const others = actions.filter((a) => !first.includes(a));
  const order = [...first, ...others.filter((a) => !isDanger(a)), ...others.filter(isDanger)];
  // actions of a group (Create, Restore) are one entry, at the place of the first of them
  const units: Unit[] = [];
  for (const a of order) {
    if (!a.group) units.push({ key: a.id, action: a });
    else {
      const g = units.find((u) => u.group === a.group);
      if (g) g.items!.push(a);
      else units.push({ key: `group:${a.group}`, group: a.group, items: [a] });
    }
  }
  const fit = useFit(units.map((u) => `${u.key}:${u.action?.label ?? u.items!.map((i) => i.label).join(',')}`).join('|'), units.length);
  const shown = units.slice(0, fit.count);
  const rest = units.slice(fit.count);
  const restActions = rest.flatMap((u) => (u.action ? [u.action] : u.items!));
  const item = (a: Action) => (
    <Item key={a.id} textValue={a.label}>
      {a.icon}
      <Text>{a.label}</Text>
      {a.shortcut && <Keyboard>{a.shortcut}</Keyboard>}
    </Item>
  );
  const safe = rest.filter((u) => !u.group && !isDanger(u.action!)).map((u) => u.action!);
  const danger = rest.filter((u) => !u.group && isDanger(u.action!)).map((u) => u.action!);
  const sections = [
    ...(safe.length ? [<Section key="more" aria-label="More actions">{safe.map(item)}</Section>] : []),
    ...rest.filter((u) => u.group).map((u) => (
      <Section key={u.key} title={u.group}>
        {u.items!.map(item)}
      </Section>
    )),
    ...(danger.length ? [<Section key="danger" aria-label="Destructive actions">{danger.map(item)}</Section>] : []),
  ];
  const fitKey = (u: Unit) => `${u.key}:${u.action?.label ?? u.items!.map((i) => i.label).join(',')}`;
  return (
    <div className="studio-selbar" role="toolbar" aria-label="Selection actions">
      <div className="studio-selbar__start">
        {start}
        <ActionButton isQuiet aria-label="Clear selection" onPress={onClear}>
          <Close />
        </ActionButton>
        <span className="studio-selbar__mode">
          <span className="studio-selbar__label">Selection</span>
          <span className="studio-selbar__count">{count} selected</span>
        </span>
      </div>
      <div className="studio-selbar__actions is-fitting" ref={fit.box}>
        {shown.map((u, i) =>
          u.action ? (
            <button
              key={u.key}
              type="button"
              data-fit={fitKey(u)}
              className={`studio-selbar__btn${i === 0 ? ' is-leading' : ''}${isDanger(u.action) ? ' is-danger' : ''}`}
              disabled={u.action.disabled}
              title={u.action.shortcut ? `${u.action.label} (${u.action.shortcut})` : u.action.label}
              onClick={u.action.run}
            >
              {u.action.icon}
              <span>{u.action.label}</span>
              {u.action.shortcut && <kbd>{u.action.shortcut}</kbd>}
            </button>
          ) : (
            <span key={u.key} className="studio-selbar__groupwrap" data-fit={fitKey(u)}>
            <MenuTrigger align="start">
              <ActionButton UNSAFE_className={`studio-selbar__btn studio-selbar__group${i === 0 ? ' is-leading' : ''}`} aria-label={u.group}>
                {u.items!.find((x) => x.groupIcon)?.groupIcon ?? u.items![0].icon}
                <Text>{u.group}</Text>
                <span className="studio-selbar__caret" aria-hidden="true" />
              </ActionButton>
              <Menu
                aria-label={u.group}
                disabledKeys={u.items!.filter((a) => a.disabled).map((a) => a.id)}
                onAction={(k: Key) => u.items!.find((a) => a.id === k)?.run()}
              >
                {u.items!.map(item)}
              </Menu>
            </MenuTrigger>
            </span>
          ),
        )}
        {sections.length > 0 && (
          <MenuTrigger align="end">
            <ActionButton UNSAFE_className="studio-selbar__more" aria-label="More actions">
              <More />
              <Text>More</Text>
            </ActionButton>
            <Menu disabledKeys={restActions.filter((a) => a.disabled).map((a) => a.id)} onAction={(k: Key) => restActions.find((a) => a.id === k)?.run()}>
              {sections}
            </Menu>
          </MenuTrigger>
        )}
        {end && <span className="studio-selbar__end">{end}</span>}
      </div>
    </div>
  );
}

/** One entry of the bar: an action, or a group of actions behind one button (Create ▾). */
interface Unit {
  key: string;
  action?: Action;
  group?: string;
  items?: Action[];
}

const MORE_WIDTH = 96; // the More button and its gap

/**
 * How many of the bar's buttons fit its width. Every button is measured once (they all render the first time a
 * set of actions shows, before the browser paints), then the count follows the bar's width. Without a layout
 * (tests) everything shows.
 */
function useFit(key: string, total: number): { count: number; box: RefObject<HTMLDivElement> } {
  const box = useRef<HTMLDivElement>(null);
  const widths = useRef(new Map<string, number>());
  const [count, setCount] = useState(total);
  const [measured, setMeasured] = useState('');

  const compute = useCallback(() => {
    const el = box.current;
    if (!el) return;
    const room = el.clientWidth;
    if (!room) return setCount(total);
    const gap = parseFloat(getComputedStyle(el).columnGap) || 0;
    const endEl = el.querySelector<HTMLElement>('.studio-selbar__end');
    const endWidth = endEl ? endEl.offsetWidth + gap : 0;
    const ids = key ? key.split('|') : [];
    let used = endWidth;
    let n = 0;
    for (let i = 0; i < ids.length; i++) {
      const w = (widths.current.get(ids[i]) ?? 0) + gap;
      const needsMore = i < ids.length - 1 ? MORE_WIDTH : 0;
      if (used + w + needsMore > room) break;
      used += w;
      n++;
    }
    setCount(n);
  }, [key, total]);

  // a new set of actions: show them all once, measure, then fit
  useLayoutEffect(() => {
    setCount(total);
    setMeasured('');
  }, [key, total]);
  // every render: what is shown is measured again (a menu button may get its width a moment after it mounts);
  // the count follows when a width changed or the set of actions is new
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    let changed = false;
    for (const b of el.querySelectorAll<HTMLElement>('[data-fit]')) {
      const w = b.offsetWidth;
      if (w && widths.current.get(b.dataset.fit!) !== w) {
        widths.current.set(b.dataset.fit!, w);
        changed = true;
      }
    }
    if (measured !== key) setMeasured(key);
    if (changed || measured !== key) compute();
  });
  useEffect(() => {
    const el = box.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => compute());
    ro.observe(el);
    return () => ro.disconnect();
  }, [compute]);

  return { count, box };
}

/**
 * The same bar for a console that lists its few actions itself (System consoles): the clear button, the word
 * Selection and the count on the left, the console's own buttons after them, the first one filled.
 */
export function SelectionActions({ count, onClear, children }: { count: number; onClear(): void; children: ReactNode }) {
  return (
    <div className="studio-selbar studio-selbar--own" role="toolbar" aria-label="Actions for the selection">
      <div className="studio-selbar__start">
        <ActionButton isQuiet aria-label="Clear selection" onPress={onClear}>
          <Close />
        </ActionButton>
        <span className="studio-selbar__mode">
          <span className="studio-selbar__label">Selection</span>
          <span className="studio-selbar__count">{count} selected</span>
        </span>
      </div>
      <div className="studio-selbar__actions studio-actionbar__actions">{children}</div>
    </div>
  );
}
