import { ProgressCircle } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Folder from '@spectrum-icons/workflow/Folder';
import WebPage from '@spectrum-icons/workflow/WebPage';
import { useCallback, useEffect, useState, type ReactElement } from 'react';
import type { StudioClient } from '../../editor/api/StudioClient';
import { ancestorsOf } from './api';

const ROOT = '/content';

/** What a tree shows of a node; pages and folders of the content tree, folders of Media. */
export interface TreeNode {
  path: string;
  title: string;
  type?: string;
  hasChildren?: boolean;
}

interface Props {
  client: StudioClient;
  /** where the tree starts (default `/content`) */
  root?: string;
  /** the children of a path, when they are not the pages below it (Media's folders) */
  loader?(path: string): Promise<TreeNode[]>;
  /** the tree's accessible name */
  label?: string;
  /** the console's current location: expanded and highlighted */
  path: string;
  onNavigate(path: string, node?: TreeNode): void;
  /** bumped by the console after a change: the tree reloads */
  version?: number;
}

/** The Content Tree rail: pages and folders below /content, loaded level by level as they are expanded. */
export function ContentTree({ client, path, onNavigate, version = 0, root = ROOT, loader, label = 'Content tree' }: Props) {
  const [levels, setLevels] = useState<Map<string, TreeNode[]>>(new Map());
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(ancestorsOf(path, root)));
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    (p: string) =>
      (loader ? loader(p) : client.siteChildren(p).then((l): TreeNode[] => l.children)).then(
        (children) => setLevels((m) => new Map(m).set(p, children)),
        (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
      ),
    [client, loader],
  );

  useEffect(() => {
    setLevels(new Map());
  }, [version]);

  // navigating elsewhere (columns, breadcrumb) opens the tree down to there
  useEffect(() => {
    setExpanded((s) => {
      const next = new Set(s);
      for (const a of ancestorsOf(path, root)) next.add(a);
      return next;
    });
  }, [path, root]);

  useEffect(() => {
    for (const p of [root, ...expanded]) if (!levels.has(p)) void load(p);
  }, [expanded, levels, load, root]);

  const toggle = (p: string) =>
    setExpanded((s) => {
      const next = new Set(s);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });

  const level = (parent: string, depth: number): ReactElement | null => {
    const items = levels.get(parent);
    if (!items) return depth === 0 ? <ProgressCircle size="S" isIndeterminate aria-label="Loading" /> : null;
    return (
      <ul role={depth === 0 ? 'tree' : 'group'} aria-label={depth === 0 ? label : undefined} className="studio-tree__level">
        {items.map((it) => {
          const open = expanded.has(it.path);
          const current = it.path === path;
          return (
            <li key={it.path} role="treeitem" aria-expanded={it.hasChildren ? open : undefined} aria-selected={current}>
              <div className={`studio-tree__row${current ? ' is-current' : ''}`} style={{ paddingLeft: 8 + depth * 14 }}>
                {it.hasChildren ? (
                  <button type="button" className="studio-tree__twisty" aria-label={`${open ? 'Collapse' : 'Expand'} ${it.title}`} onClick={() => toggle(it.path)}>
                    {open ? <ChevronDown size="XS" /> : <ChevronRight size="XS" />}
                  </button>
                ) : (
                  <span className="studio-tree__twisty" aria-hidden />
                )}
                <button
                  type="button"
                  className="studio-tree__label"
                  title={it.path}
                  onClick={() => {
                    onNavigate(it.path, it);
                    if (it.hasChildren && !open) toggle(it.path);
                  }}
                >
                  <span className="studio-tree__icon">{it.type === 'page' ? <WebPage size="S" /> : <Folder size="S" />}</span>
                  <span className="studio-tree__title">{it.title}</span>
                </button>
              </div>
              {it.hasChildren && open && level(it.path, depth + 1)}
            </li>
          );
        })}
      </ul>
    );
  };

  return (
    <div className="studio-tree">
      {error && <p className="studio-rail__empty">{error}</p>}
      {level(root, 0)}
    </div>
  );
}
