import { ActionButton, Button, Checkbox, DialogContainer, ProgressCircle, Text, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import PublishCheck from '@spectrum-icons/workflow/PublishCheck';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useState } from 'react';
import { relativeTime } from '../editor/shell/TopBar';
import { Segmented } from '../shell/Segmented';
import { Link } from '../shell/ShellContext';
import type { ReplicationClient } from './api';
import { PublishDialog } from './PublishDialog';

/**
 * What the live site does not have yet: the pages modified since they were published, unpublished, or
 * never published (`SitesServlet op=unpublished`), newest change first, by state (Changed · Unpublished · Never published · All), each row with Publish;
 * the ticked rows, or everything the state matches, publish together through the same dialog (their
 * unpublished references included). A panel of the Publishing console, under the agents.
 */
export interface UnpublishedItem {
  path: string;
  name: string;
  title: string;
  type: 'page' | 'folder';
  lastModified?: string;
  lastModifiedBy?: string;
  publication: 'modified' | 'unpublished' | 'never';
  lastPublished?: string;
}

const LABEL: Record<UnpublishedItem['publication'], string> = { modified: 'changed since published', unpublished: 'unpublished', never: 'never published' };
const ROWS = 12;
/** What one request asks for when everything is wanted (the servlet's own cap). */
const ALL = 2000;

type State = 'modified' | 'unpublished' | 'never';
type Filter = State | 'all';
interface Data {
  total: number;
  counts?: Record<State, number>;
  complete: boolean;
  items: UnpublishedItem[];
}

