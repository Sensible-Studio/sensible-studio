import { Checkbox, ProgressCircle } from '@adobe/react-spectrum';
import FileCode from '@spectrum-icons/workflow/FileCode';
import WebPage from '@spectrum-icons/workflow/WebPage';
import { useEffect, useState } from 'react';
import type { DamClient, DamReference } from './api';

/** One page (or other resource, e.g. under /conf) that references the items. */
export interface Referrer {
  /** page path, or the resource path for references outside pages */
  key: string;
  title: string;
  isPage: boolean;
  /** resources holding the references, what `move` adjusts */
  resources: string[];
  properties: string[];
}

/** Properties AEM maintains itself (folder thumbnails), not references an author made. */
const IGNORED = new Set(['dam:folderThumbnailPaths']);

/** Groups references by page; references from inside the referenced paths themselves do not count. */
export function referrers(refs: DamReference[], paths: string[]): Referrer[] {
  const map = new Map<string, Referrer>();
  for (const r of refs) {
    if (IGNORED.has(r.property) || paths.some((p) => r.path === p || r.path.startsWith(`${p}/`))) continue;
    const key = r.page ?? r.path;
    const entry = map.get(key) ?? { key, title: r.pageTitle ?? key.slice(key.lastIndexOf('/') + 1), isPage: !!r.page, resources: [], properties: [] };
    if (!entry.resources.includes(r.path)) entry.resources.push(r.path);
    if (!entry.properties.includes(r.property)) entry.properties.push(r.property);
    map.set(key, entry);
  }
  return [...map.values()].sort((a, b) => Number(b.isPage) - Number(a.isPage) || a.key.localeCompare(b.key));
}

/** References to `paths`, loaded once per set of paths. `null` while loading. */
export function useReferrers(client: DamClient, paths: string[]): { referrers: Referrer[] | null; error: string | null } {
  const key = paths.join('|');
  const [state, setState] = useState<{ key: string; referrers: Referrer[] | null; error: string | null }>({ key: '', referrers: null, error: null });
  useEffect(() => {
    let live = true;
    Promise.all(paths.map((p) => client.references(p))).then(
      (all) => live && setState({ key, referrers: referrers(all.flat(), paths), error: null }),
      (e: unknown) => live && setState({ key, referrers: [], error: e instanceof Error ? e.message : String(e) }),
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, key]);
  return state.key === key ? state : { referrers: null, error: null };
}

/** Where a referrer opens: the page editor for pages, the item itself otherwise. */
export function referrerUrl(r: Referrer): string {
  return r.isPage ? `/editor.html${encodeURI(r.key)}.html` : `/editor.html${encodeURI(r.key)}`;
}

/** List of referrers; with `selected` each gets a checkbox (pages to adjust on move). */
export function ReferenceList({
  referrers: list,
  error,
  selected,
  onSelected,
  empty = 'Not referenced by any page.',
  max = 50,
}: {
  referrers: Referrer[] | null;
  error?: string | null;
  selected?: ReadonlySet<string>;
  onSelected?(next: Set<string>): void;
  empty?: string;
  max?: number;
}) {
  if (list === null) return <ProgressCircle size="S" isIndeterminate aria-label="Loading references" />;
  if (error) return <p className="studio-assets__dialog-error">References could not be loaded: {error}</p>;
  if (list.length === 0) return <p className="studio-assets__refs-empty">{empty}</p>;
  const shown = list.slice(0, max);
  return (
    <ul className="studio-assets__refs" aria-label="References">
      {shown.map((r) => (
        <li key={r.key}>
          {selected && onSelected && (
            <Checkbox
              aria-label={`Adjust ${r.title}`}
              isSelected={selected.has(r.key)}
              onChange={(on) => {
                const next = new Set(selected);
                if (on) next.add(r.key);
                else next.delete(r.key);
                onSelected(next);
              }}
            />
          )}
          {r.isPage ? <WebPage size="S" /> : <FileCode size="S" />}
          <span className="studio-assets__refs-text">
            <a href={referrerUrl(r)} target="_blank" rel="noreferrer">
              {r.title}
            </a>
            <small>
              {r.key} · {r.properties.join(', ')}
            </small>
          </span>
        </li>
      ))}
      {list.length > shown.length && <li className="studio-assets__refs-more">and {list.length - shown.length} more</li>}
    </ul>
  );
}
