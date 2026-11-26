import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ToolCrumbs } from './ToolCrumbs';

afterEach(cleanup);
// jsdom has no CSS.escape, which Spectrum menus use
beforeAll(() => {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = { escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`) };
});

describe('ToolCrumbs', () => {
  it('lists the other tools of the group behind the arrow after the path', async () => {
    render(
      <Provider theme={defaultTheme}>
        <ToolCrumbs url="/launches.html" />
      </Provider>,
    );
    expect(screen.getByRole('navigation', { name: 'Location' }).textContent).toMatch(/Home.*System.*Publishing.*Releases/);
    fireEvent.click(screen.getByRole('button', { name: 'Tools' }));
    const menu = await screen.findByRole('menu');
    expect(menu.textContent).toContain('Link checker');
    expect(menu.textContent).toContain('Publishing');
  });

  it('shows no arrow for a group with one tool in it', () => {
    render(
      <Provider theme={defaultTheme}>
        <ToolCrumbs url="/system/mail.html" />
      </Provider>,
    );
    expect(screen.getByRole('button', { name: 'Tools' })).toBeTruthy();
  });
});
