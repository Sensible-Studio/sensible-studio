import { ActionButton, Checkbox, DialogContainer, SearchField, Text, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import { SelectionActions } from '../shell/SelectionBar';
import Delete from '@spectrum-icons/workflow/Delete';
import Attach from '@spectrum-icons/workflow/Attach';
import Download from '@spectrum-icons/workflow/Download';
import Edit from '@spectrum-icons/workflow/Edit';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import { relativeTime } from '../editor/shell/TopBar';
import { PathCrumbs } from '../shell/PathCrumbs';
import { ProductBar } from '../shell/ProductBar';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { VOCAB } from '../shell/vocabulary';
import type { Submission, SubmissionList, SubmissionsClient } from './api';
import './forms.scss';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ScreenFrame } from '../shell/ScreenFrame';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { InspectorShell } from '../shell/InspectorShell';

interface Props {
  client: SubmissionsClient;
  /** The form page: /content/forms/<site>/<form>. */
  form: string;
}

const PAGE = 100;

/**
 * The submissions of a form: a table with one column per field, newest first, a filter, one submission in
 * full on the right, export as CSV / JSON, delete. Lives at /forms/submissions.html/<form>.
 */
export function SubmissionsView({ client, form }: Props) {
  const tab = useTab();
  const go = useNavigate();
  const [data, setData] = useState<SubmissionList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [open, setOpen] = useState<Submission | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [user, setUser] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const debounce = useRef<number | undefined>(undefined);

  useScreenTitle(data ? `Submissions · ${data.title}` : 'Submissions', data ? `${data.title} | Submissions | Sensible Studio` : 'Submissions | Sensible Studio');

  useEffect(() => {
    fetch('/system/sling/info.sessionInfo.json', { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : null))
      .then((i: { userID?: string } | null) => setUser(i?.userID ?? null), () => setUser(null));
  }, []);

  const load = useCallback(() => {
    let live = true;
    client.list(form, { q: q || undefined, limit: PAGE }).then(
      (d) => {
        if (!live) return;
        setData(d);
        setError(null);
      },
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, form, q]);

  useEffect(() => {
    window.clearTimeout(debounce.current);
    debounce.current = window.setTimeout(load, q ? 250 : 0);
    return () => window.clearTimeout(debounce.current);
  }, [load, version, q]);

  const refresh = () => setVersion((v) => v + 1);

  const show = async (s: Submission) => {
    try {
      const full = await client.get(s.path);
      setOpen(full);
      setData((d) => (d ? { ...d, items: d.items.map((i) => (i.path === s.path ? { ...i, seen: true } : i)) } : d));
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const remove = async () => {
    const paths = [...selected];
    setConfirmDelete(false);
    try {
      const n = await client.remove(paths);
      ToastQueue.positive(n === 1 ? 'Submission deleted' : `${n} submissions deleted`, { timeout: 2500 });
      setSelected(new Set());
      if (open && paths.includes(open.path)) setOpen(null);
      refresh();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
  };

  const toggle = (path: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });

  // the folders above the form by their titles, as the Forms console shows them (a name only until they are known)
  const [titles, setTitles] = useState<Record<string, string>>({});
  useEffect(() => {
    let cancelled = false;
    fetch(`/bin/studio/sites.json?op=children&path=${encodeURIComponent(form)}`, { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : null))
      .then((d: { trail?: Array<{ path: string; title: string }> } | null) => {
        if (!cancelled && d?.trail) setTitles(Object.fromEntries(d.trail.map((t) => [t.path, t.title])));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [form]);

  const crumbs = useMemo(() => {
    const rel = form.replace(/^\/content\/forms\/?/, '').split('/').filter(Boolean);
    const out: Array<{ path: string; title: string }> = [{ path: '/content/forms', title: VOCAB.forms }];
    let acc = '/content/forms';
    rel.forEach((seg, i) => {
      acc += `/${seg}`;
      out.push({ path: acc, title: i === rel.length - 1 && data ? data.title : titles[acc] ?? seg });
    });
    out.push({ path: `${form}#submissions`, title: 'Submissions' });
    return out;
  }, [form, data, titles]);

  const fields = data?.fields ?? [];
  const allSelected = !!data && data.items.length > 0 && data.items.every((i) => selected.has(i.path));

  return (
    <div className="studio-sites studio-submissions">
      {!tab && <ProductBar user={user} />}
      {selected.size > 0 ? (
        <SelectionActions count={selected.size} onClear={() => setSelected(new Set())}>
            <Bar icon={<Delete />} label="Delete" onPress={() => setConfirmDelete(true)} />
          </SelectionActions>
      ) : (
        <ScreenFrame
          identity={<PathCrumbs crumbs={crumbs} onGo={(p) => go(p.startsWith('/content/forms') && !p.includes('#') ? `/sites.html${p}` : `/sites.html${form.slice(0, form.lastIndexOf('/'))}`)} />}
          tools={<SearchField aria-label="Filter submissions" placeholder="Filter" value={q} onChange={setQ} width="size-3000" />}
          actions={<>
            <Bar icon={<Edit />} label="Edit form" onPress={() => go({ kind: 'editor', path: form })} />
            <TooltipTrigger delay={600}>
              <ActionButton isQuiet onPress={() => window.open(client.exportUrl(form, 'csv'), '_blank')}>
                <Download />
                <Text>CSV</Text>
              </ActionButton>
              <Tooltip>Download every submission as CSV</Tooltip>
            </TooltipTrigger>
            <TooltipTrigger delay={600}>
              <ActionButton isQuiet onPress={() => window.open(client.exportUrl(form, 'json'), '_blank')}>
                <Download />
                <Text>JSON</Text>
              </ActionButton>
              <Tooltip>Download every submission as JSON</Tooltip>
            </TooltipTrigger>
            <TooltipTrigger delay={600}>
              <ActionButton isQuiet aria-label="Refresh" onPress={refresh}>
                <Refresh />
              </ActionButton>
              <Tooltip>Refresh</Tooltip>
            </TooltipTrigger>
          </>}
          // a form's submissions are opened from the form: Close goes back to where the author came from
          onClose={() => { const back = `/sites.html${form.slice(0, form.lastIndexOf('/'))}`; if (tab) tab.leave(back); else go(back); }}
          closeLabel="Close submissions"
        />
      )}
      <div className="studio-submissions__body">
        <div className="studio-submissions__main">
          {error && <ErrorState title="Could not load submissions">{error}</ErrorState>}
          {!data && !error && <LoadingState label="Loading submissions" />}
          {data && data.items.length === 0 && <EmptyState title={q ? 'Nothing matches' : 'No submissions yet'}>{q ? 'Change or clear the filter.' : 'New form submissions will appear here.'}</EmptyState>}
          {data && data.items.length > 0 && (
            <div className="studio-listview studio-submissions__list">
              <table className="studio-table">
                <thead>
                  <tr>
                    <th className="studio-listview__check">
                      <Checkbox aria-label="Select all" isSelected={allSelected} onChange={(v) => setSelected(v ? new Set(data.items.map((i) => i.path)) : new Set())} />
                    </th>
                    <th>Received</th>
                    {fields.map((f) => (
                      <th key={f.name}>{f.label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((s) => (
                    <tr key={s.path} className={`${selected.has(s.path) ? 'is-selected' : ''}${open?.path === s.path ? ' is-open' : ''}${s.seen ? '' : ' is-new'}`} onClick={() => void show(s)}>
                      <td className="studio-listview__check" onClick={(e) => e.stopPropagation()}>
                        <Checkbox aria-label={`Select ${s.id}`} isSelected={selected.has(s.path)} onChange={() => toggle(s.path)} />
                      </td>
                      <td className="studio-submissions__when" title={s.submittedAt ?? ''}>
                        {s.submittedAt ? relativeTime(new Date(s.submittedAt)) : ''}
                        {!s.seen && <span className="studio-submissions__new">new</span>}
                      </td>
                      {fields.map((f) => (
                        <td key={f.name} className="studio-submissions__cell">
                          {f.type === 'fileupload' && s.values[f.name] ? (
                            <span className="studio-submissions__attach">
                              <Attach size="XS" /> {s.values[f.name]}
                            </span>
                          ) : (
                            s.values[f.name] ?? ''
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
              {data.total > data.items.length && (
                <p className="studio-submissions__more">
                  Showing {data.items.length} of {data.total}; export for all of them.
                </p>
              )}
            </div>
          )}
        </div>
        {open && (
          <InspectorShell
            kind="Submission"
            subject={open.submittedAt ? new Date(open.submittedAt).toLocaleString() : open.id}
            path={open.path}
            onClose={() => setOpen(null)}
            label="Submission"
            className="studio-submissions__detail"
          >
            <dl className="studio-submissions__facts">
              {fields.map((f) => (
                <div key={f.name}>
                  <dt>{f.label}</dt>
                  <dd>{open.values[f.name] || <span className="studio-submissions__blank">—</span>}</dd>
                </div>
              ))}
              {Object.keys(open.values)
                .filter((k) => !fields.some((f) => f.name === k))
                .map((k) => (
                  <div key={k}>
                    <dt>{k}</dt>
                    <dd>{open.values[k]}</dd>
                  </div>
                ))}
            </dl>
            {open.files && open.files.length > 0 && (
              <>
                <p className="studio-submissions__section">Files</p>
                <ul className="studio-submissions__files">
                  {open.files.map((f) => (
                    <li key={f.path}>
                      <a href={client.fileUrl(f)} target="_blank" rel="noreferrer" title={`${f.field}: open ${f.name}`}>
                        <Attach size="XS" />
                        {f.name}
                        {f.size != null && <small>{sizeOf(f.size)}</small>}
                      </a>
                    </li>
                  ))}
                </ul>
              </>
            )}
            {open.actions && open.actions.length > 0 && (
              <>
                <p className="studio-submissions__section">After submit</p>
                <ul className="studio-submissions__actions">
                  {open.actions.map((a) => (
                    <li key={a.name} className={`is-${a.status}`} title={a.at ? new Date(a.at).toLocaleString() : undefined}>
                      <i aria-hidden="true" />
                      <strong>{ACTION_LABELS[a.name] ?? a.name}</strong>
                      {a.name === 'record' && a.status === 'done' && a.detail ? (
                        <span>
                          <button type="button" className="studio-submissions__link" onClick={() => go({ kind: 'cf-editor', path: recordPath(a.detail!) })} title={recordPath(a.detail)}>
                            Open the content fragment
                          </button>
                          {a.detail.includes(' — ') ? ` — ${a.detail.slice(a.detail.indexOf(' — ') + 3)}` : ''}
                        </span>
                      ) : (
                        <span>
                          {a.status === 'done' ? 'done' : a.status === 'failed' ? 'failed' : 'skipped'}
                          {a.detail ? ` — ${a.detail}` : ''}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </>
            )}
            <dl className="studio-submissions__facts studio-submissions__facts--meta">
              <div>
                <dt>Received</dt>
                <dd>{open.submittedAt ? new Date(open.submittedAt).toLocaleString() : ''}</dd>
              </div>
              {open.origin && (
                <div>
                  <dt>From</dt>
                  <dd>{open.origin}</dd>
                </div>
              )}
              {open.referrer && (
                <div>
                  <dt>Page</dt>
                  <dd>{open.referrer}</dd>
                </div>
              )}
              {open.locale && (
                <div>
                  <dt>Language</dt>
                  <dd>{open.locale}</dd>
                </div>
              )}
              {open.userAgent && (
                <div>
                  <dt>Browser</dt>
                  <dd className="studio-submissions__ua">{open.userAgent}</dd>
                </div>
              )}
              <div>
                <dt>Id</dt>
                <dd className="studio-submissions__ua">{open.id}</dd>
              </div>
            </dl>
            <div className="studio-submissions__detail-actions">
              <ActionButton
                onPress={() => {
                  setSelected(new Set([open.path]));
                  setConfirmDelete(true);
                }}
              >
                <Delete />
                <Text>Delete</Text>
              </ActionButton>
            </div>
          </InspectorShell>
        )}
      </div>
      <DialogContainer onDismiss={() => setConfirmDelete(false)}>
        {confirmDelete && (
          <StudioConfirmDialog title="Delete submissions" variant="destructive" primaryActionLabel="Delete" cancelLabel="Cancel" onPrimaryAction={() => void remove()}>
            {selected.size === 1 ? 'This submission' : `These ${selected.size} submissions`} will be deleted for good.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

const ACTION_LABELS: Record<string, string> = { record: 'Content Fragment', flow: 'Flow', webhook: 'Web address', email: 'Email' };

/** A record action's detail is "<path>" or "<path> — not written: …". */
const recordPath = (detail: string) => (detail.includes(' — ') ? detail.slice(0, detail.indexOf(' — ')) : detail);

function sizeOf(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function Bar({ icon, label, onPress }: { icon: ReactElement; label: string; onPress(): void }) {
  return (
    <TooltipTrigger delay={600}>
      <ActionButton isQuiet onPress={onPress}>
        {icon}
        <Text>{label}</Text>
      </ActionButton>
      <Tooltip>{label}</Tooltip>
    </TooltipTrigger>
  );
}
