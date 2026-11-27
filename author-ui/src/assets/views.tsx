import Images from '@spectrum-icons/workflow/Images';
import { Cell, Column, Row, TableBody, TableHeader, TableView, type Selection } from '@adobe/react-spectrum';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Document from '@spectrum-icons/workflow/Document';
import Download from '@spectrum-icons/workflow/Download';
import Edit from '@spectrum-icons/workflow/Edit';
import FilePDF from '@spectrum-icons/workflow/FilePDF';
import FileTxt from '@spectrum-icons/workflow/FileTxt';
import Folder from '@spectrum-icons/workflow/Folder';
import ImageIcon from '@spectrum-icons/workflow/Image';
import Info from '@spectrum-icons/workflow/Info';
import AudioIcon from '@spectrum-icons/workflow/Audio';
import VideoOutline from '@spectrum-icons/workflow/VideoOutline';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactElement } from 'react';
import { endDrag, startDrag } from '../editor/overlay/dragPayload';
import type { AssetItem, DamClient, DamItem, FolderItem } from './api';
import { liveCopyLabel } from './liveCopy';
import { needsLicense, useAssetsConfig } from './assetsConfig';
import { dimensions, formatBytes, formatDuration, isNew, kindOf, relativeTime, typeLabel, type Kind } from './format';
import { RecordCard, RecordIcon } from '../shell/recordIcons';

export type ViewMode = 'card' | 'list' | 'column';

export interface ViewProps {
  items: DamItem[];
  selected: ReadonlySet<string>;
  /** Toggles one item; `range` extends from the last toggled (shift-click). */
  onToggle(path: string, range?: boolean): void;
  onSelectOnly(paths: string[]): void;
  onOpen(item: DamItem): void;
  onProperties(item: DamItem): void;
  /** Right-click on an item: the console opens its menu for it. */
  onContextMenu?(item: DamItem, e: MouseEvent): void;
}

const ICONS: Record<Kind, (props: { size: 'L' | 'XL' | 'S' }) => ReactElement> = {
  folder: (p) => <Folder size={p.size} />,
  fragment: (p) => <RecordIcon size={p.size} />,
  set: (p) => <Images size={p.size} />,
  image: (p) => <ImageIcon size={p.size} />,
  video: (p) => <VideoOutline size={p.size} />,
  audio: (p) => <AudioIcon size={p.size} />,
  pdf: (p) => <FilePDF size={p.size} />,
  document: (p) => <FileTxt size={p.size} />,
  archive: (p) => <Document size={p.size} />,
  file: (p) => <Document size={p.size} />,
};

export function KindIcon({ item, size = 'XL' }: { item: DamItem; size?: 'L' | 'XL' | 'S' }) {
  return ICONS[kindOf(item)]({ size });
}

/** The thumbnail, or the type icon when there is none or it fails to load. */
export function Thumb({ item, src, className }: { item: DamItem; src?: string | null; className?: string }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [src]);
  if (!src || failed) {
    return (
      <div className={`${className ?? ''} studio-assets__noimage`} aria-hidden>
        <KindIcon item={item} />
      </div>
    );
  }
  return <img className={className} src={src} alt="" loading="lazy" draggable={false} onError={() => setFailed(true)} />;
}

/** Days until an asset expires (0 = today), or null without a date or when it has expired. */
export function daysToExpiry(item: Pick<AssetItem, 'expires' | 'expired'>, now = Date.now()): number | null {
  if (!item.expires || item.expired) return null;
  const t = Date.parse(item.expires);
  return Number.isNaN(t) ? null : Math.max(0, Math.floor((t - now) / 86_400_000));
}

/** "Expired", "Expires today", "Expires in 3 days" (a week ahead), or null. */
export function expiryLabel(item: Pick<AssetItem, 'expires' | 'expired'>, now = Date.now()): string | null {
  if (item.expired) return 'Expired';
  const d = daysToExpiry(item, now);
  if (d === null || d > 7) return null;
  return d === 0 ? 'Expires today' : `Expires in ${d} day${d === 1 ? '' : 's'}`;
}

function StateBadge({ item }: { item: AssetItem }) {
  if (item.state === 'processing') return <span className="studio-assets__badge studio-assets__badge--busy">Processing</span>;
  if (item.state === 'failed') return <span className="studio-assets__badge studio-assets__badge--error">Failed</span>;
  const expiry = expiryLabel(item);
  if (expiry) return <span className={`studio-assets__badge ${item.expired ? 'studio-assets__badge--error' : 'studio-assets__badge--warn'}`}>{expiry}</span>;
  if (isNew(item)) return <span className="studio-assets__badge">New</span>;
  return null;
}

