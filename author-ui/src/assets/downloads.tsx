import { ActionButton, Content, Dialog, DialogTrigger, Heading, ProgressBar, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import DownloadIcon from '@spectrum-icons/workflow/Download';
import { useCallback, useEffect, useState } from 'react';

/**
 * Downloads prepared in the background (AEM's asynchronous download): a large selection is
 * zipped on the server, kept 7 days, and taken from the Downloads list in Media's tool bar.
 */
export interface BackgroundDownload {
  id: string;
  name: string;
  status: 'queued' | 'working' | 'ready' | 'failed';
  files: number;
  done: number;
  size?: number | null;
  created: string;
  expires: string;
  error?: string | null;
}

const API = '/bin/studio/dam/downloads';
const CSRF = { 'X-Studio-CSRF': '1' };

/** Dispatched on window when a download was started or removed. */
export const DOWNLOADS_CHANGED = 'studio:downloads-changed';

async function answer<T>(res: Response, what: string): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(body.error ?? `${what}: HTTP ${res.status}`);
  return body;
}

export async function startDownload(paths: string[], opts: { renditions: boolean; separate: boolean; agree?: string[] }): Promise<BackgroundDownload> {
  const f = new URLSearchParams({ op: 'create' });
  for (const p of paths) f.append('path', p);
  if (opts.renditions) f.set('renditions', 'true');
  if (opts.separate) f.set('separate', 'true');
  for (const a of opts.agree ?? []) f.append('agree', a);
  const d = await answer<BackgroundDownload>(
    await fetch(`${API}.json`, { method: 'POST', credentials: 'same-origin', headers: CSRF, body: f }),
    'Download',
  );
  window.dispatchEvent(new Event(DOWNLOADS_CHANGED));
  return d;
}

export async function listDownloads(): Promise<BackgroundDownload[]> {
  return (await answer<{ downloads: BackgroundDownload[] }>(await fetch(`${API}.json`, { credentials: 'same-origin' }), 'Downloads')).downloads;
}

export async function removeDownload(id: string): Promise<void> {
  await answer(await fetch(`${API}.json`, { method: 'POST', credentials: 'same-origin', headers: CSRF, body: new URLSearchParams({ op: 'delete', id }) }), 'Remove');
  window.dispatchEvent(new Event(DOWNLOADS_CHANGED));
}

export function archiveUrl(id: string): string {
  return `${API}.zip?${new URLSearchParams({ id })}`;
}

/** A selection this big is better prepared in the background. */
export function suggestBackground(items: Array<{ type: string }>): boolean {
  return items.some((i) => i.type === 'folder') || items.length > 10;
}

export function formatSize(bytes: number | null | undefined): string {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function downloadStatus(d: BackgroundDownload): string {
  switch (d.status) {
    case 'queued':
      return `Waiting · ${d.files} file${d.files === 1 ? '' : 's'}`;
    case 'working':
      return `Preparing · ${d.done} of ${d.files} files`;
    case 'ready':
      return `${d.files} file${d.files === 1 ? '' : 's'} · ${formatSize(d.size)} · until ${new Date(d.expires).toLocaleDateString()}`;
    default:
      return `Failed${d.error ? `: ${d.error}` : ''}`;
  }
}

/** The user's background downloads; polls while one is being prepared. */
export function useDownloads(load: () => Promise<BackgroundDownload[]> = listDownloads) {
  const [list, setList] = useState<BackgroundDownload[] | null>(null);
  const reload = useCallback(async () => {
    try {
      setList(await load());
    } catch {
      setList((l) => l ?? []);
    }
  }, [load]);
  useEffect(() => {
    void reload();
    const again = () => void reload();
    window.addEventListener(DOWNLOADS_CHANGED, again);
    return () => window.removeEventListener(DOWNLOADS_CHANGED, again);
  }, [reload]);
  const busy = (list ?? []).some((d) => d.status === 'queued' || d.status === 'working');
  useEffect(() => {
    if (!busy) return;
    const t = window.setInterval(() => void reload(), 2000);
    return () => window.clearInterval(t);
  }, [busy, reload]);
  return { list, reload, busy };
}

/** Downloads in the tool bar: shown once the user has one; a badge counts the ready ones. */
export function DownloadsButton({
  load,
  remove = removeDownload,
}: {
  load?: () => Promise<BackgroundDownload[]>;
  remove?(id: string): Promise<void>;
}) {
  const { list, reload, busy } = useDownloads(load);
  if (!list || list.length === 0) return null;
  const ready = list.filter((d) => d.status === 'ready').length;
  const label = busy ? 'Downloads: being prepared' : `Downloads: ${ready} ready`;
  return (
    <DialogTrigger type="popover" placement="bottom end">
      <TooltipTrigger>
        <ActionButton isQuiet aria-label={label} UNSAFE_className="studio-assets__downloads-button">
          <DownloadIcon />
          {ready > 0 && <span className="studio-assets__downloads-badge">{ready}</span>}
        </ActionButton>
        <Tooltip>{label}</Tooltip>
      </TooltipTrigger>
      <Dialog>
        <Heading>Downloads</Heading>
        <Content>
          <ul className="studio-assets__downloads">
            {list.map((d) => (
              <li key={d.id}>
                <div className="studio-assets__downloads-text">
                  <strong>{d.name}</strong>
                  <small className={d.status === 'failed' ? 'studio-assets__dialog-error' : 'studio-assets__muted'}>{downloadStatus(d)}</small>
                  {(d.status === 'working' || d.status === 'queued') && (
                    <ProgressBar
                      aria-label={`Preparing ${d.name}`}
                      size="S"
                      width="100%"
                      isIndeterminate={d.status === 'queued' || d.files === 0}
                      value={d.files ? (100 * d.done) / d.files : 0}
                    />
                  )}
                </div>
                {d.status === 'ready' && (
                  <a className="studio-assets__linkbtn" href={archiveUrl(d.id)} download>
                    Download
                  </a>
                )}
                <button
                  type="button"
                  className="studio-assets__comment-remove"
                  aria-label={`Remove ${d.name}`}
                  onClick={() => void remove(d.id).then(reload)}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
          <p className="studio-assets__muted">Archives are kept for 7 days.</p>
        </Content>
      </Dialog>
    </DialogTrigger>
  );
}
