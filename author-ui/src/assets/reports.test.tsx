import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetsApp } from './AssetsApp';
import { summaryText } from './reports';
import { assetsRoute, routeUrl, type AssetsRoute } from './route';
import { MemoryDam } from './testing/MemoryDam';

afterEach(cleanup);

if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = {
    escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`),
  };
}

function demo(): MemoryDam {
  return new MemoryDam()
    .folder('/content/dam/wknd', 'WKND Site')
    .asset('/content/dam/wknd/surf.jpg', { size: 2048 }, { 'dc:title': 'Surf in Bali', 'dc:creator': 'Ana' })
    .asset('/content/dam/wknd/ski.jpg', { size: 1024 }, { 'dc:title': 'Ski' })
    .asset('/content/dam/other.jpg', {}, { 'dc:title': 'Elsewhere' });
}

function mount(dam: MemoryDam, route: AssetsRoute) {
  window.history.replaceState(null, '', routeUrl(route));
  return render(
    <Provider theme={defaultTheme}>
      <AssetsApp client={dam} initial={route} />
    </Provider>,
  );
}

describe('report routes', () => {
  it('are AEM reports page plus the report path', () => {
    const id = '00000000-0000-4000-8000-000000000001';
    expect(routeUrl({ kind: 'reports' })).toBe('/mnt/overlay/dam/gui/content/reports/reportspage.html/var/studio/dam/reports');
    expect(assetsRoute({ pathname: routeUrl({ kind: 'report', id }), hash: '' })).toEqual({ kind: 'report', id });
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/reports/reportspage.html', hash: '' })).toEqual({ kind: 'reports' });
    expect(assetsRoute({ pathname: '/mnt/overlay/dam/gui/content/reports/reportspage.html/var/studio/dam/reports/../x', hash: '' })).toEqual({ kind: 'reports' });
  });

  it('sum up what a report found', () => {
    expect(summaryText({ type: 'x', rows: 2, summary: { assets: 2, size: 3072 } })).toBe('2 files · 3 KB');
    expect(summaryText({ type: 'x', rows: 1, summary: { links: 1, active: 0 } })).toBe('1 link · 0 active');
    expect(summaryText({ type: 'x', rows: 4, summary: {} })).toBe('4 rows');
  });
});

describe('file reports', () => {
  it('are created with the wizard and opened', async () => {
    const dam = demo();
    mount(dam, { kind: 'reports' });
    expect(await screen.findByText('No reports yet')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(await within(dialog).findByRole('button', { name: /Files/ }));
    expect((within(dialog).getByRole('textbox', { name: /Title/ }) as HTMLInputElement).value).toBe('Files report');
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Folder' }), { target: { value: '/content/dam/wknd/' } });
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Type' }));
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Added By' }));
    const props = within(dialog).getByRole('textbox', { name: /Metadata columns/ });
    fireEvent.change(props, { target: { value: 'dc:creator' } });
    fireEvent.keyDown(props, { key: 'Enter' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() =>
      expect(dam.calls).toContain(
        'createReport {"type":"foldercontentreport","title":"Files report","path":"/content/dam/wknd","columns":["title","path","size","addedBy"],"properties":["dc:creator"],"excludeFragments":false,"includeRenditions":false,"excludeSubfolders":false}',
      ),
    );
    expect((await screen.findByRole('navigation', { name: 'Location' })).textContent).toContain('Files report');
    expect(window.location.pathname).toMatch(/reportspage\.html\/var\/studio\/dam\/reports\/[0-9a-f-]{36}$/);
    expect(screen.getByText('2 files · 3 KB')).toBeTruthy();
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Title', 'Path', 'Size', 'Added By', 'dc:creator']);
    expect(within(table).getByText('2 KB')).toBeTruthy();
    expect(within(table).getByText('Ana')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Download CSV/ }).getAttribute('href')).toMatch(/^\/bin\/studio\/dam\/reports\.csv\?id=/);
    fireEvent.click(within(table).getByRole('link', { name: '/content/dam/wknd/surf.jpg' }));
    expect(await screen.findByRole('heading', { name: 'Surf in Bali' })).toBeTruthy();
  });

  it('are listed and deleted', async () => {
    const dam = demo();
    await dam.createReport({ type: 'foldercontentreport', title: 'All files', description: 'For the audit' });
    mount(dam, { kind: 'reports' });
    expect(await screen.findByRole('link', { name: 'All files' })).toBeTruthy();
    expect(screen.getByText('For the audit')).toBeTruthy();
    expect(screen.getByText('3 files · 4 KB')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Delete All files' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('No reports yet')).toBeTruthy();
    expect(dam.calls.some((c) => c.startsWith('deleteReports '))).toBe(true);
  });

  it('ask only what the type needs', async () => {
    mount(demo(), { kind: 'reports' });
    fireEvent.click(await screen.findByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(await within(dialog).findByRole('button', { name: /Disk Usage/ }));
    expect(within(dialog).queryByRole('group', { name: 'Columns' })).toBeNull();
    expect(within(dialog).getByRole('checkbox', { name: 'Include renditions' })).toBeTruthy();
    expect(within(dialog).queryByLabelText('From')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Back' }));
    fireEvent.click(within(dialog).getByRole('button', { name: /Link Share/ }));
    expect(within(dialog).queryByRole('textbox', { name: 'Folder' })).toBeNull();
    expect(within(dialog).getByLabelText('From')).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText('From'), { target: { value: '2026-10-10' } });
    fireEvent.change(within(dialog).getByLabelText('To'), { target: { value: '2026-10-01' } });
    expect(within(dialog).getByText(/The start date is after the end date/)).toBeTruthy();
    expect((within(dialog).getByRole('button', { name: 'Create' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
