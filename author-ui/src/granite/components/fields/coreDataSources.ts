import type { DataSource, DataSourceForm, GraniteEnv, SelectOption } from '../../registry';
import type { GraniteNode } from '../../types';
import type { ChildrenValue } from './children';

/**
 * Core Components datasources (Java servlets on AEM), computed from what the dialog already has: the component's
 * content policy (`env.design`), and, for the panel containers, the items of the Items tab.
 */

const HEADINGS = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6'];

function list(v: unknown): string[] {
  if (v === undefined || v === null || v === '') return [];
  return (Array.isArray(v) ? v : [v]).map(String);
}

function design(env: GraniteEnv): Record<string, unknown> {
  return env.design ?? {};
}

/**
 * `core/wcm/components/commons/datasources/allowedheadingelements/v1`: the heading elements the policy allows
 * (`allowedHeadingElements`, all of them when it allows none), the policy's `headingElement` as the default.
 */
export const allowedHeadingElements: DataSource = (_node, env) => {
  const allowed = list(design(env).allowedHeadingElements);
  const fallback = String(design(env).headingElement ?? '');
  return (allowed.length ? allowed : HEADINGS).map((h) => ({ value: h, text: h, selected: h === fallback }));
};

/** `core/wcm/components/title/v1/datasource/allowedtypes`: the policy's `allowedTypes`, its `type` as the default. */
export const allowedTitleTypes: DataSource = (_node, env) => {
  const fallback = String(design(env).type ?? '');
  return list(design(env).allowedTypes).map((t) => ({ value: t, text: t, selected: t === fallback }));
};

/** `core/wcm/components/commons/datasources/allowedcolorswatches/v1`: the policy's `allowedColorSwatches`. */
export const allowedColorSwatches: DataSource = (_node, env) =>
  list(design(env).allowedColorSwatches).map((c) => ({ value: c, text: c }));

/** `dam/components/scene7/common/imagepresetdatasource`: Dynamic Media image presets; Sensible Studio has none. */
export const noImagePresets: DataSource = () => [];

/** The items of the Items tab (the children editor's current state), titled as the children editor shows them. */
export function panelItems(form: DataSourceForm, env: GraniteEnv): SelectOption[] {
  const value = form.get('itemOrder') as ChildrenValue | undefined;
  return (value?.items ?? []).map((item) => ({
    value: item.name,
    text: item.title || env.componentInfo?.(item.resourceType)?.title || item.name,
  }));
}

/**
 * `studio/granite/datasources/panelcontainer/items` (live): the options the Tabs and Carousel editor scripts put in
 * "Active Item", in step with the Items tab.
 */
export const panelItemsDataSource: DataSource = (_node, env, form) => (form ? panelItems(form, env) : []);

export const PANEL_ITEMS_DATASOURCE = 'studio/granite/datasources/panelcontainer/items';

// ------------------------------------------------------------------------------------------------ tree helpers

/** A copy of the tree with `fn` applied to every node (bottom up); `fn` returns null to remove a node. */
export function rewrite(node: GraniteNode, fn: (n: GraniteNode) => GraniteNode | null): GraniteNode {
  const visit = (n: GraniteNode): GraniteNode | null =>
    fn({ ...n, children: n.children.map(visit).filter((c): c is GraniteNode => c !== null) });
  return visit(node) ?? node;
}

/** The value of an editor hook (`granite:data/<attribute>`) of a node. */
export function hook(node: GraniteNode, attribute: string): string | undefined {
  const data = node.children.find((c) => c.name === 'granite:data');
  const v = data?.properties[attribute];
  return v === undefined || v === null ? undefined : String(v);
}

// ------------------------------------------------------------------------------------------------ content fragments

const fieldValue = (form: DataSourceForm | undefined, name: string) => {
  const v = form?.get(name);
  return typeof v === 'string' ? v : '';
};

/** The model a Content Fragment component's elements come from: the chosen record's. */
async function fragmentModel(env: GraniteEnv, fragmentPath: string): Promise<string | null> {
  if (!fragmentPath || !env.contentFragments) return null;
  return (await env.contentFragments.fragment(fragmentPath)).model;
}

async function elementOptions(env: GraniteEnv, modelPath: string | null): Promise<SelectOption[]> {
  if (!modelPath || !env.contentFragments) return [];
  return (await env.contentFragments.elements(modelPath)).filter((e) => e.kind !== 'tab').map((e) => ({ value: e.name, text: e.title || e.name }));
}

/** `core/wcm/components/contentfragment/v1/datasource/elements`: the elements of the chosen fragment's model. */
export const fragmentElements: DataSource = async (_node, env, form) => elementOptions(env, await fragmentModel(env, fieldValue(form, './fragmentPath')));

/** `core/wcm/components/contentfragment/v1/datasource/variations`: Master, then the chosen fragment's variations. */
export const fragmentVariations: DataSource = async (_node, env, form) => {
  const path = fieldValue(form, './fragmentPath');
  const master = { value: 'master', text: 'Master', selected: true };
  if (!path || !env.contentFragments) return [master];
  const { variations } = await env.contentFragments.fragment(path);
  return [master, ...variations.filter((v) => v.name !== 'master').map((v) => ({ value: v.name, text: v.title || v.name }))];
};

export const fragmentPathKey = (_node: GraniteNode, form: DataSourceForm) => fieldValue(form, './fragmentPath');
export const modelPathKey = (_node: GraniteNode, form: DataSourceForm) => fieldValue(form, './modelPath');

/** `core/wcm/components/contentfragmentlist/v2/datasource/models`: every Content Fragment model, by title. */
export const fragmentModels: DataSource = async (_node, env) =>
  env.contentFragments ? (await env.contentFragments.models()).filter((m) => m.enabled !== false).map((m) => ({ value: m.path, text: m.title || m.path })) : [];

/** `core/wcm/components/contentfragmentlist/v2/datasource/elements`: the elements of the chosen model. */
export const modelElements: DataSource = (_node, env, form) => elementOptions(env, fieldValue(form, './modelPath') || null);

/** Element kinds a list can be ordered by (Core's `textOnly` order-by options). */
const ORDERABLE = new Set(['text', 'longtext', 'number', 'date', 'boolean']);

/**
 * `core/wcm/components/contentfragmentlist/v2/datasource/orderby`: Created, Last Modified, then the chosen model's
 * orderable elements, as the property paths the list queries by.
 */
export const fragmentListOrderBy: DataSource = async (_node, env, form) => {
  const base = [
    { value: 'jcr:created', text: 'Created' },
    { value: 'jcr:content/jcr:lastModified', text: 'Last Modified' },
  ];
  const model = fieldValue(form, './modelPath');
  if (!model || !env.contentFragments) return base;
  const elements = (await env.contentFragments.elements(model)).filter((e) => ORDERABLE.has(e.kind));
  return [...base, ...elements.map((e) => ({ value: `jcr:content/data/master/${e.name}`, text: e.title || e.name }))];
};

// ------------------------------------------------------------------------------------------------ client libraries

/**
 * `core/wcm/components/commons/datasources/clientlibrarycategories/v1`: the client library categories (with the
 * datasource's `type`, those with JS or CSS libraries), suggested in the page configuration's Client Libraries fields.
 */
export const clientLibraryCategories: DataSource = async (node, env) => {
  if (!env.clientLibraryCategories) return [];
  const type = node.properties.type;
  const categories = await env.clientLibraryCategories(typeof type === 'string' ? type : undefined);
  return categories.map((c) => ({ value: c, text: c }));
};
