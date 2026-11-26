import type { ContentNode, GraniteNode, JcrValue } from './types';

/** `/libs/granite/ui/...` and `granite/ui/...` are the same component for us. */
export function normalizeResourceType(rt: string | null | undefined): string {
  if (!rt) return '';
  let out = String(rt).trim();
  if (out.startsWith('/libs/')) out = out.slice('/libs/'.length);
  else if (out.startsWith('/apps/')) out = out.slice('/apps/'.length);
  return out;
}

export function resourceTypeOf(node: GraniteNode): string {
  return normalizeResourceType(str(node, 'sling:resourceType'));
}

export function child(node: GraniteNode | undefined, name: string): GraniteNode | undefined {
  return node?.children.find((c) => c.name === name);
}

/** Children of the `items` child — the Granite convention for containers, tabs, selects, ... */
export function items(node: GraniteNode | undefined): GraniteNode[] {
  return child(node, 'items')?.children ?? [];
}

export function prop(node: GraniteNode | undefined, name: string): JcrValue | undefined {
  return node?.properties[name];
}

export function str(node: GraniteNode | undefined, name: string, fallback?: string): string | undefined {
  const v = prop(node, name);
  if (v === undefined || v === null) return fallback;
  if (Array.isArray(v)) return v.length ? String(v[0]) : fallback;
  return String(v);
}

/** Granite boolean properties arrive typed (`{Boolean}true`) or as plain strings. */
export function bool(node: GraniteNode | undefined, name: string, fallback = false): boolean {
  const v = prop(node, name);
  if (v === undefined || v === null) return fallback;
  if (typeof v === 'boolean') return v;
  if (Array.isArray(v)) return v.length ? String(v[0]).toLowerCase() === 'true' : fallback;
  return String(v).trim().toLowerCase() === 'true';
}

export function num(node: GraniteNode | undefined, name: string): number | undefined {
  const v = prop(node, name);
  if (v === undefined || v === null || v === '') return undefined;
  const n = Number(Array.isArray(v) ? v[0] : v);
  return Number.isFinite(n) ? n : undefined;
}

export function strList(node: GraniteNode | undefined, name: string): string[] {
  const v = prop(node, name);
  if (v === undefined || v === null || v === '') return [];
  if (Array.isArray(v)) return v.map(String);
  return [String(v)];
}

/** `granite:class` as a list of class names. */
export function graniteClasses(node: GraniteNode): string[] {
  return strList(node, 'granite:class')
    .flatMap((c) => c.split(/\s+/))
    .filter(Boolean);
}

/** Properties of the `granite:data` child, i.e. the `data-*` attributes Granite would render. */
export function graniteData(node: GraniteNode): Record<string, string> {
  const data = child(node, 'granite:data');
  const out: Record<string, string> = {};
  if (!data) return out;
  for (const [k, v] of Object.entries(data.properties)) {
    if (k.startsWith('jcr:') || v === null || v === undefined) continue;
    out[k] = Array.isArray(v) ? v.join(',') : String(v);
  }
  return out;
}

/** The title Granite shows for tabs, accordion panels, fieldsets, wells. */
export function titleOf(node: GraniteNode): string | undefined {
  return str(node, 'jcr:title') ?? str(node, 'text') ?? graniteData(node)['title'];
}

// --------------------------------------------------------------------------------------------------------
// content (Sling JSON) access by field name

/**
 * Splits a Granite field name into a content path. `./jcr:title` -> ['jcr:title'],
 * `./image/fileReference` -> ['image', 'fileReference']. Names without `./` are treated the same way
 * (Granite posts them relative to the resource too). Suffixes like `@TypeHint` are not field names.
 */
export function namePath(name: string): string[] {
  let n = name.trim();
  if (n.startsWith('./')) n = n.slice(2);
  return n.split('/').filter((s) => s && s !== '.');
}

export function isContentNode(v: unknown): v is ContentNode {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

export function readContent(content: ContentNode | undefined, name: string): JcrValue | ContentNode | undefined {
  let current: JcrValue | ContentNode | undefined = content;
  for (const segment of namePath(name)) {
    if (!isContentNode(current)) return undefined;
    current = current[segment];
  }
  return current;
}

/** Child nodes of a content node, in document order, skipping properties. */
export function contentChildren(node: ContentNode | undefined): Array<[string, ContentNode]> {
  if (!node) return [];
  return Object.entries(node).filter((e): e is [string, ContentNode] => isContentNode(e[1]));
}

export function asString(v: unknown): string {
  if (v === undefined || v === null) return '';
  if (Array.isArray(v)) return v.length ? String(v[0]) : '';
  if (isContentNode(v)) return '';
  return String(v);
}

export function asStringList(v: unknown): string[] {
  if (v === undefined || v === null || v === '') return [];
  if (Array.isArray(v)) return v.map((x) => String(x));
  if (isContentNode(v)) return [];
  return [String(v)];
}

/**
 * A policy node (dialog JSON node format) as the plain object dialogs see as `cqDesign`:
 * properties and child nodes on one object, like Sling JSON.
 */
export function designFromPolicy(node: GraniteNode | null | undefined): ContentNode {
  if (!node) return {};
  const out: ContentNode = { ...node.properties };
  for (const c of node.children) out[c.name] = designFromPolicy(c);
  return out;
}
