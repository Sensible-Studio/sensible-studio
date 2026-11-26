import type { DialogResponse, GraniteNode, JcrValue } from './types';
import { child, designFromPolicy, resourceTypeOf, str } from './node';

/**
 * Datasources that make dialog structure rather than options: on AEM a Java servlet returns resources that Granite
 * renders in place of the datasource (the Embed component's embeddable options and policy tabs, its embeddable
 * selects). Sensible Studio expands them when the dialog is loaded, from the dialogs of the components they name.
 */
export interface DialogSources {
  /** The merged dialog of a component (its root), null when it has none. */
  dialog(resourceType: string, dialogName: 'cq:dialog' | 'cq:design_dialog'): Promise<GraniteNode | null>;
  /** The embeddables (components with `sling:resourceSuperType` Core's embeddable), sorted as Core sorts them. */
  embeddables(): Promise<Array<{ resourceType: string; title: string }>>;
}

const EMBED_DS = /^core\/wcm\/components\/embed\/v\d\/datasources\/(embeddableoptions|embeddesigntabs|allowedembeddables|embeddables)$/;

/** Wraps a part that is shown while field `field` has value `value` (see `ShowWhen`). */
export const SHOW_WHEN = 'studio/granite/components/showwhen';

export async function expandDialogDataSources(response: DialogResponse, sources: DialogSources): Promise<DialogResponse> {
  if (!response.root || !hasEmbedDataSource(response.root)) return response;
  const design = designFromPolicy(response.policy);
  const allowed = list(design.allowedEmbeddables);
  let all: Array<{ resourceType: string; title: string }> | null = null;
  const embeddables = async () => (all ??= await sources.embeddables().catch(() => []));
  const titleOf = async (rt: string) => (await embeddables()).find((e) => e.resourceType === rt)?.title ?? rt.slice(rt.lastIndexOf('/') + 1);

  const visit = async (node: GraniteNode): Promise<GraniteNode> => {
    const ds = child(node, 'datasource');
    const kind = ds ? resourceTypeOf(ds).match(EMBED_DS)?.[1] : undefined;
    const children = await Promise.all(node.children.filter((c) => c !== ds || !kind).map(visit));
    if (!kind) return { ...node, children };
    const rest = children.filter((c) => c.name !== 'firsttabs' && c.name !== 'lasttabs');

    if (kind === 'allowedembeddables' || kind === 'embeddables') {
      // a select: its options become static items (Core: "Select" first when the policy allows any)
      const types = kind === 'allowedembeddables' ? allowed : (await embeddables()).map((e) => e.resourceType);
      const options = await Promise.all(types.map(async (rt) => option(rt, await titleOf(rt))));
      const first = kind === 'allowedembeddables' && options.length ? [option('', 'Select', 'select')] : [];
      return { ...node, children: [...rest.filter((c) => c.name !== 'items'), { name: 'items', properties: {}, children: [...first, ...options] }] };
    }

    if (kind === 'embeddableoptions') {
      // the options dialog of each embeddable the policy allows, shown while it is the chosen one
      const parts: GraniteNode[] = [];
      for (const rt of allowed) {
        const d = await sources.dialog(rt, 'cq:dialog').catch(() => null);
        if (!d) continue;
        parts.push({
          name: safeName(rt),
          properties: { 'sling:resourceType': SHOW_WHEN, 'studio:field': './embeddableResourceType', 'studio:value': rt },
          children: [{ name: 'items', properties: {}, children: [{ ...d, name: 'options' }] }],
        });
      }
      return { ...node, children: [...rest.filter((c) => c.name !== 'items'), { name: 'items', properties: {}, children: parts }] };
    }

    // embeddesigntabs: firsttabs, a tab per embeddable with a design dialog, lasttabs
    const tabs: GraniteNode[] = [...(child(node, 'firsttabs')?.children ?? [])];
    for (const e of await embeddables()) {
      const d = await sources.dialog(e.resourceType, 'cq:design_dialog').catch(() => null);
      if (d) tabs.push({ ...d, name: safeName(e.resourceType), properties: { ...d.properties, 'jcr:title': str(d, 'jcr:title') ?? e.title } });
    }
    tabs.push(...(child(node, 'lasttabs')?.children ?? []));
    const visited = await Promise.all(tabs.map(visit));
    return { ...node, children: [...rest.filter((c) => c.name !== 'items'), { name: 'items', properties: {}, children: visited }] };
  };

  return { ...response, root: await visit(response.root) };
}

function hasEmbedDataSource(node: GraniteNode): boolean {
  if (node.name === 'datasource' && EMBED_DS.test(resourceTypeOf(node))) return true;
  return node.children.some(hasEmbedDataSource);
}

function option(value: string, text: string, name = safeName(value)): GraniteNode {
  return { name, properties: { value, text } as Record<string, JcrValue>, children: [] };
}

function list(v: unknown): string[] {
  if (v === undefined || v === null || v === '') return [];
  return (Array.isArray(v) ? v : [v]).map(String).filter(Boolean);
}

const safeName = (rt: string) => rt.replace(/[^A-Za-z0-9_-]+/g, '_') || 'item';
