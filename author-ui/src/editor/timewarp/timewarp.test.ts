import { describe, expect, it } from 'vitest';
import { toLocalInput, versionAt } from './TimewarpBar';

const revisions = [
  { id: 'a', name: '1.0', created: '2026-10-01T10:00:00.000Z' },
  { id: 'b', name: '1.1', created: '2026-10-05T10:00:00.000Z' },
  { id: 'c', name: '1.2', created: '2026-10-09T10:00:00.000Z' },
];

describe('timewarp', () => {
  it('finds the version in force at a moment: the newest created before it', () => {
    expect(versionAt(revisions, Date.parse('2026-10-06T00:00:00Z'))?.name).toBe('1.1');
    expect(versionAt(revisions, Date.parse('2026-10-05T10:00:00Z'))?.name).toBe('1.1');
    expect(versionAt(revisions, Date.parse('2026-09-01T00:00:00Z'))).toBeNull();
    expect(versionAt(revisions, Date.now())?.name).toBe('1.2');
  });

  it('formats a moment for a datetime-local input', () => {
    const t = new Date(2026, 9, 17, 9, 5).getTime();
    expect(toLocalInput(t)).toBe('2026-10-17T09:05');
  });
});
