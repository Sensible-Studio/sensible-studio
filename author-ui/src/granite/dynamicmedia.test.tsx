import { describe, expect, it } from 'vitest';
import {
  breakpointSlug,
  breakpointsAccordion,
  DEFAULT_BREAKPOINTS,
  dynamicMediaTransform,
  policyBreakpoints,
} from './components/fields/dynamicmedia';
import type { GraniteNode } from './types';

const hooked: GraniteNode = {
  name: 'dialog',
  properties: { extraClientlibs: ['studio.dam.dynamicmedia.editor'] },
  children: [
    { name: 'preset', properties: { name: './preset' }, children: [] },
    {
      name: 'breakpoints',
      properties: { 'sling:resourceType': 'granite/ui/components/coral/foundation/container' },
      children: [{ name: 'granite:data', properties: { 'studio-dm-hook': 'breakpoints' }, children: [] }],
    },
  ],
};

describe('Image delivery breakpoints', () => {
  it('slugs breakpoint names the way dynamicmedia.js does', () => {
    expect(breakpointSlug('X-Large')).toBe('x-large');
    expect(breakpointSlug(' Tablet (portrait) ')).toBe('tablet-portrait');
  });

  it('reads the configuration, sorted by lower edge; defaults without one', () => {
    expect(policyBreakpoints(undefined)).toEqual(DEFAULT_BREAKPOINTS);
    expect(policyBreakpoints({ breakpoints: {} })).toEqual(DEFAULT_BREAKPOINTS);
    const bps = policyBreakpoints({
      breakpoints: { item0: { name: 'Desktop', min: '1024', max: '' }, item1: { name: 'Phone', min: 0, max: 1023 }, item2: { name: '  ' } },
    });
    expect(bps).toEqual([
      { name: 'Phone', min: 0, max: 1023 },
      { name: 'Desktop', min: 1024, max: 0 },
    ]);
  });

  it('replaces the hooked container with a panel per breakpoint', () => {
    const out = dynamicMediaTransform(hooked, { design: { breakpoints: { item0: { name: 'Phone', min: 0, max: 767 }, item1: { name: 'Desktop', min: 768 } } } });
    const acc = out.children[1];
    expect(acc.properties['sling:resourceType']).toBe('granite/ui/components/coral/foundation/accordion');
    const panels = acc.children[0].children;
    expect(panels.map((p) => p.properties['jcr:title'])).toEqual(['Phone (0–767 px)', 'Desktop (from 768 px)']);
    const fields = panels[0].children[0].children;
    expect(fields.map((f) => f.properties.name)).toEqual(['./crop_phone', './preset_phone']);
    expect(fields[0].children[0].properties['sling:resourceType']).toBe('studio/dam/datasources/smartcrops');
    expect(out.children[0]).toEqual(hooked.children[0]);
  });

  it('offers the five default panels without a configuration', () => {
    expect(breakpointsAccordion(undefined).children[0].children).toHaveLength(5);
  });
});
