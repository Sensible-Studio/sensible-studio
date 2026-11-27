import { ActionButton, Item, Picker, Text, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Edit from '@spectrum-icons/workflow/Edit';
import Refresh from '@spectrum-icons/workflow/Refresh';
import Undo from '@spectrum-icons/workflow/Undo';
import { useCallback, useEffect, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { ProductBar } from '../shell/ProductBar';
import { countedOn, GOAL_TYPES, totals, type Activity, type StatsSource, type TargetingClient } from './api';
import { Link, useNavigate, useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: TargetingClient;
  studio: StudioClient;
  initialRoot?: string;
}

/**
 * The Activities console (`/activities.html[/content/<site>]`), AEM's Personalization → Activities with
 * its reports: every targeted component under a site, its goal, how often each experience was shown
 * and converted, the best one; a link into the page editor's Targeting mode.
 */
export function ActivitiesConsole({ client, studio, initialRoot }: Props) {
  const go = useNavigate();
  const tab = useTab();
  const [roots, setRoots] = useState<string[]>([]);
  const [root, setRoot] = useState(initialRoot ?? '/content');
  const [rows, setRows] = useState<Activity[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [user, setUser] = useState<string | null>(null);

  useEffect(() => {
    studio.currentUser().then(setUser, () => setUser(null));
    fetch('/content.1.json', { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : {}))
      .then((j: Record<string, unknown>) => {
        const names = Object.entries(j)
          .filter(([k, v]) => !k.includes(':') && v && typeof v === 'object' && (v as Record<string, unknown>)['jcr:primaryType'] === 'cq:Page')
          .map(([k]) => `/content/${k}`);
        setRoots(['/content', ...names]);
      })
      .catch(() => setRoots(['/content']));
  }, [studio]);

  const [sources, setSources] = useState<StatsSource[] | undefined>(undefined);
  const load = useCallback(() => {
    setRows(null);
    (client.activityReport ? client.activityReport(root) : client.activities(root).then((activities) => ({ activities, sources: undefined }))).then(
      (r) => {
        setRows(r.activities);
        setSources(r.sources);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client, root]);
  useEffect(load, [load]);
  const tabRoot = tab?.place.kind === 'activities' ? (tab.place.root ?? '/content') : undefined;
  useEffect(() => {
    if (tabRoot !== undefined && tabRoot !== root) setRoot(tabRoot);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabRoot]);
  useEffect(() => {
    if (tab) {
      if (tabRoot !== root) tab.setPlace({ kind: 'activities', root: root === '/content' ? null : root }, { replace: true });
    } else window.history.replaceState(null, '', `/activities.html${root === '/content' ? '' : root}`);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [root]);

  const reset = (a: Activity) =>
    client.resetStats(a.path).then(
      (missed) => {
        load();
        if (missed && missed.length > 0) ToastQueue.negative(`Not reset on ${missed.join(', ')}: the instance did not answer`, { timeout: 8000 });
        else ToastQueue.positive('Report reset', { timeout: 3000 });
      },
      (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
    );

  const counted = countedOn(sources);

  return (
    <div className="studio-sites studio-activities">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/activities.html"
        closable={false}
        end={<>
          <Picker aria-label="Site" selectedKey={root} onSelectionChange={(k) => setRoot(String(k))} isQuiet width="size-2400">
            {roots.map((c) => (
              <Item key={c}>{c === '/content' ? 'All sites' : c.replace('/content/', '')}</Item>
            ))}
          </Picker>
          <ActionButton onPress={load} isQuiet>
            <Refresh />
            <Text>Refresh</Text>
          </ActionButton>
        </>}
      />
      <main className="studio-sites__content">
        {error && <ErrorState title="Could not load activities">{error}</ErrorState>}
        {!rows && !error && <LoadingState label="Loading activities" />}
        {rows && rows.length === 0 && <EmptyState title="No activities">No personalized components exist under {root}. In the page editor, select a component and use Personalize.</EmptyState>}
        {rows && rows.length > 0 && counted && <p className={`studio-targeting__hint studio-activities__counted${counted.warn ? ' studio-targeting__hint--problem' : ''}`}>{counted.text}</p>}
        {rows && rows.length > 0 && (
          <table className="studio-audiences__table studio-activities__table studio-table">
            <thead>
              <tr>
                <th>Activity</th>
                <th>Page</th>
                <th>Goal</th>
                <th>Experiences</th>
                <th>Shown</th>
                <th>Converted</th>
                <th>Rate</th>
                <th>Best</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => {
                const sum = totals(a.stats);
                const best = Object.entries(a.stats ?? {})
                  .filter(([, s]) => s.conversions > 0)
                  .sort((x, y) => y[1].rate - x[1].rate)[0];
                const goal = GOAL_TYPES.find((g) => g.id === a.goal?.type) ?? GOAL_TYPES[0];
                return (
                  <tr key={a.path}>
                    <td>
                      <strong>{a.goal?.activity || a.path.split('/').pop()}</strong>
                      {a.mode === 'abtest' && <span className="studio-targeting__tag">A/B test</span>}
                      <div className="studio-replication__muted">{a.path.replace(`${a.page}/jcr:content/`, '')}</div>
                    </td>
                    <td>
                      <Link to={`/editor.html${a.page}.html`}>{a.pageTitle || a.page.split('/').pop()}</Link>
                      <div className="studio-replication__muted">{a.page}</div>
                    </td>
                    <td>
                      {goal.label}
                      {a.goal?.value && <div className="studio-replication__muted">{a.goal.value}</div>}
                    </td>
                    <td>
                      {a.experiences.map((e) => (
                        <div key={e.name}>
                          {e.title} <span className="studio-replication__muted">{a.stats?.[e.name] ? `${a.stats[e.name].impressions} / ${a.stats[e.name].conversions}` : '–'}</span>
                        </div>
                      ))}
                    </td>
                    <td>{sum.impressions}</td>
                    <td>{sum.conversions}</td>
                    <td>{sum.impressions ? `${sum.rate}%` : '–'}</td>
                    <td>{best ? a.experiences.find((e) => e.name === best[0])?.title ?? best[0] : '–'}</td>
                    <td className="studio-audiences__actions">
                      <TooltipTrigger delay={400}>
                        <ActionButton isQuiet aria-label="Edit in the page editor" onPress={() => go(`/editor.html${a.page}.html?mode=targeting&select=${encodeURIComponent(a.path)}`, { newTab: true })}>
                          <Edit />
                        </ActionButton>
                        <Tooltip>Open in Personalize mode</Tooltip>
                      </TooltipTrigger>
                      <TooltipTrigger delay={400}>
                        <ActionButton isQuiet aria-label="Reset report" isDisabled={sum.impressions === 0} onPress={() => void reset(a)}>
                          <Undo />
                        </ActionButton>
                        <Tooltip>Reset the counters</Tooltip>
                      </TooltipTrigger>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </main>
    </div>
  );
}
