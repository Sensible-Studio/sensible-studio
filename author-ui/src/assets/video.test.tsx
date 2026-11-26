import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { formatDuration } from './format';
import { routeUrl, type AssetsRoute } from './route';
import { MemoryDam } from './testing/MemoryDam';
import { frameOfFile, isVideoFile } from './video';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

const CLIP = '/content/dam/wknd/clip.mp4';
const SONG = '/content/dam/wknd/song.mp3';

function demo(): MemoryDam {
  return new MemoryDam()
    .folder('/content/dam/wknd', 'WKND Site')
    .asset(
      CLIP,
      { mimeType: 'video/mp4', width: 1920, height: 1080, duration: 10.24, thumbnail: null, web: `${CLIP}/_jcr_content/renditions/cq5dam.web.1280.1280.jpeg` },
      {
        'dc:title': 'Beach clip',
        'dam:duration': 10.24,
        'xmpDM:videoFrameRate': 25,
        'xmpDM:videoCompressor': 'H.264 (avc1)',
        'xmpDM:audioCompressor': 'AAC (mp4a)',
        'xmpDM:audioChannelType': 'Stereo',
      },
    )
    .asset(SONG, { mimeType: 'audio/mpeg', width: null, height: null, duration: 185, thumbnail: null }, { 'dc:title': 'Song' });
}

function mount(dam: MemoryDam, route: AssetsRoute) {
  window.history.replaceState(null, '', routeUrl(route));
  return render(
    <Provider theme={defaultTheme}>
      <AssetsApp client={dam} initial={route} />
    </Provider>,
  );
}

describe('formatDuration', () => {
  it('reads like a player', () => {
    expect(formatDuration(10.24)).toBe('0:10');
    expect(formatDuration(245)).toBe('4:05');
    expect(formatDuration(4.994)).toBe('0:04');
    expect(formatDuration(3723)).toBe('1:02:03');
    expect(formatDuration(null)).toBe('');
    expect(formatDuration(Number.NaN)).toBe('');
  });
});

describe('videos', () => {
  it('play on their page, with what they are', async () => {
    mount(demo(), { kind: 'details', path: CLIP, properties: false });
    const video = (await screen.findByLabelText('Beach clip')) as HTMLVideoElement;
    expect(video.tagName).toBe('VIDEO');
    expect(video.getAttribute('src')).toBe(`${CLIP}/_jcr_content/renditions/original`);
    expect(video.getAttribute('poster')).toContain('cq5dam.web.1280.1280.jpeg');
    expect(video.controls).toBe(true);
    expect(screen.getByText('0:10 · 1920 x 1080 · 25 fps · H.264 · AAC, Stereo')).toBeTruthy();
  });

  it('say when the server cannot make their thumbnails and copies', async () => {
    const dam = demo();
    dam.noVideoTools = true;
    mount(dam, { kind: 'details', path: CLIP, properties: false });
    expect(await screen.findByText('Videos are not fully processed on this server')).toBeTruthy();
    cleanup();
    mount(demo(), { kind: 'details', path: CLIP, properties: false });
    await screen.findByLabelText('Beach clip');
    expect(screen.queryByText('Videos are not fully processed on this server')).toBeNull();
  });

  it('use the frame on screen as their thumbnail', async () => {
    const dam = demo();
    mount(dam, { kind: 'details', path: CLIP, properties: false });
    const video = (await screen.findByLabelText('Beach clip')) as HTMLVideoElement;
    Object.defineProperty(video, 'videoWidth', { value: 3840 });
    Object.defineProperty(video, 'videoHeight', { value: 2160 });
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined);
    const draw = vi.fn();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: draw } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (this: HTMLCanvasElement, cb: BlobCallback) {
      cb(new Blob([new Uint8Array(42)], { type: 'image/jpeg' }));
    });
    fireEvent.click(screen.getByRole('button', { name: 'Use frame as thumbnail' }));
    await waitFor(() => expect(dam.calls).toContain(`poster ${CLIP} image/jpeg 42`));
    expect(draw).toHaveBeenCalledWith(video, 0, 0, 1920, 1080); // 4K frames are stored at 1920
    expect(await screen.findByText('Thumbnail set from this frame')).toBeTruthy();
  });

  it('fall back to the download when the browser cannot play them', async () => {
    mount(demo(), { kind: 'details', path: CLIP, properties: false });
    fireEvent.error(await screen.findByLabelText('Beach clip'));
    expect(await screen.findByText(/This browser cannot play this video/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /Download clip.mp4/ })).toBeTruthy();
  });

  it('show their length on the card', async () => {
    mount(demo(), { kind: 'browse', path: '/content/dam/wknd' });
    const card = await screen.findByRole('gridcell', { name: /Beach clip/ });
    expect(within(card).getByLabelText('Duration 0:10')).toBeTruthy();
    expect(within(await screen.findByRole('gridcell', { name: /Song/ })).getByText('3:05')).toBeTruthy();
  });

  it('audio gets a player too', async () => {
    mount(demo(), { kind: 'details', path: SONG, properties: false });
    const audio = await screen.findByLabelText('Song');
    expect(audio.tagName).toBe('AUDIO');
    expect(screen.queryByRole('button', { name: 'Use frame as thumbnail' })).toBeNull();
  });

  it('recognises video files and skips what the browser cannot decode', async () => {
    expect(isVideoFile(new File([], 'a.MOV'))).toBe(true);
    expect(isVideoFile(new File([], 'a.bin', { type: 'video/webm' }))).toBe(true);
    expect(isVideoFile(new File([], 'a.jpg', { type: 'image/jpeg' }))).toBe(false);
    // jsdom decodes nothing: no frame, no waiting
    expect(await frameOfFile(new File([], 'a.mp4', { type: 'video/mp4' }), 50)).toBeNull();
  });
});
