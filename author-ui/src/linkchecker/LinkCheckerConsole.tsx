import {
  ActionButton,
  Button,
  Checkbox,
  Item,
  Picker,
  ProgressBar,
  ProgressCircle,
  SearchField,
  StatusLight,
  Text,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import { SelectionActions } from '../shell/SelectionBar';
import Close from '@spectrum-icons/workflow/Close';
import Delete from '@spectrum-icons/workflow/Delete';
import LinkOut from '@spectrum-icons/workflow/LinkOut';
import Refresh from '@spectrum-icons/workflow/Refresh';
import Search from '@spectrum-icons/workflow/Search';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import { relativeTime } from '../editor/shell/TopBar';
import { ProductBar } from '../shell/ProductBar';
import { Link as ShellLink, useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import { ownerUrl, type Link, type LinkCheckerClient, type LinkDetails, type LinkKind, type LinkList, type LinkStatus } from './api';
import { Segmented } from '../shell/Segmented';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: LinkCheckerClient;
  /** For the product bar outside the shell. */
  currentUser?: () => Promise<string | null>;
}

const PAGE = 200;

const STATUS_LABEL: Record<LinkStatus, string> = { invalid: 'Broken', pending: 'Not checked', valid: 'Working' };
const STATUS_LIGHT: Record<LinkStatus, 'negative' | 'notice' | 'positive'> = { invalid: 'negative', pending: 'notice', valid: 'positive' };
const OWNER_KIND: Record<string, string> = { page: VOCAB.page, block: VOCAB.block, record: VOCAB.record, media: VOCAB.file, other: 'Node' };

function when(iso?: string) {
  return iso ? relativeTime(new Date(iso)) : '';
}

/** What went wrong (or right) with a link, in a few words. */
function outcome(l: Link): string {
  if (l.status === 'pending') return 'Not checked yet';
  if (l.status === 'invalid') return l.error || (l.code ? `HTTP ${l.code}` : 'Broken');
  if (l.kind === 'internal') return 'Found in the repository';
  return l.code ? `HTTP ${l.code}` : 'OK';
}

/**
 * The link checker (`/etc/linkchecker.html`): every link written in content — to other sites and to pages,
 * blocks, records and media of this one — with whether it still leads somewhere and where it is used.
 * Scan content finds the links (and checks the new ones); Check again re-checks; a link's panel lists the
 * places it is written, each a way into the editor to fix it.
 */
export function LinkCheckerConsole({ client, currentUser }: Props) {
  const tab = useTab();
  const [status, setStatus] = useState<LinkStatus | 'all'>('all');
  const [kind, setKind] = useState<LinkKind | 'all'>('all');
  const [query, setQuery] = useState('');
  const [q, setQ] = useState('');
  const [limit, setLimit] = useState(PAGE);
  const [data, setData] = useState<LinkList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [focused, setFocused] = useState<string | null>(null);
  const [details, setDetails] = useState<LinkDetails | null>(null);
  const [user, setUser] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const running = data?.progress.running ?? false;
  const wasRunning = useRef(false);

  useScreenTitle(VOCAB.linkChecker, `${VOCAB.linkChecker} | ${VOCAB.product}`);
  useEffect(() => {
    if (!tab && currentUser) currentUser().then(setUser, () => setUser(null));
  }, [tab, currentUser]);

  // the search applies a moment after typing stops
  useEffect(() => {
    const t = window.setTimeout(() => setQ(query.trim()), 300);
    return () => window.clearTimeout(t);
  }, [query]);

  const load = useCallback(() => {
    client.list({ status, kind, q, limit }).then(
      (d) => {
        setData(d);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client, status, kind, q, limit]);
  useEffect(load, [load, version]);

  // while a run goes, follow it; when it ends, say what it found
  useEffect(() => {
    if (!running) {
      if (wasRunning.current && data) {
        const p = data.progress;
        if (p.error) ToastQueue.negative(`The run stopped: ${p.error}`, { timeout: 8000 });
        else
          ToastQueue.positive(
            `${p.scan ? `${p.scan.links} links found (${p.scan.added} new, ${p.scan.removed} gone). ` : ''}${p.total} checked, ${data.counts.invalid} broken.`,
            { timeout: 6000 },
          );
        if (focused) setVersion((v) => v + 1);
      }
      wasRunning.current = false;
      return;
    }
    wasRunning.current = true;
    const t = window.setInterval(load, 1500);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running, load]);

  // the panel of the focused link: every place it is written
  useEffect(() => {
    if (!focused) {
      setDetails(null);
      return;
    }
    let live = true;
    setDetails(null);
    client.info(focused).then(
      (d) => live && setDetails(d),
      () => live && setFocused(null),
    );
    return () => {
      live = false;
    };
  }, [client, focused, version]);

  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (tab && !tab.active) return;
      if (ev.key !== 'Escape') return;
      if (selected.length) setSelected([]);
      else if (focused) setFocused(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const links = useMemo(() => data?.links ?? [], [data]);
  const toggle = (url: string) => setSelected((s) => (s.includes(url) ? s.filter((u) => u !== url) : [...s, url]));
  const allSelected = links.length > 0 && links.every((l) => selected.includes(l.url));

  const run = async (what: () => Promise<boolean>, label: string) => {
    try {
      const started = await what();
      if (!started) ToastQueue.info('A run is already going; it is shown above.', { timeout: 4000 });
      else ToastQueue.info(label, { timeout: 2500 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
    load();
  };

  const forget = async (urls: string[]) => {
    try {
      const n = await client.remove(urls);
      ToastQueue.positive(`${n} link${n === 1 ? '' : 's'} removed from the list. A scan finds them again while content uses them.`, { timeout: 5000 });
      setSelected([]);
      if (focused && urls.includes(focused)) setFocused(null);
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
    load();
  };

  const counts = data?.counts;
  const progress = data?.progress;
  const never = data && counts?.all === 0 && !running;

  return (
    <div className="studio-sites studio-links">
      {!tab && <ProductBar user={user} />}

      {selected.length > 0 ? (
        <SelectionActions count={selected.length} onClear={() => setSelected([])}>
            <BarButton icon={<Refresh />} label="Check again" onPress={() => void run(() => client.check(selected), `Checking ${selected.length} link${selected.length === 1 ? '' : 's'}…`)} isDisabled={running} />
            <BarButton icon={<Delete />} label="Remove from list" onPress={() => void forget(selected)} />
          </SelectionActions>
      ) : (
        <ToolBar
          url="/etc/linkchecker.html"
          end={
            <>
            <SearchField aria-label="Search links" placeholder="Search links or pages" value={query} onChange={setQuery} width="size-2400" />
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet onPress={() => void run(() => client.checkAll(), 'Checking every link…')} isDisabled={running || !counts?.all}>
                <Refresh />
                <Text>Check all</Text>
              </ActionButton>
              <Tooltip>Check every link now, even the ones checked recently</Tooltip>
            </TooltipTrigger>
            </>
          }
          primary={
            <TooltipTrigger delay={400}>
              <Button variant="accent" onPress={() => void run(() => client.scan(), 'Scanning content…')} isDisabled={running}>
                <Search />
                <Text>Scan content</Text>
              </Button>
              <Tooltip>Find the links in content, then check the new ones and those not checked for {data?.checkPeriodHours ?? 24} hours</Tooltip>
            </TooltipTrigger>
          }
        />
      )}

      <div className="studio-links__filters">
        <Segmented<LinkStatus | 'all'>
          label="Status"
          items={[
            { key: 'all', label: `All${counts ? ` ${counts.all}` : ''}` },
            { key: 'invalid', label: `Broken${counts ? ` ${counts.invalid}` : ''}` },
            { key: 'pending', label: `Not checked${counts ? ` ${counts.pending}` : ''}` },
            { key: 'valid', label: `Working${counts ? ` ${counts.valid}` : ''}` },
          ]}
          value={status}
          onChange={(k) => {
            setStatus(k);
            setLimit(PAGE);
          }}
        />
        <Picker aria-label="Kind" isQuiet selectedKey={kind} onSelectionChange={(k) => setKind(k as LinkKind | 'all')} width="size-2400">
          <Item key="all">All links</Item>
          <Item key="external">To other sites</Item>
          <Item key="internal">Inside this site</Item>
        </Picker>
        <div className="studio-links__run" aria-live="polite">
          {progress?.running ? (
            <ProgressBar
              size="S"
              width="size-3600"
              label={progress.phase === 'scan' ? 'Scanning content…' : `Checking ${progress.done} of ${progress.total}`}
              isIndeterminate={progress.phase === 'scan' || !progress.total}
              value={progress.total ? (100 * progress.done) / progress.total : 0}
            />
          ) : progress?.finished ? (
            <Text UNSAFE_className="studio-links__muted">
              Last run {when(progress.finished)}
              {progress.by && progress.by !== 'scheduled' ? ` by ${progress.by}` : progress.by === 'scheduled' ? ' (scheduled)' : ''}
            </Text>
          ) : (
            <Text UNSAFE_className="studio-links__muted">Scanned and checked every {data?.checkPeriodHours ?? 24} hours</Text>
          )}
        </div>
      </div>

      <div className={`studio-links__body${focused ? ' has-panel' : ''}`}>
        <main className="studio-sites__content studio-links__list">
          {error ? (
            <ErrorState title="Could not read the links">{error}</ErrorState>
          ) : !data ? (
            <LoadingState />
          ) : never ? (
            <div className="studio-links__empty">
              <p>No links yet. Scan content to find the links written in pages, experience fragments, content fragments and media, and check where they lead.</p>
              <Button variant="accent" onPress={() => void run(() => client.scan(), 'Scanning content…')}>
                <Search />
                <Text>Scan content</Text>
              </Button>
            </div>
          ) : links.length === 0 ? (
            <EmptyState title={running ? 'Nothing here yet' : 'No links match'}>{running ? 'The current scan has not found a link yet.' : 'Change the filters or scan content again.'}</EmptyState>
          ) : (
            <div className="studio-listview studio-links__table">
              <table className="studio-table">
                <colgroup>
                  <col className="studio-links__col-check" />
                  <col className="studio-links__col-status" />
                  <col />
                  <col className="studio-links__col-used" />
                  <col className="studio-links__col-when" />
                </colgroup>
                <thead>
                  <tr>
                    <th className="studio-listview__check">
                      <Checkbox
                        aria-label="Select all"
                        isSelected={allSelected}
                        isIndeterminate={!allSelected && links.some((l) => selected.includes(l.url))}
                        onChange={(on) => setSelected(on ? links.map((l) => l.url) : [])}
                        isEmphasized
                      />
                    </th>
                    <th>Status</th>
                    <th>Link</th>
                    <th>Used in</th>
                    <th>Checked</th>
                  </tr>
                </thead>
                <tbody>
                  {links.map((l) => {
                    const isSelected = selected.includes(l.url);
                    return (
                      <tr key={l.url} className={`${isSelected ? 'is-selected' : ''}${focused === l.url ? ' is-focused' : ''}`}>
                        <td className="studio-listview__check">
                          <Checkbox aria-label={`Select ${l.url}`} isSelected={isSelected} onChange={() => toggle(l.url)} isEmphasized />
                        </td>
                        <td className="studio-links__status">
                          <StatusLight variant={STATUS_LIGHT[l.status]}>{STATUS_LABEL[l.status]}</StatusLight>
                          <span className="studio-links__muted">{outcome(l)}</span>
                        </td>
                        <td className="studio-links__url">
                          <button type="button" className="studio-listview__title" onClick={() => setFocused(focused === l.url ? null : l.url)} title={l.url}>
                            {l.url}
                          </button>
                          <span className="studio-links__muted">
                            {l.kind === 'internal' ? 'Inside this site' : 'Another site'}
                            {l.finalUrl ? ` · redirects to ${l.finalUrl}` : ''}
                          </span>
                        </td>
                        <td className="studio-links__used">
                          {l.owners?.[0] ? (
                            <span className="studio-links__clip" title={l.owners.join('\n')}>
                              {/* clipped from the left (rtl), the path itself still reads left to right */}
                              <bdi dir="ltr">{l.owners[0]}</bdi>
                            </span>
                          ) : null}
                          {l.referrerCount > 1 ? <span className="studio-links__muted">{l.referrerCount} places</span> : null}
                        </td>
                        <td className="studio-listview__muted">{when(l.lastChecked) || '—'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {data.total > links.length && (
                <div className="studio-links__more">
                  <Text>
                    {links.length} of {data.total}
                  </Text>
                  <ActionButton isQuiet onPress={() => setLimit((n) => n + PAGE)}>
                    Show more
                  </ActionButton>
                </div>
              )}
            </div>
          )}
        </main>

        {focused && (
          <aside className="studio-links__panel" aria-label="Link details">
            <div className="studio-links__panelhead">
              <Text UNSAFE_className="studio-links__paneltitle">Link</Text>
              <ActionButton isQuiet aria-label="Close the panel" onPress={() => setFocused(null)}>
                <Close />
              </ActionButton>
            </div>
            {!details ? (
              <ProgressCircle size="S" isIndeterminate aria-label="Loading" />
            ) : (
              <LinkPanel
                link={details}
                onCheck={() => void run(() => client.check([details.url]), 'Checking…')}
                onForget={() => void forget([details.url])}
                running={running}
              />
            )}
          </aside>
        )}
      </div>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function LinkPanel({ link, onCheck, onForget, running }: { link: LinkDetails; onCheck(): void; onForget(): void; running: boolean }) {
  return (
    <div className="studio-links__details">
      <div className="studio-links__bigurl">
        {link.kind === 'external' ? (
          <a href={link.url} target="_blank" rel="noreferrer noopener">
            {link.url}
            <LinkOut size="XS" />
          </a>
        ) : (
          <code>{link.url}</code>
        )}
      </div>
      <StatusLight variant={STATUS_LIGHT[link.status]}>{STATUS_LABEL[link.status]}</StatusLight>
      <dl className="studio-preview__meta">
        <div>
          <dt>Result</dt>
          <dd>{outcome(link)}</dd>
        </div>
        {link.finalUrl && (
          <div>
            <dt>Redirects to</dt>
            <dd>{link.finalUrl}</dd>
          </div>
        )}
        <div>
          <dt>Checked</dt>
          <dd>{link.lastChecked ? new Date(link.lastChecked).toLocaleString() : 'Not yet'}</dd>
        </div>
        <div>
          <dt>Found</dt>
          <dd>{link.firstSeen ? new Date(link.firstSeen).toLocaleDateString() : ''}</dd>
        </div>
      </dl>
      <div className="studio-links__panelactions">
        <ActionButton onPress={onCheck} isDisabled={running}>
          <Refresh />
          <Text>Check again</Text>
        </ActionButton>
        <ActionButton isQuiet onPress={onForget}>
          <Delete />
          <Text>Remove</Text>
        </ActionButton>
      </div>
      <h3 className="studio-preview__heading">Used in ({link.referrers.length})</h3>
      <ul className="studio-links__refs">
        {link.referrers.map((r) => (
          <li key={`${r.node}@${r.property}`}>
            <ShellLink to={ownerUrl(r)} title={r.owner}>
              {r.ownerTitle}
            </ShellLink>
            <span className="studio-links__muted">
              {OWNER_KIND[r.ownerKind] ?? r.ownerKind} · {r.owner}
            </span>
            {(r.component || r.property) && (
              <span className="studio-links__muted">
                {r.component ? `${r.component} · ` : ''}
                {r.property}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function BarButton({ icon, label, onPress, isDisabled }: { icon: ReactElement; label: string; onPress(): void; isDisabled?: boolean }) {
  return (
    <ActionButton isQuiet onPress={onPress} isDisabled={isDisabled}>
      {icon}
      <Text>{label}</Text>
    </ActionButton>
  );
}
