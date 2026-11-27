import { DialogContainer, ProgressCircle, ToastQueue } from '@adobe/react-spectrum';
import { Button } from '@adobe/react-spectrum';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  createDefaultRegistry,
  GraniteForm,
  useGraniteForm,
  withoutInherited,
  type ContentNode,
  type DialogResponse,
  type GraniteNode,
  type PostEntry,
} from '../granite';
import type { GraniteEnv } from '../granite/registry';
import type { StudioClient } from '../editor/api/StudioClient';
import { studioGraniteEnv } from '../editor/dialogs/graniteEnv';
import { HttpMsmClient, type MsmClient } from '../msm/MsmClient';
import type { MsmStatus } from '../msm/types';
import { usePageInheritance } from '../msm/usePageInheritance';
import { HttpTagsClient, TAG_ROOT, type TagsClient } from '../tags/api';
import { TagPicker } from '../tags/TagPicker';
import { HttpPagePermissionsClient, type PagePermissionsClient } from './api';
import { ExtraTabsContext, PendingChangesContext, type ExtraTab, type PendingChange } from './context';
import { CloudServicesTab, HttpCloudServicesClient, type CloudServicesClient } from './CloudServicesTab';
import { BlueprintTab, LiveCopyTab } from './MsmTabs';
import { PagePropsTabs } from './PagePropsTabs';
import { PermissionsTab } from './PermissionsTab';
import './pageprops.scss';
import { useTab } from '../shell/ShellContext';
import { ScreenFrame } from '../shell/ScreenFrame';
import { StudioConfirmDialog } from '../shell/StudioDialog';

interface Props {
  client: StudioClient;
  page: string;
  msm?: MsmClient;
  permissions?: PagePermissionsClient;
  tags?: TagsClient;
  cloud?: CloudServicesClient;
}

interface Loaded {
  title: string;
  dialog: DialogResponse & { root: GraniteNode };
  content: ContentNode;
}

/** `ns:a/b` for the tag at `/content/cq:tags/ns/a/b`. */
/**
 * What a page without a title is called: its name, except for the page a template keeps for the properties of
 * new pages, whose name ("initial") says nothing to an author.
 */
function nameOf(page: string): string {
  const template = /\/settings\/wcm\/templates\/([^/]+)\/initial$/.exec(page);
  return template ? `New pages of ${template[1]}` : page.slice(page.lastIndexOf('/') + 1);
}

export function tagIdOf(path: string): string {
  const rest = path.slice(TAG_ROOT.length + 1);
  const i = rest.indexOf('/');
  return i < 0 ? `${rest}:` : `${rest.slice(0, i)}:${rest.slice(i + 1)}`;
}

/** `/content/cq:tags/ns/a/b` for `ns:a/b` (no namespace: `default`). */
export function tagPathOf(id: string): string {
  const i = id.indexOf(':');
  const ns = i < 0 ? 'default' : id.slice(0, i);
  const rest = i < 0 ? id : id.slice(i + 1);
  return `${TAG_ROOT}/${ns}${rest ? `/${rest}` : ''}`;
}

/** Where Cancel and Save & Close go: back where the author came from, else the page's folder in Sites. */
export function returnUrl(page: string, referrer = document.referrer): string {
  try {
    const r = new URL(referrer);
    if (r.origin === window.location.origin && !r.pathname.includes('/sites/properties.html')) return r.pathname + r.search + r.hash;
  } catch {
    // no referrer
  }
  return `/sites.html${page.slice(0, page.lastIndexOf('/'))}`;
}

/**
 * The page properties, at AEM's URL (`/mnt/overlay/wcm/core/content/sites/properties.html?item=<page>`) and with its
 * layout: the page title with Cancel and Save & Close, centered tabs, the fields in a centered column. The tabs are
 * those of the page component's dialog (Basic, Advanced, Images, …) and Sensible Studio's own: Cloud Services, Permissions, and
 * Blueprint or Live Copy for pages in MSM.
 */
