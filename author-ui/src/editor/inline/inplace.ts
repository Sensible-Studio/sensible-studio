import type { ComponentInfo, InplaceConfig } from '../api/StudioClient';
import type { Editable } from '../model/editables';

/** How a component is edited in place: a plain string (title) or rich text (text). */
export type InplaceKind = 'title' | 'text';

export interface InplaceEditing {
  kind: InplaceKind;
  /** The property the edited value is written to. */
  property: string;
  /** For rich text: formats of the paragraph format menu (`paraformat/formats`). */
  formats: Array<{ tag: string; label: string }>;
  /** For rich text: which toolbar features are on. */
  features: RteFeatures;
}

export interface RteFeatures {
  bold: boolean;
  italic: boolean;
  underline: boolean;
  justify: boolean;
  lists: boolean;
  links: boolean;
  paraformat: boolean;
}

const DEFAULT_FORMATS = [
  { tag: 'p', label: 'Paragraph' },
  { tag: 'h1', label: 'Heading 1' },
  { tag: 'h2', label: 'Heading 2' },
  { tag: 'h3', label: 'Heading 3' },
  { tag: 'h4', label: 'Heading 4' },
  { tag: 'h5', label: 'Heading 5' },
  { tag: 'h6', label: 'Heading 6' },
  { tag: 'blockquote', label: 'Quote' },
  { tag: 'pre', label: 'Preformatted' },
];

/** Does the component edit in place at all (text, title or the image editor)? */
export function hasInplaceEditor(info: ComponentInfo | undefined): boolean {
  const ipe = info?.inplace;
  if (!ipe || ipe.active === false || ipe.active === 'false') return false;
  return ['text', 'title', 'plaintext', 'image'].includes((ipe.editorType ?? '').toLowerCase());
}

/**
 * What the component's `cq:inplaceEditing` asks for, or null when the component is only edited
 * through its dialog (no config, `active=false`, or an editor type Sensible Studio does not edit in place: image, hybrid).
 */
export function inplaceEditing(info: ComponentInfo | undefined): InplaceEditing | null {
  const ipe = info?.inplace;
  if (!ipe) return null;
  if (ipe.active === false || ipe.active === 'false') return null;
  const type = (ipe.editorType ?? '').toLowerCase();
  const config = configOf(ipe);
  if (type === 'title' || type === 'plaintext') {
    return {
      kind: 'title',
      property: str(config.textPropertyName) ?? str(ipe.textPropertyName) ?? (type === 'title' ? 'jcr:title' : 'text'),
      formats: [],
      features: NO_FEATURES,
    };
  }
  if (type === 'text') {
    return {
      kind: 'text',
      property: str(config.textPropertyName) ?? str(ipe.textPropertyName) ?? 'text',
      formats: formatsOf(config),
      features: featuresOf(config),
    };
  }
  return null;
}

const NO_FEATURES: RteFeatures = {
  bold: false,
  italic: false,
  underline: false,
  justify: false,
  lists: false,
  links: false,
  paraformat: false,
};

type Node = Record<string, unknown>;

function configOf(ipe: InplaceConfig): Node {
  const name = str(ipe.configPath) ?? 'inplaceEditingConfig';
  const c = ipe[name];
  return c && typeof c === 'object' ? (c as Node) : {};
}

const str = (v: unknown): string | undefined => (typeof v === 'string' && v.trim() ? v.trim() : undefined);
const node = (v: unknown): Node | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Node) : undefined);

/** `rtePlugins/<plugin>/features`: `*` (all), `-` (none) or a comma-separated list. */
function featureList(plugins: Node | undefined, plugin: string): string[] | 'all' | 'none' {
  const p = node(plugins?.[plugin]);
  const f = p?.features;
  const list = Array.isArray(f) ? f.map(String) : typeof f === 'string' ? f.split(',') : null;
  if (!list)
    return plugin === 'format' || plugin === 'lists' || plugin === 'links' || plugin === 'paraformat' || plugin === 'justify'
      ? 'all'
      : 'none';
  const items = list.map((s) => s.trim()).filter(Boolean);
  if (items.includes('*')) return 'all';
  if (items.length === 0 || items.includes('-')) return 'none';
  return items;
}

const has = (f: string[] | 'all' | 'none', name: string) => f === 'all' || (f !== 'none' && f.includes(name));

function featuresOf(config: Node): RteFeatures {
  const plugins = node(config.rtePlugins);
  const format = featureList(plugins, 'format');
  const lists = featureList(plugins, 'lists');
  const links = featureList(plugins, 'links');
  const justify = featureList(plugins, 'justify');
  const para = featureList(plugins, 'paraformat');
  return {
    bold: has(format, 'bold'),
    italic: has(format, 'italic'),
    underline: has(format, 'underline'),
    justify: justify !== 'none',
    lists: lists !== 'none',
    links: has(links, 'modifylink'),
    paraformat: para !== 'none',
  };
}

function formatsOf(config: Node): Array<{ tag: string; label: string }> {
  const formats = node(node(node(config.rtePlugins)?.paraformat)?.formats);
  if (!formats) return DEFAULT_FORMATS;
  const out: Array<{ tag: string; label: string }> = [];
  for (const v of Object.values(formats)) {
    const f = node(v);
    const tag = str(f?.tag);
    if (tag && tag !== 'hr') out.push({ tag: tag.toLowerCase(), label: str(f?.description) ?? tag });
  }
  return out.length ? out : DEFAULT_FORMATS;
}

/**
 * The element whose content is edited: `editElementQuery` from the config, else (AEM's default) the
 * component's first element child; for titles the heading inside it.
 */
export function inplaceTarget(e: Editable, editing: InplaceEditing, info: ComponentInfo | undefined): HTMLElement | null {
  const config = info?.inplace ? configOf(info.inplace) : {};
  const query = str(config.editElementQuery) ?? str(info?.inplace?.editElementQuery);
  let el: HTMLElement | null = null;
  if (query && query !== ':first') {
    try {
      el = e.el.querySelector<HTMLElement>(query);
    } catch {
      el = null;
    }
  }
  if (!el) el = (e.el.firstElementChild as HTMLElement | null) ?? e.el;
  if (editing.kind === 'title') {
    const heading = el.matches('h1,h2,h3,h4,h5,h6') ? el : el.querySelector<HTMLElement>('h1,h2,h3,h4,h5,h6');
    if (heading) return heading;
    // a title rendered as a link: edit the link text
    return el.querySelector<HTMLElement>('a') ?? el;
  }
  return el;
}

/** The HTML saved for a rich text: what the author typed, without editor artefacts or empty tails. */
export function cleanHtml(html: string): string {
  return html
    .replace(/\s(contenteditable|spellcheck|data-studio-[a-z-]+)="[^"]*"/g, '')
    .replace(/<(p|div)><br\s*\/?><\/\1>\s*$/i, '')
    .trim();
}

/** The text of a title as typed (one line, no HTML). */
export function cleanText(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}
