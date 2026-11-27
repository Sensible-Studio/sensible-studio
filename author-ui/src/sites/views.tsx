import { ActionButton, Checkbox, Item, Menu, MenuTrigger, Text } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Folder from '@spectrum-icons/workflow/Folder';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import ViewColumn from '@spectrum-icons/workflow/ViewColumn';
import ViewGrid from '@spectrum-icons/workflow/ViewGrid';
import ViewList from '@spectrum-icons/workflow/ViewList';
import { useEffect, useRef, type Key, type ReactNode, type MouseEvent } from 'react';
import type { StudioClient, SiteItem } from '../editor/api/StudioClient';
import { relativeTime } from '../editor/shell/TopBar';
import { ReferenceList, useReferences } from './References';
import { LiveCopyBadge } from '../msm/LiveCopyBadge';
import { LiveCopyStatus } from '../msm/LiveCopyStatus';
import { PageVersions } from '../msm/PageVersions';
import type { MsmClient } from '../msm/MsmClient';
import { RecordIcon } from '../shell/recordIcons';
import { PageIcon } from '../shell/pageIcons';

/**
 * What the three views show: Sites' pages and folders (`SiteItem`) or anything shaped like them — a
 * content fragment is a `fragment` whose `templateTitle` is its model. The consoles that list other
 * things map their items onto this.
 */
export type BrowserItem = Omit<SiteItem, 'type'> & { type: SiteItem['type'] | 'fragment' };

/** A level of the tree: the item, the trail down to it and its children. */
export interface BrowserListing<T extends BrowserItem = BrowserItem> {
  item: T;
  trail: Array<{ path: string; title: string }>;
  children: T[];
}

/** Words the views use that differ per console: what the "template" column is called, what an empty place says. */
export interface ViewLabels {
  kind?: string;
  empty?: string;
  /** what a folder-like item is called on a card ("Namespace") */
  folder?: string;
}

interface ViewProps<T extends BrowserItem> {
  client: StudioClient;
  selected: string[];
  onOpen(item: T): void;
  onToggle(item: T): void;
  /** Right-click on an item: the console opens its menu for it. */
  onContextMenu?(item: T, e: MouseEvent): void;
  labels?: ViewLabels;
  /** The item's icon when the console has its own (tags, say); folder / record / page otherwise. */
  renderIcon?(item: T): ReactNode;
  /** A small mark after the title (the Forms console's submission count, say). */
  renderBadge?(item: T): ReactNode;
}

export type ViewMode = 'column' | 'list' | 'card';

/** The console's view, remembered per console in localStorage under `key`. */
export function readView(key: string): ViewMode {
  try {
    const v = window.localStorage.getItem(key);
    return v === 'list' || v === 'card' ? v : 'column';
  } catch {
    return 'column';
  }
}

export function storeView(key: string, view: ViewMode): void {
  try {
    window.localStorage.setItem(key, view);
  } catch {
    /* private window */
  }
}

/** The Column / Card / List switch at the end of a console's bar — the same on every console. */
export function ViewSwitch({ view, onChange }: { view: ViewMode; onChange(view: ViewMode): void }) {
  return (
    <MenuTrigger align="end">
      <ActionButton isQuiet aria-label="Switch display format">
        {view === 'column' ? <ViewColumn /> : view === 'list' ? <ViewList /> : <ViewGrid />}
        <ChevronDown size="S" />
      </ActionButton>
      <Menu selectionMode="single" selectedKeys={[view]} onAction={(k: Key) => onChange(k as ViewMode)}>
        <Item key="column" textValue="Column View">
          <ViewColumn />
          <Text>Column View</Text>
        </Item>
        <Item key="card" textValue="Card View">
          <ViewGrid />
          <Text>Card View</Text>
        </Item>
        <Item key="list" textValue="List View">
          <ViewList />
          <Text>List View</Text>
        </Item>
      </Menu>
    </MenuTrigger>
  );
}

/** URL of an item's thumbnail: a media file's thumbnail rendition, or the template's thumbnail file. */
export function thumbUrl(client: StudioClient, item: { thumbnail?: string }): string | null {
  const t = item.thumbnail;
  if (!t) return null;
  return client.assetUrl(t.startsWith('/content/dam/') ? `${t}/jcr:content/renditions/cq5dam.thumbnail.319.319.png` : t);
}

export function ItemIcon({ item }: { item: BrowserItem }) {
  return item.type === 'folder' ? <Folder size="S" /> : item.type === 'fragment' ? <RecordIcon size="S" /> : <PageIcon size="S" />;
}

function when(iso?: string) {
  return iso ? relativeTime(new Date(iso)) : '';
}

// ------------------------------------------------------------------------------------------ column view

