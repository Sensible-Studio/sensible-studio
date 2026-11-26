import { diffWords, plainText, type DiffChange } from './api';

/**
 * Highlighting the changes inside two rendered pages (a version and the current page, in same-origin frames).
 *
 * The pages are compared as what a reader sees: the text blocks (elements with text of their own) and the images,
 * in document order. Blocks that are the same in both anchor the alignment; between anchors, blocks that resemble each
 * other are paired as changed (their words are marked), the rest are removed (version) or added (current).
 */

export interface Highlighted {
  added: number;
  removed: number;
  changed: number;
  /** hidden parts of the pages (closed accordion items, other tabs) opened because changes are inside */
  revealed: number;
}

interface Block {
  el: Element;
  key: string;
  /** for images, nothing to diff word by word */
  image: boolean;
}

const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'svg', 'SVG', 'HEAD', 'TITLE', 'IFRAME']);

const STYLE = `
.studio-diff-added{background:rgba(45,157,120,.16)!important;outline:2px solid #2d9d78!important;outline-offset:2px}
.studio-diff-removed{background:rgba(227,72,80,.13)!important;outline:2px solid #e34850!important;outline-offset:2px}
.studio-diff-changed{outline:2px dashed #e68619!important;outline-offset:2px}
img.studio-diff-added,img.studio-diff-removed{outline-width:4px!important;outline-offset:-4px!important}
ins.studio-diff{background:rgba(45,157,120,.32);color:inherit;text-decoration:none}
del.studio-diff{background:rgba(227,72,80,.28);color:inherit}
.studio-diff-revealed{outline:2px dotted #2680eb!important;outline-offset:4px}
`;

const CLASSES = ['studio-diff-added', 'studio-diff-removed', 'studio-diff-changed'];

/** The text an element shows by itself (not its child elements'), whitespace collapsed. */
function ownText(el: Element): string {
  let t = '';
  for (const n of Array.from(el.childNodes)) if (n.nodeType === 3) t += n.textContent ?? '';
  return t.replace(/\s+/g, ' ').trim();
}

/**
 * An image, by the file it shows (and the time in its URL): the preview and the live page serve the same image under
 * different URLs.
 */
function imageKey(img: Element): string {
  const src = img.getAttribute('src') ?? '';
  let path = src;
  try {
    const url = new URL(src, 'http://x');
    path = url.searchParams.get('res') ?? url.pathname;
  } catch {
    // keep the raw value
  }
  const file = path.split('/').filter(Boolean).pop() ?? '';
  // the image servlet's URLs carry a modification time: an uploaded file replaced under the same name gets a new one
  const time = path.match(/\/(\d{13})(?=[/.])/)?.[1] ?? '';
  return `image:${file}|${time}|${img.getAttribute('alt') ?? ''}`;
}

export function blocks(doc: Document): Block[] {
  const out: Block[] = [];
  const walk = (el: Element) => {
    if (SKIP.has(el.tagName)) return;
    if (el.tagName === 'IMG') {
      out.push({ el, key: imageKey(el), image: true });
      return;
    }
    const text = ownText(el);
    if (text) out.push({ el, key: text, image: false });
    for (const c of Array.from(el.children)) walk(c);
  };
  if (doc.body) walk(doc.body);
  return out;
}

function words(s: string): Set<string> {
  return new Set(s.toLowerCase().split(/\s+/).filter(Boolean));
}

/** How alike two texts are (shared words over the longer one), 0..1. */
function likeness(a: string, b: string): number {
  const x = words(a);
  const y = words(b);
  if (!x.size || !y.size) return 0;
  let common = 0;
  for (const w of x) if (y.has(w)) common++;
  return common / Math.max(x.size, y.size);
}

type Pair = { kind: 'same' | 'changed'; a: Block; b: Block } | { kind: 'removed'; a: Block } | { kind: 'added'; b: Block };

