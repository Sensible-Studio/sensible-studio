import { describe, expect, it } from 'vitest';
import { accepts, applyColumnState, breakpointFor, columnState, containerOf, nextSibling, scanEditables } from './editables';
import { instanceLabel } from './labels';

const C = '/content/site/en/jcr:content';

function page(html: string): Document {
  const doc = document.implementation.createHTMLDocument('page');
  doc.body.innerHTML = html;
  return doc;
}

// The markup Sensible Studio renders with wcmmode=edit (see EditMeta / ResponsiveGridServlet).
const EDIT_MARKUP = `
  <div class="container" data-studio-path="${C}/root" data-studio-type="site/components/container" data-studio-title="Container" data-studio-dialog="true" data-studio-container="true">
    <div class="aem-Grid aem-Grid--12" data-studio-grid="${C}/root">
      <div class="title aem-GridColumn aem-GridColumn--default--6 aem-GridColumn--offset--default--2" data-studio-path="${C}/root/title" data-studio-type="site/components/title" data-studio-title="Title" data-studio-dialog="true"><h1>Hi</h1></div>
      <div class="image aem-GridColumn aem-GridColumn--default--12 aem-GridColumn--phone--hide" data-studio-path="${C}/root/image" data-studio-type="site/components/image" data-studio-title="Image" data-studio-drop="./fileReference" data-studio-drop-accept="image/.*"></div>
      <div class="new section aem-GridColumn" data-studio-placeholder="${C}/root"></div>
    </div>
  </div>
  <div class="aem-Grid" data-studio-grid="${C}/legacy"><div data-studio-placeholder="${C}/legacy"></div></div>`;

describe('scanEditables', () => {
  const tree = scanEditables(page(EDIT_MARKUP));

  it('builds the component hierarchy from data-studio attributes', () => {
    expect(tree.all.map((e) => e.path)).toEqual([`${C}/root`, `${C}/root/title`, `${C}/root/image`, `${C}/legacy`]);
    const root = tree.byPath.get(`${C}/root`)!;
    expect(root.container).toBe(true);
    expect(root.placeholder).not.toBeNull();
    expect(root.grid?.dataset.studioGrid).toBe(`${C}/root`);
    expect(root.children.map((c) => c.title)).toEqual(['Title', 'Image']);
    const title = tree.byPath.get(`${C}/root/title`)!;
    expect(title.dialog).toBe(true);
    expect(containerOf(title)).toBe(root);
    expect(nextSibling(title)?.path).toBe(`${C}/root/image`);
    expect(tree.byPath.get(`${C}/root/image`)!.drop).toBe('./fileReference');
  });

  it('reads the Sync state of linked copy components', () => {
    const t = scanEditables(
      page(`<div data-studio-path="${C}/a" data-studio-type="t" data-studio-msm="inherited"></div>
            <div data-studio-path="${C}/b" data-studio-type="t" data-studio-msm="cancelled"></div>
            <div data-studio-path="${C}/c" data-studio-type="t"></div>`),
    );
    expect(t.all.map((e) => e.msm)).toEqual(['inherited', 'cancelled', undefined]);
  });

  it('turns a bare grid into a synthetic layout container', () => {
    const legacy = tree.byPath.get(`${C}/legacy`)!;
    expect(legacy.container).toBe(true);
    expect(legacy.title).toBe('Layout Container');
    expect(legacy.placeholder).not.toBeNull();
  });

  it('reads and rewrites column classes per breakpoint', () => {
    const title = tree.byPath.get(`${C}/root/title`)!.el;
    expect(columnState(title, 'default')).toEqual({ width: 6, offset: 2, hidden: false, newline: false });
    expect(columnState(title, 'phone')).toEqual({ width: 6, offset: 2, hidden: false, newline: false }); // falls back to default
    const image = tree.byPath.get(`${C}/root/image`)!.el;
    expect(columnState(image, 'phone').hidden).toBe(true);
    applyColumnState(title, 'phone', { width: 12, offset: 0, hidden: false, newline: true });
    expect(title.className).toContain('aem-GridColumn--phone--12');
    expect(title.className).toContain('aem-GridColumn--phone--newline');
    expect(title.className).toContain('aem-GridColumn--default--6');
    // the explicit 0 is what overrides the default offset inside the breakpoint's media query
    expect(title.className).toContain('aem-GridColumn--offset--phone--0');
    expect(columnState(title, 'phone')).toEqual({ width: 12, offset: 0, hidden: false, newline: true });
  });
});

