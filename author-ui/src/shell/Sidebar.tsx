import { SearchField } from '@adobe/react-spectrum';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { DAM_ROOT, DamHttpClient } from '../assets/api';
import type { StudioClient } from '../editor/api/StudioClient';
import { ContentTree, type TreeNode } from '../sites/rail/ContentTree';
import '../sites/rail/rail.scss';
import '../start/start.scss';
import { Card, PinnedList } from '../start/YourWork';
import { NavIcon } from '../start/icons';
import { searchContent, type Command } from './palette/commands';
import type { Place } from './places';
import { Link } from './ShellContext';
import { VOCAB } from './vocabulary';

/**
 * The sidebar of the document screens: while a page, a block, a template or a record is open, it finds
 * and opens the next one without leaving the editor — a field that finds pages, media and records by
 * text, the pinned screens, the content tree (a click on a page opens it in the editor, in the same tab;
 * a folder only unfolds) and the folders of Media and of Records (those open their consoles). Toggled
 * from the bar (Alt+B), remembered per browser. Its button is in the bar only while an editor is in
 * front: the consoles have their own trees and Home its own rail (with its own hide button).
 */
export const SIDEBAR_KEY = 'studio.shell.sidebar';
const FOLDS_KEY = 'studio.shell.sidebar.folded';

type Section = 'pinned' | 'pages' | 'media' | 'records';

/**
 * The places that take the sidebar: the document screens (the page, block and template editor, a record,
 * a record model) — it is there to open the next page without leaving the editor. The consoles have their
 * own trees and Home its own rail.
 */
export function takesSidebar(place: Place): boolean {
  return place.kind === 'editor' || place.kind === 'cf-editor' || place.kind === 'cf-model-editor';
}

export function readSidebar(): boolean {
  try {
    return window.localStorage.getItem(SIDEBAR_KEY) === 'open';
  } catch {
    return false;
  }
}

export function storeSidebar(open: boolean) {
  try {
    window.localStorage.setItem(SIDEBAR_KEY, open ? 'open' : 'closed');
  } catch {
    /* per-viewer convenience only */
  }
}

function readFolds(): Section[] {
  try {
    const v = JSON.parse(window.localStorage.getItem(FOLDS_KEY) ?? '["pinned","records"]') as unknown;
    return Array.isArray(v) ? (v.filter((x) => x === 'pinned' || x === 'pages' || x === 'media' || x === 'records') as Section[]) : [];
  } catch {
    return [];
  }
}

/** The content path a place is at, for the trees: a Pages folder, the page an editor or a form shows, a Media folder or file. */
export function treePathOf(place: Place): string {
  switch (place.kind) {
    case 'sites':
      return place.path;
    case 'editor':
      return place.path.replace(/\.html$/, '');
    case 'page-properties':
      return place.item;
    case 'assets':
      return 'path' in place.route ? place.route.path : '';
    case 'cf':
      return place.path;
    default:
      return '';
  }
}

interface Props {
  client: StudioClient;
  /** where the focused pane is */
  place: Place;
  onNavigate(place: Place): void;
}

export function Sidebar({ client, place, onNavigate }: Props) {
  const [folded, setFolded] = useState<Section[]>(readFolds);
  const fold = (s: Section) => {
    const next = folded.includes(s) ? folded.filter((x) => x !== s) : [...folded, s];
    setFolded(next);
    try {
      window.localStorage.setItem(FOLDS_KEY, JSON.stringify(next));
    } catch {
      /* per-viewer convenience only */
    }
  };
  const dam = useMemo(() => new DamHttpClient(), []);
  const folders = useCallback((path: string): Promise<TreeNode[]> => dam.folders(path), [dam]);
  const path = treePathOf(place);
  // a file's folder is what the Media tree can show
  const mediaPath = place.kind === 'assets' && place.route.kind !== 'browse' ? path.slice(0, path.lastIndexOf('/')) : path;

  // the field at the top: pages, media and records by text; while it has text the results replace the trees
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<Command[] | null>(null);
  useEffect(() => {
    const text = query.trim();
    if (text.length < 2) {
      setFound(null);
      return;
    }
    const abort = new AbortController();
    const timer = window.setTimeout(() => {
      searchContent(text, abort.signal).then(
        (hits) => !abort.signal.aborted && setFound(hits),
        () => undefined,
      );
    }, 250);
    return () => {
      window.clearTimeout(timer);
      abort.abort();
    };
  }, [query]);
  const searching = query.trim().length >= 2;

  return (
    <aside className="studio-sidebar" aria-label="Sidebar">
      <div className="studio-sidebar__search">
        <SearchField aria-label={`Find ${VOCAB.pages}, ${VOCAB.media} and ${VOCAB.records}`} placeholder="Find content" width="100%" isQuiet value={query} onChange={setQuery} onClear={() => setQuery('')} />
      </div>
      {searching && (
        <div className="studio-sidebar__results" role="region" aria-label="Found">
          {found === null && <p className="studio-work__empty">Looking…</p>}
          {found !== null && found.length === 0 && <p className="studio-work__empty">Nothing found for “{query.trim()}”.</p>}
          {found !== null && found.length > 0 && (
            <ul className="studio-work__list">
              {found.map((c) => (
                <li key={c.id} className="studio-work__row">
                  <Link to={c.group === 'pages' && c.hint ? `/editor.html${c.hint}.html` : (c.url ?? '/')} className="studio-work__link" title={c.hint}>
                    <span className="studio-work__icon" aria-hidden>
                      <NavIcon name={c.icon ?? 'sites'} size="S" />
                    </span>
                    <span className="studio-work__texts">
                      <span className="studio-work__title">{c.title}</span>
                      <span className="studio-work__hint">{c.hint}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {!searching && (
        <>
      <Card title="Pinned" open={!folded.includes('pinned')} onToggle={() => fold('pinned')}>
        <PinnedList />
      </Card>
      <Card title={VOCAB.pages} open={!folded.includes('pages')} onToggle={() => fold('pages')}>
        <ContentTree client={client} path={path.startsWith(DAM_ROOT) ? '' : path} onNavigate={(p, node) => node?.type === 'page' && onNavigate({ kind: 'editor', path: p })} />
      </Card>
      <Card title={VOCAB.media} open={!folded.includes('media')} onToggle={() => fold('media')}>
        <ContentTree client={client} root={DAM_ROOT} loader={folders} label={`${VOCAB.media} folders`} path={place.kind === 'assets' && mediaPath.startsWith(DAM_ROOT) ? mediaPath : ''} onNavigate={(p) => onNavigate({ kind: 'assets', route: { kind: 'browse', path: p } })} />
      </Card>
      <Card title={VOCAB.records} open={!folded.includes('records')} onToggle={() => fold('records')}>
        <ContentTree client={client} root={DAM_ROOT} loader={folders} label={`${VOCAB.records} folders`} path={place.kind === 'cf' ? path : ''} onNavigate={(p) => onNavigate({ kind: 'cf', path: p })} />
      </Card>
        </>
      )}
    </aside>
  );
}
