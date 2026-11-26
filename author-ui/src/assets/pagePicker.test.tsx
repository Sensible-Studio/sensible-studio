import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PagePickerButton, pagesOf, recordsOf, startFolder, type PageItem } from './pagePicker';

describe('page picker', () => {
  it('reads pages and folders from the repository JSON', () => {
    const items = pagesOf(
      {
        'jcr:primaryType': 'cq:Page',
        'jcr:content': { 'jcr:title': 'WKND' },
        us: { 'jcr:primaryType': 'cq:Page', 'jcr:content': { 'jcr:title': 'United States' }, en: { 'jcr:primaryType': 'cq:Page' } },
        ca: { 'jcr:primaryType': 'cq:Page', 'jcr:content': {} },
        shared: { 'jcr:primaryType': 'sling:OrderedFolder', 'jcr:title': 'Shared' },
        'rep:policy': { 'jcr:primaryType': 'rep:ACL' },
        data: { 'jcr:primaryType': 'nt:unstructured' },
      },
      '/content/wknd',
    );
    expect(items).toEqual([
      { name: 'us', path: '/content/wknd/us', title: 'United States', page: true, hasChildren: true },
      { name: 'ca', path: '/content/wknd/ca', title: 'ca', page: true, hasChildren: false },
      { name: 'shared', path: '/content/wknd/shared', title: 'Shared', page: false, hasChildren: false },
    ]);
  });

  it('starts next to the page the field already names', () => {
    expect(startFolder('/content/wknd/us/en/adventures.html', '/content')).toBe('/content/wknd/us/en');
    expect(startFolder('https://example.com', '/content')).toBe('/content');
    expect(startFolder('', '/content')).toBe('/content');
  });

  it('browses and hands back the chosen page', async () => {
    const tree: Record<string, PageItem[]> = {
      '/content': [{ name: 'wknd', path: '/content/wknd', title: 'WKND', page: true, hasChildren: true }],
      '/content/wknd': [{ name: 'us', path: '/content/wknd/us', title: 'United States', page: true, hasChildren: false }],
    };
    const onPick = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <PagePickerButton value="" onPick={onPick} list={async (p) => tree[p] ?? []} />
      </Provider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Browse pages' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Open WKND' }));
    fireEvent.click(await within(dialog).findByText('United States'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Choose' }));
    await waitFor(() => expect(onPick).toHaveBeenCalledWith('/content/wknd/us'));
  });

  it('lists content fragments, and the picker switches to them', async () => {
    expect(
      recordsOf(
        {
          faq: { 'jcr:primaryType': 'dam:Asset', 'jcr:content': { contentFragment: true, 'jcr:title': 'FAQ' } },
          'photo.jpg': { 'jcr:primaryType': 'dam:Asset', 'jcr:content': {} },
          more: { 'jcr:primaryType': 'sling:Folder', 'jcr:content': { 'jcr:title': 'More' } },
        },
        '/content/dam/site',
      ),
    ).toEqual([
      { name: 'faq', path: '/content/dam/site/faq', title: 'FAQ', page: true, hasChildren: false },
      { name: 'more', path: '/content/dam/site/more', title: 'More', page: false, hasChildren: true },
    ]);
    const onPick = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <PagePickerButton value="" onPick={onPick} list={async () => []} records={async (p) => (p === '/content/dam' ? [{ name: 'faq', path: '/content/dam/faq', title: 'FAQ', page: true, hasChildren: false }] : [])} />
      </Provider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Browse pages and content fragments' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('tab', { name: 'Content Fragments' }));
    fireEvent.click(await within(dialog).findByText('FAQ'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Choose' }));
    await waitFor(() => expect(onPick).toHaveBeenCalledWith('/content/dam/faq'));
  });
});
