import type { ContentFragmentServices, GraniteEnv } from '../../granite/registry';
import { HttpCfClient, type CfClient } from '../../cf/api';
import type { EditorPageData, StudioClient } from '../api/StudioClient';

/**
 * What Granite dialog fields get from Sensible Studio: repository listing, asset previews and metadata, page images, and (in
 * the editor, from the page data) component titles and the components each container allows.
 */
export function studioGraniteEnv(client: StudioClient, contentPath?: string, page?: EditorPageData | null): GraniteEnv {
  return {
    contentPath,
    componentInfo: page ? (rt) => page.components[rt] : undefined,
    allowedComponents: page
      ? (container) => (page.containers[container] ?? []).map((rt) => page.components[rt] ?? { resourceType: rt, title: rt, group: '' })
      : undefined,
    listChildren: (p) => client.listChildren(p),
    assetUrl: (p) => client.assetUrl(p),
    assetThumbnail: (p) => client.assetThumbnail(p),
    assetInfo: (p) => client.assetInfo(p),
    pageImage: (p) => client.pageImage(p),
    contentFragments: contentFragmentServices(new HttpCfClient()),
    clientLibraryCategories,
  };
}

const categoriesByType = new Map<string, Promise<string[]>>();

/** `GET /bin/studio/clientlibs`: the client library categories (read once per type). */
function clientLibraryCategories(type?: string): Promise<string[]> {
  const key = type ?? '';
  let p = categoriesByType.get(key);
  if (!p) {
    const qs = type ? `?type=${encodeURIComponent(type)}` : '';
    p = fetch(`/bin/studio/clientlibs${qs}`, { credentials: 'same-origin' })
      .then((r) => (r.ok ? (r.json() as Promise<{ categories: string[] }>) : { categories: [] }))
      .then((j) => j.categories ?? []);
    p.catch(() => categoriesByType.delete(key));
    categoriesByType.set(key, p);
  }
  return p;
}

/** The Content Fragment (List) datasources' view of Sensible Studio's Content Fragments (each model and fragment read once). */
export function contentFragmentServices(cf: Pick<CfClient, 'models' | 'model' | 'fragment'>): ContentFragmentServices {
  const models = new Map<string, ReturnType<CfClient['model']>>();
  const fragments = new Map<string, ReturnType<CfClient['fragment']>>();
  const once = <T,>(cache: Map<string, Promise<T>>, key: string, load: () => Promise<T>) => {
    let p = cache.get(key);
    if (!p) {
      p = load();
      p.catch(() => cache.delete(key));
      cache.set(key, p);
    }
    return p;
  };
  return {
    models: () => cf.models(),
    elements: async (modelPath) =>
      (await once(models, modelPath, () => cf.model(modelPath))).fields.map((f) => ({ name: f.name, title: f.label, kind: f.kind })),
    fragment: async (path) => {
      const f = await once(fragments, path, () => cf.fragment(path));
      return { model: f.model, variations: f.variations.map((v) => ({ name: v.name, title: v.title })) };
    },
  };
}
