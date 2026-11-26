import type { ContentNode } from '../../granite';

/** A short text that tells instances apart: their title, else the start of their text. */
export function instanceLabel(content: ContentNode | null, contentRoot: string, path: string): string | null {
  if (!content || !path.startsWith(`${contentRoot}/`)) return null;
  let node: ContentNode | undefined = content;
  for (const seg of path.slice(contentRoot.length + 1).split('/')) {
    const next: unknown = node?.[seg];
    node = next && typeof next === 'object' && !Array.isArray(next) ? (next as ContentNode) : undefined;
  }
  if (!node) return null;
  for (const key of ['jcr:title', 'title', 'text', 'linkText', 'alt']) {
    const v = node[key];
    if (typeof v === 'string' && v.trim()) {
      const plain = v.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
      if (plain) return plain.length > 40 ? `${plain.slice(0, 40)}…` : plain;
    }
  }
  return null;
}
