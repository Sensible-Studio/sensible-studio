import type { ContentNode, RepositoryItem } from '../../granite';
import { contentChildren } from '../../granite/node';

const LEAF_TYPES = new Set(['dam:Asset', 'nt:file', 'nt:resource', 'cq:PageContent']);
const SKIPPED = new Set(['jcr:content', 'rep:policy', 'rep:repoPolicy', 'cq:ChunkedList']);

/** Turns a Sling `.2.json` listing into picker items. */
export function itemsFromListing(path: string, json: ContentNode): RepositoryItem[] {
  const base = path.replace(/\/$/, '');
  return contentChildren(json)
    .filter(([name, node]) => !SKIPPED.has(name) && !name.startsWith('rep:') && !SKIPPED.has(String(node['jcr:primaryType'])))
    .map(([name, node]) => {
      const type = String(node['jcr:primaryType'] ?? '');
      const content = node['jcr:content'] as ContentNode | undefined;
      const title = (content?.['jcr:title'] as string | undefined) ?? (node['jcr:title'] as string | undefined);
      return {
        name,
        path: `${base}/${name}`,
        title,
        type,
        hasChildren: !LEAF_TYPES.has(type),
      };
    })
    .sort((a, b) => Number(b.hasChildren) - Number(a.hasChildren) || (a.title ?? a.name).localeCompare(b.title ?? b.name));
}
