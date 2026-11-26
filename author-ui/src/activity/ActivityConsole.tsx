import { ActionButton, Button, ProgressCircle, SearchField, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useState } from 'react';
import { relativeTime } from '../editor/shell/TopBar';
import { Segmented } from '../shell/Segmented';
import { Link, useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState } from '../shell/FeedbackState';
import '../sites/sites.scss';
import './activity.scss';

/**
 * The Activity log (Home › System › Users › Activity): who created, changed, removed, published or
 * unpublished which page, block, form, record or media file, and when — newest first, by kind of change,
 * by person, by where. For administrators; what AEM calls the audit log.
 */
export type ActivityType = 'created' | 'modified' | 'deleted' | 'published' | 'unpublished';

export interface ActivityEntry {
  id: string;
  at: number;
  user: string;
  type: ActivityType;
  path: string;
  kind: 'page' | 'block' | 'form' | 'media';
  title: string;
  exists: boolean;
}

interface Answer {
  entries: ActivityEntry[];
  more: boolean;
  keepDays: number;
}

export const ACTIVITY_URL = '/studio/activity.html';
const VERB: Record<ActivityType, string> = { created: 'created', modified: 'changed', deleted: 'removed', published: 'published', unpublished: 'unpublished' };
type Filter = ActivityType | 'all';

/** Where a line's content opens: the editor for pages, blocks and forms, the details for media. */
export function openUrl(e: Pick<ActivityEntry, 'kind' | 'path'>): string {
  return e.kind === 'media' ? `/assetdetails.html${e.path}` : `/editor.html${e.path}.html`;
}

export function ActivityConsole({ fetcher = fetch }: { fetcher?: typeof fetch }) {
  useScreenTitle('Activity', 'Activity | Sensible Studio');
  const [filter, setFilter] = useState<Filter>('all');
  const [user, setUser] = useState('');
  const [path, setPath] = useState('');
  const [data, setData] = useState<Answer | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const ask = useCallback(
    (before?: number) => {
      const q = new URLSearchParams({ limit: '100' });
      if (filter !== 'all') q.set('type', filter);
      if (user.trim()) q.set('user', user.trim());
      if (path.trim()) q.set('path', path.trim());
      if (before) q.set('before', String(before));
      return fetcher(`/bin/studio/activity.json?${q.toString()}`, { credentials: 'same-origin' }).then(async (r) => {
        const body = (await r.json().catch(() => ({}))) as Answer & { error?: string };
        if (!r.ok) throw new Error(body.error ?? `HTTP ${r.status}`);
        return body;
      });
    },
    [fetcher, filter, user, path],
  );

  const load = useCallback(() => {
    setLoading(true);
    ask().then(
      (d) => {
        setData(d);
        setError(null);
        setLoading(false);
      },
      (e: unknown) => {
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      },
    );
  }, [ask]);

  // typing in a filter asks again a moment after the last key
  useEffect(() => {
    const t = window.setTimeout(load, 250);
    return () => window.clearTimeout(t);
  }, [load]);

  const more = () => {
    if (!data || data.entries.length === 0) return;
    setLoading(true);
    ask(data.entries[data.entries.length - 1].at).then(
      (d) => {
        setData({ ...d, entries: [...data.entries, ...d.entries] });
        setLoading(false);
      },
      (e: unknown) => {
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      },
    );
  };

  return (
    <div className="studio-sites studio-activity">
      <ToolBar
        url={ACTIVITY_URL}
        closable={false}
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Refresh</Tooltip>
          </TooltipTrigger>
        }
      >
        <Segmented<Filter>
          label="Kind of change"
          items={[
            { key: 'all', label: 'All' },
            { key: 'created', label: 'Created' },
            { key: 'modified', label: 'Changed' },
            { key: 'published', label: 'Published' },
            { key: 'unpublished', label: 'Unpublished' },
            { key: 'deleted', label: 'Removed' },
          ]}
          value={filter}
          onChange={setFilter}
        />
      </ToolBar>
      <main className="studio-activity__main">
        <div className="studio-activity__filters">
          <SearchField aria-label="By person" placeholder="Person (user id)" width="size-3000" value={user} onChange={setUser} onClear={() => setUser('')} />
          <SearchField aria-label="By place" placeholder="At or below a path, e.g. /content/wknd" width="size-5000" value={path} onChange={setPath} onClear={() => setPath('')} />
          {loading && <ProgressCircle size="S" isIndeterminate aria-label="Loading" />}
          {data && <span className="studio-activity__muted">Kept for {data.keepDays} days</span>}
        </div>
        {error && <ErrorState title="Could not load activity">{error}</ErrorState>}
        {!error && data && data.entries.length === 0 && <EmptyState title="Nothing recorded">{filter !== 'all' || user || path ? 'No activity matches this filter.' : 'What people do to content from now on will appear here.'}</EmptyState>}
        {!error && data && data.entries.length > 0 && (
          <table className="studio-activity__table studio-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Who</th>
                <th>What</th>
                <th>Where</th>
              </tr>
            </thead>
            <tbody>
              {data.entries.map((e) => (
                <tr key={e.id}>
                  <td title={new Date(e.at).toLocaleString()}>{relativeTime(new Date(e.at))}</td>
                  <td>
                    <button type="button" className="studio-activity__who" title={`Only ${e.user}`} onClick={() => setUser(e.user)}>
                      {e.user}
                    </button>
                  </td>
                  <td>
                    <span className={`studio-activity__type studio-activity__type--${e.type}`}>{VERB[e.type]}</span> {e.kind === 'media' ? 'a media item' : e.kind === 'block' ? 'an experience fragment' : `a ${e.kind}`}
                  </td>
                  <td>
                    {e.exists ? <Link to={openUrl(e)}>{e.title}</Link> : <span>{e.title}</span>}
                    <div className="studio-activity__muted">
                      <code>{e.path}</code>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data?.more && (
          <p>
            <Button variant="secondary" onPress={more} isDisabled={loading}>
              Older
            </Button>
          </p>
        )}
      </main>
    </div>
  );
}
