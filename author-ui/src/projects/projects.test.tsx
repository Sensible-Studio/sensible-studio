import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { dueText, projectUrl, type Project, type ProjectsClient, type ProjectSummary } from './api';
import { ProjectsConsole } from './ProjectsConsole';

afterEach(cleanup);

const SUMMARY: ProjectSummary = { path: '/content/projects/spring', name: 'spring', title: 'Spring campaign', description: 'Relaunch', due: null, active: true, owner: 'ana', members: [{ user: 'ana', role: 'owner' }], myRole: 'owner', open: 1, done: 1, overdue: 0 };
const PROJECT: Project = {
  ...SUMMARY,
  links: [{ path: '/content/wknd/en', title: 'English', exists: true, kind: 'page' }],
  tasks: [
    { id: 't1', title: 'Write the copy', assignee: 'ana', priority: 'high', status: 'open', overdue: false },
    { id: 't2', title: 'Pick the photos', priority: 'medium', status: 'done', doneBy: 'bob', overdue: false },
  ],
};

function client(over: Partial<ProjectsClient> = {}): ProjectsClient {
  return {
    list: vi.fn(() => Promise.resolve({ projects: [SUMMARY, { ...SUMMARY, path: '/content/projects/old', name: 'old', title: 'Old one', active: false }], canCreate: true })),
    get: vi.fn(() => Promise.resolve(PROJECT)),
    myTasks: vi.fn(() => Promise.resolve([])),
    create: vi.fn(() => Promise.resolve(PROJECT)),
    change: vi.fn(() => Promise.resolve(PROJECT)),
    remove: vi.fn(() => Promise.resolve()),
    ...over,
  };
}

const show = (c: ProjectsClient, name: string | null) =>
  render(
    <Provider theme={defaultTheme}>
      <ProjectsConsole client={c} name={name} />
    </Provider>,
  );

describe('projects', () => {
  it('says when something is due', () => {
    const today = new Date(2026, 10, 11);
    expect(dueText('2026-11-11', today)).toBe('due today');
    expect(dueText('2026-11-12', today)).toBe('due tomorrow');
    expect(dueText('2026-11-21', today)).toBe('due in 10 days');
    expect(dueText('2026-11-10', today)).toBe('1 day late');
    expect(dueText('2026-11-01', today)).toBe('10 days late');
    expect(dueText(null, today)).toBe('');
    expect(projectUrl('spring campaign')).toBe('/studio/projects.html/spring%20campaign');
  });

  it('lists the active projects as cards and the others on request', async () => {
    show(client(), null);
    const card = await screen.findByRole('link', { name: /Spring campaign/ });
    expect(card.getAttribute('href')).toBe('/studio/projects.html/spring');
    expect(screen.getByText(/1 of 2 done/)).toBeTruthy();
    expect(screen.queryByText('Old one')).toBeNull();
    fireEvent.click(screen.getByRole('radio', { name: 'All (2)' }));
    expect(screen.getByText('Old one')).toBeTruthy();
  });

  it('shows a project with its tasks, team and content, and finishes a task on a tick', async () => {
    const c = client();
    show(c, 'spring');
    expect(await screen.findByRole('heading', { name: 'Spring campaign' })).toBeTruthy();
    expect(screen.getByText('Write the copy')).toBeTruthy();
    expect(screen.getByText(/done by bob/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'English' }).getAttribute('href')).toBe('/sites.html/content/wknd/en');
    fireEvent.click(screen.getByRole('checkbox', { name: 'Write the copy: done' }));
    await waitFor(() => expect(c.change).toHaveBeenCalledWith('/content/projects/spring', 'task', { id: 't1', status: 'done' }));
  });
});
