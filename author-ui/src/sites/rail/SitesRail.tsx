import './rail.scss';
import type { StudioClient, SiteItem } from '../../editor/api/StudioClient';
import type { MsmClient } from '../../msm/MsmClient';
import { LiveCopyStatus } from '../../msm/LiveCopyStatus';
import type { TagsClient } from '../../tags/api';
import { ReferenceList, useReferences } from '../References';
import { useEffect, useState } from 'react';
import { RAILS, ago, type FilterCriteria, type PageLaunch, type RailId, type SearchResults, type SiteRailClient } from './api';
import { ContentTree } from './ContentTree';
import { FilterRail } from './FilterRail';

interface Props {
  rail: RailId;
  client: StudioClient;
  siteRail: SiteRailClient;
  /** the console's current location */
  path: string;
  version: number;
  onNavigate(path: string): void;
  onChanged(): void;
  criteria: FilterCriteria;
  onCriteria(next: FilterCriteria): void;
  onResults(results: SearchResults | null): void;
  tags?: TagsClient;
}

/** The left rail of the Sites console (nothing for Content Only): the Content Tree or the Filter. */
export function SitesRail(props: Props) {
  const { rail, client, path, version, onNavigate } = props;
  if (rail === 'content') return null;
  const label = RAILS.find((r) => r.id === rail)?.label ?? rail;
  return (
    <aside className={`studio-rail studio-rail--${rail}`} aria-label={label}>
      <h2 className="studio-rail__label">{label}</h2>
      <div className="studio-rail__body">
        {rail === 'tree' ? (
          <ContentTree client={client} path={path} onNavigate={onNavigate} version={version} />
        ) : (
          <FilterRail rail={props.siteRail} criteria={props.criteria} onCriteria={props.onCriteria} onResults={props.onResults} tags={props.tags} />
        )}
      </div>
    </aside>
  );
}

/** References, in the inspector: pages that refer to the selection and, for one page, its live copy relations and releases. */
export function ReferencesRail({
  client,
  msm,
  siteRail,
  selection,
  onChanged,
}: {
  client: StudioClient;
  msm?: MsmClient;
  siteRail: SiteRailClient;
  selection: SiteItem[];
  onChanged(): void;
}) {
  const refs = useReferences(
    client,
    selection.map((s) => s.path),
  );
  if (!selection.length) return <p className="studio-rail__empty">Select an item to see its references.</p>;
  const single = selection.length === 1 ? selection[0] : null;
  return (
    <div className="studio-rail__sections">
      <section aria-label="References">
        <h3 className="studio-rail__heading">References{refs.pages ? ` (${refs.pages.length})` : ''}</h3>
        <p className="studio-rail__hint">Pages that link to {single ? single.title : `the ${selection.length} selected items`} or use content from it.</p>
        <ReferenceList pages={refs.pages} error={refs.error} />
      </section>
      {msm && single?.type === 'page' && (
        <section aria-label="Linked copy">
          <LiveCopyStatus key={single.path} msm={msm} path={single.path} onChanged={onChanged} />
        </section>
      )}
      {single?.type === 'page' && <LaunchesOfPage key={single.path} rail={siteRail} path={single.path} />}
    </div>
  );
}

/** The launches that hold a copy of the page: open the copy in the editor. */
function LaunchesOfPage({ rail, path }: { rail: SiteRailClient; path: string }) {
  const [launches, setLaunches] = useState<PageLaunch[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    rail.launchesOf(path).then(
      (l) => live && setLaunches(l),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [rail, path]);
  if (!error && launches?.length === 0) return null;
  return (
    <section aria-label="Releases">
      <h3 className="studio-rail__heading">Releases{launches ? ` (${launches.length})` : ''}</h3>
      {error ? (
        <p className="studio-rail__error">{error}</p>
      ) : (
        <ul className="studio-launches">
          {(launches ?? []).map((l) => (
            <li key={l.path}>
              <a href={`/editor.html${l.pageInLaunch}.html`} target="_blank" rel="noreferrer" className="studio-launches__title">
                {l.title}
              </a>
              <span className="studio-launches__meta">
                {l.promoted ? `promoted ${ago(l.promoted)}` : l.liveDate ? `live ${new Date(l.liveDate).toLocaleDateString()}` : 'no live date'}
                {l.createdBy ? ` · by ${l.createdBy}` : ''}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
