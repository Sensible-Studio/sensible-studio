/**
 * AEM's Components console (Tools › General › Components): every component of `/apps` and `/libs`, and one
 * component's properties, policies and the pages that use it. The server side is `/bin/studio/components.json`
 * (`ComponentsConsoleServlet` in studio-cq-impl).
 */

export const COMPONENTS_URL = '/libs/wcm/core/content/sites/components.html';
const DETAILS = '/mnt/overlay/wcm/core/content/sites/components/details.html';

/** A component's details screen, as AEM addresses it. */
export const componentUrl = (path: string) => `${DETAILS}${encodeURI(path)}`;
export const componentOfUrl = (pathname: string): string | null => {
  if (!pathname.startsWith(`${DETAILS}/`)) return null;
  return decodeURI(pathname.slice(DETAILS.length)).replace(/\/+$/, '') || null;
};

export interface ComponentSummary {
  path: string;
  /** Relative, as pages name it: `wknd/components/accordion`. */
  resourceType: string;
  title: string;
  /** `componentGroup`; absent for components authors never add, `.hidden` & co. for hidden ones. */
  group?: string;
  container?: boolean;
  superType?: string;
  /** Two letters shown when the component has no icon image. */
  abbreviation: string;
  /** `cq:icon` (a Coral icon name). */
  icon?: string;
  /** `icon.svg` / `icon.png` of the component. */
  iconImage?: string;
}

export interface ComponentRef {
  path: string;
  title: string;
}

export interface ComponentDetail extends ComponentSummary {
  description?: string;
  /** The super type's component path, when it resolves. */
  superTypePath?: string;
  /** Super type, its super type, … */
  superTypes?: ComponentRef[];
  created?: string;
  createdBy?: string;
  lastModified?: string;
  lastModifiedBy?: string;
  dialog?: string;
  designDialog?: string;
  editConfig?: string;
  childEditConfig?: string;
  /** `cq:template`: what a new instance starts with. */
  template?: string;
  scripts?: string[];
  readme?: string;
}

export interface ComponentPolicy {
  path: string;
  title: string;
  description?: string;
  conf: string;
  confTitle?: string;
  lastModified?: string;
  lastModifiedBy?: string;
  templates: ComponentRef[];
}

export interface UsagePage {
  path: string;
  title: string;
  /** How many instances of the component the page holds. */
  count: number;
}

export interface ComponentUsage {
  pages: UsagePage[];
  /** Instances found. */
  uses: number;
  /** The search stopped early: there are more. */
  truncated: boolean;
}

export interface ComponentsClient {
  list(): Promise<ComponentSummary[]>;
  component(path: string): Promise<ComponentDetail>;
  policies(path: string): Promise<ComponentPolicy[]>;
  usage(path: string): Promise<ComponentUsage>;
}

const API = '/bin/studio/components.json';

export class HttpComponentsClient implements ComponentsClient {
  constructor(private readonly base = '') {}

  private async get<T>(params: Record<string, string> = {}): Promise<T> {
    const q = new URLSearchParams(params).toString();
    const res = await fetch(`${this.base}${API}${q ? `?${q}` : ''}`, { credentials: 'same-origin', cache: 'no-store' });
    const body = (await res.json().catch(() => null)) as (T & { error?: string }) | null;
    if (!res.ok || !body) throw new Error(body?.error ?? `HTTP ${res.status}`);
    return body;
  }

  async list() {
    return (await this.get<{ components: ComponentSummary[] }>()).components;
  }

  component(path: string) {
    return this.get<ComponentDetail>({ op: 'component', path });
  }

  async policies(path: string) {
    return (await this.get<{ policies: ComponentPolicy[] }>({ op: 'policies', path })).policies;
  }

  usage(path: string) {
    return this.get<ComponentUsage>({ op: 'usage', path });
  }
}

/** `/apps` or `/libs`: where a component comes from. */
export const locationOf = (path: string) => path.split('/')[1] ?? '';

export const NO_GROUP = 'n/a';
export const groupOf = (c: ComponentSummary) => c.group ?? NO_GROUP;
/** Groups starting with a dot are hidden from authors (`.hidden`, `.core-wcm`). */
export const isHidden = (c: ComponentSummary) => !c.group || c.group.startsWith('.');

export interface ComponentFilter {
  text: string;
  /** A group, or '' for all. */
  group: string;
  /** 'apps', 'libs', or '' for both. */
  location: string;
}

/** The console's filter: words in title, resource type, path or group; one group; one location. */
export function filterComponents(list: ComponentSummary[], f: ComponentFilter): ComponentSummary[] {
  const words = f.text.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return list.filter((c) => {
    if (f.group && groupOf(c) !== f.group) return false;
    if (f.location && locationOf(c.path) !== f.location) return false;
    const hay = `${c.title} ${c.resourceType} ${c.path} ${c.group ?? ''}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  });
}

/** The groups of the list, the ones authors see first, hidden ones and "n/a" last. */
export function groupsOf(list: ComponentSummary[]): string[] {
  const all = [...new Set(list.map(groupOf))];
  const rank = (g: string) => (g === NO_GROUP ? 2 : g.startsWith('.') ? 1 : 0);
  return all.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b, undefined, { sensitivity: 'base' }));
}
