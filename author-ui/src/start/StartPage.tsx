import { ProgressCircle, SearchField, Text } from '@adobe/react-spectrum';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { PageListItem, StudioClient } from '../editor/api/StudioClient';
import { relativeTime } from '../editor/shell/TopBar';
import { ProductBar } from '../shell/ProductBar';
import { Link, useTab } from '../shell/ShellContext';
import { NavIcon } from './icons';
import { NAVIGATION, NAV_SECTIONS, TOOLS, type NavTile } from './navigation';
import { useTools } from './audience';
import { VOCAB } from '../shell/vocabulary';
import { WorkRail } from './YourWork';
import { ScreenFrame } from '../shell/ScreenFrame';

interface Props {
  client: StudioClient;
}

/** The tiles, a group's page list, or every tool (`category` is the one to scroll to). */
type View = { tab: 'navigation'; group: string | null } | { tab: 'tools'; category: string | null };

/** `#tools`, `#tools/deployment`, `#sites`: the sub-view a hash or a Start place names. */
function viewOf(spec: string): View {
  const [first, second] = spec.split('/');
  if (first === 'tools') return { tab: 'tools', category: TOOLS.some((c) => c.id === second) ? second : null };
  return { tab: 'navigation', group: NAVIGATION.some((t) => t.id === first && t.target.kind === 'pages') ? first : null };
}

const viewFromHash = () => viewOf(window.location.hash.slice(1));

function hashOf(v: View): string {
  return v.tab === 'tools' ? `#tools${v.category ? `/${v.category}` : ''}` : v.group ? `#${v.group}` : '';
}

/**
 * The Start screen (Home): a rail of Recent and Pinned screens on the left (start/YourWork), and three
 * rows of tiles: Content (Pages, Blocks, Records, Media, Forms), Structure (Templates, Record models, Tags) and System — the tool groups, each a tile into the System page
 * scrolled to its group. Group tiles list their pages; a page opens in the editor.
 */
