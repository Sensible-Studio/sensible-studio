import { ActionButton, Item, Menu, MenuTrigger, Provider, Section } from '@adobe/react-spectrum';
import type { Key } from 'react';
import { InboxBell } from '../workflow/InboxBell';

/** The dark bar at the top of the consoles: product name (links to Start) and the user menu. */
export function ProductBar({ user }: { user: string | null }) {
  const signOut = () => {
    window.location.href = `/system/sling/logout?resource=${encodeURIComponent(window.location.pathname)}`;
  };
  return (
    <Provider colorScheme="dark" UNSAFE_className="studio-shell">
      <header className="studio-shell__bar">
        <a className="studio-shell__brand" href="/studio/start.html">
          <span className="studio-shell__mark" aria-hidden>
            S
          </span>
          <span>Sensible Studio</span>
        </a>
        <div className="studio-shell__actions">
          <InboxBell />
          <MenuTrigger align="end">
            <ActionButton isQuiet aria-label="User">
              <span className="studio-shell__avatar" aria-hidden>
                {(user ?? '?').slice(0, 1).toUpperCase()}
              </span>
            </ActionButton>
            <Menu onAction={(k: Key) => k === 'signout' && signOut()}>
              <Section title={user ? `Signed in as ${user}` : 'Not signed in'}>
                <Item key="signout">Sign Out</Item>
              </Section>
            </Menu>
          </MenuTrigger>
        </div>
      </header>
    </Provider>
  );
}