describe('helpers', () => {
  it('picks the breakpoint for a viewport width', () => {
    const bps = [
      { name: 'phone', width: 768 },
      { name: 'tablet', width: 1200 },
    ];
    expect(breakpointFor(375, bps)).toBe('phone');
    expect(breakpointFor(768, bps)).toBe('tablet');
    expect(breakpointFor(1024, bps)).toBe('tablet');
    expect(breakpointFor(1440, bps)).toBe('default');
    expect(breakpointFor(500, [])).toBe('default');
  });

  it('matches mime types against accept patterns', () => {
    expect(accepts('image/.*', 'image/png')).toBe(true);
    expect(accepts('image/.*,video/.*', 'video/mp4')).toBe(true);
    expect(accepts('image/.*', 'application/pdf')).toBe(false);
    expect(accepts(undefined, 'anything')).toBe(true);
  });

  it('labels instances by title or text', () => {
    const content = { root: { teaser: { 'jcr:title': 'Climbing New Zealand' }, text: { text: '<p>Hello&nbsp;<b>world</b></p>' } } };
    expect(instanceLabel(content, C, `${C}/root/teaser`)).toBe('Climbing New Zealand');
    expect(instanceLabel(content, C, `${C}/root/text`)).toBe('Hello world');
    expect(instanceLabel(content, C, `${C}/root`)).toBeNull();
  });
});

describe('new section slots', () => {
  it("treats a container's `<path>/*` include as its placeholder", () => {
    const doc = document.implementation.createHTMLDocument('x');
    doc.body.innerHTML = `
      <div data-studio-path="/c/root/container" data-studio-type="site/container" data-studio-container="true">
        <div class="new section" data-studio-path="/c/root/container/*" data-studio-type="wcm/foundation/components/parsys/newpar"></div>
      </div>`;
    const tree = scanEditables(doc);
    const c = tree.byPath.get('/c/root/container')!;
    expect(c.placeholder?.dataset.studioPath).toBe('/c/root/container/*');
    expect(c.children).toHaveLength(0);
    expect(tree.byPath.has('/c/root/container/*')).toBe(false);
    // its height comes from the authoring styles the editor adds to the page (AEM's `.new`), not from inline style
    expect(c.placeholder?.style.minHeight).toBe('');
  });

  it('gives an empty container that rendered no slot one of its own, once', () => {
    const doc = document.implementation.createHTMLDocument('x');
    doc.body.innerHTML = `
      <div data-studio-path="/c/root/container" data-studio-type="site/container" data-studio-container="true">
        <div class="cmp-container"></div>
      </div>
      <div data-studio-path="/c/root/full" data-studio-type="site/container" data-studio-container="true">
        <div data-studio-path="/c/root/full/title" data-studio-type="site/title"></div>
      </div>`;
    const first = scanEditables(doc).byPath.get('/c/root/container')!;
    expect(first.placeholder?.parentElement?.className).toBe('cmp-container');
    expect(first.placeholder?.dataset.studioPath).toBe('/c/root/container/*');
    const again = scanEditables(doc);
    expect(again.byPath.get('/c/root/container')!.placeholder).toBe(first.placeholder);
    expect(doc.querySelectorAll('[data-studio-path$="/*"]')).toHaveLength(1);
    expect(again.byPath.get('/c/root/full')!.placeholder).toBeNull();
  });
});
