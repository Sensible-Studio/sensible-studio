import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { HealthClient, Run, Settings, Status } from './api';
import { bytes } from './api';
import { HealthConsole } from './HealthConsole';

const SETTINGS: Settings = { enabled: true, hour: 2, compact: true, purgeVersions: true, keepVersions: 5, keepDays: 30 };
const RUN: Run = { id: '1', kind: 'all', by: 'scheduled', started: '2026-11-01T00:00:00Z', finished: '2026-11-01T00:03:00Z', millis: 180000, sizeBefore: 2_500_000_000, sizeAfter: 400_000_000, recovered: 2_100_000_000, versions: { nodes: 10, versions: 80, removed: 40 } };
const STATUS: Status = {
  overall: 'warn',
  checks: [
    { id: 'maintenance', title: 'Nightly maintenance', status: 'ok', summary: 'Last run 1 d ago, 2.0 GB recovered', details: {} },
    { id: 'repository', title: 'Repository size', status: 'ok', summary: '400 MB', details: { size: 400_000_000, samples: { '2026-10-30': 2_500_000_000, '2026-11-01': 400_000_000 } } },
    { id: 'disk', title: 'Disk space', status: 'ok', summary: '300 GB free of 900 GB', details: { free: 300e9, total: 900e9 } },
    { id: 'log', title: 'Errors in the log', status: 'warn', summary: '3 errors, 1 warning in the last 24 hours', details: { errors: 3, last: ['02.11.2026 10:00:00 *ERROR* [x] boom'] } },
  ],
  settings: SETTINGS,
  progress: { running: false },
  lastRun: RUN,
  nextRun: '2026-11-03T00:00:00Z',
  compactionAvailable: true,
  now: new Date().toISOString(),
};

class FakeClient implements HealthClient {
  calls: string[] = [];
  async status() {
    return STATUS;
  }
  async runs() {
    return [RUN];
  }
  async progress() {
    return STATUS.progress;
  }
  async run(kind: 'all' | 'compact' | 'versions') {
    this.calls.push(`run ${kind}`);
    return true;
  }
  async saveSettings(v: Partial<Settings>) {
    this.calls.push(`settings ${JSON.stringify(v)}`);
    return { ...SETTINGS, ...v };
  }
}

describe('health console', () => {
  beforeAll(() => {
    globalThis.ResizeObserver ??= class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
    window.HTMLElement.prototype.scrollIntoView ??= () => {};
    (globalThis as { CSS?: { escape?: (s: string) => string } }).CSS ??= {};
    globalThis.CSS.escape ??= (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`);
  });
  afterEach(cleanup);

  it('shows the checks, the history and what maintenance recovered', async () => {
    render(
      <Provider theme={defaultTheme}>
        <HealthConsole client={new FakeClient()} />
      </Provider>,
    );
    expect(await screen.findByText('Worth a look')).toBeTruthy();
    expect(screen.getByText('3 errors, 1 warning in the last 24 hours')).toBeTruthy();
    expect(screen.getByText('boom')).toBeTruthy();
    expect(screen.getByText('2.0 GB')).toBeTruthy();
    expect(screen.getByText('40 of 80 removed')).toBeTruthy();
    expect(screen.getByRole('img', { name: /From 2.3 GB on 2026-10-30/ })).toBeTruthy();
  });

  it('starts a run and saves the settings', async () => {
    const client = new FakeClient();
    render(
      <Provider theme={defaultTheme}>
        <HealthConsole client={client} />
      </Provider>,
    );
    await screen.findByText('Worth a look');
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run maintenance now/ }));
    });
    await act(async () => {
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Compact the repository only' }));
    });
    expect(client.calls).toContain('run compact');

    fireEvent.click(screen.getByRole('switch', { name: 'Run every night' }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    expect(client.calls.some((c) => c.startsWith('settings') && c.includes('"enabled":false'))).toBe(true);
  });

  it('formats bytes', () => {
    expect(bytes(2_100_000_000)).toBe('2.0 GB');
    expect(bytes(512)).toBe('512 B');
  });
});
