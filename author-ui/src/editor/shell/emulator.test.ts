import { describe, expect, it } from 'vitest';
import { breakpointOf, clampSize, DEVICES, deviceHeight, deviceWidth, effectiveScale, fitScale, MAX_SIZE, MIN_SIZE, PRESETS, responsiveWith } from './EmulatorBar';

describe('emulator devices', () => {
  it('rotating a device swaps width and height; the desktop has no size', () => {
    const se = DEVICES.find((d) => d.id === 'iphone-se')!;
    expect(deviceWidth(se, 'portrait')).toBe(375);
    expect(deviceHeight(se, 'portrait')).toBe(667);
    expect(deviceWidth(se, 'landscape')).toBe(667);
    expect(deviceHeight(se, 'landscape')).toBe(375);
    expect(deviceWidth(DEVICES[0], 'portrait')).toBeNull();
    expect(deviceHeight(DEVICES[0], 'landscape')).toBeNull();
  });

  it('the list starts with Desktop and Responsive, like the Chrome device toolbar, and every device has a kind', () => {
    expect(DEVICES.slice(0, 2).map((d) => d.id)).toEqual(['native', 'responsive']);
    for (const d of DEVICES.slice(1)) expect(d.kind).toBeTruthy();
    expect(DEVICES.filter((d) => d.kind === 'phone').length).toBeGreaterThan(3);
    expect(DEVICES.filter((d) => d.kind === 'tablet').length).toBeGreaterThan(2);
  });

  it('a Responsive size is stored in portrait terms, so what the user types stays what they see after rotating', () => {
    const r = DEVICES.find((d) => d.id === 'responsive')!;
    const portrait = responsiveWith(r, 'portrait', 500, 900);
    expect([portrait.width, portrait.height]).toEqual([500, 900]);
    const landscape = responsiveWith(r, 'landscape', 1000, 600);
    expect([deviceWidth(landscape, 'landscape'), deviceHeight(landscape, 'landscape')]).toEqual([1000, 600]);
    expect([landscape.width, landscape.height]).toEqual([600, 1000]);
  });

  it('typed and dragged sizes are clamped and rounded', () => {
    expect(clampSize(10)).toBe(MIN_SIZE);
    expect(clampSize(99999)).toBe(MAX_SIZE);
    expect(clampSize(399.6)).toBe(400);
    const r = DEVICES.find((d) => d.id === 'responsive')!;
    expect(responsiveWith(r, 'portrait', 1, 1).width).toBe(MIN_SIZE);
  });

  it('the breakpoint of a viewport is the smallest one it is below; none means the default layout', () => {
    const bps = [
      { name: 'tablet', title: 'Tablet', width: 1200 },
      { name: 'phone', title: 'Smaller Screen', width: 768 },
      { name: 'default', title: 'Default' },
    ];
    expect(breakpointOf(375, bps)?.name).toBe('phone');
    expect(breakpointOf(768, bps)?.name).toBe('tablet');
    expect(breakpointOf(1199, bps)?.name).toBe('tablet');
    expect(breakpointOf(1200, bps)).toBeNull();
  });

  it('the five presets go from a phone to a large desktop and walk through every WKND layout', () => {
    expect(PRESETS.map((p) => [p.title, p.width])).toEqual([
      ['Phone', 360],
      ['Tablet', 768],
      ['Desktop S', 1024],
      ['Desktop M', 1280],
      ['Desktop L', 1920],
    ]);
    const bps = [
      { name: 'tablet', title: 'Tablet', width: 1200 },
      { name: 'phone', title: 'Smaller Screen', width: 768 },
      { name: 'default', title: 'Default' },
    ];
    expect(PRESETS.map((p) => breakpointOf(p.width, bps)?.name ?? 'default')).toEqual(['phone', 'tablet', 'tablet', 'default', 'default']);
  });

  it('fit to window scales a wide viewport down to the stage and never scales a narrow one up', () => {
    expect(fitScale(2560, 1328)).toBeCloseTo(0.5, 2);
    expect(fitScale(375, 1328)).toBe(1);
    expect(effectiveScale('fit', 2560, 1328)).toBeCloseTo(0.5, 2);
    expect(effectiveScale(0.75, 375, 1328)).toBe(0.75);
    expect(effectiveScale(1.5, 2560, 400)).toBe(1.5);
  });
});