/** Aligns the blocks of two pages (same blocks by longest common subsequence, the rest paired when alike). */
export function align(a: Block[], b: Block[]): Pair[] {
  const out: Pair[] = [];
  const n = a.length;
  const m = b.length;
  if (n * m > 4_000_000) return out; // too large to compare in the browser
  const lcs: Uint16Array[] = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i].key === b[j].key ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  let ra: Block[] = [];
  let rb: Block[] = [];
  const flush = () => {
    // between two anchors: images that are not the same were removed or added (one that stayed is an anchor)
    for (const x of ra) if (x.image) out.push({ kind: 'removed', a: x });
    for (const y of rb) if (y.image) out.push({ kind: 'added', b: y });
    const ta = ra.filter((x) => !x.image);
    const tb = rb.filter((y) => !y.image);
    ra = [];
    rb = [];
    // the same elements one for one: the same places with new texts ("FAQs" → "Help")
    if (ta.length && ta.length === tb.length && ta.every((x, k) => x.el.tagName === tb[k].el.tagName)) {
      ta.forEach((x, k) => out.push({ kind: 'changed', a: x, b: tb[k] }));
      return;
    }
    // otherwise pair alike texts in order; the rest were removed or added
    let j = 0;
    for (const x of ta) {
      let k = j;
      while (k < tb.length && likeness(x.key, tb[k].key) < 0.3) k++;
      if (k < tb.length) {
        for (; j < k; j++) out.push({ kind: 'added', b: tb[j] });
        out.push({ kind: 'changed', a: x, b: tb[k] });
        j = k + 1;
      } else {
        out.push({ kind: 'removed', a: x });
      }
    }
    for (; j < tb.length; j++) out.push({ kind: 'added', b: tb[j] });
  };
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i].key === b[j].key) {
      flush();
      out.push({ kind: 'same', a: a[i], b: b[j] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      ra.push(a[i++]);
    } else {
      rb.push(b[j++]);
    }
  }
  while (i < n) ra.push(a[i++]);
  while (j < m) rb.push(b[j++]);
  flush();
  return out;
}

/** The text nodes an element shows by itself. */
function ownTextNodes(el: Element): Text[] {
  return Array.from(el.childNodes).filter((n): n is Text => n.nodeType === 3 && !!n.textContent?.trim());
}

const collapse = (s: string | null) => (s ?? '').replace(/\s+/g, ' ').trim();

/**
 * Marks the words of a changed block in both pages: in the version what went, in the current page what came. Text
 * node by text node, when both have the same text nodes (links and line breaks in between stay as they are); otherwise
 * the block as a whole. Each rewritten node keeps its original text for {@link unhighlight}.
 */
function markWords(a: Element, b: Element) {
  a.classList.add('studio-diff-changed');
  b.classList.add('studio-diff-changed');
  const na = ownTextNodes(a);
  const nb = ownTextNodes(b);
  if (na.length !== nb.length) return;
  const texts = na.map((n, i) => [collapse(n.textContent), collapse(nb[i].textContent)] as const);
  texts.forEach(([before, after], i) => {
    if (before === after) return;
    const parts = diffWords(before, after);
    rewrite(na[i], parts, 'removed');
    rewrite(nb[i], parts, 'added');
  });
}

function rewrite(node: Text, parts: ReturnType<typeof diffWords>, side: 'removed' | 'added') {
  const doc = node.ownerDocument;
  const wrap = doc.createElement('span');
  wrap.className = 'studio-diff-words';
  wrap.dataset.studioDiffText = node.textContent ?? '';
  const raw = node.textContent ?? '';
  // keep the spaces around the text, which separate it from its neighbours
  const lead = raw.match(/^\s*/)?.[0] ?? '';
  const trail = raw.match(/\s*$/)?.[0] ?? '';
  if (lead) wrap.append(doc.createTextNode(' '));
  for (const p of parts) {
    if (p.type === 'same') wrap.append(doc.createTextNode(p.text));
    else if (p.type === side) {
      const mark = doc.createElement(side === 'added' ? 'ins' : 'del');
      mark.className = 'studio-diff';
      mark.textContent = p.text;
      wrap.append(mark);
    }
  }
  if (trail) wrap.append(doc.createTextNode(' '));
  node.replaceWith(wrap);
}

/** Highlights what changed from the version (`before`) to the current page (`after`); returns how much. */
export function highlight(before: Document, after: Document): Highlighted {
  for (const d of [before, after]) {
    if (!d.getElementById('studio-diff-style')) {
      const style = d.createElement('style');
      style.id = 'studio-diff-style';
      style.textContent = STYLE;
      (d.head ?? d.documentElement).append(style);
    }
  }
  const count: Highlighted = { added: 0, removed: 0, changed: 0, revealed: 0 };
  for (const p of align(blocks(before), blocks(after))) {
    if (p.kind === 'removed') {
      p.a.el.classList.add('studio-diff-removed');
      count.removed++;
    } else if (p.kind === 'added') {
      p.b.el.classList.add('studio-diff-added');
      count.added++;
    } else if (p.kind === 'changed') {
      markWords(p.a.el, p.b.el);
      count.changed++;
    }
  }
  count.revealed = reveal(before) + reveal(after);
  return count;
}

/** Page chrome whose hidden parts (a collapsed menu, the mobile navigation) stay as they are. */
const CHROME = 'header, nav, footer, [role="navigation"], [role="menu"], [role="banner"], [role="contentinfo"]';

/**
 * Opens the hidden parts of a page that hold marked changes — a closed accordion item, a tab not shown — so the
 * changes can be seen; each is outlined and remembers how it was, for {@link unhighlight}. Returns how many.
 */
