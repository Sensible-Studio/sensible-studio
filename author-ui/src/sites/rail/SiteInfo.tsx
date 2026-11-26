import { ProgressCircle } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { ago, type SiteRailClient, type SiteSummary } from './api';

const parent = (p: string) => p.slice(0, p.lastIndexOf('/')) || '/';

/** Whether a path lies in a site (below /content, not in the DAM or the tags). */
export const inSite = (path: string | null | undefined) => !!path && /^\/content\/(?!dam\/|cq:)[^/]+/.test(path);

/**
 * The Site rail: the site of the selected item (or of the current location) — its pages, live copy and locked
 * pages, the latest change, the configuration, the language roots and the templates it may use.
 */
export function SiteInfo({ rail, path, onNavigate }: { rail: SiteRailClient; path: string | null; onNavigate(path: string): void }) {
  const [site, setSite] = useState<SiteSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ok = inSite(path);

  useEffect(() => {
    setSite(null);
    setError(null);
    if (!path || !ok) return;
    rail.site(path).then(setSite, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [rail, path, ok]);

  if (!ok) return <p className="studio-rail__empty">Select an item to see the site controls.</p>;
  if (error) return <p className="studio-rail__empty">{error}</p>;
  if (!site) return <ProgressCircle size="S" isIndeterminate aria-label="Loading" />;

  return (
    <div className="studio-rail__sections">
      <section aria-label="Site">
        <h3 className="studio-rail__title">{site.title}</h3>
        <button type="button" className="studio-rail__link" onClick={() => onNavigate(site.path)}>
          {site.path}
        </button>
        <dl className="studio-rail__facts">
          <dt>Pages</dt>
          <dd>{site.pages}</dd>
          {site.liveCopyPages > 0 && (
            <>
              <dt>Linked copy pages</dt>
              <dd>{site.liveCopyPages}</dd>
            </>
          )}
          {site.lockedPages > 0 && (
            <>
              <dt>Locked pages</dt>
              <dd>{site.lockedPages}</dd>
            </>
          )}
          {site.lastModified && (
            <>
              <dt>Last change</dt>
              <dd>
                <button type="button" className="studio-rail__link" onClick={() => onNavigate(parent(site.lastModified!.path))} title={site.lastModified.path}>
                  {site.lastModified.title}
                </button>{' '}
                <span className="studio-rail__muted">{ago(site.lastModified.time)}</span>
              </dd>
            </>
          )}
          <dt>Configuration</dt>
          <dd>
            {site.conf ? (
              <a className="studio-rail__link" href={`/bin/browser.html${site.conf}`}>
                {site.conf}
              </a>
            ) : (
              <span className="studio-rail__muted">none</span>
            )}
          </dd>
        </dl>
      </section>
      <section aria-label="Languages">
        <h3 className="studio-rail__heading">Languages ({site.languages.length})</h3>
        {site.languages.length === 0 ? (
          <p className="studio-rail__muted">No language roots.</p>
        ) : (
          <ul className="studio-rail__list">
            {site.languages.map((l) => (
              <li key={l.path}>
                <button type="button" className="studio-rail__link" onClick={() => onNavigate(l.path)} title={l.path}>
                  {l.title}
                </button>{' '}
                <span className="studio-rail__muted">
                  {l.language} · {l.path.slice(site.path.length + 1)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-label="Templates">
        <h3 className="studio-rail__heading">Templates ({site.templates.length})</h3>
        <ul className="studio-rail__list">
          {site.templates.map((t) => (
            <li key={t.path}>
              {t.title} {t.status && t.status !== 'enabled' && <span className="studio-rail__muted">({t.status})</span>}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
