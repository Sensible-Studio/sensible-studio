import { Text, TreeView, TreeViewItem, TreeViewItemContent } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { ContentNode } from '../../granite';
import { instanceLabel } from '../model/labels';
import { ancestors, type Editable, type EditableTree } from '../model/editables';

interface Props {
  tree: EditableTree;
  /** Page content (`jcr:content.infinity.json`), for instance labels such as a teaser's title. */
  content: ContentNode | null;
  contentRoot: string;
  selected: string | null;
  onSelect(path: string): void;
}

/** The page's components as a tree; selecting an item selects (and scrolls to) the component. */
export function ContentTreePanel({ tree, content, contentRoot, selected, onSelect }: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(tree.roots.map((r) => r.path)));

  // keep the selection visible
  useEffect(() => {
    const e = selected ? tree.byPath.get(selected) : undefined;
    if (!e) return;
    setExpanded((prev) => {
      const next = new Set(prev);
      for (const a of ancestors(e)) next.add(a.path);
      return next.size === prev.size ? prev : next;
    });
  }, [selected, tree]);

  useEffect(() => {
    setExpanded((prev) => (prev.size ? prev : new Set(tree.roots.map((r) => r.path))));
  }, [tree]);

  if (!tree.all.length) return <p className="studio-panel__empty">No editable components on this page.</p>;

  const render = (e: Editable) => {
    const detail = instanceLabel(content, contentRoot, e.path);
    return (
      <TreeViewItem key={e.path} id={e.path} textValue={e.title}>
        <TreeViewItemContent>
          <Text>
            <span className="studio-tree__type">{e.title}</span>
            {detail && <span className="studio-tree__detail">: {detail}</span>}
          </Text>
        </TreeViewItemContent>
        {e.children.map(render)}
      </TreeViewItem>
    );
  };

  return (
    <div className="studio-panel studio-panel--tree">
      <TreeView
        aria-label="Content tree"
        selectionMode="single"
        selectionStyle="highlight"
        disallowEmptySelection={false}
        selectedKeys={selected ? [selected] : []}
        onSelectionChange={(keys) => {
          if (keys === 'all') return;
          const first = [...keys][0];
          if (first !== undefined) onSelect(String(first));
        }}
        expandedKeys={expanded}
        onExpandedChange={(keys) => setExpanded(new Set([...keys].map(String)))}
        width="100%"
      >
        {tree.roots.map(render)}
      </TreeView>
    </div>
  );
}