export function StartPage({ client }: Props) {
  // in the shell the sub-view is part of the tab's place (so Back works across it); on its own, the hash
  const tab = useTab();
  const placeView = tab?.place.kind === 'start' ? tab.place.view : undefined;
  const [ownView, setOwnView] = useState<View>(viewFromHash);
  const view = tab ? viewOf(placeView ?? '') : ownView;
  const [user, setUser] = useState<string | null>(null);

  useEffect(() => {
    if (tab) return;
    const onHash = () => setOwnView(viewFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, [tab]);

  useEffect(() => {
    if (!tab) document.title = `${VOCAB.product} ${VOCAB.home}`;
    client.currentUser().then(setUser, () => setUser(null));
  }, [client, tab]);

  const go = (v: View) => {
    if (tab) {
      const hash = hashOf(v).slice(1);
      tab.setPlace(hash ? { kind: 'start', view: hash } : { kind: 'start' });
      return;
    }
    window.history.pushState(null, '', `${window.location.pathname}${hashOf(v)}`);
    setOwnView(v);
  };

  const group = view.tab === 'navigation' && view.group ? NAVIGATION.find((t) => t.id === view.group) ?? null : null;
  const sub = view.tab === 'tools' ? VOCAB.system : group?.title;

  return (
    <div className="studio-start">
      {!tab && <ProductBar user={user} />}
      <ScreenFrame
        identity={
          <h1>
            {sub ? <><button type="button" className="studio-start__crumb" onClick={() => go({ tab: 'navigation', group: null })}>{VOCAB.home}</button><ChevronRight size="XS" UNSAFE_className="studio-start__crumb-sep" />{sub}</> : VOCAB.home}
          </h1>
        }
      />
      <div className="studio-start__body">
        <WorkRail />
        <main className="studio-start__main">
          {view.tab === 'tools' ? (
            <ToolsView category={view.category} onCategory={(c) => go({ tab: 'tools', category: c })} />
          ) : group && group.target.kind === 'pages' ? (
            <PageList client={client} tile={group} target={group.target} />
          ) : (
            <Tiles onGroup={(id) => go({ tab: 'navigation', group: id })} onTools={(category) => go({ tab: 'tools', category: category ?? null })} />
          )}
        </main>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------------------------------ navigation

function Tiles({ onGroup, onTools }: { onGroup(id: string): void; onTools(category?: string): void }) {
  const tools = useTools();
  return (
    <div className="studio-tilesections">
      <div className="studio-tilesections__inner">
        {NAV_SECTIONS.map((section) => (
          <section key={section.id} className="studio-tilesection" aria-label={section.title}>
            <h2 className="studio-tilesection__heading">{section.title}</h2>
            <ul className="studio-tiles" aria-label={section.title}>
              {NAVIGATION.filter((t) => t.section === section.id).map((t) => (
                <li key={t.id}>
                  <TileLink tile={t} onGroup={onGroup} />
                </li>
              ))}
            </ul>
          </section>
        ))}
        <section className="studio-tilesection" aria-label={VOCAB.system}>
          <h2 className="studio-tilesection__heading">{VOCAB.system}</h2>
          <ul className="studio-tiles" aria-label={VOCAB.system}>
            {tools.map((c) => (
              <li key={c.id}>
                <button type="button" className="studio-tile studio-tile--group" onClick={() => onTools(c.id)}>
                  <span className="studio-tile__icon">
                    <NavIcon name={c.icon} badge={c.badge} size="XL" />
                  </span>
                  <span className="studio-tile__title">{c.title}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}

function TileLink({ tile, onGroup }: { tile: NavTile; onGroup(id: string): void }) {
  const body = (
    <>
      <span className="studio-tile__icon">
        <NavIcon name={tile.icon} size="XL" />
        {tile.target.kind !== 'href' && tile.target.kind !== 'soon' && <ChevronRight size="S" UNSAFE_className="studio-tile__more" />}
      </span>
      <span className="studio-tile__title">{tile.title}</span>
      {tile.target.kind === 'soon' && <span className="studio-tile__soon">Coming soon</span>}
    </>
  );
  if (tile.target.kind === 'href') {
    return (
      <Link className="studio-tile" to={tile.target.url}>
        {body}
      </Link>
    );
  }
  if (tile.target.kind === 'pages' || tile.target.kind === 'tools') {
    return (
      <button type="button" className="studio-tile" onClick={() => onGroup(tile.id)}>
        {body}
      </button>
    );
  }
  return (
    <span className="studio-tile is-disabled" aria-disabled="true">
      {body}
    </span>
  );
}

function PageList({
  client,
  tile,
  target,
}: {
  client: StudioClient;
  tile: NavTile;
  target: { root: string; depth: number; xf?: boolean };
}) {
  const [pages, setPages] = useState<PageListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    setPages(null);
    setError(null);
    client.listPages(target.root, target.depth, target.xf).then(setPages, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, target.root, target.depth, target.xf]);

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return (pages ?? []).filter((p) => !q || p.title.toLowerCase().includes(q) || p.path.toLowerCase().includes(q));
  }, [pages, filter]);

  return (
    <section className="studio-pagelist" aria-label={tile.title}>
      <div className="studio-pagelist__tools">
        <SearchField aria-label="Filter pages" placeholder="Filter" value={filter} onChange={setFilter} width="size-3600" />
        <Text UNSAFE_className="studio-pagelist__count">{pages ? `${shown.length} of ${pages.length}` : ''}</Text>
      </div>
      {error && <p className="studio-pagelist__empty">{error}</p>}
      {!pages && !error && <ProgressCircle isIndeterminate aria-label="Loading pages" size="M" />}
      {pages && shown.length === 0 && <p className="studio-pagelist__empty">No pages.</p>}
      {pages && shown.length > 0 && (
        <table className="studio-pagelist__table studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Path</th>
              <th>Modified</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((p) => (
              <tr key={p.path}>
                <td style={{ paddingLeft: filter ? undefined : `${12 + (p.level - 1) * 20}px` }}>
                  {p.editable === false ? (
                    <span className="studio-pagelist__title is-group">
                      <NavIcon name={tile.icon} size="S" />
                      {p.title}
                    </span>
                  ) : (
                    <Link to={{ kind: 'editor', path: p.path }} className="studio-pagelist__title">
                      <NavIcon name={tile.icon} size="S" />
                      {p.title}
                    </Link>
                  )}
                </td>
                <td className="studio-pagelist__path">{p.path}</td>
                <td className="studio-pagelist__date">{p.lastModified ? relativeTime(new Date(p.lastModified)) : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ------------------------------------------------------------------------------------------ tools

/**
 * System (Tools), like AEM's: the categories in a rail on the left; on the right the same categories as
 * an accordion, all open until the author folds one. The rail is an anchor: it scrolls to the category
 * and unfolds it if it was folded. The category is part of the place (`#tools/<category>`).
 */
function ToolsView({ category, onCategory }: { category: string | null; onCategory(id: string): void }) {
  const tools = useTools();
  const [folded, setFolded] = useState<Set<string>>(() => new Set());
  const [current, setCurrent] = useState<string>(category ?? TOOLS[0].id);
  const body = useRef<HTMLDivElement>(null);

  /** Show a category: unfold it if folded and scroll to it. */
  const jump = (id: string, smooth = true) => {
    setFolded((f) => {
      if (!f.has(id)) return f;
      const next = new Set(f);
      next.delete(id);
      return next;
    });
    setCurrent(id);
    // after the unfold has rendered
    window.requestAnimationFrame(() => document.getElementById(`studio-tools-${id}`)?.scrollIntoView({ block: 'start', behavior: smooth ? 'smooth' : 'auto' }));
  };

  // the place names a category (a link, Back): show it
  useEffect(() => {
    if (category) jump(category, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [category]);

  // the rail follows the scroll: the section at the top is the current one
  const onScroll = () => {
    const host = body.current;
    if (!host) return;
    const top = host.getBoundingClientRect().top + 40;
    let found = tools[0].id;
    for (const c of tools) {
      const el = document.getElementById(`studio-tools-${c.id}`);
      if (el && el.getBoundingClientRect().top <= top) found = c.id;
    }
    setCurrent(found);
  };

  const toggle = (id: string) =>
    setFolded((f) => {
      const next = new Set(f);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <div className="studio-tools">
      <nav className="studio-tools__rail" aria-label={VOCAB.system}>
        <h2 className="studio-tools__railtitle">{VOCAB.system}</h2>
        <ul>
          {tools.map((c) => (
            <li key={c.id}>
              <button
                type="button"
                className={c.id === current ? 'is-current' : undefined}
                aria-current={c.id === current ? 'true' : undefined}
                onClick={() => {
                  jump(c.id);
                  onCategory(c.id);
                }}
              >
                {c.title}
              </button>
            </li>
          ))}
        </ul>
      </nav>
      <div ref={body} className="studio-tools__body" onScroll={onScroll}>
        <div className="studio-tools__accordion">
          {tools.map((c) => {
            const isOpen = !folded.has(c.id);
            return (
              <section key={c.id} id={`studio-tools-${c.id}`} className={`studio-tools__section${isOpen ? ' is-open' : ''}`} aria-label={c.title}>
                <h2 className="studio-tools__heading">
                  <button type="button" aria-expanded={isOpen} onClick={() => toggle(c.id)}>
                    <ChevronRight size="XS" UNSAFE_className="studio-tools__chevron" />
                    <span>{c.title}</span>
                    <span className="studio-tools__count">{c.tools.length}</span>
                  </button>
                </h2>
                {isOpen && (
                  <ul className="studio-tools__cards">
                    {c.tools.map((t) => (
                      <li key={t.url}>
                        <Link className="studio-toolcard" to={t.url}>
                          <span className="studio-toolcard__icon">
                            <NavIcon name={t.icon} badge={t.badge} size="L" />
                          </span>
                          <span className="studio-toolcard__title">{t.title}</span>
                          <span className="studio-toolcard__desc">{t.description}</span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            );
          })}
          <div className="studio-tools__tail" aria-hidden />
        </div>
      </div>
    </div>
  );
}