interface ColumnProps<T extends BrowserItem> extends ViewProps<T> {
  listings: Map<string, BrowserListing<T>>;
  trail: Array<{ path: string; title: string }>;
  current: BrowserListing<T>;
  /** MSM status in the preview (live copy / blueprint) */
  msm?: MsmClient;
  onChanged?(): void;
  /** The preview at the end of the columns; Sites' (thumbnail, meta, references, MSM, versions) when not given. */
  renderPreview?(item: T): ReactNode;
}

/** One column per level from the root to the current item; the item's preview at the end. */
export function ColumnView<T extends BrowserItem>({ client, msm, onChanged, listings, trail, current, selected, onOpen, onToggle, onContextMenu, renderPreview, renderIcon, renderBadge, labels }: ColumnProps<T>) {
  const scroller = useRef<HTMLDivElement>(null);
  const preview =
    selected.length === 1
      ? current.children.find((c) => c.path === selected[0]) ?? null
      : current.item.type !== 'folder' && !current.item.hasChildren
        ? current.item
        : null;

  // keep the deepest column in view
  useEffect(() => {
    const el = scroller.current;
    if (el) el.scrollLeft = el.scrollWidth;
  }, [current.item.path, preview?.path]);

  const columns = trail.filter((t) => listings.get(t.path)?.children.length || t.path === current.item.path);

  return (
    <div className="studio-columns" ref={scroller}>
      {columns.map((t) => {
        const listing = listings.get(t.path);
        if (!listing || listing.children.length === 0) return null;
        const next = trail[trail.findIndex((x) => x.path === t.path) + 1]?.path;
        return (
          <ul key={t.path} className="studio-column" aria-label={t.title}>
            {listing.children.map((item) => {
              const active = item.path === next;
              // where you are: the end of the path, or the one item the preview shows
              const isCurrent = item.path === current.item.path || item.path === preview?.path;
              const isSelected = selected.includes(item.path);
              return (
                <li key={item.path} className={`studio-column__item${active ? ' is-active' : ''}${isCurrent ? ' is-current' : ''}${isSelected ? ' is-selected' : ''}`} onContextMenu={onContextMenu && ((e) => onContextMenu(item, e))}>
                  <span className="studio-column__check" onClick={(e) => e.stopPropagation()}>
                    <Checkbox aria-label={`Select ${item.title}`} isSelected={isSelected} onChange={() => onToggle(item)} isEmphasized />
                  </span>
                  <button type="button" className="studio-column__open" onClick={() => onOpen(item)} title={item.path}>
                    <span className="studio-column__thumb">{renderIcon ? renderIcon(item) : <ItemIcon item={item} />}</span>
                    <span className="studio-column__text">
                      <span className="studio-column__title">
                        {item.title}
                        {item.locked && <LockClosed size="XS" UNSAFE_className="studio-lock" />}
                        <LiveCopyBadge state={item.liveCopy} root={item.liveCopyRoot} />
                        {renderBadge?.(item)}
                      </span>
                      <span className="studio-column__name">{item.name}</span>
                    </span>
                    {item.hasChildren && <ChevronRight size="S" UNSAFE_className="studio-column__more" />}
                  </button>
                </li>
              );
            })}
          </ul>
        );
      })}
      {/* an empty folder says so: a blank column reads as still loading */}
      {current.item.type === 'folder' && current.children.length === 0 && (
        <p className="studio-column studio-column--empty">{labels?.empty ?? 'Nothing here yet.'}</p>
      )}
      {preview && (renderPreview ? renderPreview(preview) : <Preview client={client} msm={msm} onChanged={onChanged} item={preview} renderIcon={renderIcon} />)}
    </div>
  );
}

