import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ToolCrumbs, toolOf } from './ToolCrumbs';

afterEach(cleanup);

describe('toolOf', () => {
  it('finds the System group of a tool by its URL', () => {
    expect(toolOf('/studio/health.html')?.group.id).toBe('developer');
    expect(toolOf('/etc/linkchecker.html')?.group.id).toBe('publishing');
    expect(toolOf('/workflow/inbox.html')?.tool.title).toBe('Inbox');
    expect(toolOf('/security/permissions.html/admin')?.group.id).toBe('security');
    expect(toolOf('/cloudservices/translation.html/conf/we-retail')?.group.id).toBe('translations');
    expect(toolOf('/sites.html/content/we-retail')).toBeNull();
  });
});

describe('ToolCrumbs', () => {
  it('reads Home › System › group › tool', () => {
    render(<ToolCrumbs url="/studio/health.html" />);
    const text = screen.getByRole('navigation').textContent ?? '';
    expect(text.replace(/\s+/g, ' ')).toMatch(/Home.*System.*Developer.*Health/);
  });

  it('adds the console’s own crumbs after the tool', () => {
    render(<ToolCrumbs url="/translation/projects.html" extra={[{ path: 'project:p1', title: 'Spring campaign' }]} />);
    const text = screen.getByRole('navigation').textContent ?? '';
    expect(text.replace(/\s+/g, ' ')).toMatch(/System.*Translations.*Jobs.*Spring campaign/);
  });
});
