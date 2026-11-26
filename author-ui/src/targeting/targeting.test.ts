import { describe as suite, expect, it } from 'vitest';
import { describe, segmentName, type Expression } from './api';
import { evenShares, sharesProblem } from './api';

suite('personalization api helpers', () => {
  it('describes an expression tree the way the console lists it', () => {
    const e: Expression = {
      type: 'or',
      items: [
        { type: 'and', items: [{ type: 'comparison', property: 'profile/age', operator: 'greater-than', value: '30' }, { type: 'comparison', property: 'geolocation/country', operator: 'equals', value: 'RO' }] },
        { type: 'comparison', property: 'profile/loyalty', operator: 'exists' },
      ],
    };
    expect(describe(e)).toBe('(profile/age is greater than 30 and geolocation/country equals RO) or profile/loyalty exists');
    expect(describe(null)).toMatch(/never matches/);
    expect(describe({ type: 'and', items: [] })).toBe('No conditions');
  });

  it('derives a segment name from a title', () => {
    expect(segmentName('Summer visitors')).toBe('summer-visitors');
    expect(segmentName('Vizitatori din Cluj-Napoca!')).toBe('vizitatori-din-cluj-napoca');
  });
});

suite('A/B test shares', () => {
  it('splits evenly in whole numbers that add up to 100', () => {
    expect(evenShares(['default', 'b'])).toEqual({ default: 50, b: 50 });
    expect(evenShares(['default', 'b', 'c'])).toEqual({ default: 34, b: 33, c: 33 });
    expect(Object.values(evenShares(['a', 'b', 'c', 'd', 'e', 'f', 'g'])).reduce((x, y) => x + y, 0)).toBe(100);
    expect(evenShares([])).toEqual({});
  });

  it('says why shares cannot be saved', () => {
    expect(sharesProblem({ default: 50, b: 50 })).toBeNull();
    expect(sharesProblem({ default: 70, b: 20 })).toBe('The shares add up to 90%; they must add up to 100%');
    expect(sharesProblem({ default: 100, b: 0 })).toBe('An A/B test needs at least two experiences with a share');
    expect(sharesProblem({ default: 50.5, b: 49.5 })).toBe('A share is a whole number from 0 to 100');
  });
});
