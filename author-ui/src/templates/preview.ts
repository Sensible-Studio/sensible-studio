/**
 * "Generate Preview" for a template's thumbnail, in the browser: the template's structure is rendered (as published,
 * `wcmmode=disabled`) in a hidden frame, its styles and images are inlined, and the result is drawn through an SVG
 * `foreignObject` onto a canvas, scaled to a 4:3 thumbnail. Same-origin content only: what the browser may not read
 * (fonts or images from other hosts) falls back or is left out, as in a quick preview.
 */

const WIDTH = 1280;
const HEIGHT = 960;
const OUT_WIDTH = 480;
const OUT_HEIGHT = 360;

export async function generatePreview(pageUrl: string): Promise<File> {
  const frame = document.createElement('iframe');
  frame.setAttribute('aria-hidden', 'true');
  frame.style.cssText = `position:fixed;left:-${WIDTH * 2}px;top:0;width:${WIDTH}px;height:${HEIGHT}px;border:0;visibility:hidden`;
  document.body.appendChild(frame);
  try {
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error('The template did not render in time')), 20000);
      frame.onload = () => {
        window.clearTimeout(timer);
        resolve();
      };
      frame.src = pageUrl;
    });
    const doc = frame.contentDocument;
    if (!doc?.documentElement) throw new Error('The template could not be rendered');
    // let late images and layout settle
    await Promise.all([...doc.images].map((img) => (img.complete ? null : new Promise((r) => ((img.onload = r), (img.onerror = r))))));
    await new Promise((r) => window.setTimeout(r, 300));

    const css = await inlineStyles(doc);
    const clone = doc.documentElement.cloneNode(true) as HTMLElement;
    for (const s of [...clone.querySelectorAll('script, noscript, link[rel="stylesheet"], style, iframe')]) s.remove();
    await inlineImages(clone, doc.baseURI);
    const head = clone.querySelector('head') ?? clone.insertBefore(document.createElement('head'), clone.firstChild);
    const style = document.createElement('style');
    style.textContent = css;
    head.appendChild(style);
    clone.setAttribute('xmlns', 'http://www.w3.org/1999/xhtml');
    const html = new XMLSerializer().serializeToString(clone);
    const svg =
      `<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}">` +
      `<foreignObject x="0" y="0" width="100%" height="100%">${html}</foreignObject></svg>`;
    const image = await loadImage(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`);
    const canvas = document.createElement('canvas');
    canvas.width = OUT_WIDTH;
    canvas.height = OUT_HEIGHT;
    const g = canvas.getContext('2d');
    if (!g) throw new Error('No canvas');
    g.fillStyle = '#fff';
    g.fillRect(0, 0, OUT_WIDTH, OUT_HEIGHT);
    g.drawImage(image, 0, 0, OUT_WIDTH, OUT_HEIGHT);
    const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/png'));
    if (!blob) throw new Error('The preview could not be drawn');
    return new File([blob], 'thumbnail.png', { type: 'image/png' });
  } finally {
    frame.remove();
  }
}

/** The page's style sheets as text, with their `url(...)`s made absolute (same-origin ones as data URLs). */
async function inlineStyles(doc: Document): Promise<string> {
  const parts: string[] = [];
  for (const sheet of [...doc.styleSheets]) {
    let rules: CSSRuleList | null = null;
    try {
      rules = sheet.cssRules;
    } catch {
      continue; // another origin's sheet
    }
    const base = sheet.href ?? doc.baseURI;
    let text = [...rules].map((r) => r.cssText).join('\n');
    const urls = [...new Set([...text.matchAll(/url\((['"]?)([^'")]+)\1\)/g)].map((m) => m[2]))];
    for (const u of urls) {
      if (u.startsWith('data:')) continue;
      const abs = new URL(u, base);
      const data = abs.origin === window.location.origin ? await toDataUrl(abs.href) : null;
      text = text.split(u).join(data ?? abs.href);
    }
    parts.push(text);
  }
  return parts.join('\n');
}

async function inlineImages(root: HTMLElement, base: string): Promise<void> {
  for (const img of [...root.querySelectorAll('img')]) {
    const src = img.getAttribute('src');
    img.removeAttribute('srcset');
    img.removeAttribute('loading');
    if (!src || src.startsWith('data:')) continue;
    const abs = new URL(src, base);
    const data = abs.origin === window.location.origin ? await toDataUrl(abs.href) : null;
    if (data) img.setAttribute('src', data);
    else img.remove();
  }
}

async function toDataUrl(url: string): Promise<string | null> {
  try {
    const res = await fetch(url, { credentials: 'same-origin' });
    if (!res.ok) return null;
    const blob = await res.blob();
    return await new Promise<string>((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result));
      r.onerror = () => reject(r.error);
      r.readAsDataURL(blob);
    });
  } catch {
    return null;
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('The preview could not be drawn'));
    img.src = src;
  });
}
