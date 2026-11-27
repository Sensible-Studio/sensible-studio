import type { AssetItem, DamItem } from './api';

export { relativeTime } from '../editor/shell/TopBar';

/** 433.4 KB, 485 KB, 2.3 MB: 1024-based, one decimal, no trailing ".0", as the AEM cards show them. */
export function formatBytes(n?: number | null): string {
  if (n === undefined || n === null || n < 0) return '';
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n;
  let i = -1;
  do {
    v /= 1024;
    i++;
  } while (v >= 1024 && i < units.length - 1);
  return `${String(Math.round(v * 10) / 10)} ${units[i]}`;
}

export type Kind = 'folder' | 'fragment' | 'set' | 'image' | 'video' | 'audio' | 'pdf' | 'document' | 'archive' | 'file';

export function kindOf(item: DamItem): Kind {
  if (item.type === 'folder') return 'folder';
  if (item.contentFragment) return 'fragment';
  if (item.imageSet) return 'set';
  const m = item.mimeType ?? '';
  if (m.startsWith('image/')) return 'image';
  if (m.startsWith('video/')) return 'video';
  if (m.startsWith('audio/')) return 'audio';
  if (m === 'application/pdf') return 'pdf';
  if (m.includes('zip') || m.includes('compressed')) return 'archive';
  if (m.startsWith('text/') || m.includes('officedocument') || m.includes('msword') || m.includes('opendocument')) return 'document';
  // a short text file is told apart by nothing but its name once the type was looked for in its bytes
  if ((!m || m === 'application/octet-stream') && /\.(txt|md|csv|rtf|docx?|odt|xlsx?|pptx?)$/i.test(item.name ?? item.path ?? '')) return 'document';
  return 'file';
}

/** IMAGE, VIDEO, ... (the type line on a card). */
export function typeLabel(item: DamItem): string {
  const k = kindOf(item);
  return k === 'pdf' ? 'DOCUMENT' : k === 'fragment' ? 'CONTENT FRAGMENT' : k === 'set' ? (item.type === 'asset' && item.setKind === 'spin' ? 'SPIN SET' : 'IMAGE SET') : k.toUpperCase();
}

export function dimensions(item: AssetItem): string {
  return item.width && item.height ? `${item.width} x ${item.height}` : '';
}

/** 0:10, 4:05, 1:02:03: a video's length in whole seconds, as the browser's player shows it. */
export function formatDuration(seconds?: number | null): string {
  if (seconds === undefined || seconds === null || !Number.isFinite(seconds) || seconds < 0) return '';
  const t = Math.floor(seconds);
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = String(t % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}

/** Created in the last 24 hours: the blue "New" badge. */
export function isNew(item: AssetItem, now = Date.now()): boolean {
  if (!item.created) return false;
  const t = Date.parse(item.created);
  return Number.isFinite(t) && now - t < 24 * 3600 * 1000;
}

export function parentOf(path: string): string {
  return path.slice(0, path.lastIndexOf('/')) || '/';
}

export function nameOf(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1);
}

/** Where an item opens: folders and assets in this console, content fragments in their editor. */
export function fragmentEditorUrl(path: string): string {
  return `/editor.html${encodeURI(path)}`;
}
