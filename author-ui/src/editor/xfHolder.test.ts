import { describe, expect, it } from 'vitest';
import { xfVariation } from './App';

describe('opening an experience fragment in the editor', () => {
  const P = '/content/experience-fragments/site/en/contributors/ian';
  const page = (master?: boolean) => ({ 'jcr:primaryType': 'cq:Page', 'jcr:content': master === undefined ? {} : { 'cq:xfMasterVariation': master } });

  it('opens the master variation', () => {
    expect(xfVariation(P, { 'jcr:primaryType': 'cq:Page', 'jcr:content': {}, byline: page(), master: page(true) })).toBe(`${P}/master`);
  });

  it('opens the first variation when none is the master, and nothing when there is none', () => {
    expect(xfVariation(P, { 'jcr:content': {}, byline: page(), other: page(false) })).toBe(`${P}/byline`);
    expect(xfVariation(P, { 'jcr:content': {} })).toBeNull();
    expect(xfVariation(P, null)).toBeNull();
  });
});
