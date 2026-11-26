import { Checkbox, ProgressCircle } from '@adobe/react-spectrum';
import { useEffect, useMemo, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { byPage, type ReferringPage } from './referenceGroups';
import { PageIcon } from '../shell/pageIcons';

export type { ReferringPage };

/** Loads the references to each of `paths` (pages referring to several of them are listed once). */
export function useReferences(client: StudioClient, paths: string[]) {
  const key = paths.join('|');
  const [state, setState] = useState<{ key: string; pages: ReferringPage[] | null; error: string | null }>({ key: '', pages: null, error: null });
  useEffect(() => {
    let cancelled = false;
    setState({ key, pages: null, error: null });
    Promise.all(paths.map((p) => client.references(p)))
      .then((all) => {
        if (cancelled) return;
        // a page inside one of the paths does not count as referring to it
        const outside = all.flat().filter((r) => !paths.some((p) => r.path === p || r.path.startsWith(`${p}/`)));
        setState({ key, pages: byPage(outside), error: null });
      })
      .catch((e: unknown) => !cancelled && setState({ key, pages: null, error: e instanceof Error ? e.message : String(e) }));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, key]);
  return state.key === key ? state : { key, pages: null, error: null };
}

interface ListProps {
  pages: ReferringPage[] | null;
  error?: string | null;
  /** With a selection the list shows checkboxes (which pages to adjust). */
  selected?: Set<string>;
  onSelected?(next: Set<string>): void;
  empty?: string;
}

export function ReferenceList({ pages, error, selected, onSelected, empty = 'No other page refers to it.' }: ListProps) {
  const all = useMemo(() => new Set((pages ?? []).map((p) => p.page)), [pages]);
  if (error) return <p className="studio-refs__empty">{error}</p>;
  if (!pages) return <ProgressCircle size="S" isIndeterminate aria-label="Looking for references" />;
  if (!pages.length) return <p className="studio-refs__empty">{empty}</p>;
  return (
    <div className="studio-refs">
      {selected && onSelected && pages.length > 1 && (
        <Checkbox
          isSelected={selected.size === all.size}
          isIndeterminate={selected.size > 0 && selected.size < all.size}
          onChange={(v) => onSelected(v ? new Set(all) : new Set())}
        >
          All {pages.length} pages
        </Checkbox>
      )}
      <ul className="studio-refs__list">
        {pages.map((p) => (
          <li key={p.page}>
            {selected && onSelected ? (
              <Checkbox
                isSelected={selected.has(p.page)}
                onChange={(v) => {
                  const next = new Set(selected);
                  if (v) next.add(p.page);
                  else next.delete(p.page);
                  onSelected(next);
                }}
                aria-label={`Adjust references in ${p.title}`}
              />
            ) : (
              <PageIcon size="S" UNSAFE_className="studio-refs__icon" />
            )}
            <span className="studio-refs__text">
              <a href={p.page.startsWith('/content/') ? `/editor.html${p.page}.html` : undefined} className="studio-refs__title" target="_blank" rel="noreferrer">
                {p.title}
              </a>
              <span className="studio-refs__path">
                {p.page} · {p.count} × {p.properties.join(', ')}
              </span>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
