import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { describeGalleryPreset, GalleryPresetsSection } from './galleryPresets';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

describe('gallery presets', () => {
  it('say how a gallery looks', () => {
    expect(describeGalleryPreset({ thumbs: 'below', arrows: true, ratio: '16:9', zoom: true })).toBe('Thumbnails below · arrows · 16:9 · enlarging');
    expect(describeGalleryPreset({ thumbs: 'dots', arrows: false, ratio: '', zoom: false })).toBe('Dots · own shapes');
    expect(describeGalleryPreset({ thumbs: 'left', arrows: false, ratio: '1:1', zoom: false })).toBe('Thumbnails on the left · 1:1');
  });

  it('are created, listed and deleted on the Image Presets page', async () => {
    const dam = new MemoryDam();
    render(
      <Provider theme={defaultTheme}>
        <GalleryPresetsSection client={dam} />
      </Provider>,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Create Gallery Preset' }));
    fireEvent.change(await screen.findByRole('textbox', { name: /Title/ }), { target: { value: 'Product gallery' } });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Arrows on the picture' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('product-gallery')).toBeTruthy();
    expect(dam.galleryPresetList[0]).toMatchObject({ title: 'Product gallery', arrows: true, thumbs: 'below', ratio: '' });
    fireEvent.click(screen.getByRole('button', { name: 'Delete Product gallery' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(dam.galleryPresetList).toHaveLength(0));
  });
});
