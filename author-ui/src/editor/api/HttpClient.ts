import { expandDialogDataSources, toUrlEncoded, type ContentNode, type DialogResponse, type PostEntry, type RepositoryItem } from '../../granite';
import { itemsFromListing } from './listing';
import {
  nameOf,
  parentOf,
  StudioHttpError,
  type AssetPage,
  type AssetQuery,
  type EditorPageData,
  type NodeSnapshot,
  type PageInfo,
  type PageListItem,
  type ReferenceInfo,
  type SiteItem,
  type SiteListing,
  type TemplateInfo,
  type Placement,
  type ResponsiveSetting,
  type StudioClient,
  type StylesInfo,
  type XfTemplate,
} from './StudioClient';

const FORM = 'application/x-www-form-urlencoded;charset=UTF-8';

/**
 * Talks to a Sensible Studio instance: dialogs from `/bin/studio/dialog`, editor data from `/bin/studio/editor`, content
 * through the Sling default GET servlet, writes through the Sling POST servlet. Same origin (the dev
 * server proxies to :4502).
 */
export class HttpClient implements StudioClient {
  constructor(private readonly base = '') {}

  async getDialog(
    resourceType: string,
    dialogName: 'cq:dialog' | 'cq:design_dialog' = 'cq:dialog',
    contentPath?: string,
  ): Promise<DialogResponse> {
    const response = await this.rawDialog(resourceType, dialogName, contentPath);
    // datasources that make dialog structure (Embed): expanded from the dialogs they name
    return expandDialogDataSources(response, {
      dialog: async (rt, name) => (await this.rawDialog(rt, name)).root,
      embeddables: () => this.embeddables(),
    });
  }

  /** Core's embeddables (Core finds them by `sling:resourceSuperType`; they live under the embeddable folder). */
  private async embeddables(): Promise<Array<{ resourceType: string; title: string }>> {
    const base = 'core/wcm/components/embed/v1/embed/embeddable';
    const found = new Map<string, { resourceType: string; title: string; order: number }>();
    for (const root of ['/apps', '/libs']) {
      const res = await fetch(`${this.base}${root}/${base}.1.json`, { credentials: 'same-origin' });
      if (!res.ok) continue;
      const json = (await res.json()) as Record<string, unknown>;
      for (const [name, v] of Object.entries(json)) {
        if (!v || typeof v !== 'object' || Array.isArray(v)) continue;
        const c = v as Record<string, unknown>;
        const rt = `${base}/${name}`;
        if (c['sling:resourceSuperType'] !== base || found.has(rt) || String(c.enabled ?? 'true') === 'false') continue;
        found.set(rt, { resourceType: rt, title: String(c['jcr:title'] ?? name), order: Number(c.order ?? 0) || 0 });
      }
    }
    return [...found.values()].sort((a, b) => a.order - b.order || a.title.localeCompare(b.title)).map(({ resourceType, title }) => ({ resourceType, title }));
  }

  private async rawDialog(
    resourceType: string,
    dialogName: 'cq:dialog' | 'cq:design_dialog' = 'cq:dialog',
    contentPath?: string,
  ): Promise<DialogResponse> {
    const qs = new URLSearchParams({ resourceType, dialog: dialogName });
    if (contentPath) qs.set('path', contentPath);
    const res = await fetch(`${this.base}/bin/studio/dialog?${qs}`, { credentials: 'same-origin' });
    const type = res.headers.get('Content-Type') ?? '';
    if (res.status === 404 && type.includes('json')) return (await res.json()) as DialogResponse; // body explains why
    if (!res.ok) throw new StudioHttpError(res.status, `Dialog for ${resourceType}: HTTP ${res.status}`);
    return (await res.json()) as DialogResponse;
  }

