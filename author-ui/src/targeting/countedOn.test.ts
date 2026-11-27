import { describe, expect, it } from 'vitest';
import { countedOn } from './api';

describe('where a report was counted', () => {
  it('says nothing before the answer is there', () => {
    expect(countedOn(undefined)).toBeNull();
  });

  it('says so when no target delivers to a live instance', () => {
    expect(countedOn([])).toEqual({ text: 'Counted on this instance only: no publishing target delivers to a live instance.', warn: false });
  });

  it('names the live site, and the number of instances when there are several', () => {
    expect(countedOn([{ id: 'a', title: 'Live', ok: true }])?.text).toBe('Counted on the live site.');
    expect(countedOn([{ id: 'a', title: 'Live', ok: true }, { id: 'b', title: 'Live 2', ok: true }])).toEqual({ text: 'Counted on the live site: 2 instances, added up.', warn: false });
  });

  it('warns about an instance that did not answer', () => {
    const c = countedOn([{ id: 'a', title: 'Live', ok: true }, { id: 'b', title: 'Live 2', ok: false, error: 'HTTP 503' }]);
    expect(c?.warn).toBe(true);
    expect(c?.text).toBe('Live 2 did not answer: what it counted is missing from these numbers.');
  });
});
