import { describe, expect, it } from 'vitest';
import { isAdminOnly, toolsFor } from './audience';
import { TOOLS } from './navigation';

describe('the System tools an author is offered', () => {
  it('an administrator gets every tool', () => {
    expect(toolsFor(true)).toBe(TOOLS);
  });

  it('an author gets none of the administrators\' tools, and no empty category', () => {
    const tools = toolsFor(false);
    const urls = tools.flatMap((c) => c.tools.map((t) => t.url));
    expect(urls).not.toContain('/replication.html');
    expect(urls).not.toContain('/security/users.html');
    expect(urls).not.toContain('/studio/health.html');
    expect(urls.some((u) => u.startsWith('/system/console/'))).toBe(false);
    expect(urls).toContain('/launches.html');
    expect(urls).toContain('/workflow/inbox.html');
    expect(tools.every((c) => c.tools.length > 0)).toBe(true);
    expect(tools.length).toBeLessThan(TOOLS.length);
  });

  it('knows an administrators\' tool by its address', () => {
    expect(isAdminOnly('/security/permissions.html?principal=everyone')).toBe(true);
    expect(isAdminOnly('/sites.html/content')).toBe(false);
  });
});
