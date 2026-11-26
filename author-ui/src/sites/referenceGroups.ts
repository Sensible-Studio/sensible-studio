import type { ReferenceInfo } from '../editor/api/StudioClient';

export interface ReferringPage {
  /** page path, or the resource path for references outside pages (e.g. /conf) */
  page: string;
  title: string;
  /** what the references look like, e.g. `linkURL`, `text` */
  properties: string[];
  count: number;
}

/** Groups references by the page they are on. */
export function byPage(refs: ReferenceInfo[]): ReferringPage[] {
  const map = new Map<string, ReferringPage>();
  for (const r of refs) {
    const key = r.page ?? r.path;
    const entry = map.get(key) ?? { page: key, title: r.pageTitle ?? key.split('/').pop() ?? key, properties: [], count: 0 };
    entry.count++;
    if (!entry.properties.includes(r.property)) entry.properties.push(r.property);
    map.set(key, entry);
  }
  return [...map.values()].sort((a, b) => a.page.localeCompare(b.page));
}
