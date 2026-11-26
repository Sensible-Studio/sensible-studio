/** Generate Variations: copy written by the model of a translation provider (VariationsServlet on the server). */

export const VARIATIONS_URL = '/aem/generate-variations.html';
const API = '/bin/studio/variations.json';

export interface VariationField {
  key: string;
  label: string;
}

export interface PromptTemplate {
  id: string;
  title: string;
  description: string;
  fields: VariationField[];
}

export interface VariationsProvider {
  path: string;
  title: string;
  /** "OpenRouter (anthropic/claude-sonnet-4.6)" */
  engine: string;
}

export interface VariationsSetup {
  templates: PromptTemplate[];
  tones: string[];
  maxCount: number;
  providers: VariationsProvider[];
}

export interface VariationsInput {
  template: string;
  request: string;
  audience?: string;
  tone?: string;
  language?: string;
  /** a page whose texts the model takes facts and voice from */
  page?: string;
  count: number;
  /** 0..1 */
  creativity: number;
  provider?: string;
}

export type Variation = Record<string, string>;

export interface Generation {
  id: string;
  created?: string;
  template: string;
  engine?: string;
  provider?: string;
  input: VariationsInput;
  favorites: number[];
  count: number;
  /** only when the generation is read whole */
  variations?: Variation[];
}

/** An experience fragment template, as the editor lists them. */
export interface XfTemplateChoice {
  path: string;
  title: string;
}

export interface VariationsClient {
  setup(): Promise<VariationsSetup>;
  history(): Promise<Generation[]>;
  generation(id: string): Promise<Generation>;
  generate(input: VariationsInput): Promise<Generation>;
  favorite(id: string, index: number, on: boolean): Promise<Generation>;
  remove(id: string): Promise<void>;
  /** Experience fragment templates (the editor's `op=xfTemplates`). */
  xfTemplates(): Promise<XfTemplateChoice[]>;
  /** A new experience fragment whose variations are the variations chosen (`parts`: see {@link partsOf}). */
  exportToXf(options: { parent: string; title: string; template: string; parts: string[] }): Promise<{ fragment: string; variations: string[] }>;
}

async function errorOf(res: Response, what: string): Promise<Error> {
  try {
    const body = (await res.json()) as { error?: string };
    if (body.error) return new Error(body.error);
  } catch {
    /* not JSON */
  }
  return new Error(`${what} failed (HTTP ${res.status})`);
}

export class HttpVariationsClient implements VariationsClient {
  constructor(private readonly base = '') {}

  private async get<T>(params: Record<string, string>, what: string): Promise<T> {
    const res = await fetch(`${this.base}${API}?${new URLSearchParams(params)}`, { credentials: 'same-origin' });
    if (!res.ok) throw await errorOf(res, what);
    return (await res.json()) as T;
  }

  private async post<T>(body: object, what: string): Promise<T> {
    const res = await fetch(`${this.base}${API}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw await errorOf(res, what);
    return (await res.json()) as T;
  }

  setup() {
    return this.get<VariationsSetup>({ op: 'setup' }, 'Generate Variations');
  }

  async history() {
    return (await this.get<{ items: Generation[] }>({ op: 'history' }, 'History')).items;
  }

  generation(id: string) {
    return this.get<Generation>({ op: 'generation', id }, 'Generation');
  }

  generate(input: VariationsInput) {
    return this.post<Generation>({ op: 'generate', ...input }, 'Generate');
  }

  favorite(id: string, index: number, on: boolean) {
    return this.post<Generation>({ op: 'favorite', id, index, on }, 'Favorite');
  }

  async remove(id: string) {
    await this.post({ op: 'delete', id }, 'Delete');
  }

  async xfTemplates() {
    const res = await fetch(`${this.base}/bin/studio/editor.json?op=xfTemplates`, { credentials: 'same-origin' });
    if (!res.ok) throw await errorOf(res, 'Experience fragment templates');
    return ((await res.json()) as { templates: XfTemplateChoice[] }).templates;
  }

  async exportToXf(options: { parent: string; title: string; template: string; parts: string[] }) {
    const body = new URLSearchParams({ op: 'exportToXf', parent: options.parent, title: options.title, template: options.template, _charset_: 'utf-8' });
    for (const p of options.parts) body.append('part', p);
    const res = await fetch(`${this.base}/bin/studio/editor.json`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body,
    });
    if (!res.ok) throw await errorOf(res, 'Export');
    return (await res.json()) as { fragment: string; variations: string[] };
  }
}

/** The component a part becomes in an experience fragment: Title, Button or Text. */
export function kindOf(key: string): 'title' | 'text' | 'button' {
  if (['title', 'headline', 'name', 'subject', 'question'].includes(key)) return 'title';
  if (key === 'cta') return 'button';
  return 'text';
}

/** The chosen variations as `<n>:<kind>:<text>` parts, in their order (n counts the chosen ones). */
export function partsOf(variations: Variation[], chosen: number[], fields: VariationField[]): string[] {
  const out: string[] = [];
  [...chosen].sort((a, b) => a - b).forEach((i, n) => {
    for (const f of fields) {
      const text = (variations[i]?.[f.key] ?? '').trim();
      if (text) out.push(`${n}:${kindOf(f.key)}:${text}`);
    }
  });
  return out;
}

/** Where a fragment for content of a page goes: the site's experience fragments folder. */
export function xfFolderFor(page?: string): string {
  const m = (page ?? '').match(/^\/content\/([^/]+)/);
  return m && m[1] !== 'experience-fragments' ? `/content/experience-fragments/${m[1]}` : '/content/experience-fragments';
}

/** A variation as text to paste: each part on its own line, labelled when there are several. */
export function variationText(v: Variation, fields: VariationField[]): string {
  if (fields.length === 1) return v[fields[0].key] ?? '';
  return fields
    .filter((f) => (v[f.key] ?? '').trim())
    .map((f) => `${f.label}: ${v[f.key]}`)
    .join('\n');
}

/** The first words of a request, for a list. */
export function snippet(text: string, max = 80): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
}
