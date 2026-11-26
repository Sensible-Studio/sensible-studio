import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { describeRendition } from './profiles';
import { MemoryDam } from './testing/MemoryDam';
import { WatermarkSection, watermarkSummary } from './watermark';

afterEach(cleanup);

describe('the watermark', () => {
  it('is summed up in one line', () => {
    expect(watermarkSummary(undefined)).toMatch(/No watermark set/);
    expect(watermarkSummary({ image: '/content/dam/brand/logo.png', position: 'bottom-right', opacity: 40, size: 20, ready: true })).toBe(
      '/content/dam/brand/logo.png · Bottom right · 40% opacity · 20% of the width',
    );
    expect(watermarkSummary({ image: '/content/dam/gone.png', position: 'center', opacity: 50, size: 25, ready: false })).toMatch(/missing/);
  });

  it('shows on the renditions that ask for it', () => {
    expect(describeRendition({ name: 'proof', width: 800, height: 600, format: 'jpeg', quality: 80, watermark: true })).toBe(
      'proof.jpeg (800 x 600, watermark)',
    );
    expect(describeRendition({ name: 'clean', width: 200, height: 200, format: 'png', quality: 80 })).toBe('clean.png (200 x 200)');
    expect(describeRendition({ name: 'web', width: 0, height: 720, format: 'mp4', quality: 0 })).toBe('web.mp4 (720p)');
  });

  it('is changed by administrators and saved', async () => {
    const dam = new MemoryDam();
    dam.watermark = { image: '/content/dam/brand/logo.png', position: 'center', opacity: 50, size: 25, ready: true, thumbnail: null };
    const onSaved = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <WatermarkSection client={dam} watermark={dam.watermark} canEdit onSaved={onSaved} />
      </Provider>,
    );
    expect(screen.getByText(/logo\.png · Centre · 50% opacity/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Save' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(onSaved.mock.calls[0][0].image).toBe('/content/dam/brand/logo.png');

    cleanup();
    render(
      <Provider theme={defaultTheme}>
        <WatermarkSection client={dam} watermark={dam.watermark} canEdit={false} onSaved={onSaved} />
      </Provider>,
    );
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
  });
});