  async getContent(path: string): Promise<ContentNode> {
    const res = await fetch(`${this.base}${encodePath(path)}.infinity.json`, { credentials: 'same-origin' });
    if (res.status === 300) {
      // Sling refuses very large trees with a list of depths it would accept; take a deep one.
      const res6 = await fetch(`${this.base}${encodePath(path)}.6.json`, { credentials: 'same-origin' });
      if (!res6.ok) throw new StudioHttpError(res6.status, `Content of ${path}: HTTP ${res6.status}`);
      return (await res6.json()) as ContentNode;
    }
    if (!res.ok) throw new StudioHttpError(res.status, `Content of ${path}: HTTP ${res.status}`);
    return (await res.json()) as ContentNode;
  }

  async post(path: string, entries: PostEntry[]): Promise<void> {
    await this.slingPost(path, entries);
  }

  async snapshot(path: string): Promise<NodeSnapshot> {
    const res = await fetch(`${this.base}${encodePath(path)}.infinity.json`, { credentials: 'same-origin' });
    if (res.status === 404) return { content: null, next: null };
    if (!res.ok) throw new StudioHttpError(res.status, `Content of ${path}: HTTP ${res.status}`);
    const content = (await res.json()) as ContentNode;
    const siblings = await this.childNames(parentOf(path));
    const i = siblings.indexOf(nameOf(path));
    return { content, next: i >= 0 && i + 1 < siblings.length ? siblings[i + 1] : null };
  }

  async restore(path: string, snapshot: NodeSnapshot): Promise<void> {
    if (!snapshot.content) {
      await this.deleteResource(path).catch((e: unknown) => {
        if (!(e instanceof StudioHttpError) || e.status !== 404) throw e;
      });
      return;
    }
    // Sling's import operation recreates the node from the JSON the default GET servlet produced
    await this.slingPost(parentOf(path), [
      { name: ':operation', value: 'import' },
      { name: ':contentType', value: 'json' },
      { name: ':name', value: nameOf(path) },
      { name: ':replace', value: 'true' },
      { name: ':replaceProperties', value: 'true' },
      { name: ':content', value: JSON.stringify(snapshot.content) },
    ]);
    // a replaced node lands last among its siblings
    const siblings = await this.childNames(parentOf(path));
    if (snapshot.next && siblings.includes(snapshot.next)) {
      await this.slingPost(path, [{ name: ':order', value: `before ${snapshot.next}` }]);
    } else if (!snapshot.next && siblings[siblings.length - 1] !== nameOf(path)) {
      await this.slingPost(path, [{ name: ':order', value: 'last' }]);
    }
  }

  /** Child node names of `path`, in order. */
  private async childNames(path: string): Promise<string[]> {
    const res = await fetch(`${this.base}${encodePath(path)}.1.json`, { credentials: 'same-origin' });
    if (!res.ok) return [];
    const json = (await res.json()) as Record<string, unknown>;
    return Object.keys(json).filter((k) => json[k] && typeof json[k] === 'object' && !Array.isArray(json[k]));
  }

  async getPageData(pagePath: string): Promise<EditorPageData> {
    const qs = new URLSearchParams({ op: 'page', path: pagePath });
    return this.getJson<EditorPageData>(`/bin/studio/editor.json?${qs}`, `Page ${pagePath}`);
  }

  async getStyles(path: string): Promise<StylesInfo> {
    const qs = new URLSearchParams({ op: 'styles', path });
    return this.getJson<StylesInfo>(`/bin/studio/editor.json?${qs}`, `Styles of ${path}`);
  }

