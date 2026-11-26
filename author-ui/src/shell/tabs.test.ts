import { describe, expect, it } from 'vitest';
import type { Place } from './places';
import { boot, canBack, canForward, deserialize, focusedId, initialState, paneOf, placeOf, reducer, serialize, tabOf, type ShellState } from './tabs';

const sites = (path: string): Place => ({ kind: 'sites', path });
const editor = (path: string): Place => ({ kind: 'editor', path });

const run = (state: ShellState, ...actions: Parameters<typeof reducer>[1][]) => actions.reduce(reducer, state);

describe('tabs', () => {
  it('starts with one tab at the given place', () => {
    const s = initialState(sites('/content/wknd'));
    expect(s.tabs).toHaveLength(1);
    expect(placeOf(tabOf(s))).toEqual(sites('/content/wknd'));
    expect(canBack(tabOf(s))).toBe(false);
  });

  it('pushes places on the active tab and walks back and forward', () => {
    let s = initialState(sites('/content'));
    s = run(s, { type: 'open', place: sites('/content/wknd') }, { type: 'open', place: editor('/content/wknd/us/en') });
    expect(tabOf(s).entries).toHaveLength(3);
    expect(placeOf(tabOf(s))).toEqual(editor('/content/wknd/us/en'));
    s = reducer(s, { type: 'back' });
    expect(placeOf(tabOf(s))).toEqual(sites('/content/wknd'));
    expect(canForward(tabOf(s))).toBe(true);
    s = reducer(s, { type: 'forward' });
    expect(placeOf(tabOf(s))).toEqual(editor('/content/wknd/us/en'));
    expect(canForward(tabOf(s))).toBe(false);
  });

  it('drops the forward entries when a new place is opened after going back', () => {
    let s = run(initialState(sites('/content')), { type: 'open', place: sites('/content/a') }, { type: 'open', place: sites('/content/b') });
    s = run(s, { type: 'back' }, { type: 'open', place: sites('/content/c') });
    expect(tabOf(s).entries.map((p) => (p.kind === 'sites' ? p.path : ''))).toEqual(['/content', '/content/a', '/content/c']);
    expect(canForward(tabOf(s))).toBe(false);
  });

  it('does not push the place the tab already shows', () => {
    const s = run(initialState(sites('/content')), { type: 'open', place: sites('/content') });
    expect(tabOf(s).entries).toHaveLength(1);
  });

  it('replaces the current entry in place', () => {
    let s = run(initialState(sites('/content')), { type: 'open', place: sites('/content/a') });
    s = reducer(s, { type: 'replace', place: sites('/content/a-filtered') });
    expect(tabOf(s).entries).toHaveLength(2);
    expect(placeOf(tabOf(s))).toEqual(sites('/content/a-filtered'));
  });

  it('opens a new tab after the active one and activates it', () => {
    let s = initialState(sites('/content'));
    const first = s.active;
    s = reducer(s, { type: 'open', place: editor('/content/x'), newTab: true });
    expect(s.tabs).toHaveLength(2);
    expect(s.active).not.toBe(first);
    expect(placeOf(tabOf(s))).toEqual(editor('/content/x'));
    expect(tabOf(s, first).entries).toHaveLength(1); // the first tab is untouched
    // a third tab opened from the first lands between them
    s = run(s, { type: 'activate', tab: first }, { type: 'open', place: sites('/content/dam'), newTab: true });
    expect(s.tabs.map((t) => t.id)[1]).toBe(s.active);
  });

  it('closes a tab and hands over to the left neighbour, the right one for the first', () => {
    let s = initialState(sites('/content'));
    const a = s.active;
    s = reducer(s, { type: 'open', place: sites('/content/b'), newTab: true });
    const b = s.active;
    s = reducer(s, { type: 'open', place: sites('/content/c'), newTab: true });
    const c = s.active;
    s = reducer(s, { type: 'close' }); // closes c
    expect(s.tabs.map((t) => t.id)).toEqual([a, b]);
    expect(s.active).toBe(b);
    s = reducer(s, { type: 'close', tab: a }); // closes the first while b is active
    expect(s.active).toBe(b);
    expect(s.closed.map((t) => t.id)).toEqual([c, a]);
  });

  it('never has zero tabs: closing the last one opens Home', () => {
    const s = reducer(initialState(sites('/content/wknd')), { type: 'close' });
    expect(s.tabs).toHaveLength(1);
    expect(placeOf(tabOf(s))).toEqual({ kind: 'start' });
    expect(s.closed).toHaveLength(1);
  });

  it('reopens the last closed tab with its history', () => {
    let s = run(initialState(sites('/content')), { type: 'open', place: sites('/content/a') });
    s = run(s, { type: 'close' }, { type: 'reopen' });
    expect(s.tabs).toHaveLength(2);
    expect(tabOf(s).entries).toHaveLength(2);
    expect(placeOf(tabOf(s))).toEqual(sites('/content/a'));
    expect(reducer(s, { type: 'reopen' })).toBe(s); // nothing left to reopen
  });

  it('closes the other tabs', () => {
    let s = initialState(sites('/content'));
    s = run(s, { type: 'open', place: sites('/content/b'), newTab: true }, { type: 'open', place: sites('/content/c'), newTab: true });
    const keep = s.tabs[1].id;
    s = reducer(s, { type: 'closeOthers', tab: keep });
    expect(s.tabs.map((t) => t.id)).toEqual([keep]);
    expect(s.active).toBe(keep);
  });

  it('jumps to a recorded entry of any tab (the browser landing on it)', () => {
    let s = run(initialState(sites('/content')), { type: 'open', place: sites('/content/a') });
    const first = s.active;
    s = reducer(s, { type: 'open', place: editor('/content/x'), newTab: true });
    s = reducer(s, { type: 'goto', tab: first, index: 0 });
    expect(s.active).toBe(first);
    expect(placeOf(tabOf(s))).toEqual(sites('/content'));
    expect(reducer(s, { type: 'goto', tab: 'missing', index: 0 })).toBe(s);
  });

  it('moves and titles tabs', () => {
    let s = initialState(sites('/content'));
    const a = s.active;
    s = reducer(s, { type: 'open', place: sites('/content/b'), newTab: true });
    const b = s.active;
    s = reducer(s, { type: 'move', tab: b, to: 0 });
    expect(s.tabs.map((t) => t.id)).toEqual([b, a]);
    s = reducer(s, { type: 'title', tab: b, title: 'Magazine' });
    expect(tabOf(s, b).title).toBe('Magazine');
    s = reducer(s, { type: 'open', place: sites('/content/c') });
    expect(tabOf(s).title).toBeUndefined(); // a new place, a new title
  });

  it('survives a round trip through storage as URLs', () => {
    let s = run(initialState(sites('/content')), { type: 'open', place: editor('/content/wknd/us/en') });
    s = reducer(s, { type: 'open', place: { kind: 'workflow', route: { kind: 'inbox' } }, newTab: true });
    const back = deserialize(serialize(s))!;
    expect(back.tabs.map((t) => t.entries.map((p) => p.kind))).toEqual([['sites', 'editor'], ['workflow']]);
    expect(back.active).toBe(s.active);
    expect(back.tabs[0].index).toBe(1);
    expect(deserialize(null)).toBeNull();
    expect(deserialize('{"v":0}')).toBeNull();
    expect(deserialize('not json')).toBeNull();
  });

  it('boots on the saved tabs and shows the opened URL without duplicating it', () => {
    const saved = run(initialState(sites('/content')), { type: 'open', place: editor('/content/wknd/us/en') });
    // a reload of the active tab's URL: nothing changes
    expect(boot(saved, editor('/content/wknd/us/en'))).toBe(saved);
    // a URL another tab shows: that tab becomes active
    const two = reducer(saved, { type: 'open', place: sites('/content/dam'), newTab: true });
    const first = two.tabs[0].id;
    expect(boot(two, editor('/content/wknd/us/en')).active).toBe(first);
    // a URL nobody shows: a new tab at the end
    const booted = boot(two, { kind: 'launches' });
    expect(booted.tabs).toHaveLength(3);
    expect(placeOf(tabOf(booted))).toEqual({ kind: 'launches' });
    expect(booted.tabs[2].id).toBe(booted.active);
    // nothing saved: one tab at the URL
    expect(boot(null, { kind: 'launches' }).tabs).toHaveLength(1);
  });

  it('closes the tabs to the right and duplicates a tab with its history', () => {
    let s = initialState({ kind: 'sites', path: '/content/a' });
    s = reducer(s, { type: 'open', place: { kind: 'sites', path: '/content/a/b' } });
    s = reducer(s, { type: 'open', place: { kind: 'start' }, newTab: true });
    s = reducer(s, { type: 'open', place: { kind: 'launches' }, newTab: true });
    const [first] = s.tabs;
    s = reducer(s, { type: 'closeRight', tab: first.id });
    expect(s.tabs.map((t) => t.id)).toEqual([first.id]);
    expect(s.active).toBe(first.id);
    expect(s.closed).toHaveLength(2);
    s = reducer(s, { type: 'duplicate', tab: first.id });
    expect(s.tabs).toHaveLength(2);
    expect(s.tabs[1].entries).toEqual(first.entries);
    expect(s.tabs[1].index).toBe(first.index);
    expect(s.tabs[1].id).not.toBe(first.id);
    expect(s.active).toBe(s.tabs[1].id);
  });
});

