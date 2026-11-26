import { ProgressCircle } from '@adobe/react-spectrum';
import Folder from '@spectrum-icons/workflow/Folder';
import Images from '@spectrum-icons/workflow/Images';
import Label from '@spectrum-icons/workflow/Label';
import WebPage from '@spectrum-icons/workflow/WebPage';
import { useEffect, useState } from 'react';
import type { TagItem, TagReference, TagsClient } from './api';
import { Link } from '../shell/ShellContext';

/** Namespaces look like folders, tags like tags (as in AEM). */
export function TagIcon({ item, size = 'S' }: { item: TagItem; size?: 'S' | 'M' | 'L' | 'XL' }) {
  return item.namespace || item.root ? <Folder size={size} /> : <Label size={size} />;
}

/** Loads the content that uses a tag (or one of its sub-tags). */
export function useTagReferences(client: TagsClient, paths: string[]) {
  const key = paths.join('|');
  const [state, setState] = useState<{ key: string; refs: TagReference[] | null; error: string | null }>({ key: '', refs: null, error: null });
  useEffect(() => {
    let live = true;
    setState({ key, refs: null, error: null });
    Promise.all(paths.map((p) => client.references(p)))
      .then((all) => {
        if (!live) return;
        const seen = new Set<string>();
        const refs = all.flat().filter((r) => !seen.has(r.path) && seen.add(r.path));
        setState({ key, refs, error: null });
      })
      .catch((e: unknown) => live && setState({ key, refs: null, error: e instanceof Error ? e.message : String(e) }));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, key]);
  return state.key === key ? state : { key, refs: null, error: null };
}

const KIND_LABEL: Record<TagReference['kind'], string> = { page: 'Page', asset: 'Asset', content: 'Content' };

function referenceHref(r: TagReference): string {
  if (r.kind === 'page') return `/sites.html${r.owner.slice(0, r.owner.lastIndexOf('/'))}`;
  if (r.kind === 'asset') return `/assetdetails.html${r.owner}`;
  return `/bin/browser.html${r.path}`;
}

export function ReferenceList({ refs, error, empty = 'Not used by any page or file.' }: { refs: TagReference[] | null; error?: string | null; empty?: string }) {
  if (error) return <p className="studio-refs__empty">{error}</p>;
  if (!refs) return <ProgressCircle size="S" isIndeterminate aria-label="Looking for references" />;
  if (!refs.length) return <p className="studio-refs__empty">{empty}</p>;
  return (
    <ul className="studio-refs__list studio-tagrefs">
      {refs.map((r) => (
        <li key={r.path}>
          <Link to={referenceHref(r)} title={r.path}>
            {r.kind === 'asset' ? <Images size="XS" /> : <WebPage size="XS" />}
            <span className="studio-tagrefs__title">{r.title}</span>
            <span className="studio-tagrefs__kind">{KIND_LABEL[r.kind]}</span>
          </Link>
          <span className="studio-tagrefs__path">{r.owner}</span>
        </li>
      ))}
    </ul>
  );
}

export function TagPreview({ client, item }: { client: TagsClient; item: TagItem }) {
  const { refs, error } = useTagReferences(client, [item.path]);
  const rows: Array<[string, string | undefined]> = [
    ['Title', item.title],
    ['Name', item.name],
    ['Tag ID', item.tagID],
    ['Description', item.description || undefined],
    ['Modified', item.lastModified ? new Date(item.lastModified).toLocaleDateString(undefined, { dateStyle: 'medium' }) : undefined],
    ['Modified By', item.lastModifiedBy],
  ];
  return (
    <aside className="studio-preview" aria-label={`Details of ${item.title}`}>
      <div className="studio-preview__thumb studio-tags__bigicon">
        <TagIcon item={item} size="XL" />
      </div>
      <dl className="studio-preview__meta">
        {rows
          .filter(([, v]) => v)
          .map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
      </dl>
      <section className="studio-preview__refs" aria-label="References">
        <h3>
          References
          {refs ? ` (${refs.length})` : ''}
        </h3>
        <ReferenceList refs={refs} error={error} />
      </section>
    </aside>
  );
}
