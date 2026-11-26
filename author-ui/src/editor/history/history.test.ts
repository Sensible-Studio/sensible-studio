import { describe, expect, it } from 'vitest';
import type { NodeSnapshot, StudioClient } from '../api/StudioClient';
import { EMPTY_HISTORY, push, redo, scopeFor, undo } from './history';

const snap = (text: string | null, next: string | null = null): NodeSnapshot => ({
  content: text === null ? null : { 'jcr:primaryType': 'nt:unstructured', text },
  next,
});

function fakeClient() {
  const restored: Array<{ path: string; snapshot: NodeSnapshot }> = [];
  const client = {
    restore: async (path: string, snapshot: NodeSnapshot) => void restored.push({ path, snapshot }),
  } as unknown as StudioClient;
  return { client, restored };
}

describe('scopeFor', () => {
  it('is the deepest common ancestor below jcr:content', () => {
    const c = '/content/site/en/jcr:content/root/container';
    expect(scopeFor([`${c}/text`])).toBe(`${c}/text`);
    expect(scopeFor([`${c}/a`, `${c}/b`])).toBe(c);
    expect(scopeFor([`${c}/a`, `${c}/inner/b`])).toBe(c);
    expect(scopeFor(['/content/site/en/jcr:content/root/one/a', '/content/site/en/jcr:content/root/two/b'])).toBe(
      '/content/site/en/jcr:content/root',
    );
  });

  it('refuses the page content node and anything above it', () => {
    expect(scopeFor(['/content/site/en/jcr:content'])).toBeNull();
    expect(scopeFor(['/content/site/en/jcr:content/root', '/content/site/fr/jcr:content/root'])).toBeNull();
    expect(scopeFor([])).toBeNull();
  });
});

describe('history', () => {
  const entry = {
    label: 'edit Text',
    scope: '/content/p/jcr:content/root/text',
    before: snap('old', 'sep'),
    after: snap('new', 'sep'),
  };

  it('ignores changes that changed nothing', () => {
    expect(push(EMPTY_HISTORY, { ...entry, after: entry.before })).toBe(EMPTY_HISTORY);
    expect(push(EMPTY_HISTORY, entry).undo).toHaveLength(1);
  });

  it('undo restores the earlier snapshot and redo the later one', async () => {
    const { client, restored } = fakeClient();
    const state = push(EMPTY_HISTORY, entry);
    const u = await undo(client, state);
    expect(restored[0]).toEqual({ path: entry.scope, snapshot: entry.before });
    expect(u!.state.undo).toHaveLength(0);
    expect(u!.state.redo).toHaveLength(1);
    const r = await redo(client, u!.state);
    expect(restored[1]).toEqual({ path: entry.scope, snapshot: entry.after });
    expect(r!.state.undo).toHaveLength(1);
    expect(r!.state.redo).toHaveLength(0);
    expect(await undo(client, EMPTY_HISTORY)).toBeNull();
  });

  it('a new change drops the redo stack', async () => {
    const { client } = fakeClient();
    const u = await undo(client, push(EMPTY_HISTORY, entry));
    const next = push(u!.state, {
      ...entry,
      label: 'other',
      after: snap('newer', 'sep'),
    });
    expect(next.redo).toHaveLength(0);
    expect(next.undo.map((e) => e.label)).toEqual(['other']);
  });
});