  async searchAssets(query: AssetQuery): Promise<AssetPage> {
    const qs = new URLSearchParams({ op: 'assets' });
    for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== '') qs.set(k, String(v));
    return this.getJson<AssetPage>(`/bin/studio/editor.json?${qs}`, 'Media');
  }

  async listPages(root: string, depth: number, xf = false): Promise<PageListItem[]> {
    const qs = new URLSearchParams({ op: 'pages', root, depth: String(depth) });
    if (xf) qs.set('xf', 'true');
    const res = await this.getJson<{ pages: PageListItem[] }>(`/bin/studio/editor.json?${qs}`, `Pages below ${root}`);
    return res.pages;
  }

  async siteChildren(path: string): Promise<SiteListing> {
    return this.getJson<SiteListing>(`/bin/studio/sites.json?${new URLSearchParams({ op: 'children', path })}`, path);
  }

  async pageTemplates(parent: string): Promise<TemplateInfo[]> {
    return this.getJson<TemplateInfo[]>(`/bin/studio/sites.json?${new URLSearchParams({ op: 'templates', path: parent })}`, 'Templates');
  }

  async createPage(parent: string, template: string, title: string, name?: string): Promise<SiteItem> {
    const qs = new URLSearchParams({ op: 'create', parent, template, title });
    if (name) qs.set('name', name);
    const res = await fetch(`${this.base}/bin/studio/sites.json?${qs}`, { method: 'POST', credentials: 'same-origin' });
    const body = (await res.json().catch(() => ({}))) as SiteItem & { error?: string };
    if (!res.ok) throw new StudioHttpError(res.status, body.error || `Could not create the page (HTTP ${res.status})`);
    return body;
  }

  async references(path: string): Promise<ReferenceInfo[]> {
    const res = await this.getJson<{ references: ReferenceInfo[] }>(
      `/bin/studio/sites.json?${new URLSearchParams({ op: 'references', path })}`,
      `References to ${path}`,
    );
    return res.references;
  }

  async movePage(path: string, destParent: string, name: string, adjustPages: string[] = []): Promise<{ path: string; adjusted: number }> {
    const body = new URLSearchParams({ op: 'move', path, dest: destParent.replace(/\/$/, ''), name });
    for (const p of adjustPages) body.append('adjust', p);
    const res = await fetch(`${this.base}/bin/studio/sites.json`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': FORM },
      body,
    });
    const json = (await res.json().catch(() => ({}))) as { path?: string; adjusted?: number; error?: string };
    if (!res.ok || !json.path) throw new StudioHttpError(res.status, json.error || `Could not move ${path} (HTTP ${res.status})`);
    return { path: json.path, adjusted: json.adjusted ?? 0 };
  }

  async currentUser(): Promise<string> {
    const info = await this.getJson<{ userID?: string }>('/system/sling/info.sessionInfo.json', 'Session');
    return info.userID ?? 'anonymous';
  }

  async createComponent(resourceType: string, at: Placement, properties: PostEntry[] = []): Promise<string> {
    const nameHint = nameOf(resourceType) || 'component';
    // './' everywhere: once one parameter uses it (e.g. './fileReference'), Sling ignores the others
    const res = await this.slingPost(`${at.container}/*`, [
      { name: './jcr:primaryType', value: 'nt:unstructured' },
      { name: './sling:resourceType', value: resourceType },
      { name: ':nameHint', value: nameHint },
      { name: ':order', value: order(at) },
      ...properties.map((p) => (p.name.startsWith('./') || p.name.startsWith(':') ? p : { ...p, name: `./${p.name}` })),
    ]);
    const json = (await res.json().catch(() => null)) as { path?: string } | null;
    const location = res.headers.get('Location');
    const path = json?.path ?? (location ? new URL(location, window.location.origin).pathname : null);
    if (!path) throw new Error('Sling did not return the path of the new component');
    return path;
  }

  async deleteResource(path: string): Promise<void> {
    await this.slingPost(path, [{ name: ':operation', value: 'delete' }]);
  }

  async moveResource(path: string, to: Placement): Promise<string> {
    if (parentOf(path) === to.container) {
      if (to.before !== nameOf(path)) await this.slingPost(path, [{ name: ':order', value: order(to) }]);
      return path;
    }
    const dest = `${to.container}/${await this.freeName(to.container, nameOf(path))}`;
    await this.slingPost(path, [
      { name: ':operation', value: 'move' },
      { name: ':dest', value: dest },
      { name: ':order', value: order(to) },
    ]);
    return dest;
  }

  async copyResource(path: string, to: Placement): Promise<string> {
    const dest = `${to.container}/${await this.freeName(to.container, nameOf(path))}`;
    await this.slingPost(path, [
      { name: ':operation', value: 'copy' },
      { name: ':dest', value: dest },
      { name: ':order', value: order(to) },
    ]);
    return dest;
  }

  async setResponsive(path: string, breakpoint: string, setting: ResponsiveSetting): Promise<void> {
    const bp = `cq:responsive/${breakpoint}`;
    const entries: PostEntry[] = [
      { name: 'cq:responsive/jcr:primaryType', value: 'nt:unstructured' },
      { name: `${bp}/jcr:primaryType`, value: 'nt:unstructured' },
    ];
    if (setting.width !== undefined) entries.push({ name: `${bp}/width`, value: String(setting.width) });
    if (setting.offset !== undefined) entries.push({ name: `${bp}/offset`, value: String(setting.offset) });
    if (setting.behavior === null) entries.push({ name: `${bp}/behavior@Delete`, value: '' });
    else if (setting.behavior) entries.push({ name: `${bp}/behavior`, value: setting.behavior });
    await this.slingPost(path, entries);
  }

  async resetResponsive(paths: string[], breakpoint: string): Promise<void> {
    for (const p of paths) {
      try {
        await this.slingPost(`${p}/cq:responsive/${breakpoint}`, [{ name: ':operation', value: 'delete' }]);
      } catch (e) {
        if (!(e instanceof StudioHttpError) || e.status !== 404) throw e;
      }
    }
  }

  async setPageLock(pagePath: string, locked: boolean): Promise<PageInfo> {
    const qs = new URLSearchParams({ op: locked ? 'lock' : 'unlock', path: pagePath });
    const res = await fetch(`${this.base}/bin/studio/editor.json?${qs}`, { method: 'POST', credentials: 'same-origin' });
    const body = (await res.json().catch(() => ({}))) as PageInfo & { error?: string };
    if (!res.ok) throw new StudioHttpError(res.status, body.error || `HTTP ${res.status}`);
    return body;
  }

  async xfTemplates(): Promise<XfTemplate[]> {
    const res = await this.getJson<{ templates: XfTemplate[] }>('/bin/studio/editor.json?op=xfTemplates', 'Experience Fragment templates');
    return res.templates;
  }

  async convertToXf(paths: string[], options: { parent: string; title: string; template: string; resourceType: string }): Promise<{ fragment: string; variation: string; component: string }> {
    const body = new URLSearchParams({ op: 'convertToXf', parent: options.parent, title: options.title, template: options.template, resourceType: options.resourceType });
    for (const p of paths) body.append('path', p);
    const res = await fetch(`${this.base}/bin/studio/editor.json`, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': FORM }, body });
    const json = (await res.json().catch(() => ({}))) as { fragment: string; variation: string; component: string; error?: string };
    if (!res.ok) throw new StudioHttpError(res.status, json.error || `Could not convert (HTTP ${res.status})`);
    return json;
  }

  async setPublished(pagePath: string, published: boolean, deep = false): Promise<PageInfo> {
    const qs = new URLSearchParams({ op: published ? 'publish' : 'unpublish', path: pagePath });
    if (deep) qs.set('deep', 'true');
    const res = await fetch(`${this.base}/bin/studio/editor.json?${qs}`, { method: 'POST', credentials: 'same-origin' });
    const body = (await res.json().catch(() => ({}))) as PageInfo & { error?: string };
    if (!res.ok) throw new StudioHttpError(res.status, body.error || `HTTP ${res.status}`);
    return body;
  }

  async createVersion(pagePath: string, label?: string, comment?: string): Promise<{ id: string; name: string; created?: string }> {
    const qs = new URLSearchParams({ op: 'version', path: pagePath });
    if (label) qs.set('label', label);
    if (comment) qs.set('comment', comment);
    const res = await fetch(`${this.base}/bin/studio/editor.json?${qs}`, { method: 'POST', credentials: 'same-origin' });
    const body = (await res.json().catch(() => ({}))) as { id: string; name: string; created?: string; error?: string };
    if (!res.ok) throw new StudioHttpError(res.status, body.error || `HTTP ${res.status}`);
    return body;
  }

  async listChildren(path: string): Promise<RepositoryItem[]> {
    const res = await fetch(`${this.base}${encodePath(path)}.2.json`, { credentials: 'same-origin' });
    if (!res.ok) throw new StudioHttpError(res.status, `HTTP ${res.status}`);
    return itemsFromListing(path, (await res.json()) as ContentNode);
  }

  pageUrl(pagePath: string, mode: 'edit' | 'disabled'): string {
    return `${this.base}${encodePath(pagePath)}.html?wcmmode=${mode}`;
  }

  assetUrl(path: string): string {
    return `${this.base}${encodePath(path)}`;
  }

  assetThumbnail(path: string): string {
    return `${this.base}${encodePath(path)}/_jcr_content/renditions/cq5dam.thumbnail.319.319.png`;
  }

  async assetInfo(path: string): Promise<{ path: string; title?: string; description?: string; mimeType?: string } | null> {
    const res = await fetch(`${this.base}${encodePath(path)}/_jcr_content/metadata.json`, { credentials: 'same-origin' });
    if (!res.ok) return null;
    const m = (await res.json()) as Record<string, unknown>;
    const text = (v: unknown) => (Array.isArray(v) ? (v[0] as string | undefined) : typeof v === 'string' ? v : undefined);
    return { path, title: text(m['dc:title']), description: text(m['dc:description']), mimeType: text(m['dc:format']) };
  }

  async pageImage(pagePath: string): Promise<{ fileReference: string; alt?: string } | null> {
    const res = await fetch(`${this.base}${encodePath(pagePath)}/_jcr_content/cq:featuredimage.json`, { credentials: 'same-origin' });
    if (!res.ok) return null;
    const node = (await res.json()) as { fileReference?: string; alt?: string };
    return node.fileReference ? { fileReference: node.fileReference, alt: node.alt } : null;
  }

  // ------------------------------------------------------------------------------------------ helpers

  private async getJson<T>(url: string, what: string): Promise<T> {
    const res = await fetch(`${this.base}${url}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    if (!res.ok) throw new StudioHttpError(res.status, `${what}: HTTP ${res.status}`);
    return (await res.json()) as T;
  }

  private async slingPost(path: string, entries: PostEntry[]): Promise<Response> {
    const res = await fetch(`${this.base}${encodePath(path)}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': FORM, Accept: 'application/json' },
      body: toUrlEncoded(entries),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new StudioHttpError(res.status, `Saving ${path} failed (HTTP ${res.status}). ${extractSlingMessage(text)}`.trim());
    }
    return res;
  }

  /** `name`, or `name_1`, `name_2`, … when the container already has a child called that. */
  private async freeName(container: string, name: string): Promise<string> {
    const res = await fetch(`${this.base}${encodePath(container)}.1.json`, { credentials: 'same-origin' });
    const taken = new Set(res.ok ? Object.keys((await res.json()) as object) : []);
    if (!taken.has(name)) return name;
    const stem = name.replace(/_\d+$/, '');
    for (let i = 1; ; i++) if (!taken.has(`${stem}_${i}`)) return `${stem}_${i}`;
  }
}

function order(at: Placement): string {
  return at.before ? `before ${at.before}` : 'last';
}

export function encodePath(path: string): string {
  return path
    .split('/')
    .map((s) => encodeURIComponent(s).replace(/%3A/gi, ':'))
    .join('/');
}

function extractSlingMessage(html: string): string {
  const m = html.match(/<div id="Message">([\s\S]*?)<\/div>/i) ?? html.match(/"status\.message"\s*:\s*"([^"]*)"/);
  return m ? m[1].replace(/<[^>]+>/g, '').trim() : '';
}
