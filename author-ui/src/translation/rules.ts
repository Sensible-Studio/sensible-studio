/**
 * AEM's Translation Configuration (Tools › General): the translation rules of each context (a content path). The most
 * specific context above a page decides which of its properties are translated. The server side is
 * `/bin/studio/translation/rules.json` (`TranslationRulesServlet`, `PageTranslationRules` in studio-translation).
 */

export const RULES_URL = '/libs/cq/translation/translationrules/contexts.html';
const EDITOR = '/libs/cq/translation/translationrules/contextproperties.html';

/** The editor of a context; without one, a new context. */
export const ruleEditorUrl = (context: string | null) => (context ? `${EDITOR}?context=${encodeURIComponent(context)}` : EDITOR);
export const isRuleEditor = (pathname: string) => pathname === EDITOR;
export const isRulesList = (pathname: string) => pathname === RULES_URL;

export interface ComponentRule {
  resourceType: string;
  properties: string[];
}

export interface TranslationContext {
  /** `/content/wknd` */
  path: string;
  /** The OSGi configuration's `/content`, not stored in /conf yet. */
  builtIn: boolean;
  /** Translated in every component (and the page) when they hold text. */
  properties: string[];
  /** Properties of one component, added to the ones above. */
  resourceTypes: ComponentRule[];
  /** Components whose texts stay as they are. */
  excludedResourceTypes: string[];
  /** Never translated, even if listed. */
  excludedProperties: string[];
  /** Property names ending like this are translated too (accessibilityLabel, signInLabel). */
  propertySuffixes: string[];
  /** The assets a page shows get language copies with their metadata translated. */
  translateAssets: boolean;
  assetProperties: string[];
  modified?: string;
  modifiedBy?: string;
}

export interface PreviewText {
  node: string;
  property: string;
  label: string;
  text: string;
  html: boolean;
}

export interface RulesPreview {
  /** The context that applies to the page. */
  context: string;
  texts: PreviewText[];
  total: number;
}

export interface TranslationRulesClient {
  list(): Promise<TranslationContext[]>;
  /** Stores a context; `previous`: the path it had, when it moves. */
  save(rules: TranslationContext, previous?: string | null): Promise<TranslationContext>;
  remove(path: string): Promise<void>;
  preview(page: string): Promise<RulesPreview>;
}

const API = '/bin/studio/translation/rules.json';

async function body<T>(res: Response): Promise<T> {
  const json = (await res.json().catch(() => null)) as (T & { error?: string }) | null;
  if (!res.ok || !json) throw new Error(json?.error ?? `HTTP ${res.status}`);
  return json;
}

export class HttpTranslationRulesClient implements TranslationRulesClient {
  constructor(private readonly base = '') {}

  async list() {
    const r = await body<{ contexts: TranslationContext[] }>(await fetch(`${this.base}${API}`, { credentials: 'same-origin', cache: 'no-store' }));
    return r.contexts;
  }

  private post<T>(json: unknown) {
    return fetch(`${this.base}${API}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(json),
    }).then((r) => body<T>(r));
  }

  save(rules: TranslationContext, previous?: string | null) {
    return this.post<TranslationContext>({ op: 'save', rules, ...(previous ? { previous } : {}) });
  }

  async remove(path: string) {
    await this.post({ op: 'delete', path });
  }

  async preview(page: string) {
    return body<RulesPreview>(await fetch(`${this.base}${API}?op=preview&path=${encodeURIComponent(page)}`, { credentials: 'same-origin', cache: 'no-store' }));
  }
}

/** The context whose rules apply to a path: the most specific one above it (as the server decides). */
export function contextFor(contexts: TranslationContext[], path: string): TranslationContext | null {
  let best: TranslationContext | null = null;
  for (const c of contexts) {
    if ((path === c.path || path.startsWith(`${c.path}/`)) && (!best || c.path.length > best.path.length)) best = c;
  }
  return best;
}

/** What a context's own form is checked for before saving. */
export function contextProblems(rules: TranslationContext, others: TranslationContext[]): string[] {
  const out: string[] = [];
  const path = rules.path.trim().replace(/\/+$/, '');
  if (!/^\/content(\/[^/]+)*$/.test(path) || path.includes('..')) out.push('The context is a path under /content, such as /content/wknd.');
  else if (others.some((o) => o.path === path && !o.builtIn)) out.push(`There is a context for ${path} already.`);
  const types = rules.resourceTypes.map((t) => t.resourceType.trim());
  if (types.some((t) => !t)) out.push('Every component needs its resource type.');
  if (new Set(types).size !== types.length) out.push('A component is listed twice.');
  return out;
}

/** A new context's rules: a copy of what applies there now. */
export function newContext(from: TranslationContext | null, path = ''): TranslationContext {
  return {
    path,
    builtIn: false,
    properties: [...(from?.properties ?? [])],
    resourceTypes: (from?.resourceTypes ?? []).map((t) => ({ resourceType: t.resourceType, properties: [...t.properties] })),
    excludedResourceTypes: [...(from?.excludedResourceTypes ?? [])],
    excludedProperties: [...(from?.excludedProperties ?? [])],
    propertySuffixes: [...(from?.propertySuffixes ?? [])],
    translateAssets: from?.translateAssets ?? true,
    assetProperties: [...(from?.assetProperties ?? [])],
  };
}

/** The parts of a context the form edits, for comparing before and after. */
export const comparable = (c: TranslationContext) =>
  JSON.stringify([c.path, c.properties, c.resourceTypes, c.excludedResourceTypes, c.excludedProperties, c.propertySuffixes, c.translateAssets, c.assetProperties]);
