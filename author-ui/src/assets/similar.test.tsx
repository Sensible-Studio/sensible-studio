import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { fitLabel } from './views';
import { describe, expect, it, vi } from 'vitest';
import { SimilarRail } from './similar';
import { MemoryDam } from './testing/MemoryDam';
import { parseSearch, searchString } from './route';
import { SearchRail } from './search';

const item = (name: string, score: number) => ({ type: 'asset' as const, path: `/content/dam/wknd/${name}`, name, title: name, mimeType: 'image/jpeg', score });

describe('Similar rail', () => {
  it('lists the pictures most like this one and opens the one clicked', async () => {
    const dam = new MemoryDam();
    dam.similarTo = { path: '', items: [item('near.jpg', 0.913), item('alike.jpg', 0.6)], compared: 2, notIndexed: 0 };
    const navigate = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <SimilarRail client={dam} path="/content/dam/wknd/mine.jpg" canEdit navigate={navigate} />
      </Provider>,
    );
    expect(await screen.findByText('91%')).toBeTruthy();
    expect(screen.getByText('60%')).toBeTruthy();
    expect(dam.calls).toContain('similar /content/dam/wknd/mine.jpg 24');
    expect(screen.queryByRole('button', { name: 'Look at them now' })).toBeNull();
    fireEvent.click(screen.getByText('near.jpg'));
    expect(navigate).toHaveBeenCalledWith({ kind: 'details', path: '/content/dam/wknd/near.jpg', properties: false });
  });

  it('offers to look at the pictures that were not compared', async () => {
    const dam = new MemoryDam();
    dam.similarTo = { path: '', items: [], compared: 0, notIndexed: 3 };
    render(
      <Provider theme={defaultTheme}>
        <SimilarRail client={dam} path="/content/dam/wknd/mine.jpg" canEdit navigate={() => undefined} />
      </Provider>,
    );
    expect(await screen.findByText(/3 pictures have not been looked at yet/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Look at them now' }));
    await waitFor(() => expect(dam.calls).toContain('similarIndex '));
    expect(await screen.findByText(/3 pictures are being looked at/)).toBeTruthy();
  });

  it('says so when the file has no picture', async () => {
    const dam = new MemoryDam();
    dam.similarTo = { path: '', items: [], compared: 0, notIndexed: 0, noImage: true };
    render(
      <Provider theme={defaultTheme}>
        <SimilarRail client={dam} path="/content/dam/wknd/notes.txt" canEdit={false} navigate={() => undefined} />
      </Provider>,
    );
    expect(await screen.findByText('This file has no picture to compare.')).toBeTruthy();
  });
});

describe('searching by what a picture shows', () => {
  it('is part of the search address', () => {
    expect(searchString({ fulltext: 'a red tent', look: true })).toBe('?fulltext=a+red+tent&look=1');
    expect(parseSearch('?fulltext=a+red+tent&look=1')).toEqual({ fulltext: 'a red tent', look: true });
    expect(parseSearch('?fulltext=tent')).toEqual({ fulltext: 'tent' });
  });

  it('keeps the wish for weak matches in the address, only with a description', () => {
    expect(searchString({ fulltext: 'a dog', look: true, weak: true })).toBe('?fulltext=a+dog&look=1&weak=1');
    expect(parseSearch('?fulltext=a+dog&look=1&weak=1')).toEqual({ fulltext: 'a dog', look: true, weak: true });
    expect(parseSearch('?fulltext=a+dog&weak=1')).toEqual({ fulltext: 'a dog' });
  });

  it('keeps the wish to translate the description in the address', () => {
    expect(searchString({ fulltext: 'un cort roșu', look: true, translate: true })).toBe('?fulltext=un+cort+ro%C8%99u&look=1&tr=1');
    expect(parseSearch('?fulltext=un+cort&look=1&tr=1')).toEqual({ fulltext: 'un cort', look: true, translate: true });
    expect(parseSearch('?fulltext=un+cort&tr=1')).toEqual({ fulltext: 'un cort' });
  });

  it('offers the weak matches once pictures are described, and drops them with it', () => {
    const onChange = vi.fn();
    const { unmount } = render(
      <Provider theme={defaultTheme}>
        <SearchRail query={{ fulltext: 'tent', look: true }} scopeTitle="Media" onChange={onChange} />
      </Provider>,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: 'Also weak matches' }));
    expect(onChange).toHaveBeenCalledWith({ fulltext: 'tent', look: true, weak: true });
    unmount();
  });

  it('says how well a picture fits without rounding a little to nothing', () => {
    expect(fitLabel(0.7749)).toBe('77%');
    expect(fitLabel(0.012)).toBe('1%');
    expect(fitLabel(0.0013)).toBe('under 1%');
  });

  it('is switched on in the filters rail', () => {
    const onChange = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <SearchRail query={{ fulltext: 'tent' }} scopeTitle="Media" onChange={onChange} />
      </Provider>,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: 'As what the picture shows' }));
    expect(onChange).toHaveBeenCalledWith({ fulltext: 'tent', look: true });
  });
});