export function Preview<T extends BrowserItem>({ client, msm, onChanged, item, renderIcon }: { client: StudioClient; msm?: MsmClient; onChanged?(): void; item: T; renderIcon?(item: T): ReactNode }) {
  const thumb = thumbUrl(client, item);
  const refs = useReferences(client, [item.path]);
  const rows: Array<[string, string | undefined]> = [
    ['Title', item.title],
    ['Name', item.name],
    ['Template', item.templateTitle ?? item.template],
    ['Modified', item.lastModified ? new Date(item.lastModified).toLocaleDateString(undefined, { dateStyle: 'medium' }) : undefined],
    ['Modified By', item.lastModifiedBy],
    ['Language', item.language],
    ['Locked by', item.locked ? item.lockOwner : undefined],
  ];
  return (
    <aside className="studio-preview" aria-label={`Preview of ${item.title}`}>
      <div className="studio-preview__thumb">{thumb ? <img src={thumb} alt="" /> : renderIcon ? renderIcon(item) : <ItemIcon item={item} />}</div>
      <dl className="studio-preview__meta">
        {rows
          .filter(([, v]) => v)
          .map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
      </dl>
      <section className="studio-preview__refs" aria-label="References">
        <h3>
          References
          {refs.pages ? ` (${refs.pages.length})` : ''}
        </h3>
        <ReferenceList pages={refs.pages} error={refs.error} />
      </section>
      {msm && item.type === 'page' && (
        <section className="studio-preview__msm" aria-label="Linked copy and Source">
          <LiveCopyStatus key={item.path} msm={msm} path={item.path} onChanged={onChanged} />
        </section>
      )}
      {msm && item.type === 'page' && (
        <section className="studio-preview__msm" aria-label="Versions">
          <h3 className="studio-preview__heading">Versions</h3>
          <PageVersions key={item.path} msm={msm} path={item.path} onRestored={onChanged} />
        </section>
      )}
    </aside>
  );
}

// ------------------------------------------------------------------------------------------ list view

export function ListView<T extends BrowserItem>({ client, items, selected, onOpen, onToggle, onContextMenu, labels, renderIcon, renderBadge }: ViewProps<T> & { items: T[] }) {
  if (!items.length) return <p className="studio-sites__empty">{labels?.empty ?? 'This location has no pages yet.'}</p>;
  return (
    <div className="studio-listview">
      <table className="studio-table">
        <thead>
          <tr>
            <th aria-label="Select" />
            <th>Title</th>
            <th>Name</th>
            <th>{labels?.kind ?? 'Template'}</th>
            <th>Modified</th>
            <th>Modified By</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const isSelected = selected.includes(item.path);
            const thumb = thumbUrl(client, item);
            return (
              <tr key={item.path} className={isSelected ? 'is-selected' : undefined} onContextMenu={onContextMenu && ((e) => onContextMenu(item, e))}>
                <td className="studio-listview__check">
                  <Checkbox aria-label={`Select ${item.title}`} isSelected={isSelected} onChange={() => onToggle(item)} isEmphasized />
                </td>
                <td>
                  <button type="button" className="studio-listview__title" onClick={() => onOpen(item)}>
                    <span className="studio-listview__thumb">{thumb ? <img src={thumb} alt="" loading="lazy" /> : renderIcon ? renderIcon(item) : <ItemIcon item={item} />}</span>
                    {item.title}
                    {item.locked && <LockClosed size="XS" UNSAFE_className="studio-lock" />}
                    <LiveCopyBadge state={item.liveCopy} root={item.liveCopyRoot} />
                    {renderBadge?.(item)}
                  </button>
                </td>
                <td className="studio-listview__muted">{item.name}</td>
                <td className="studio-listview__muted">{item.templateTitle ?? ''}</td>
                <td className="studio-listview__muted">{when(item.lastModified)}</td>
                <td className="studio-listview__muted">{item.lastModifiedBy ?? ''}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ------------------------------------------------------------------------------------------ card view

export function CardView<T extends BrowserItem>({ client, items, selected, onOpen, onToggle, onContextMenu, labels, renderIcon, renderBadge }: ViewProps<T> & { items: T[] }) {
  if (!items.length) return <p className="studio-sites__empty">{labels?.empty ?? 'This location has no pages yet.'}</p>;
  return (
    <ul className="studio-cards">
      {items.map((item) => {
        const isSelected = selected.includes(item.path);
        const thumb = thumbUrl(client, item);
        return (
          <li key={item.path} className={`studio-card${isSelected ? ' is-selected' : ''}`} onContextMenu={onContextMenu && ((e) => onContextMenu(item, e))}>
            <button type="button" className="studio-card__open" onClick={() => onOpen(item)} title={item.path}>
              <span className="studio-card__thumb">{thumb ? <img src={thumb} alt="" loading="lazy" /> : renderIcon ? renderIcon(item) : <ItemIcon item={item} />}</span>
              <span className="studio-card__body">
                <span className="studio-card__type">{item.type === 'folder' ? labels?.folder ?? 'Folder' : item.templateTitle ?? (item.type === 'fragment' ? labels?.kind ?? 'Content Fragment' : 'Page')}</span>
                <span className="studio-card__title">
                  {item.title}
                  {item.locked && <LockClosed size="XS" UNSAFE_className="studio-lock" />}
                  <LiveCopyBadge state={item.liveCopy} root={item.liveCopyRoot} />
                  {renderBadge?.(item)}
                </span>
                <span className="studio-card__meta">{when(item.lastModified)}</span>
              </span>
            </button>
            <span className="studio-card__check">
              <Checkbox aria-label={`Select ${item.title}`} isSelected={isSelected} onChange={() => onToggle(item)} isEmphasized />
            </span>
          </li>
        );
      })}
    </ul>
  );
}