export function Unpublished({ client, tick }: { client: ReplicationClient; tick?: number }) {
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  // what the dialog publishes: one row's page, the ticked ones, or all of the filter
  const [publishing, setPublishing] = useState<{ paths: string[]; title: string } | null>(null);
  const [filter, setFilter] = useState<Filter>('modified');
  const [showAll, setShowAll] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [gathering, setGathering] = useState(false);

  const query = useCallback(
    (limit: number) =>
      fetch(`/bin/studio/sites.json?op=unpublished&path=/content&limit=${limit}${filter === 'all' ? '' : `&state=${filter}`}`, { credentials: 'same-origin' }).then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return (await r.json()) as Data;
      }),
    [filter],
  );

  const load = useCallback(() => {
    query(showAll ? ALL : ROWS).then(
      (d) => {
        setData(d);
        setError(null);
        // what is no longer listed is no longer ticked
        setSelected((sel) => new Set(d.items.filter((i) => sel.has(i.path)).map((i) => i.path)));
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [query, showAll]);

  useEffect(load, [load, tick]);

  const toggle = (path: string, on: boolean) =>
    setSelected((sel) => {
      const next = new Set(sel);
      if (on) next.add(path);
      else next.delete(path);
      return next;
    });
  const shown = data?.items ?? [];
  const allTicked = shown.length > 0 && shown.every((i) => selected.has(i.path));

  /** Everything the filter matches, listed or not: asked again without the row limit. */
  const publishAll = () => {
    setGathering(true);
    query(ALL).then(
      (d) => {
        setGathering(false);
        if (d.items.length) setPublishing({ paths: d.items.map((i) => i.path), title: `${d.items.length} pages` });
      },
      (e: unknown) => {
        setGathering(false);
        setError(e instanceof Error ? e.message : String(e));
      },
    );
  };
  const publishSelected = () => {
    const picked = shown.filter((i) => selected.has(i.path));
    if (picked.length) setPublishing({ paths: picked.map((i) => i.path), title: picked.length === 1 ? picked[0].title : `${picked.length} pages` });
  };
  const counts = data?.counts;
  const all = counts ? counts.modified + counts.unpublished + counts.never : undefined;
  const n = (c: number | undefined) => (c ? ` (${c})` : '');

  return (
    <section className="studio-replication__queue studio-replication__unpublished" aria-label="Unpublished changes">
      <div className="studio-replication__panehead">
        <h3>Unpublished changes</h3>
        <Segmented<Filter>
          label="Which changes"
          items={[
            { key: 'modified', label: `Changed${n(counts?.modified)}` },
            { key: 'unpublished', label: `Unpublished${n(counts?.unpublished)}` },
            { key: 'never', label: `Never published${n(counts?.never)}` },
            { key: 'all', label: `All${n(all)}` },
          ]}
          value={filter}
          onChange={(f) => {
            setFilter(f);
            setShowAll(false);
            setSelected(new Set());
          }}
        />
        <span className="studio-replication__bulk">
          <Button variant="secondary" isDisabled={selected.size === 0} onPress={publishSelected}>
            <PublishCheck />
            <Text>Publish selected{selected.size ? ` (${selected.size})` : ''}</Text>
          </Button>
          <Button variant="accent" isDisabled={!data || data.total === 0 || gathering} onPress={publishAll}>
            <PublishCheck />
            <Text>Publish all{data?.total ? ` (${data.total})` : ''}</Text>
          </Button>
          <TooltipTrigger delay={600}>
            <ActionButton isQuiet aria-label="Refresh unpublished changes" onPress={load}>
              <Refresh size="S" />
            </ActionButton>
            <Tooltip>Refresh</Tooltip>
          </TooltipTrigger>
        </span>
      </div>
      {error && <p className="studio-replication__error">{error}</p>}
      {!error && !data && <ProgressCircle size="S" isIndeterminate aria-label="Looking for unpublished changes" />}
      {!error && data && data.items.length === 0 && <p className="studio-replication__muted">{filter === 'all' ? 'The live site has every change.' : 'Nothing in this state.'}</p>}
      {!error && data && data.items.length > 0 && (
        <table className="studio-table">
          <thead>
            <tr>
              <th className="studio-replication__check">
                <Checkbox aria-label="Select all listed" isSelected={allTicked} isIndeterminate={!allTicked && selected.size > 0} onChange={(on) => setSelected(on ? new Set(shown.map((i) => i.path)) : new Set())} />
              </th>
              <th>Page</th>
              <th>State</th>
              <th>Changed</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data.items.map((i) => (
              <tr key={i.path}>
                <td className="studio-replication__check">
                  <Checkbox aria-label={`Select ${i.title}`} isSelected={selected.has(i.path)} onChange={(on) => toggle(i.path, on)} />
                </td>
                <td>
                  <Link to={`/editor.html${i.path}.html`} title={i.path}>
                    {i.title}
                  </Link>
                  <div className="studio-replication__muted">
                    <code>{i.path}</code>
                  </div>
                </td>
                <td>{LABEL[i.publication]}</td>
                <td title={i.lastModified ? new Date(i.lastModified).toLocaleString() : undefined}>
                  {i.lastModified ? relativeTime(new Date(i.lastModified)) : '—'}
                  {i.lastModifiedBy ? ` by ${i.lastModifiedBy}` : ''}
                </td>
                <td>
                  <TooltipTrigger delay={600}>
                    <ActionButton isQuiet aria-label={`Publish ${i.title}`} onPress={() => setPublishing({ paths: [i.path], title: i.title })}>
                      <PublishCheck size="S" />
                    </ActionButton>
                    <Tooltip>Publish</Tooltip>
                  </TooltipTrigger>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && data.total > data.items.length && (
        <p className="studio-replication__muted">
          <button type="button" className="studio-replication__linkbtn" onClick={() => setShowAll(true)}>
            Show all {data.total}
          </button>
          {!data.complete ? ' (the first 5000 nodes were looked at)' : ''}
        </p>
      )}
      <DialogContainer onDismiss={() => setPublishing(null)}>
        {publishing && (
          <PublishDialog
            client={client}
            paths={publishing.paths}
            title={publishing.title}
            action="activate"
            offerDeep={publishing.paths.length === 1}
            onDone={() => {
              setPublishing(null);
              setSelected(new Set());
              load();
            }}
          />
        )}
      </DialogContainer>
    </section>
  );
}