export function PagePropertiesApp({ client, page, msm: msmIn, permissions: permsIn, tags: tagsIn, cloud: cloudIn }: Props) {
  const cloud = useMemo(() => cloudIn ?? new HttpCloudServicesClient(), [cloudIn]);
  // In the shell the form is an entry of the tab: Cancel and Save & Close go back to the entry before it.
  const tab = useTab();
  const exit = () => (tab ? tab.leave({ kind: 'sites', path: page.slice(0, page.lastIndexOf('/')) }) : (window.location.href = returnUrl(page)));
  const msm = useMemo(() => msmIn ?? new HttpMsmClient(), [msmIn]);
  const permissions = useMemo(() => permsIn ?? new HttpPagePermissionsClient(), [permsIn]);
  const tags = useMemo(() => tagsIn ?? new HttpTagsClient(), [tagsIn]);
  const contentPath = `${page}/jcr:content`;
  const [version, setVersion] = useState(0);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<MsmStatus | null | undefined>(undefined);

  useEffect(() => {
    let live = true;
    (async () => {
      const content = await client.getContent(contentPath);
      const rt = typeof content['sling:resourceType'] === 'string' ? (content['sling:resourceType'] as string) : '';
      if (!rt) throw new Error(`${page} is not a page with content`);
      const dialog = await client.getDialog(rt, 'cq:dialog', contentPath);
      if (!dialog.root) throw new Error(`${rt} has no page properties dialog. ${dialog.warnings.join(' ')}`.trim());
      const title = (content['jcr:title'] as string) || nameOf(page);
      if (live) setLoaded({ title, dialog: dialog as Loaded['dialog'], content });
    })().catch((e: unknown) => live && setError(e instanceof Error ? e.message : String(e)));
    msm.status(page).then(
      (s) => live && setStatus(s),
      () => live && setStatus(null),
    );
    return () => {
      live = false;
    };
  }, [client, msm, page, contentPath, version]);

  const tabRef = useRef(tab);
  tabRef.current = tab;
  useEffect(() => {
    const title = loaded?.title ?? nameOf(page);
    if (tabRef.current) tabRef.current.setTitle(`Properties · ${title}`);
    else document.title = `${title} | Sensible Studio Pages | Page Properties`;
  }, [loaded, page]);

  const reload = useCallback(() => {
    setStatus(undefined);
    setVersion((v) => v + 1);
  }, []);

  if (error) {
    return (
      <div className="studio-pp">
        <Header title={page.slice(page.lastIndexOf('/') + 1)} onClose={exit} />
        <p className="studio-pp-error studio-pp-column">{error}</p>
      </div>
    );
  }
  if (!loaded || status === undefined) {
    return (
      <div className="studio-pp">
        <Header title={loaded?.title ?? page.slice(page.lastIndexOf('/') + 1)} onClose={exit} />
        <div className="studio-pp-loading">
          <ProgressCircle isIndeterminate aria-label="Loading page properties" />
        </div>
      </div>
    );
  }
  return (
    <PropertiesForm
      key={version}
      client={client}
      msm={msm}
      permissions={permissions}
      tags={tags}
      cloud={cloud}
      page={page}
      loaded={loaded}
      status={status}
      onReload={reload}
      onExit={exit}
    />
  );
}

