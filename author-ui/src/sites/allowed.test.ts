import { describe, expect, it } from 'vitest';
import { allowedFor } from './SitesConsole';

describe('what the selection bar offers', () => {
  const reader = { canEdit: false, canDelete: false, canPublish: false };
  const author = { canEdit: true, canDelete: true, canPublish: true };

  it('offers a reader what reads, not what changes, publishes or removes', () => {
    expect(['edit', 'properties', 'copy', 'csv', 'siteconfig', 'overview'].every((id) => allowedFor(id, [reader]))).toBe(true);
    expect(['lock', 'version', 'create-page', 'publish', 'unpublish', 'move', 'delete', 'translate'].some((id) => allowedFor(id, [reader]))).toBe(false);
  });

  it('offers an author everything, and a mixed selection only what holds for all of it', () => {
    expect(['lock', 'publish', 'delete'].every((id) => allowedFor(id, [author]))).toBe(true);
    expect(allowedFor('delete', [author, reader])).toBe(false);
  });

  it('offers everything when the server does not say', () => {
    expect(allowedFor('delete', [{}])).toBe(true);
  });
});
