import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AssetItem } from './api';
import { LiveCopyPane, liveCopyLabel, rolloutSummary } from './liveCopy';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

const file = (path: string, liveCopy?: AssetItem['liveCopy']): AssetItem =>
  ({ type: 'asset', path, name: path.split('/').pop()!, title: 'Logo', mimeType: 'image/png', liveCopy }) as AssetItem;

const show = (dam: MemoryDam, item: AssetItem, onOpen = vi.fn()) =>
  render(
    <Provider theme={defaultTheme}>
      <LiveCopyPane client={dam} item={item} onChanged={() => {}} onOpen={onOpen} />
    </Provider>,
  );

describe('Live Copy in Media', () => {
  it('is named in lists and summed up after a rollout', () => {
    expect(liveCopyLabel(undefined)).toBeNull();
    expect(liveCopyLabel('inherited')).toBe('Live copy');
    expect(liveCopyLabel('suspended')).toBe('Live copy · suspended');
    expect(rolloutSummary({ added: 1, updated: 2, removed: 0, skipped: ['/x'] })).toBe('1 added, 2 updated, 1 left as they are');
    expect(rolloutSummary({ added: 0, updated: 0, removed: 0, skipped: [] })).toBe('Already up to date');
  });

  it('offers a live copy of a file outside Live Copy, kept at its name', async () => {
    const dam = new MemoryDam();
    show(dam, file('/content/dam/plain.png'));
    fireEvent.click(await screen.findByRole('button', { name: 'Create live copy…' }));
    expect(await screen.findByText(/Name: plain\.png/)).toBeTruthy();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Take the source’s changes at once' }));
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(dam.liveCopyCalls[0]).toMatch(/^create \/content\/dam\/plain\.png \/content\/dam\/plain\.png +push$/));
  });

  it('turns pushing on for a live copy', async () => {
    const dam = new MemoryDam();
    const root = '/content/dam/markets/de';
    dam.liveCopyInfo[root] = { root, source: '/content/dam/brand', sourceExists: true, status: 'root', pushOnModify: false, liveCopies: [] };
    show(dam, { type: 'folder', path: root, name: 'de', title: 'DE', liveCopy: 'root' } as never);
    fireEvent.click(await screen.findByRole('switch', { name: 'Take the source’s changes at once' }));
    await waitFor(() => expect(dam.liveCopyCalls).toContain(`push ${root}`));
    expect(screen.getByRole('button', { name: 'Detach' })).toBeTruthy();
  });

  it('offers what a live copy file can do, and its changed fields', async () => {
    const dam = new MemoryDam();
    const path = '/content/dam/markets/de/logo.png';
    dam.liveCopyInfo[path] = {
      root: '/content/dam/markets/de',
      source: '/content/dam/brand/logo.png',
      sourceExists: true,
      status: 'inherited',
      cancelledProperties: ['dc:description'],
      liveCopies: [],
    };
    const onOpen = vi.fn();
    show(dam, file(path, 'inherited'), onOpen);
    fireEvent.click(await screen.findByRole('button', { name: '/content/dam/brand/logo.png' }));
    expect(onOpen).toHaveBeenCalledWith('/content/dam/brand/logo.png');
    fireEvent.click(screen.getByRole('button', { name: 'Synchronize' }));
    await waitFor(() => expect(dam.liveCopyCalls).toContain(`sync ${path}`));
    fireEvent.click(screen.getByRole('button', { name: 'Suspend' }));
    await waitFor(() => expect(dam.liveCopyCalls).toContain(`suspend ${path}`));
    fireEvent.click(screen.getByRole('button', { name: 'Follow the source' }));
    await waitFor(() => expect(dam.liveCopyCalls).toContain(`inherit ${path} dc:description`));
    expect(screen.queryByRole('button', { name: 'Detach' })).toBeNull();
  });

  it('lists the live copies of a source and rolls out to them', async () => {
    const dam = new MemoryDam();
    dam.liveCopyInfo['/content/dam/brand'] = {
      liveCopies: [{ root: '/content/dam/markets/de', path: '/content/dam/markets/de', title: 'Brand DE', status: 'root' }],
    };
    show(dam, { type: 'folder', path: '/content/dam/brand', name: 'brand', title: 'Brand' } as never);
    expect(await screen.findByText('/content/dam/markets/de')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Rollout' }));
    await waitFor(() => expect(dam.liveCopyCalls).toContain('rollout /content/dam/brand'));
  });
});
