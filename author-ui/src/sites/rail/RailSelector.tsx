import { ActionButton, Item, Keyboard, Menu, MenuTrigger, Text } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import RailLeft from '@spectrum-icons/workflow/RailLeft';
import { useEffect, type Key } from 'react';
import { RAILS, type RailId } from './api';

/**
 * The rail switcher at the left of the console bar: Content Only, Content Tree and Filter, with the
 * shortcuts ` and alt+1, alt+2 (not while typing in a field or inside a dialog). The inspector at the
 * right has alt+3 … alt+6.
 */
export function RailSelector({ rail, onChange }: { rail: RailId; onChange(rail: RailId): void }) {
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      const t = ev.target instanceof Element ? ev.target : null;
      if (t?.closest('[role="dialog"]')) return;
      const typing = !!t?.closest('input:not([type="checkbox"]), textarea, select, [contenteditable="true"]');
      if (ev.key === '`' && !typing && !ev.altKey && !ev.metaKey && !ev.ctrlKey) {
        onChange('content');
        return;
      }
      // alt+digit gives other characters on a Mac keyboard: go by the physical key
      const n = ev.code?.startsWith('Digit') ? Number(ev.code.slice(5)) : Number(ev.key);
      if (ev.altKey && !ev.metaKey && !ev.ctrlKey && n >= 1 && n < RAILS.length) {
        ev.preventDefault();
        onChange(RAILS[n].id);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onChange]);

  const current = RAILS.find((r) => r.id === rail) ?? RAILS[0];
  return (
    <MenuTrigger>
      <ActionButton isQuiet aria-label={`Rail: ${current.label}`} UNSAFE_className="studio-rail__selector">
        <RailLeft />
        {rail !== 'content' && <Text>{current.label}</Text>}
        <ChevronDown size="S" />
      </ActionButton>
      <Menu selectionMode="single" selectedKeys={[rail]} onAction={(k: Key) => onChange(k as RailId)} aria-label="Rails">
        {RAILS.map((r) => (
          <Item key={r.id} textValue={r.label}>
            <Text>{r.label}</Text>
            <Keyboard>{r.shortcut}</Keyboard>
          </Item>
        ))}
      </Menu>
    </MenuTrigger>
  );
}