describe('split view', () => {

  it('splits a tab into two panes with their own histories, and the focused one takes Back and links', () => {
    let s = initialState(sites('/content/a'));
    const id = s.active;
    s = reducer(s, { type: 'split' });
    expect(tabOf(s).second?.entries).toEqual([sites('/content/a')]);
    expect(tabOf(s).focus).toBe('second');
    expect(focusedId(s)).toBe(`${id}#2`);
    // a link in the second pane pushes there; the first pane is untouched
    s = reducer(s, { type: 'open', place: sites('/content/b') });
    expect(tabOf(s).second?.entries.map((p) => (p as { path: string }).path)).toEqual(['/content/a', '/content/b']);
    expect(tabOf(s).entries).toHaveLength(1);
    // a view in the first pane navigates in its own pane
    s = reducer(s, { type: 'open', place: sites('/content/c'), tab: id });
    expect(tabOf(s).entries.map((p) => (p as { path: string }).path)).toEqual(['/content/a', '/content/c']);
    // Back on the focused (second) pane
    s = reducer(s, { type: 'back' });
    expect(placeOf(paneOf(s))).toEqual(sites('/content/a'));
    expect(placeOf(tabOf(s))).toEqual(sites('/content/c'));
    // focus the first pane: Back now acts there
    s = reducer(s, { type: 'focus', tab: id, pane: 'first' });
    expect(focusedId(s)).toBe(id);
    s = reducer(s, { type: 'back' });
    expect(placeOf(tabOf(s))).toEqual(sites('/content/a'));
  });

  it('unsplits keeping either pane, and survives a save', () => {
    let s = initialState(sites('/content/a'));
    s = reducer(s, { type: 'split', place: sites('/content/dam') });
    const saved = deserialize(serialize(s))!;
    expect(tabOf(saved).second?.entries).toEqual([{ kind: 'sites', path: '/content/dam' }]);
    expect(tabOf(saved).focus).toBe('second');
    s = reducer(s, { type: 'unsplit', keep: 'second' });
    expect(tabOf(s).second).toBeUndefined();
    expect(placeOf(tabOf(s))).toEqual(sites('/content/dam'));
    expect(reducer(s, { type: 'unsplit' })).toBe(s);
  });

  it('boots on the pane that already shows the URL', () => {
    let s = initialState(sites('/content/a'));
    s = reducer(s, { type: 'split', place: sites('/content/b') });
    s = reducer(s, { type: 'focus', tab: s.active, pane: 'first' });
    const booted = boot(s, sites('/content/b'));
    expect(booted.tabs).toHaveLength(1);
    expect(tabOf(booted).focus).toBe('second');
  });
});
