import { ActionButton, Button, Text } from '@adobe/react-spectrum';
import Edit from '@spectrum-icons/workflow/Edit';
import Properties from '@spectrum-icons/workflow/Properties';
import { useEffect, useState, type ReactNode } from 'react';
import { HttpReplicationClient } from '../replication/api';
import type { SiteItem, StudioClient } from '../editor/api/StudioClient';
import type { MsmClient } from '../msm/MsmClient';
import { LiveCopyStatus } from '../msm/LiveCopyStatus';
import { PageVersions } from '../msm/PageVersions';
import { Segmented } from '../shell/Segmented';
import type { WorkflowClient } from '../workflow/api';
import { ReferencesRail } from './rail/SitesRail';
import { SiteInfo } from './rail/SiteInfo';
import { TimelineRail } from './rail/TimelineRail';
import type { SiteRailClient } from './rail/api';
import { ItemIcon, thumbUrl } from './views';
import './inspector.scss';
import { InspectorShell } from '../shell/InspectorShell';

/**
 * The inspector: a panel at the right of the console for what is selected — or, with nothing selected,
 * for where you are. Properties, Timeline, References and Site, as tabs; the same data the rails and the
 * column preview show, without opening a screen. Its state (open, tab) is remembered per browser.
 */
export type InspectorTab = 'properties' | 'timeline' | 'references' | 'site';

export const INSPECTOR_TABS: Array<{ key: InspectorTab; label: string; shortcut: string }> = [
  { key: 'properties', label: 'Properties', shortcut: 'alt+3' },
  { key: 'timeline', label: 'Timeline', shortcut: 'alt+4' },
  { key: 'references', label: 'References', shortcut: 'alt+5' },
  { key: 'site', label: 'Site', shortcut: 'alt+6' },
];

const KEY = 'studio.sites.inspector';

export function readInspector(): InspectorTab | null {
  try {
    const v = window.localStorage.getItem(KEY);
    // never chosen: the details open with the selection; 'closed' is the author's own choice
    if (v === null) return 'properties';
    return INSPECTOR_TABS.some((t) => t.key === v) ? (v as InspectorTab) : null;
  } catch {
    return 'properties';
  }
}

export function storeInspector(tab: InspectorTab | null) {
  try {
    if (tab) window.localStorage.setItem(KEY, tab);
    else window.localStorage.setItem(KEY, 'closed');
  } catch {
    /* per-viewer convenience only */
  }
}

interface Props {
  tab: InspectorTab;
  onTab(tab: InspectorTab): void;
  onClose(): void;
  client: StudioClient;
  siteRail: SiteRailClient;
  msm?: MsmClient;
  workflow?: WorkflowClient;
  /** where the console is */
  location: SiteItem | null;
  selection: SiteItem[];
  onNavigate(path: string): void;
  onChanged(): void;
  onEdit(item: SiteItem): void;
  onProperties(item: SiteItem): void;
  onPublish?(item: SiteItem): void;
  /** A dialog of the Timeline to open (Restore Version / Restore Tree from the selection bar). */
  timelineRequest?: { dialog: 'version' | 'restoreVersion' | 'restoreTree'; nonce: number } | null;
}

const KINDS: Record<string, string> = { page: 'Page', folder: 'Folder', site: 'Site' };
const XF_TYPE = 'cq/experience-fragments/components/experiencefragment';
const XF_TEMPLATE = '/libs/cq/experience-fragments/components/experiencefragment/template';
/** What the selected item is, in words: a page below Experience Fragments is a fragment or one of its variants. */
function kindOf(item: { type: string; path: string; resourceType?: string }): string {
  if (item.type === 'page' && item.path.startsWith('/content/experience-fragments/')) return item.resourceType === XF_TYPE ? 'Experience fragment' : 'Variant';
  if (item.type === 'page' && item.path.startsWith('/content/forms/')) return 'Form';
  return KINDS[item.type] ?? item.type;
}
/** The template by its title; the fragment itself has a fixed one without a title. */
function templateOf(item: { template?: string; templateTitle?: string }): string | undefined {
  return item.templateTitle ?? (item.template === XF_TEMPLATE ? 'Experience Fragment' : item.template);
}
const PUBLICATION = { published: 'Published', modified: 'Modified since published', unpublished: 'Unpublished' } as const;
const PREVIEW = { published: 'On Preview', modified: 'Modified since sent to Preview', unpublished: 'Removed from Preview' } as const;
const when = (iso?: string) => (iso ? ` · ${new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}` : '');
const replication = new HttpReplicationClient();

