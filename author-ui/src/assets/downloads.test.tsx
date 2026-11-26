import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { archiveUrl, downloadStatus, DownloadsButton, formatSize, suggestBackground, type BackgroundDownload } from './downloads';

afterEach(cleanup);

const base: BackgroundDownload = {
  id: '0123456789abcdef',
  name: 'trip.zip',
  status: 'ready',
  files: 12,
  done: 12,
  size: 5 * 1024 * 1024,
  created: '2026-11-23T18:00:00+02:00',
  expires: '2026-11-30T18:00:00+02:00',
};

describe('downloads prepared in the background', () => {
  it('are suggested for folders and large selections', () => {
    expect(suggestBackground([{ type: 'folder' }])).toBe(true);
    expect(suggestBackground(Array.from({ length: 11 }, () => ({ type: 'asset' })))).toBe(true);
    expect(suggestBackground([{ type: 'asset' }, { type: 'asset' }])).toBe(false);
  });

  it('say where they are', () => {
    expect(downloadStatus({ ...base, status: 'queued' })).toBe('Waiting · 12 files');
    expect(downloadStatus({ ...base, status: 'working', done: 5 })).toBe('Preparing · 5 of 12 files');
    expect(downloadStatus(base)).toMatch(/^12 files · 5\.0 MB · until /);
    expect(downloadStatus({ ...base, status: 'failed', error: 'disk full' })).toBe('Failed: disk full');
    expect(formatSize(900)).toBe('900 B');
    expect(archiveUrl(base.id)).toBe('/bin/studio/dam/downloads.zip?id=0123456789abcdef');
  });

  it('show in the tool bar once there is one, with the ready ones counted', async () => {
    const none = vi.fn(async () => [] as BackgroundDownload[]);
    const { container } = render(
      <Provider theme={defaultTheme}>
        <DownloadsButton load={none} />
      </Provider>,
    );
    await waitFor(() => expect(none).toHaveBeenCalled());
    expect(container.querySelector('.studio-assets__downloads-button')).toBeNull();
    cleanup();

    const remove = vi.fn(async () => {});
    const load = vi.fn(async () => [base, { ...base, id: 'fedcba9876543210', name: 'other.zip', status: 'failed' as const, error: 'gone' }]);
    render(
      <Provider theme={defaultTheme}>
        <DownloadsButton load={load} remove={remove} />
      </Provider>,
    );
    const button = await screen.findByRole('button', { name: 'Downloads: 1 ready' });
    await act(async () => {
      fireEvent.click(button);
    });
    const link = await screen.findByRole('link', { name: 'Download' });
    expect(link.getAttribute('href')).toBe(archiveUrl(base.id));
    expect(screen.getByText('Failed: gone')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove other.zip' }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith('fedcba9876543210'));
  });
});