/** How well a picture fits a description, as a percentage ("under 1%" instead of a rounded 0). */
export function fitLabel(score: number): string {
  const pct = Math.round(score * 100);
  return pct < 1 ? 'under 1%' : `${pct}%`;
}

// --------------------------------------------------------------------------------------------- cards

export function CardView(props: ViewProps) {
  const selecting = props.selected.size > 0;
  return (
    <div className="studio-assets__cards" role="grid" aria-label="Media" aria-multiselectable>
      {props.items.map((item) => (
        <Card key={item.path} {...props} item={item} isSelected={props.selected.has(item.path)} selecting={selecting} />
      ))}
    </div>
  );
}

function Card({
  item,
  isSelected: selected,
  selecting,
  onToggle,
  onOpen,
  onProperties,
  onContextMenu,
}: ViewProps & { item: DamItem; isSelected: boolean; selecting: boolean }) {
  const assetsConfig = useAssetsConfig();
  const activate = (e: MouseEvent | KeyboardEvent) => {
    if (selecting || e.metaKey || e.ctrlKey || e.shiftKey) onToggle(item.path, e.shiftKey);
    else onOpen(item);
  };
  const stop = (fn: () => void) => (e: MouseEvent) => {
    e.stopPropagation();
    fn();
  };
  const folder = item.type === 'folder';
  // a file can be dragged onto a page in the editor beside it (split view), as from the editor's own side panel
  const drag = !folder && item.type === 'asset';
  return (
    <div
      role="gridcell"
      tabIndex={0}
      draggable={drag || undefined}
      onDragStart={drag ? (e) => startDrag(e, { kind: 'asset', asset: { path: item.path, name: item.name, title: item.title, mimeType: item.mimeType ?? '', size: item.size ?? undefined, width: item.width ?? undefined, height: item.height ?? undefined, thumbnail: item.thumbnail ?? undefined } }, item.path) : undefined}
      onDragEnd={drag ? endDrag : undefined}
      aria-selected={selected}
      aria-label={`${item.title}, ${typeLabel(item).toLowerCase()}`}
      className={`studio-assets__card${folder ? ' studio-assets__card--folder' : ''}${selected ? ' is-selected' : ''}`}
      onClick={activate}
      onContextMenu={onContextMenu && ((e) => onContextMenu(item, e))}
      onKeyDown={(e) => {
        if (e.key === 'Enter') activate(e);
        if (e.key === ' ') {
          e.preventDefault();
          onToggle(item.path);
        }
      }}
    >
      <div className="studio-assets__card-media">
        {folder ? (
          <FolderCollage folder={item} />
        ) : item.contentFragment ? (
          <FragmentPreview item={item} />
        ) : (
          <Thumb item={item} src={item.thumbnail} className="studio-assets__card-img" />
        )}
        {!folder && <StateBadge item={item} />}
        {item.liveCopy && (
          <span className={`studio-assets__badge studio-assets__badge--live${item.liveCopy === 'suspended' ? ' is-suspended' : ''}`}>{liveCopyLabel(item.liveCopy)}</span>
        )}
        {item.type === 'asset' && item.score !== undefined && (
          <span className="studio-assets__fit" title="How well the picture fits the description">
            {fitLabel(item.score)}
          </span>
        )}
        {!folder && item.duration ? (
          <span className="studio-assets__duration" aria-label={`Duration ${formatDuration(item.duration)}`}>
            {formatDuration(item.duration)}
          </span>
        ) : null}
        <div className={`studio-assets__quick${selected ? ' is-on' : ''}`}>
          <button type="button" className="studio-assets__quick-btn" aria-label={selected ? 'Deselect' : 'Select'} title="Select" onClick={stop(() => onToggle(item.path))}>
            <Checkmark size="S" />
          </button>
          {!selecting && item.type === 'asset' && item.original && !needsLicense(assetsConfig, item) && (
            <a className="studio-assets__quick-btn" aria-label="Download" title="Download" href={item.original} download={item.name} onClick={(e) => e.stopPropagation()}>
              <Download size="S" />
            </a>
          )}
          {!selecting && (
            <button type="button" className="studio-assets__quick-btn" aria-label="Properties" title="Properties" onClick={stop(() => onProperties(item))}>
              <Info size="S" />
            </button>
          )}
        </div>
      </div>
      {folder ? (
        <div className="studio-assets__card-folder-label">
          <div className="studio-assets__card-title">{item.title}</div>
          <div className="studio-assets__card-type">FOLDER</div>
        </div>
      ) : (
        <div className="studio-assets__card-body">
          <div className="studio-assets__card-title">{item.title}</div>
          <div className="studio-assets__card-type">{typeLabel(item)}</div>
          {!item.contentFragment && <div className="studio-assets__card-type">{item.name.toUpperCase()}</div>}
          <hr />
          <div className="studio-assets__card-meta">
            {item.modified && (
              <span>
                <Edit size="XS" /> {relativeTime(new Date(item.modified))}
              </span>
            )}
            <span>{formatBytes(item.size)}</span>
          </div>
          {dimensions(item) && <div className="studio-assets__card-meta">{dimensions(item)}</div>}
          {item.checkedOutBy && (
            <div className="studio-assets__card-meta studio-assets__lock" title={`Checked out by ${item.checkedOutBy}`}>
              <LockClosed size="XS" /> {item.checkedOutBy}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Content fragments have no image: AEM shows their title, name and the start of the description. */
function FragmentPreview({ item }: { item: AssetItem }) {
  return <RecordCard title={item.title} description={item.description} />;
}

function FolderCollage({ folder }: { folder: FolderItem }) {
  const previews = folder.previews ?? [];
  if (previews.length === 0) {
    return (
      <div className="studio-assets__collage studio-assets__collage--empty" aria-hidden>
        <Folder size="XL" />
      </div>
    );
  }
  return (
    <div className={`studio-assets__collage studio-assets__collage--${previews.length}`} aria-hidden>
      {previews.map((src) => (
        <img key={src} src={src} alt="" loading="lazy" draggable={false} />
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------- list

export function ListView({ items, selected, onSelectOnly, onOpen, onContextMenu }: ViewProps) {
  const onSelectionChange = (keys: Selection) => {
    onSelectOnly(keys === 'all' ? items.map((i) => i.path) : [...keys].map(String));
  };
  // the table is Spectrum's; a right-click lands on a row, whose key is the item's path
  const onMenu = (e: MouseEvent) => {
    if (!onContextMenu) return;
    const row = (e.target as HTMLElement).closest<HTMLElement>('[role="row"][data-key]');
    const item = row && items.find((i) => i.path === row.dataset.key);
    if (item) onContextMenu(item, e);
  };
  // in a narrow place (a small window, the inspector open) the list shows fewer columns whole instead of all of them cut
  const host = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(1200);
  useEffect(() => {
    // the host itself takes no box (display: contents): the room is its parent's
    const el = host.current?.parentElement;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => setWidth(el.clientWidth || 1200));
    ro.observe(el);
    setWidth(el.clientWidth || 1200);
    return () => ro.disconnect();
  }, []);
  const columns = listColumns(width);
  return (
    <div className="studio-assets__tablehost" onContextMenu={onMenu} ref={host}>
    <TableView
      key={columns.map((c) => c.key).join()}
      aria-label="Media"
      selectionMode="multiple"
      selectionStyle="checkbox"
      selectedKeys={new Set(selected)}
      onSelectionChange={onSelectionChange}
      onAction={(key) => {
        const item = items.find((i) => i.path === key);
        if (item) onOpen(item);
      }}
      density="spacious"
      overflowMode="truncate"
      UNSAFE_className="studio-assets__table"
      height="100%"
    >
      <TableHeader columns={columns}>
        {(c) => (
          <Column key={c.key} width={c.key === 'name' ? (columns.length > 3 ? '40%' : undefined) : undefined} minWidth={c.key === 'modified' ? 136 : c.key === 'name' ? 180 : undefined} align={c.key === 'size' ? 'end' : undefined}>
            {c.label}
          </Column>
        )}
      </TableHeader>
      <TableBody items={items}>
        {(item) => (
          <Row key={item.path}>
            {(columnKey) => {
              switch (columnKey) {
                case 'name':
                  return (
                    <Cell textValue={item.title}>
                      <div className="studio-assets__row-name">
                        <Thumb item={item} src={item.type === 'asset' ? item.thumbnail : item.previews?.[0]} className="studio-assets__row-thumb" />
                        <div>
                          <div className="studio-assets__row-title">{item.title}</div>
                          <div className="studio-assets__row-sub">
                            {item.name}
                            {item.type === 'asset' && item.score !== undefined ? ` · fits ${fitLabel(item.score)}` : ''}
                          </div>
                        </div>
                      </div>
                    </Cell>
                  );
                case 'type':
                  return <Cell>{typeLabel(item)}</Cell>;
                case 'dims':
                  return <Cell>{item.type === 'asset' ? dimensions(item) : ''}</Cell>;
                case 'size':
                  return <Cell>{item.type === 'asset' ? formatBytes(item.size) : ''}</Cell>;
                case 'status':
                  return <Cell>{item.type === 'asset' ? statusText(item) : liveCopyLabel(item.liveCopy) ?? ''}</Cell>;
                default:
                  return <Cell>{item.modified ? relativeTime(new Date(item.modified)) : ''}</Cell>;
              }
            }}
          </Row>
        )}
      </TableBody>
    </TableView>
    </div>
  );
}

const LIST_COLUMNS = [
  { key: 'name', label: 'Name', from: 0 },
  { key: 'type', label: 'Type', from: 560 },
  { key: 'dims', label: 'Dimensions', from: 900 },
  { key: 'size', label: 'Size', from: 420 },
  { key: 'status', label: 'Status', from: 760 },
  { key: 'modified', label: 'Modified', from: 660 },
] as const;

/** The list's columns for a width: the name always, the others as there is room for them whole. */
export function listColumns(width: number): Array<{ key: string; label: string }> {
  return LIST_COLUMNS.filter((c) => width >= c.from).map(({ key, label }) => ({ key, label }));
}

function statusText(item: AssetItem): string {
  const live = liveCopyLabel(item.liveCopy);
  const with_ = (s: string) => [s, live].filter(Boolean).join(' · ');
  if (item.checkedOutBy) return with_(`Checked out by ${item.checkedOutBy}`);
  if (item.state === 'processing') return with_('Processing');
  if (item.state === 'failed') return with_('Failed');
  const expiry = expiryLabel(item);
  if (expiry) return with_(expiry);
  return with_(isNew(item) ? 'New' : '');
}

// -------------------------------------------------------------------------------------------- column

interface ColumnViewProps extends ViewProps {
  client: DamClient;
  /** The folder being browsed; columns go from the DAM root down to it. */
  path: string;
  ancestors: string[];
  onNavigate(path: string): void;
}

/** Miller columns: one per folder on the path, then a preview of the selected asset. */
export function ColumnView({ client, path, ancestors, items, selected, onNavigate, onSelectOnly, onOpen, onContextMenu }: ColumnViewProps) {
  const [columns, setColumns] = useState<Record<string, DamItem[]>>({});
  const parents = ancestors.filter((p) => p !== path);

  useEffect(() => {
    let live = true;
    Promise.all(parents.map((p) => client.list(p).then((l) => [p, l.items] as const)))
      .then((entries) => live && setColumns(Object.fromEntries(entries)))
      .catch(() => live && setColumns({}));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, parents.join('|')]);

  const focused = items.find((i) => selected.has(i.path) && i.type === 'asset') as AssetItem | undefined;
  const lists = [...parents.map((p, i) => ({ folder: p, items: columns[p] ?? [], active: ancestors[i + 1] })), { folder: path, items, active: focused?.path }];

  return (
    <div className="studio-assets__columns" role="tree" aria-label="Media">
      {lists.map((col) => (
        <ul key={col.folder} className="studio-assets__column" role="group" aria-label={col.folder}>
          {col.items.map((item) => (
            <li key={item.path} role="treeitem" aria-selected={item.path === col.active || selected.has(item.path)} onContextMenu={onContextMenu && col.folder === path ? (e) => onContextMenu(item, e) : undefined}>
              <button
                type="button"
                className={`studio-assets__colitem${item.path === col.active || selected.has(item.path) ? ' is-active' : ''}`}
                onClick={() => (item.type === 'folder' ? onNavigate(item.path) : onSelectOnly([item.path]))}
                onDoubleClick={() => item.type === 'asset' && onOpen(item)}
              >
                <Thumb item={item} src={item.type === 'asset' ? item.thumbnail : null} className="studio-assets__colthumb" />
                <span className="studio-assets__coltitle">{item.title}</span>
                {item.type === 'folder' && <ChevronRight size="XS" />}
              </button>
            </li>
          ))}
          {col.items.length === 0 && <li className="studio-assets__colempty">Empty</li>}
        </ul>
      ))}
      {focused && (
        <aside className="studio-assets__colpreview" aria-label="Preview">
          <Thumb item={focused} src={focused.web ?? focused.thumbnail} className="studio-assets__colpreview-img" />
          <h3>{focused.title}</h3>
          <dl>
            <dt>Name</dt>
            <dd>{focused.name}</dd>
            <dt>Type</dt>
            <dd>{focused.mimeType}</dd>
            {dimensions(focused) && (
              <>
                <dt>Dimensions</dt>
                <dd>{dimensions(focused)}</dd>
              </>
            )}
            <dt>Size</dt>
            <dd>{formatBytes(focused.size)}</dd>
            {focused.modified && (
              <>
                <dt>Modified</dt>
                <dd>{new Date(focused.modified).toLocaleString()}</dd>
              </>
            )}
          </dl>
        </aside>
      )}
    </div>
  );
}
