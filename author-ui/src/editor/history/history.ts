import type { NodeSnapshot, StudioClient } from '../api/StudioClient';

/** One change the author made, as the node it touched looked before and after. */
export interface HistoryEntry {
  label: string;
  /** The node that covers the change: a component for property edits, a container for structural ones. */
  scope: string;
  before: NodeSnapshot;
  after: NodeSnapshot;
}

export interface HistoryState {
  undo: HistoryEntry[];
  redo: HistoryEntry[];
}

export const EMPTY_HISTORY: HistoryState = { undo: [], redo: [] };

const MAX_ENTRIES = 50;

export function push(state: HistoryState, entry: HistoryEntry): HistoryState {
  // a change that left the node as it was is not worth an undo step
  if (JSON.stringify(entry.before) === JSON.stringify(entry.after)) return state;
  return { undo: [...state.undo.slice(-(MAX_ENTRIES - 1)), entry], redo: [] };
}

/** Applies the latest change in reverse; returns the new state and what was undone. */
export async function undo(client: StudioClient, state: HistoryState): Promise<{ state: HistoryState; entry: HistoryEntry } | null> {
  const entry = state.undo[state.undo.length - 1];
  if (!entry) return null;
  await client.restore(entry.scope, entry.before);
  return {
    state: { undo: state.undo.slice(0, -1), redo: [...state.redo, entry] },
    entry,
  };
}

export async function redo(client: StudioClient, state: HistoryState): Promise<{ state: HistoryState; entry: HistoryEntry } | null> {
  const entry = state.redo[state.redo.length - 1];
  if (!entry) return null;
  await client.restore(entry.scope, entry.after);
  return {
    state: { undo: [...state.undo, entry], redo: state.redo.slice(0, -1) },
    entry,
  };
}

/**
 * The node whose snapshot covers a change to all of `paths`: their deepest common ancestor. Null when
 * that would be the page content node or above: those are not restored wholesale (locks, versions).
 */
export function scopeFor(paths: string[]): string | null {
  if (paths.length === 0) return null;
  const parts = paths.map((p) => p.split('/'));
  const common: string[] = [];
  for (let i = 0; i < parts[0].length; i++) {
    const seg = parts[0][i];
    if (parts.every((p) => p[i] === seg)) common.push(seg);
    else break;
  }
  const scope = common.join('/');
  const content = scope.indexOf('/jcr:content');
  if (content < 0 || scope.length <= content + '/jcr:content'.length) return null;
  return scope;
}

export const parentOf = (path: string) => path.slice(0, path.lastIndexOf('/'));
