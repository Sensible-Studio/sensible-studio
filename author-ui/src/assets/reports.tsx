import {
  ActionButton,
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  DialogContainer,
  Divider,
  Heading,
  IllustratedMessage,
  ProgressCircle,
  TextArea,
  TextField,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import ChevronLeft from '@spectrum-icons/workflow/ChevronLeft';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Delete from '@spectrum-icons/workflow/Delete';
import Download from '@spectrum-icons/workflow/Download';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { DAM_ROOT, reportCsvUrl, type DamClient, type ReportCatalog, type ReportInfo, type ReportPage as Page, type ReportType } from './api';
import { TagsField } from './AssetDetails';
import { formatBytes } from './format';
import type { AssetsRoute } from './route';
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { routeUrl } from './route';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

const PAGE = 100;

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** 17.10.2026, 14:05 (the browser's locale), or the text itself if it is not a date. */
function when(v?: string | null): string {
  if (!v) return '';
  const t = Date.parse(v);
  return Number.isFinite(t) ? new Date(t).toLocaleString() : v;
}

function day(v?: string | null): string {
  if (!v) return '';
  const t = Date.parse(v);
  return Number.isFinite(t) ? new Date(t).toLocaleDateString() : v;
}

/** What a report's numbers add up to: "12 files · 4.3 MB". */
export function summaryText(r: Pick<ReportInfo, 'summary' | 'type' | 'rows'>): string {
  const s = r.summary ?? {};
  const parts: string[] = [];
  if (typeof s.links === 'number') parts.push(`${s.links} link${s.links === 1 ? '' : 's'}`, `${s.active ?? 0} active`);
  if (typeof s.folders === 'number') parts.push(`${s.folders} folder${s.folders === 1 ? '' : 's'}`);
  if (typeof s.assets === 'number') parts.push(`${s.assets} file${s.assets === 1 ? '' : 's'}`);
  if (typeof s.size === 'number') parts.push(formatBytes(s.size) || '0 B');
  if (typeof s.renditionsSize === 'number') parts.push(`${formatBytes(s.renditionsSize) || '0 B'} renditions`);
  return parts.length ? parts.join(' · ') : `${r.rows} row${r.rows === 1 ? '' : 's'}`;
}

/** Tools > Assets > Reports: the reports the user made, newest first. */
export function ReportsPage({ client, navigate }: { client: DamClient; navigate(route: AssetsRoute): void }) {
  const [reports, setReports] = useState<ReportInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<ReportInfo | null>(null);

  const load = useCallback(async () => {
    try {
      setReports(await client.reports());
      setError(null);
    } catch (e) {
      setError(message(e));
    }
  }, [client]);

  useScreenTitle('Media Reports', 'Media Reports | Sensible Studio Media');
  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="studio-assets__profiles studio-assets__reports">
      <ToolBar
        url={routeUrl({ kind: 'reports' })}
        primary={
          <Button variant="accent" onPress={() => setCreating(true)}>
            <Add />
            Create
          </Button>
        }
      />
      {error ? (
        <ErrorState title="Reports could not be loaded">{error}</ErrorState>
      ) : reports === null ? (
        <LoadingState label="Loading reports" />
      ) : reports?.length === 0 ? (
        <EmptyState title="No reports yet">Create one to see what was uploaded, changed, expires or takes up space, and download it as CSV.</EmptyState>
      ) : (
        <table className="studio-assets__profiles-table studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Type</th>
              <th>Folder</th>
              <th>Created</th>
              <th>Result</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {reports?.map((r) => (
              <tr key={r.id}>
                <td>
                  <a
                    href={`#${r.id}`}
                    onClick={(e) => {
                      e.preventDefault();
                      navigate({ kind: 'report', id: r.id });
                    }}
                  >
                    {r.title}
                  </a>
                  {r.description && <div className="studio-assets__muted">{r.description}</div>}
                </td>
                <td>{r.typeTitle}</td>
                <td>{r.path ? <code>{r.path}</code> : '—'}</td>
                <td>{when(r.created)}</td>
                <td>
                  {summaryText(r)}
                  {r.truncated && <span className="studio-assets__muted"> (stopped at the limit)</span>}
                </td>
                <td className="studio-assets__report-actions">
                  <a className="studio-assets__linkbtn" href={reportCsvUrl(r.id)} download aria-label={`Download ${r.title}`}>
                    <Download size="S" /> CSV
                  </a>
                  <ActionButton isQuiet aria-label={`Delete ${r.title}`} onPress={() => setDeleting(r)}>
                    <Delete />
                  </ActionButton>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <DialogContainer onDismiss={() => setCreating(false)}>
        {creating && (
          <CreateReportDialog
            client={client}
            close={() => setCreating(false)}
            onCreated={(r) => {
              setCreating(false);
              navigate({ kind: 'report', id: r.id });
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog
            title="Delete report"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteReports([deleting.id]);
              } catch (e) {
                setError(message(e));
              }
              void load();
            }}
            onCancel={() => setDeleting(null)}
          >
            Delete <strong>{deleting.title}</strong>? The assets are not touched.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

/** A date field (yyyy-mm-dd) with a label, the browser's own picker. */
function DateField({ label, value, onChange }: { label: string; value: string; onChange(v: string): void }) {
  const id = `studio-report-${label.toLowerCase().replace(/\W+/g, '-')}`;
  return (
    <div className="studio-assets__datefield">
      <label htmlFor={id}>{label}</label>
      <input id={id} type="date" value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

/** AEM's Create Report wizard: choose the type, then title, folder, dates and columns. */
export function CreateReportDialog({
  client,
  close,
  onCreated,
  folder = DAM_ROOT,
}: {
  client: DamClient;
  close(): void;
  onCreated(r: ReportInfo): void;
  folder?: string;
}) {
  const [catalog, setCatalog] = useState<ReportCatalog | null>(null);
  const [type, setType] = useState<ReportType | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [path, setPath] = useState(folder);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [columns, setColumns] = useState<string[]>([]);
  const [properties, setProperties] = useState<string[]>([]);
  const [excludeFragments, setExcludeFragments] = useState(false);
  const [includeRenditions, setIncludeRenditions] = useState(true);
  const [excludeSubfolders, setExcludeSubfolders] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    client.reportCatalog().then(
      (c) => {
        setCatalog(c);
        setColumns(c.columns.filter((x) => x.default).map((x) => x.id));
      },
      (e) => setError(message(e)),
    );
  }, [client]);

  const choose = (t: ReportType) => {
    setType(t);
    if (!title) setTitle(`${t.title} report`);
  };

  const problems = useMemo(() => {
    const out: string[] = [];
    if (!type) return out;
    if (!title.trim()) out.push('The report needs a title.');
    if (type.folder && !(path === DAM_ROOT || path.startsWith(`${DAM_ROOT}/`))) out.push(`The folder must be in ${DAM_ROOT}.`);
    if (type.dates && from && to && from > to) out.push('The start date is after the end date.');
    if (type.columns && columns.length + properties.length === 0) out.push('Choose at least one column.');
    return out;
  }, [type, title, path, from, to, columns, properties]);

  const create = async () => {
    if (!type || problems.length) return;
    setBusy(true);
    setError(null);
    try {
      onCreated(
        await client.createReport({
          type: type.id,
          title: title.trim(),
          description: description.trim() || undefined,
          path: type.folder ? path : undefined,
          from: type.dates ? from || undefined : undefined,
          to: type.dates ? to || undefined : undefined,
          columns: type.columns ? columns : undefined,
          properties: type.columns ? properties : undefined,
          excludeFragments: type.columns && excludeFragments,
          includeRenditions: type.id === 'foldersizeandstrengthreport' && includeRenditions,
          excludeSubfolders: type.id === 'foldersizeandstrengthreport' && excludeSubfolders,
        }),
      );
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="L">
      <Heading>{type ? `Create ${type.title} report` : 'Create report'}</Heading>
      <Divider />
      <Content>
        {!catalog && !error && <ProgressCircle isIndeterminate aria-label="Loading" size="S" />}
        {catalog && !type && (
          <ul className="studio-assets__report-types" aria-label="Report type">
            {catalog.types.map((t) => (
              <li key={t.id}>
                <button type="button" onClick={() => choose(t)}>
                  <strong>{t.title}</strong>
                  <span>{t.description}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {catalog && type && (
          <div className="studio-assets__report-form">
            <TextField label="Title" value={title} onChange={setTitle} isRequired width="100%" autoFocus />
            <TextArea label="Description" value={description} onChange={setDescription} width="100%" />
            {type.folder && <TextField label="Folder" value={path} onChange={(v) => setPath(v.trim().replace(/\/+$/, '') || DAM_ROOT)} width="100%" />}
            {type.dates && (
              <div className="studio-assets__report-dates">
                <DateField label={type.id === 'assetexpiryreport' ? 'Expires from' : 'From'} value={from} onChange={setFrom} />
                <DateField label={type.id === 'assetexpiryreport' ? 'Expires until' : 'To'} value={to} onChange={setTo} />
                {type.id === 'assetexpiryreport' && !to && <p className="studio-assets__muted">Without an end date: what has expired by now.</p>}
              </div>
            )}
            {type.id === 'foldersizeandstrengthreport' && (
              <>
                <Checkbox isSelected={includeRenditions} onChange={setIncludeRenditions}>
                  Include renditions
                </Checkbox>
                <Checkbox isSelected={excludeSubfolders} onChange={setExcludeSubfolders}>
                  Only this folder (no sub-folder rows)
                </Checkbox>
              </>
            )}
            {type.columns && (
              <>
                <Checkbox isSelected={excludeFragments} onChange={setExcludeFragments}>
                  Leave out content fragments
                </Checkbox>
                <fieldset className="studio-assets__report-columns">
                  <legend>Columns</legend>
                  {catalog.columns.map((c) => (
                    <Checkbox
                      key={c.id}
                      isSelected={columns.includes(c.id)}
                      onChange={(on) => setColumns((list) => (on ? catalog.columns.map((x) => x.id).filter((x) => x === c.id || list.includes(x)) : list.filter((x) => x !== c.id)))}
                    >
                      {c.title}
                    </Checkbox>
                  ))}
                </fieldset>
                <TagsField label="Metadata columns (e.g. dc:creator)" values={properties} onChange={setProperties} />
              </>
            )}
            {problems.length > 0 && <p className="studio-assets__muted">{problems.join(' ')}</p>}
          </div>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        {type && (
          <Button variant="secondary" onPress={() => setType(null)} isDisabled={busy}>
            Back
          </Button>
        )}
        {type && (
          <Button variant="accent" onPress={() => void create()} isDisabled={busy || problems.length > 0}>
            {busy ? 'Creating…' : 'Create'}
          </Button>
        )}
      </ButtonGroup>
    </StudioDialog>
  );
}

const SIZE_COLUMNS = new Set(['Size', 'Renditions Size']);
const DATE_COLUMNS = new Set(['Added', 'Modified', 'Expires', 'Published', 'Created']);

function Cell({ header, value, navigate }: { header: string; value: string; navigate(route: AssetsRoute): void }) {
  if (!value) return <td />;
  if (SIZE_COLUMNS.has(header) && /^\d+$/.test(value)) return <td className="is-number">{formatBytes(Number(value))}</td>;
  if (DATE_COLUMNS.has(header)) return <td>{when(value)}</td>;
  if ((header === 'Path' || header === 'Folder') && (value === DAM_ROOT || value.startsWith(`${DAM_ROOT}/`))) {
    const route: AssetsRoute = header === 'Folder' ? { kind: 'browse', path: value } : { kind: 'details', path: value, properties: false };
    return (
      <td>
        <a
          href={value}
          onClick={(e) => {
            e.preventDefault();
            navigate(route);
          }}
        >
          {value}
        </a>
      </td>
    );
  }
  if (header === 'Items') return <td className="studio-assets__report-wrap">{value.split(' | ').join('\n')}</td>;
  return <td className={/^\d+$/.test(value) ? 'is-number' : undefined}>{value}</td>;
}

/** One report: what it covers, its numbers, the rows a page at a time, and the CSV. */
export function ReportPage({ client, id, navigate }: { client: DamClient; id: string; navigate(route: AssetsRoute): void }) {
  const [page, setPage] = useState<Page | null>(null);
  useScreenTitle(page ? `Report · ${page.title}` : null, page ? `${page.title} | Media Reports | Sensible Studio Media` : null);
  const [offset, setOffset] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    let live = true;
    client.report(id, offset, PAGE).then(
      (p) => {
        if (!live) return;
        setPage(p);
        setError(null);
      },
      (e) => live && setError(message(e)),
    );
    return () => {
      live = false;
    };
  }, [client, id, offset]);

  const back = () => navigate({ kind: 'reports' });
  if (error && !page) {
    return (
      <div className="studio-assets__profiles">
        <IllustratedMessage>
          <Heading>Cannot open the report</Heading>
          <Content>{error}</Content>
        </IllustratedMessage>
        <div className="studio-assets__center">
          <Button variant="secondary" onPress={back}>
            Back to reports
          </Button>
        </div>
      </div>
    );
  }
  if (!page) {
    return (
      <div className="studio-assets__loading">
        <ProgressCircle isIndeterminate aria-label="Loading" />
      </div>
    );
  }
  const last = Math.min(page.rows, offset + page.items.length);
  return (
    <div className="studio-assets__profiles studio-assets__reports">
      <ToolBar
        url={routeUrl({ kind: 'reports' })}
        extra={[{ path: `report:${page.id}`, title: page.title }]}
        onGo={back}
        end={
          <>
            <a className="studio-assets__linkbtn" href={reportCsvUrl(page.id)} download>
              <Download size="S" /> Download CSV
            </a>
            <ActionButton isQuiet onPress={() => setDeleting(true)}>
              <Delete />
              Delete
            </ActionButton>
          </>
        }
      />
      <dl className="studio-assets__report-facts">
        <div>
          <dt>Type</dt>
          <dd>{page.typeTitle}</dd>
        </div>
        {page.path && (
          <div>
            <dt>Folder</dt>
            <dd>
              <code>{page.path}</code>
            </dd>
          </div>
        )}
        {(page.from || page.to) && (
          <div>
            <dt>Dates</dt>
            <dd>
              {day(page.from) || '…'} – {day(page.to) || '…'}
            </dd>
          </div>
        )}
        <div>
          <dt>Created</dt>
          <dd>
            {when(page.created)} by {page.owner}
          </dd>
        </div>
        <div>
          <dt>Result</dt>
          <dd>
            {summaryText(page)}
            {page.truncated && ' (stopped at 100,000 files)'}
          </dd>
        </div>
      </dl>
      {page.description && <p className="studio-assets__muted">{page.description}</p>}
      {page.rows === 0 ? (
        <IllustratedMessage>
          <Heading>Nothing to report</Heading>
          <Content>No files match this report.</Content>
        </IllustratedMessage>
      ) : (
        <>
          <div className="studio-assets__report-scroll">
            <table className="studio-assets__profiles-table studio-assets__report-table studio-table">
              <thead>
                <tr>
                  {page.header.map((h) => (
                    <th key={h}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {page.items.map((row, i) => (
                  // eslint-disable-next-line react/no-array-index-key
                  <tr key={offset + i}>
                    {page.header.map((h, j) => (
                      <Cell key={h} header={h} value={row[j] ?? ''} navigate={navigate} />
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {page.rows > PAGE && (
            <div className="studio-assets__report-pager">
              <ActionButton isQuiet aria-label="Previous page" isDisabled={offset === 0} onPress={() => setOffset(Math.max(0, offset - PAGE))}>
                <ChevronLeft />
              </ActionButton>
              <span>
                {offset + 1}–{last} of {page.rows}
              </span>
              <ActionButton isQuiet aria-label="Next page" isDisabled={last >= page.rows} onPress={() => setOffset(offset + PAGE)}>
                <ChevronRight />
              </ActionButton>
            </div>
          )}
        </>
      )}
      <DialogContainer onDismiss={() => setDeleting(false)}>
        {deleting && (
          <StudioConfirmDialog
            title="Delete report"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteReports([page.id]);
                back();
              } catch (e) {
                setError(message(e));
              }
            }}
            onCancel={() => setDeleting(false)}
          >
            Delete <strong>{page.title}</strong>? The assets are not touched.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}
