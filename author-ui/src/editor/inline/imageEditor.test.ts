import { describe, expect, it } from 'vitest';
import { areasFromAssetMap, assetOfRendition, formatImageMap, imageEditEntries, imageEditorFeatures, parseImageMap, readImageEdits } from './ImageEditorDialog';

describe('image editor', () => {
  it('reads the plugins of the in-place configuration', () => {
    const f = imageEditorFeatures({
      editorType: 'image',
      inplaceEditingConfig: {
        plugins: {
          crop: { features: '*', aspectRatios: { square: { name: 'Square', ratio: '1' }, portrait: { name: 'Portrait', ratio: '1.6180' } } },
          flip: { features: '-' },
          rotate: { features: '*' },
        },
      },
    });
    expect(f).toEqual({ crop: true, rotate: true, flip: false, map: false, ratios: [{ name: 'Square', ratio: 1 }, { name: 'Portrait', ratio: 1.618 }] });
    expect(imageEditorFeatures(undefined)).toMatchObject({ crop: true, rotate: true, flip: false, map: false, ratios: [] });
  });

  it('reads and writes the edits like AEM stores them', () => {
    expect(readImageEdits({ imageCrop: '10,20,110,220', imageRotate: '90', imageFlipHorizontal: 'true' })).toEqual({ crop: '10,20,110,220', rotate: 90, flipH: true, flipV: false, areas: [] });
    expect(readImageEdits({})).toEqual({ crop: null, rotate: 0, flipH: false, flipV: false, areas: [] });
    const entries = imageEditEntries({ crop: '1,2,3,4', rotate: 180, flipH: false, flipV: true, areas: [] });
    expect(entries).toEqual(
      expect.arrayContaining([
        { name: './imageCrop', value: '1,2,3,4' },
        { name: './imageRotate', value: '180' },
        { name: './imageFlipHorizontal@Delete', value: 'true' },
        { name: './imageFlipVertical', value: 'true' },
        { name: './imageFlipVertical@TypeHint', value: 'Boolean' },
      ]),
    );
    expect(imageEditEntries({ crop: null, rotate: 0, flipH: false, flipV: false, areas: [] }).map((e) => e.name)).toEqual([
      './imageMap@Delete',
      './imageCrop@Delete',
      './imageRotate@Delete',
      './imageFlipHorizontal@Delete',
      './imageFlipVertical@Delete',
      './jcr:lastModified',
      './jcr:lastModifiedBy',
    ]);
  });

  it('writes and reads the image map in AEM\'s format', () => {
    const areas = [
      { shape: 'rect' as const, coords: [10, 20, 110, 220], href: '/content/wknd/us/en.html', target: '_blank', alt: 'Home' },
      { shape: 'circle' as const, coords: [300, 300, 50], href: 'https://example.com', target: '', alt: 'Ex' },
      { shape: 'poly' as const, coords: [0, 0, 100, 0, 50, 80], href: '', target: '', alt: '' },
    ];
    const map = formatImageMap(areas, 1280, 853);
    expect(map).toBe(
      '[rect(10,20,110,220)"/content/wknd/us/en.html"|"_blank"|"Home"|(0.0078,0.0234,0.0859,0.2579)]' +
        '[circle(300,300,50)"https://example.com"|""|"Ex"|(0.2344,0.3517,0.0391)]' +
        '[poly(0,0,100,0,50,80)""|""|""|(0.0000,0.0000,0.0781,0.0000,0.0391,0.0938)]',
    );
    expect(parseImageMap(map)).toEqual(areas);
    expect(formatImageMap([], 1, 1)).toBeNull();
    expect(parseImageMap('garbage')).toEqual([]);
    expect(imageEditEntries({ crop: null, rotate: 0, flipH: false, flipV: false, areas }, { w: 1280, h: 853 })[0]).toEqual({ name: './imageMap', value: map });
  });

  it('turns the map drawn on the asset into areas of the image shown', () => {
    const map = '[rect(162,432,551,1058)"/content/wknd/en/adventures"|""|"Surf"|(0.1,0.4,0.34,0.98)][circle(680,508,113)"https://x.y"|"_blank"|"Island"|(0.5,0.5,0.1)][rect(1,1,2,2)"/a"|""|""]';
    expect(areasFromAssetMap(map, 1280, 853)).toEqual([
      { shape: 'rect', coords: [128, 341, 435, 836], href: '/content/wknd/en/adventures', target: '', alt: 'Surf' },
      { shape: 'circle', coords: [640, 427, 128], href: 'https://x.y', target: '_blank', alt: 'Island' },
    ]);
    expect(areasFromAssetMap(null, 10, 10)).toEqual([]);
    expect(assetOfRendition('/content/dam/a/b.jpg/_jcr_content/renditions/cq5dam.web.1280.1280.jpeg')).toBe('/content/dam/a/b.jpg');
    expect(assetOfRendition('/x.jpg')).toBeNull();
  });
});
