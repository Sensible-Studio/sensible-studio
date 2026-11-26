import type { ComponentChoice } from '../../granite/registry';

/** An editable template as the template editor shows it (`/bin/studio/templates.json?op=template`). */
export interface TemplateInfo {
  path: string;
  name: string;
  title: string;
  description?: string;
  /** `draft` (new), `enabled` (pages can use it) or `disabled` */
  status: 'draft' | 'enabled' | 'disabled' | string;
  templateType?: string;
  conf: string;
  structure: string;
  initial: string;
  policies: string;
  thumbnail?: string | null;
  lastModified?: string;
  lastModifiedBy?: string;
  /** structure component path → unlocked (authors edit it on pages) */
  unlocked: Record<string, boolean>;
  /** set in the editor's page data: which part of the template the editor shows */
  mode?: 'structure' | 'initial';
}

export interface PolicySummary {
  path: string;
  name: string;
  title: string;
  description?: string;
}

/** `GET op=policy`: the policy of a component in the template, and the policies it can have. */
export interface PolicyInfo {
  type: string;
  componentTitle: string;
  designDialog: boolean;
  mapping: string;
  policy: PolicySummary | null;
  /** the policy comes from the container the component sits in, not a mapping of its own */
  inherited: boolean;
  policies: PolicySummary[];
  usedBy: Array<{ path: string; title: string; thumbnail?: string | null }>;
}

/** Where a policy applies: a component of the template, or a component type inside a container. */
export interface PolicyTarget {
  path: string;
  type: string;
  /** with it: the policy of `type` inside this container (an Allowed Components entry) */
  container?: string;
  title: string;
}

export interface TemplateClient {
  template(path: string): Promise<TemplateInfo>;
  policy(target: PolicyTarget): Promise<PolicyInfo>;
  /** every component a policy can allow, with its group */
  components(): Promise<ComponentChoice[]>;
  /** assigns an existing policy, or (`create`) a new one, copied from `copyFrom` if given; returns it */
  assignPolicy(
    target: PolicyTarget,
    choice: { policy: string } | { create: true; title: string; description?: string; copyFrom?: string },
  ): Promise<PolicySummary>;
  setLocked(path: string, locked: boolean): Promise<void>;
  setStatus(template: string, status: 'enabled' | 'disabled' | 'draft'): Promise<TemplateInfo>;
}

const API = '/bin/studio/templates.json';

async function call<T>(method: 'GET' | 'POST', params: Record<string, string | undefined>): Promise<T> {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') qs.set(k, v);
  const res =
    method === 'GET'
      ? await fetch(`${API}?${qs}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      : await fetch(API, {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', Accept: 'application/json' },
          body: qs,
        });
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error((body as { error?: string }).error || `HTTP ${res.status}`);
  return body;
}

export class HttpTemplateClient implements TemplateClient {
  private componentsCache: Promise<ComponentChoice[]> | null = null;

  template(path: string) {
    return call<TemplateInfo>('GET', { op: 'template', path });
  }

  policy(t: PolicyTarget) {
    return call<PolicyInfo>('GET', { op: 'policy', path: t.path, type: t.type, container: t.container });
  }

  components() {
    if (!this.componentsCache) {
      this.componentsCache = call<{ components: ComponentChoice[] }>('GET', { op: 'components' }).then((r) => r.components);
      this.componentsCache.catch(() => (this.componentsCache = null));
    }
    return this.componentsCache;
  }

  async assignPolicy(t: PolicyTarget, choice: { policy: string } | { create: true; title: string; description?: string; copyFrom?: string }) {
    const params: Record<string, string | undefined> = { op: 'policy', path: t.path, type: t.type, container: t.container };
    if ('policy' in choice) params.policy = choice.policy;
    else Object.assign(params, { new: 'true', title: choice.title, description: choice.description, copyFrom: choice.copyFrom });
    return (await call<{ policy: PolicySummary }>('POST', params)).policy;
  }

  async setLocked(path: string, locked: boolean) {
    await call('POST', { op: locked ? 'lock' : 'unlock', path });
  }

  setStatus(template: string, status: 'enabled' | 'disabled' | 'draft') {
    return call<TemplateInfo>('POST', { op: 'status', path: template, status });
  }
}

/** `/conf/x/settings/wcm/templates/t/structure` → the template and the part, or null for ordinary pages. */
export function templatePart(pagePath: string): { template: string; part: 'structure' | 'initial' } | null {
  const m = pagePath.match(/^(\/conf\/.+\/settings\/wcm\/templates\/[^/]+)\/(structure|initial)$/);
  return m ? { template: m[1], part: m[2] as 'structure' | 'initial' } : null;
}