/** The page on the preview instance, once it was sent there (AEM as a Cloud Service's preview URL). */
export function OpenInPreview({ path, previewed }: { path: string; previewed?: string }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    setUrl(null);
    replication.status([path]).then(
      (s) => live && setUrl(s[0]?.preview?.published ? s[0].preview.url ?? null : null),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [path, previewed]);
  return url ? (
    <a className="studio-inspector__preview-link" href={url} target="_blank" rel="noreferrer">
      Open in Preview
    </a>
  ) : null;
}

export function Inspector({ tab, onTab, onClose, client, siteRail, msm, workflow, location, selection, onNavigate, onChanged, onEdit, onProperties, onPublish, timelineRequest }: Props) {
  const single = selection.length === 1 ? selection[0] : null;
  // the subject: the one selected item, else where you are
  const subject = single ?? (selection.length ? null : location);
  const page = subject?.type === 'page' ? { path: subject.path, title: subject.title } : null;

  const title = single ? single.title : selection.length ? `${selection.length} items selected` : location?.title ?? '';
  const sub = single ? single.path : selection.length ? '' : location?.path ?? '';

  let body: ReactNode;
  if (tab === 'properties') {
    body = subject ? <PropertiesPane client={client} msm={msm} item={subject} onChanged={onChanged} onEdit={onEdit} onProperties={onProperties} onPublish={onPublish} /> : <p className="studio-rail__empty">Select one item to see its properties.</p>;
  } else if (tab === 'timeline') {
    body = <TimelineRail rail={siteRail} page={page} onChanged={onChanged} workflow={workflow} request={timelineRequest} />;
  } else if (tab === 'references') {
    body = <ReferencesRail client={client} msm={msm} siteRail={siteRail} selection={subject ? [subject] : selection} onChanged={onChanged} />;
  } else {
    body = <SiteInfo rail={siteRail} path={subject?.path ?? location?.path ?? null} onNavigate={onNavigate} />;
  }

  return (
    <InspectorShell
      kind={subject ? kindOf(subject) : 'Selection'}
      subject={title}
      path={sub}
      onClose={onClose}
      className={`studio-inspector studio-inspector--${tab}`}
      pills={subject?.type === 'page' ? (
          <div className="studio-inspector__badges">
            <span className={`studio-pill studio-pill--${subject.publication ?? 'none'}`}>{subject.publication ? PUBLICATION[subject.publication] : 'Not published'}</span>
            {templateOf(subject) && <span className="studio-pill">{templateOf(subject)}</span>}
            {subject.language && <span className="studio-pill studio-pill--accent">{subject.language}</span>}
          </div>
        ) : undefined}
    >
      <div className="studio-inspector__tabs">
        <Segmented<InspectorTab> label="Inspector" items={INSPECTOR_TABS.map(({ key, label }) => ({ key, label }))} value={tab} onChange={onTab} />
      </div>
      <div className="studio-inspector__body">{body}</div>
    </InspectorShell>
  );
}

/** Properties at a glance, as the column preview shows them, plus the way to the full form. */
function PropertiesPane({ client, msm, item, onChanged, onEdit, onProperties, onPublish }: { client: StudioClient; msm?: MsmClient; item: SiteItem; onChanged(): void; onEdit(item: SiteItem): void; onProperties(item: SiteItem): void; onPublish?(item: SiteItem): void }) {
  const thumb = thumbUrl(client, item);
  const rows: Array<[string, string | undefined]> = [
    ['Title', item.title],
    ['Name', item.name],
    ['Template', templateOf(item)],
    ['Modified', item.lastModified ? new Date(item.lastModified).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : undefined],
    ['Modified by', item.lastModifiedBy],
    ['Language', item.language],
    ['Locked by', item.locked ? item.lockOwner : undefined],
    ['Publishing', item.publication ? PUBLICATION[item.publication] + when(item.lastPublished) : 'Not published'],
    ['Preview', item.preview ? PREVIEW[item.preview] + when(item.lastPreviewed) : undefined],
  ];
  return (
    <div className="studio-inspector__props">
      <div className="studio-preview__thumb">{thumb ? <img src={thumb} alt="" /> : <ItemIcon item={item} />}</div>
      {item.type === 'page' && (
        <div className="studio-inspector__actions">
          <Button variant="accent" onPress={() => onEdit(item)}>
            <Edit />
            <Text>Edit</Text>
          </Button>
          {onPublish && (
            <Button variant="secondary" onPress={() => onPublish(item)}>
              <Text>Publish</Text>
            </Button>
          )}
          <ActionButton isQuiet onPress={() => onProperties(item)}>
            <Properties />
            <Text>Properties</Text>
          </ActionButton>
        </div>
      )}
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
      {item.type === 'page' && (item.preview === 'published' || item.preview === 'modified') ? <OpenInPreview path={item.path} previewed={item.lastPreviewed} /> : null}
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
    </div>
  );
}