function PropertiesForm({
  client,
  msm,
  permissions,
  tags,
  cloud,
  page,
  loaded,
  status,
  onReload,
  onExit: exit,
}: {
  client: StudioClient;
  msm: MsmClient;
  permissions: PagePermissionsClient;
  tags: TagsClient;
  cloud: CloudServicesClient;
  page: string;
  loaded: Loaded;
  status: MsmStatus | null;
  onReload(): void;
  /** Cancel and Save & Close: back where the author came from */
  onExit(): void;
}) {
  const contentPath = `${page}/jcr:content`;
  const registry = useMemo(() => createDefaultRegistry().registerComponent('granite/ui/components/coral/foundation/tabs', PagePropsTabs), []);
  const staged = useRef(new Map<string, File>());
  const env: GraniteEnv = useMemo(() => {
    const base = studioGraniteEnv(client, contentPath);
    const inheritedCache = new Map<string, Promise<{ value: string; from: string } | null>>();
    return {
      ...base,
      tagTitle: async (id: string) => {
        const listing = await tags.children(tagPathOf(id));
        const titles = [...listing.trail.filter((t) => t.path !== TAG_ROOT).map((t) => t.title)];
        if (!listing.trail.some((t) => t.path === listing.item.path)) titles.push(listing.item.title);
        const [ns, ...rest] = titles;
        return rest.length ? `${ns} : ${rest.join(' / ')}` : (ns ?? id);
      },
      TagPicker: ({ onPick, onCancel }) => (
        <DialogContainer onDismiss={onCancel}>
          <TagPicker client={tags} title="Select Tags" start={TAG_ROOT} onPick={(path) => onPick(tagIdOf(path))} />
        </DialogContainer>
      ),
      inheritedValue: (pagePath: string, property: string) => {
        const key = `${pagePath}|${property}`;
        if (!inheritedCache.has(key)) {
          inheritedCache.set(
            key,
            (async () => {
              for (let p = pagePath; p.split('/').length > 2; p = p.slice(0, p.lastIndexOf('/'))) {
                const res = await fetch(`${encodeURI(p)}/jcr:content.json`, { credentials: 'same-origin' });
                if (!res.ok) continue;
                const json = (await res.json()) as Record<string, unknown>;
                const v = json[property];
                if (typeof v === 'string' && v) return { value: v, from: p };
              }
              return null;
            })(),
          );
        }
        return inheritedCache.get(key)!;
      },
      stageFile: (name: string, file: File | null) => {
        if (file) staged.current.set(name, file);
        else staged.current.delete(name);
      },
      fileUrl: (path: string) => `${encodeURI(path)}?ck=${Date.now()}`,
    };
  }, [client, contentPath, tags]);

  const form = useGraniteForm(loaded.dialog.root, loaded.content, registry, env);
  const inheritance = usePageInheritance(msm, status?.inLiveCopy ? contentPath : null);
  const pending = useRef(new Map<string, PendingChange>());
  const registerPending = useCallback((key: string, change: PendingChange | null) => {
    if (change) pending.current.set(key, change);
    else pending.current.delete(key);
  }, []);
  const [showErrors, setShowErrors] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  /** The Discard dialog is open: what to do with the answer (Cancel leaves; the shell's guard resolves). */
  const [confirmDiscard, setConfirmDiscard] = useState<((discard: boolean) => void) | null>(null);

  const extras: ExtraTab[] = useMemo(() => {
    const out: ExtraTab[] = [
      { name: 'studio-cloudservices', title: 'Cloud Services', after: 'thumbnail', content: <CloudServicesTab client={cloud} page={page} /> },
      { name: 'studio-permissions', title: 'Permissions', after: 'thumbnail', content: <PermissionsTab client={permissions} page={page} /> },
    ];
    if (status?.isSource) {
      out.push({ name: 'studio-blueprint', title: 'Source', after: 'thumbnail', content: <BlueprintTab msm={msm} page={page} status={status} onChanged={onReload} /> });
    }
    if (status?.inLiveCopy && status.relationship) {
      out.push({ name: 'studio-livecopy', title: 'Linked copy', after: 'thumbnail', content: <LiveCopyTab msm={msm} page={page} status={status} onChanged={onReload} /> });
    }
    return out;
  }, [permissions, cloud, msm, page, status, onReload]);

  const dirty = () => form.isDirty() || staged.current.size > 0 || [...pending.current.values()].some((p) => p.isDirty());

  const save = async (close: boolean) => {
    if (inheritance === undefined) return;
    const errors = form.validate();
    if (errors.length) {
      setShowErrors(true);
      setFailure(`${errors.length} field${errors.length > 1 ? 's need' : ' needs'} attention: ${errors.map((e) => e.label ?? e.name).join(', ')}`);
      return;
    }
    setSaving(true);
    setFailure(null);
    try {
      const entries: PostEntry[] = withoutInherited(form.serialize(), inheritance);
      await client.post(contentPath, entries);
      for (const [name, file] of staged.current) {
        const body = new FormData();
        body.append('_charset_', 'UTF-8');
        body.append(name, file, file.name);
        const res = await fetch(encodeURI(contentPath), { method: 'POST', credentials: 'same-origin', body, headers: { Accept: 'application/json' } });
        if (!res.ok) throw new Error(`Uploading ${file.name} failed: HTTP ${res.status}`);
      }
      staged.current.clear();
      for (const p of pending.current.values()) await p.save();
      if (close) {
        exit();
        return;
      }
      ToastQueue.positive('The page properties have been saved', { timeout: 3000 });
      onReload();
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  const close = () => {
    if (dirty()) setConfirmDiscard(() => (discard: boolean) => discard && exit());
    else exit();
  };
  const reset = () => {
    staged.current.clear();
    pending.current.clear();
    onReload();
  };

  // In the shell, Back / Forward / a link / closing the tab ask first while there are unsaved changes.
  const tab = useTab();
  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;
  useEffect(() => {
    if (!tab) return;
    tab.setGuard(() => (dirtyRef.current() ? new Promise<boolean>((resolve) => setConfirmDiscard(() => resolve)) : Promise.resolve(true)));
    return () => tab.setGuard(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab?.id]);

  return (
    <div className="studio-pp">
      <Header
        title={loaded.title}
        dirty={dirty()}
        onReset={reset}
        onClose={close}
        onSave={() => void save(false)}
        saving={saving}
        saveDisabled={inheritance === undefined}
      />
      {failure && (
        <div className="studio-pp-failure" role="alert">
          {failure}
        </div>
      )}
      <ExtraTabsContext.Provider value={extras}>
        <PendingChangesContext.Provider value={registerPending}>
          <main className="studio-pp-body">
            <GraniteForm form={form} registry={registry} env={env} showErrors={showErrors} inheritance={inheritance} />
          </main>
        </PendingChangesContext.Provider>
      </ExtraTabsContext.Provider>
      <DialogContainer onDismiss={() => setConfirmDiscard(null)}>
        {confirmDiscard && (
          <StudioConfirmDialog
            title="Discard changes?"
            variant="destructive"
            primaryActionLabel="Discard"
            cancelLabel="Keep editing"
            onCancel={() => confirmDiscard(false)}
            onPrimaryAction={() => {
              const answer = confirmDiscard;
              setConfirmDiscard(null);
              answer(true);
            }}
          >
            The changes made to the page properties have not been saved.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

function Header({
  title,
  dirty,
  onReset,
  onClose,
  onSave,
  saving,
  saveDisabled,
}: {
  title: string;
  dirty?: boolean;
  onReset?(): void;
  onClose?(): void;
  onSave?(): void;
  saving?: boolean;
  saveDisabled?: boolean;
}) {
  useEffect(() => {
    if (!onSave) return;
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault();
        onSave();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onSave]);
  return (
    <ScreenFrame
      identity={<div className="studio-form-page__identity"><span>Page properties</span><h1>{title}</h1><em className={`studio-pill studio-pill--${dirty ? 'warning' : 'success'}`}>{dirty ? 'Unsaved changes' : 'Saved'}</em></div>}
      actions={onReset ? <Button variant="secondary" onPress={onReset} isDisabled={saving || !dirty}>Cancel</Button> : undefined}
      primary={onSave ? <Button variant="accent" onPress={onSave} isDisabled={saving || saveDisabled}>{saving ? 'Saving…' : 'Save'}</Button> : undefined}
      onClose={onClose}
      closeLabel="Close Page properties"
    />
  );
}