function reveal(doc: Document): number {
  const view = doc.defaultView;
  if (!view) return 0;
  let opened = 0;
  for (const el of Array.from(doc.querySelectorAll('.studio-diff-added, .studio-diff-removed, .studio-diff-changed'))) {
    if (el.closest(CHROME)) continue;
    for (let p: Element | null = el; p && p !== doc.body; p = p.parentElement) {
      if (!(p instanceof view.HTMLElement) || p.dataset.studioDiffRevealed !== undefined) continue;
      const computed = view.getComputedStyle(p);
      const closedDetails = p instanceof view.HTMLDetailsElement && !p.open;
      const hidden = p.hasAttribute('hidden') || computed.display === 'none' || computed.visibility === 'hidden';
      if (!hidden && !closedDetails) continue;
      p.dataset.studioDiffRevealed = JSON.stringify({ style: p.getAttribute('style'), hidden: p.hasAttribute('hidden'), details: closedDetails });
      p.removeAttribute('hidden');
      if (closedDetails) (p as HTMLDetailsElement).open = true;
      if (view.getComputedStyle(p).display === 'none') p.style.setProperty('display', 'block', 'important');
      if (view.getComputedStyle(p).visibility === 'hidden') p.style.setProperty('visibility', 'visible', 'important');
      p.classList.add('studio-diff-revealed');
      opened++;
    }
  }
  return opened;
}

/** Takes the highlighting off again. */
export function unhighlight(doc: Document) {
  doc.getElementById('studio-diff-style')?.remove();
  for (const w of Array.from(doc.querySelectorAll<HTMLElement>('span.studio-diff-words'))) {
    w.replaceWith(doc.createTextNode(w.dataset.studioDiffText ?? ''));
  }
  for (const c of CLASSES) for (const el of Array.from(doc.querySelectorAll(`.${c}`))) el.classList.remove(c);
  for (const el of Array.from(doc.querySelectorAll<HTMLElement>('[data-studio-diff-revealed]'))) {
    const was = JSON.parse(el.dataset.studioDiffRevealed ?? '{}') as { style?: string | null; hidden?: boolean; details?: boolean };
    if (was.style == null) el.removeAttribute('style');
    else el.setAttribute('style', was.style);
    if (was.hidden) el.setAttribute('hidden', '');
    if (was.details) (el as HTMLDetailsElement).open = false;
    el.classList.remove('studio-diff-revealed');
    delete el.dataset.studioDiffRevealed;
  }
}

/** The first highlighted element of a page, to scroll to. */
export function firstChange(doc: Document): Element | null {
  return doc.querySelector('.studio-diff-added, .studio-diff-removed, .studio-diff-changed');
}

const pageText = new WeakMap<Document, string>();
const pageImages = new WeakMap<Document, string>();

/** All of a page's text without whitespace (rich text and rendered HTML space their blocks differently). */
const squeeze = (t: string) => t.replace(/\s+/g, '');

function textOf(d: Document): string {
  let t = pageText.get(d);
  if (t === undefined) {
    t = squeeze(d.body?.textContent ?? '');
    pageText.set(d, t);
  }
  return t;
}

function imagesOf(d: Document): string {
  let t = pageImages.get(d);
  if (t === undefined) {
    t = Array.from(d.querySelectorAll('img, [data-cmp-src], source'))
      .map((e) => [e.getAttribute('src'), e.getAttribute('srcset'), e.getAttribute('data-cmp-src')].join(' '))
      .join(' ');
    pageImages.set(d, t);
  }
  return t;
}

/**
 * Whether a change of the content shows on the rendered pages: one of its values is found in the page it belongs to
 * (the version for what went, the current page for what came) — as text, or for a DAM path as the image it names.
 * Settings that render nothing (a flag, an ID, a link target) are only in the Changes tab.
 */
export function shownOnPage(c: DiffChange, before: Document, after: Document): boolean {
  const values: Array<[string | undefined, Document]> =
    c.kind === 'changed'
      ? [
          [c.before, before],
          [c.after, after],
        ]
      : c.kind === 'propertyAdded'
        ? [[c.after, after]]
        : c.kind === 'propertyRemoved'
          ? [[c.before, before]]
          : Object.values(c.properties ?? {}).map((v): [string, Document] => [v, c.kind === 'added' ? after : before]);
  for (const [value, doc] of values) {
    const text = plainText(value).trim();
    if (text.length < 3 || /^(true|false|-?\d+(\.\d+)?)$/.test(text)) continue;
    if (/^\/content\/dam\//.test(text)) {
      const file = text.split('/').pop() ?? '';
      if (file && imagesOf(doc).includes(file)) return true;
      continue;
    }
    if (text.startsWith('/')) continue; // a path: where a link goes is not something the page shows
    const words = squeeze(text);
    if (textOf(doc).includes(words.slice(0, 120))) return true;
  }
  return false;
}
