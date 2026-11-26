import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { routeUrl, type AssetsRoute } from './route';
import { MemoryDam } from './testing/MemoryDam';
import { hasAction } from '../testing/selection';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

const SURF = '/content/dam/wknd/surf.jpg';

function demo(): MemoryDam {
  return new MemoryDam().folder('/content/dam/wknd', 'WKND Site').asset(SURF, { mimeType: 'image/jpeg' }, { 'dc:title': 'Surf in Bali' });
}

function mount(dam: MemoryDam, route: AssetsRoute) {
  window.history.replaceState(null, '', routeUrl(route));
  return render(
    <Provider theme={defaultTheme}>
      <AssetsApp client={dam} initial={route} />
    </Provider>,
  );
}

describe('permissions', () => {
  it('offer only what the user may do in a folder', async () => {
    const dam = demo();
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    const cell = await screen.findByRole('gridcell', { name: /Surf in Bali/ });
    // the frame's primary action, before the selection bar takes the frame's place
    expect(screen.getByRole('button', { name: /Create/ })).toBeTruthy();
    fireEvent.click(within(cell).getByRole('button', { name: 'Select' }));
    expect(await hasAction('Delete')).toBe(true);
    expect(await hasAction('Move')).toBe(true);
    cleanup();

    dam.readOnly.add('/content/dam/wknd');
    mount(dam, { kind: 'browse', path: '/content/dam/wknd' });
    fireEvent.click(within(await screen.findByRole('gridcell', { name: /Surf in Bali/ })).getByRole('button', { name: 'Select' }));
    expect(await hasAction('Delete')).toBe(false);
    expect(await hasAction('Move')).toBe(false);
    expect(await hasAction('Reprocess files')).toBe(false);
    expect(await hasAction('Download')).toBe(true);
    expect(await hasAction('Properties')).toBe(true);
    expect(screen.queryByRole('button', { name: /^Create/ })).toBeNull();
  });

  it('leave a read-only file to look at', async () => {
    const dam = demo();
    dam.readOnly.add('/content/dam/wknd');
    mount(dam, { kind: 'details', path: SURF, properties: false });
    await screen.findByRole('button', { name: /Properties/ });
    // the shell has a banner of its own; the screen frame is the one below it
    const bar = within(document.querySelector('.studio-screenframe') as HTMLElement);
    expect(bar.getByRole('button', { name: /Properties/ })).toBeTruthy();
    expect(bar.queryByRole('button', { name: /Delete/ })).toBeNull();
    expect(bar.queryByRole('button', { name: /Edit/ })).toBeNull();
    expect(bar.queryByRole('button', { name: /Checkout/ })).toBeNull();
  });
});
