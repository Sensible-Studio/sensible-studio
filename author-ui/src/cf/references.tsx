import { Flex, Heading, Link, ProgressCircle, Text } from '@adobe/react-spectrum';
import Document from '@spectrum-icons/workflow/Document';
import Image from '@spectrum-icons/workflow/Image';
import WebPage from '@spectrum-icons/workflow/WebPage';
import ViewGrid from '@spectrum-icons/workflow/ViewGrid';
import { useEffect, useState } from 'react';
import { cfEditorUrl, encodePath, type CfClient, type CfReference, type CfReferences } from './api';
import { RecordIcon } from '../shell/recordIcons';

/** Where a reference opens: pages and experience fragments in the page editor, fragments here, assets in Assets. */
export function referenceUrl(r: CfReference): string {
  switch (r.type) {
    case 'page':
    case 'experience-fragment':
      return `/editor.html${encodePath(r.path)}.html`;
    case 'fragment':
      return cfEditorUrl(r.path);
    case 'asset':
      return `/assetdetails.html${encodePath(r.path)}`;
    default:
      return `/bin/browser.html${encodePath(r.path)}`;
  }
}

const ICONS: Record<string, typeof Document> = {
  page: WebPage,
  'experience-fragment': ViewGrid,
  fragment: RecordIcon,
  asset: Image,
};

const TYPE_LABEL: Record<string, string> = {
  page: 'Page',
  'experience-fragment': 'Experience Fragment',
  fragment: 'Content Fragment',
  asset: 'Asset',
};

/** Where the item lives, e.g. `wknd/us/en/magazine/members-only` for a page: tells apart pages with the same title. */
function location(path: string): string {
  const parent = path.slice(0, path.lastIndexOf('/'));
  return parent.replace(/^\/content\/(dam\/|experience-fragments\/)?/, '');
}

function detail(r: CfReference, used: boolean): string {
  const kind = r.type === 'fragment' && r.model ? `${r.model} content fragment` : TYPE_LABEL[r.type] ?? r.type;
  if (!used && r.elements?.length) return `${kind} · ${r.elements.join(', ')}`;
  return used ? `${kind} · ${location(r.path)}` : kind;
}

/** Tooltip: the full path and, for usages, the component and property that name the fragment. */
function hint(r: CfReference): string {
  const via = (r.via ?? []).map((v) => `${v.node} @ ${v.property}${v.resourceType ? ` (${v.resourceType})` : ''}`);
  return [r.path, ...via].join('\n');
}

function List({ title, items, used, onOpen }: { title: string; items: CfReference[]; used: boolean; onOpen(url: string): void }) {
  return (
    <section className="studio-cf-refs__group" aria-label={title}>
      <Heading level={4} margin={0} UNSAFE_className="studio-cf-refs__title">
        {title} <span className="studio-cf-refs__count">{items.length}</span>
      </Heading>
      {items.length === 0 ? (
        <Text UNSAFE_className="studio-cf-refs__empty">{used ? 'Not used anywhere' : 'No references'}</Text>
      ) : (
        <ul className="studio-cf-refs__list">
          {items.map((r) => {
            const Icon = ICONS[r.type] ?? Document;
            return (
              <li key={`${r.type}:${r.path}`} className="studio-cf-refs__item" title={hint(r)}>
                <Icon size="S" UNSAFE_className="studio-cf-refs__icon" aria-hidden />
                <span className="studio-cf-refs__text">
                  <Link isQuiet onPress={() => onOpen(referenceUrl(r))}>
                    {r.title || r.path}
                  </Link>
                  <span className="studio-cf-refs__detail">{detail(r, used)}</span>
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/**
 * The fragment's references: where it is used (pages, experience fragments, other fragments) and what it uses
 * (assets, fragments). Reloads when `version` changes, e.g. after a save.
 */
export function ReferencesPanel({ client, path, version, onOpen }: { client: CfClient; path: string; version?: string | null; onOpen(url: string): void }) {
  const [refs, setRefs] = useState<CfReferences | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    client
      .references(path)
      .then((r) => !cancelled && setRefs(r))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [client, path, version]);

  return (
    <Flex direction="column" gap="size-150" UNSAFE_className="studio-cf-refs">
      {error ? (
        <Text UNSAFE_className="studio-cf-refs__empty">References are not available: {error}</Text>
      ) : !refs ? (
        <ProgressCircle aria-label="Loading references" isIndeterminate size="S" />
      ) : (
        <>
          <List title="Used in" items={refs.usedIn} used onOpen={onOpen} />
          {refs.truncated && <Text UNSAFE_className="studio-cf-refs__empty">The search stopped early; there may be more.</Text>}
          <List title="Uses" items={refs.references} used={false} onOpen={onOpen} />
        </>
      )}
    </Flex>
  );
}
