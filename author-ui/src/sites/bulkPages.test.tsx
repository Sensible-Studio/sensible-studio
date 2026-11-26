import { DialogContainer, Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { PostEntry } from '../granite';
import { BulkPagePropertiesDialog, BulkVersionDialog, bulkEntries, editTouches, NO_EDIT, type BulkPagesClient } from './BulkPageDialogs';

afterEach(cleanup);
beforeAll(() => {
  // the Picker's list asks for CSS.escape, which jsdom lacks
  (globalThis as { CSS?: { escape?: (s: string) => string } }).CSS ??= {};
  globalThis.CSS.escape ??= (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`);
});

const PAGES = [
  { path: '/content/wknd/a', title: 'A' },
  { path: '/content/wknd/b', title: 'B' },
];

describe('properties of several pages', () => {
  it('writes only what was set', () => {
    expect(editTouches(NO_EDIT)).toBe(false);
    expect(bulkEntries(NO_EDIT, ['x:y'])).toEqual([]);
    // tags are added to each page's own, without doubles
    expect(bulkEntries({ ...NO_EDIT, tags: ['wknd:surf', 'x:y'] }, ['x:y'])).toEqual([
      { name: './cq:tags', value: 'x:y' },
      { name: './cq:tags', value: 'wknd:surf' },
      { name: './cq:tags@TypeHint', value: 'String[]' },
    ]);
    // or put instead of them; none at all removes the property
    expect(bulkEntries({ ...NO_EDIT, tags: ['wknd:surf'], replaceTags: true }, ['x:y']).map((e) => e.value)).toEqual(['wknd:surf', 'String[]']);
    expect(bulkEntries({ ...NO_EDIT, replaceTags: true }, ['x:y'])).toEqual([{ name: './cq:tags@Delete', value: 'true' }]);
    expect(bulkEntries({ ...NO_EDIT, hideInNav: 'yes' }, [])).toEqual([
      { name: './hideInNav', value: 'true' },
      { name: './hideInNav@TypeHint', value: 'Boolean' },
    ]);
    expect(bulkEntries({ ...NO_EDIT, hideInNav: 'no' }, [])).toEqual([{ name: './hideInNav@Delete', value: 'true' }]);
    const timed = bulkEntries({ ...NO_EDIT, onTime: { mode: 'set', value: '2027-01-02T10:00' }, offTime: { mode: 'clear', value: '' } }, []);
    expect(timed[0].name).toBe('./onTime');
    expect(timed[0].value).toBe(new Date('2027-01-02T10:00').toISOString());
    expect(timed.slice(1)).toEqual([
      { name: './onTime@TypeHint', value: 'Date' },
      { name: './offTime@Delete', value: 'true' },
    ]);
  });

  it('saves the same change on every page and says how many', async () => {
    const posts: Array<[string, PostEntry[]]> = [];
    const client: BulkPagesClient = { post: async (p, e) => void posts.push([p, e]), tagsOf: async () => [] };
    const onDone = vi.fn();
    const close = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <DialogContainer onDismiss={close}>
          <BulkPagePropertiesDialog client={client} pages={PAGES} user="asmith" onDone={onDone} close={close} />
        </DialogContainer>
      </Provider>,
    );
    expect(screen.getByText('2 pages')).toBeTruthy();
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save.hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: /Hide in Navigation/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Hide' }));
    expect(save.hasAttribute('disabled')).toBe(false);
    fireEvent.click(save);
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(2));
    expect(posts.map((p) => p[0])).toEqual(['/content/wknd/a/jcr:content', '/content/wknd/b/jcr:content']);
    expect(posts[0][1].slice(0, 2)).toEqual([
      { name: './hideInNav', value: 'true' },
      { name: './hideInNav@TypeHint', value: 'Boolean' },
    ]);
    // and the page counts as changed, by who changed it
    expect(posts[0][1].slice(2).map((e) => e.name)).toEqual(['./cq:lastModified', './cq:lastModified@TypeHint', './cq:lastModifiedBy']);
    expect(posts[0][1][4].value).toBe('asmith');
    expect(close).toHaveBeenCalled();
  });

  it('says how far it got when a page cannot be saved', async () => {
    const client: BulkPagesClient = {
      post: async (p) => {
        if (p.includes('/b/')) throw new Error('locked by someone else');
      },
      tagsOf: async () => [],
    };
    const onDone = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <DialogContainer onDismiss={() => undefined}>
          <BulkPagePropertiesDialog client={client} pages={PAGES} onDone={onDone} close={vi.fn()} />
        </DialogContainer>
      </Provider>,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: 'Replace the tags the pages have' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findByRole('alert')).textContent).toBe('1 of 2 saved. locked by someone else');
    expect(onDone).toHaveBeenCalledWith(1);
  });
});

describe('a version of several pages', () => {
  it('creates one of each with the label and comment', async () => {
    const made: string[] = [];
    const onDone = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <DialogContainer onDismiss={() => undefined}>
          <BulkVersionDialog pages={PAGES} createVersion={async (p, l, c) => void made.push(`${p} ${l} ${c}`)} onDone={onDone} close={vi.fn()} />
        </DialogContainer>
      </Provider>,
    );
    fireEvent.change(screen.getByLabelText('Label'), { target: { value: 'Before the campaign' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(2));
    expect(made).toEqual(['/content/wknd/a Before the campaign undefined', '/content/wknd/b Before the campaign undefined']);
  });
});
