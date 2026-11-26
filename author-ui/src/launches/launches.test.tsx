import { defaultTheme, DialogContainer, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MsmClient } from '../msm/MsmClient';
import type { Launch, LaunchesClient } from './api';
import { CreateLaunchDialog } from './CreateLaunchDialog';

afterEach(cleanup);

const L = '/content/launches/2026/11/26/autumn';
const launch = (sources: string[]): Launch => ({ path: L, name: 'autumn', title: 'Autumn', source: sources[0], sources, sourceInLaunch: `${L}/content${sources[0]}`, liveCopy: true, deep: true });

function setup(more: string[], fail = false) {
  const calls: string[] = [];
  const sources: string[] = [];
  const client = {
    create: vi.fn(async (r: { source: string; productionReady?: boolean }) => {
      sources.push(r.source);
      calls.push(`create ${r.source}${r.productionReady ? ' ready' : ''}`);
      return { launch: launch(sources), liveCopyParent: `${L}/content/content/site`, liveCopyName: 'a' };
    }),
    addSource: vi.fn(async (_p: string, r: { source: string }) => {
      if (fail) throw new Error('refused');
      sources.push(r.source);
      calls.push(`add ${r.source}`);
      return { launch: launch([...sources]), liveCopyParent: `${L}/content/content/site`, liveCopyName: 'x' };
    }),
    remove: vi.fn(async () => {
      calls.push('remove');
    }),
  } as unknown as LaunchesClient;
  const msm = { createLiveCopy: vi.fn(async (r: { source: string }) => (calls.push(`livecopy ${r.source}`), { path: '', result: {} })) } as unknown as MsmClient;
  const created = vi.fn();
  render(
    <Provider theme={defaultTheme}>
      <DialogContainer onDismiss={() => undefined}>
        <CreateLaunchDialog client={client} msm={msm} source="/content/site/a/deep" sourceTitle="Deep" more={more} onCreated={created} />
      </DialogContainer>
    </Provider>,
  );
  return { calls, created };
}

describe('a release of several pages', () => {
  it('creates one release with every selected page; a page below another comes with its parent', async () => {
    const { calls, created } = setup(['/content/site/a', '/content/site/b']);
    expect(screen.getByText('3 pages')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/Release title/), { target: { value: 'Autumn' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(created).toHaveBeenCalled());
    expect(calls).toEqual(['create /content/site/a', 'livecopy /content/site/a', 'add /content/site/b', 'livecopy /content/site/b']);
    expect((created.mock.calls[0][0] as Launch).sources).toEqual(['/content/site/a', '/content/site/b']);
  });

  it('leaves no half release behind when a page cannot be added', async () => {
    const { calls, created } = setup(['/content/site/b'], true);
    fireEvent.change(screen.getByLabelText(/Release title/), { target: { value: 'Autumn' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(calls[calls.length - 1]).toBe('remove');
    expect(created).not.toHaveBeenCalled();
  });

  it('can be production ready: it goes live by itself at the live date', async () => {
    const { calls, created } = setup([]);
    fireEvent.change(screen.getByLabelText(/Release title/), { target: { value: 'Autumn' } });
    fireEvent.click(screen.getByRole('checkbox', { name: /Production ready/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(created).toHaveBeenCalled());
    expect(calls[0]).toBe('create /content/site/a/deep ready');
  });
});
