import { Button, Text } from '@adobe/react-spectrum';
import Edit from '@spectrum-icons/workflow/Edit';
import Info from '@spectrum-icons/workflow/Info';
import { useEffect, useState, type ReactNode } from 'react';
import { Segmented } from '../shell/Segmented';
import type { AssetItem, DamClient, DamItem } from './api';
import { dimensions, formatBytes, relativeTime, typeLabel } from './format';
import { ReferenceList, useReferrers } from './references';
import { LiveCopyPane, liveCopyLabel } from './liveCopy';
import { Timeline } from './timeline';
import { Thumb } from './views';
import '../sites/inspector.scss';
import { InspectorShell } from '../shell/InspectorShell';

/**
 * The inspector of the Media console: Properties, Timeline and References for the selected item — or,
 * with nothing selected, for the folder you are in. The same panel as the Pages console's, with the
 * DAM's own panes; its state is remembered per browser.
 */
export type MediaInspectorTab = 'properties' | 'timeline' | 'references';

export const MEDIA_INSPECTOR_TABS: Array<{ key: MediaInspectorTab; label: string; shortcut: string }> = [
  { key: 'properties', label: 'Properties', shortcut: 'alt+3' },
  { key: 'timeline', label: 'Timeline', shortcut: 'alt+4' },
  { key: 'references', label: 'References', shortcut: 'alt+5' },
];

const KEY = 'studio.assets.inspector';

export function readMediaInspector(): MediaInspectorTab | null {
  try {
    const v = window.localStorage.getItem(KEY);
    // never chosen: the details open with the selection; 'closed' is the author's own choice
    if (v === null) return 'properties';
    return MEDIA_INSPECTOR_TABS.some((t) => t.key === v) ? (v as MediaInspectorTab) : null;
  } catch {
    return 'properties';
  }
}

export function storeMediaInspector(tab: MediaInspectorTab | null) {
  try {
    if (tab) window.localStorage.setItem(KEY, tab);
    else window.localStorage.setItem(KEY, 'closed');
  } catch {
    /* per-viewer convenience only */
  }
}

interface Props {
  tab: MediaInspectorTab;
  onTab(tab: MediaInspectorTab): void;
  onClose(): void;
  client: DamClient;
  /** the folder being browsed */
  location: { path: string; title: string } | null;
  selection: DamItem[];
  onChanged(): void;
  onOpen(item: DamItem): void;
  onProperties(item: DamItem): void;
  /** opens a folder or file by its path (a live copy's source, a source's live copies) */
  onOpenPath?(path: string): void;
}

export function MediaInspector({ tab, onTab, onClose, client, location, selection, onChanged, onOpen, onProperties, onOpenPath }: Props) {
  const single = selection.length === 1 ? selection[0] : null;
  const asset = single?.type === 'asset' ? single : null;
  const title = single ? single.title : selection.length ? `${selection.length} items selected` : location?.title ?? '';
  const sub = single ? single.path : selection.length ? '' : location?.path ?? '';

  let body: ReactNode;
  if (tab === 'properties') {
    body = single ? (
      <PropertiesPane client={client} item={single} onOpen={onOpen} onProperties={onProperties} />
    ) : (
      <p className="studio-rail__empty">{selection.length ? 'Select one item to see its properties.' : location ? `The folder ${location.title}. Select a file or folder.` : ''}</p>
    );
  } else if (tab === 'timeline') {
    body = asset ? <Timeline key={asset.path} client={client} path={asset.path} onRestored={onChanged} /> : <p className="studio-rail__empty">Select one file to see its timeline and versions.</p>;
  } else {
    body = selection.length ? (
      <>
        <ReferencesPane client={client} paths={selection.map((i) => i.path)} />
        {single && <LiveCopyPane key={single.path} client={client} item={single} onChanged={onChanged} onOpen={onOpenPath} />}
      </>
    ) : (
      <p className="studio-rail__empty">Select an item to see which pages use it.</p>
    );
  }

  return (
    <InspectorShell
      kind={single ? (single.type === 'folder' ? 'Folder' : 'Media') : 'Selection'}
      subject={title}
      path={sub}
      onClose={onClose}
      className={`studio-inspector studio-inspector--${tab}`}
    >
      <div className="studio-inspector__tabs">
        <Segmented<MediaInspectorTab> label="Inspector" items={MEDIA_INSPECTOR_TABS.map(({ key, label }) => ({ key, label }))} value={tab} onChange={onTab} />
      </div>
      <div className="studio-inspector__body">{body}</div>
    </InspectorShell>
  );
}

function PropertiesPane({ client, item, onOpen, onProperties }: { client: DamClient; item: DamItem; onOpen(item: DamItem): void; onProperties(item: DamItem): void }) {
  // the descriptive metadata (title, description, tags) comes with the asset's details
  const [meta, setMeta] = useState<Record<string, unknown> | null>(null);
  useEffect(() => {
    let live = true;
    setMeta(null);
    if (item.type !== 'asset') return;
    client.asset(item.path).then(
      (a) => live && setMeta(a.metadata ?? {}),
      () => live && setMeta({}),
    );
    return () => {
      live = false;
    };
  }, [client, item.path, item.type]);
  const asset = item.type === 'asset' ? (item as AssetItem) : null;
  const tags = meta && Array.isArray(meta['cq:tags']) ? (meta['cq:tags'] as string[]) : [];
  const description = (meta?.['dc:description'] as string | undefined) ?? asset?.description ?? undefined;
  const rows: Array<[string, ReactNode]> = [
    ['Title', item.title],
    ['Name', item.name],
    ['Type', typeLabel(item)],
    ['Dimensions', asset ? dimensions(asset) : ''],
    ['Size', asset?.size ? formatBytes(asset.size) : ''],
    ['Modified', item.modified ? `${relativeTime(new Date(item.modified))}` : ''],
    ['Description', description ?? ''],
    ['Tags', tags.length ? tags.join(', ') : ''],
    ['Checked out by', asset?.checkedOutBy ?? ''],
    ['Processing', asset?.state && asset.state !== 'processed' ? asset.state : ''],
    ['Live copy', liveCopyLabel(item.liveCopy) ? `${item.liveCopy === 'root' ? 'Root' : item.liveCopy === 'suspended' ? 'Suspended' : 'Follows its source'} (see References)` : ''],
  ];
  return (
    <div className="studio-inspector__props">
      <div className="studio-preview__thumb">
        <Thumb item={item} src={asset ? asset.web ?? asset.thumbnail : item.type === 'folder' ? item.previews?.[0] : null} className="studio-inspector__thumb" />
      </div>
      <div className="studio-inspector__actions">
        <Button variant="accent" onPress={() => onOpen(item)}>
          <Edit />
          <Text>{item.type === 'folder' ? 'Open' : asset?.contentFragment ? 'Edit' : 'Details'}</Text>
        </Button>
        <Button variant="secondary" onPress={() => onProperties(item)}>
          <Info />
          <Text>All properties</Text>
        </Button>
      </div>
      <dl className="studio-preview__meta">
        {rows
          .filter(([, v]) => v !== '' && v !== null && v !== undefined)
          .map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
      </dl>
    </div>
  );
}

function ReferencesPane({ client, paths }: { client: DamClient; paths: string[] }) {
  const { referrers, error } = useReferrers(client, paths);
  return (
    <section aria-label="References">
      <h3 className="studio-rail__heading">References{referrers ? ` (${referrers.length})` : ''}</h3>
      <ReferenceList referrers={referrers} error={error} max={200} />
    </section>
  );
}
