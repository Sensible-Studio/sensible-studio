import { describe, expect, it } from 'vitest';
import { align, blocks, highlight, shownOnPage, unhighlight } from './highlight';

const page = (body: string) => new DOMParser().parseFromString(`<!doctype html><html><head></head><body>${body}</body></html>`, 'text/html');

const VERSION = `
  <header><nav><a href="/en.html">Home</a></nav></header>
  <h1>Ghost Test</h1>
  <img src="/bin/studio/versionpreview.html?path=%2Fcontent%2Fsite%2Fen&amp;version=v1&amp;res=/content/site/en/jcr:content/root/image.coreimg.jpeg/1/hero.jpeg" alt="Hero">
  <p>WKND is a collective of outdoors and travel enthusiasts.</p>
  <p>This paragraph was taken out later.</p>
  <p>Contact <b>us</b> today</p>
  <script>var x = 'not text';</script>
`;

const CURRENT = `
  <header><nav><a href="/en.html">Home</a></nav></header>
  <h1>Ghost Test</h1>
  <img src="/site/en/_jcr_content/root/image.coreimg.60.1280.jpeg/2/hero.jpeg" alt="Hero">
  <p>WKND is a collective of outdoors, music and travel enthusiasts.</p>
  <p>Contact <b>us</b> tomorrow</p>
  <p>A brand new closing line.</p>
  <img src="/site/en/_jcr_content/root/image_2.coreimg.jpeg/3/new.jpeg" alt="">
`;

describe('highlighting changes in rendered pages', () => {
  it('reads what a reader sees: texts of their own and images by file', () => {
    const keys = blocks(page(VERSION)).map((b) => b.key);
    expect(keys).toEqual([
      'Home',
      'Ghost Test',
      'image:hero.jpeg||Hero',
      'WKND is a collective of outdoors and travel enthusiasts.',
      'This paragraph was taken out later.',
      'Contact today',
      'us',
    ]);
    // the same image through the preview and on the live page is the same image
    expect(blocks(page(CURRENT))[2].key).toBe('image:hero.jpeg||Hero');
  });

  it('pairs alike texts as changed and the rest as added or removed', () => {
    const pairs = align(blocks(page(VERSION)), blocks(page(CURRENT))).map((p) => p.kind);
    expect(pairs).toEqual(['same', 'same', 'same', 'changed', 'removed', 'changed', 'same', 'added', 'added']);
  });

  it('marks words in both pages and takes the marks off again', () => {
    const before = page(VERSION);
    const after = page(CURRENT);
    expect(highlight(before, after)).toEqual({ changed: 2, added: 2, removed: 1, revealed: 0 });

    const p = after.querySelectorAll('p')[0];
    expect(p.classList.contains('studio-diff-changed')).toBe(true);
    expect(Array.from(p.querySelectorAll('ins.studio-diff')).map((e) => e.textContent?.trim())).toEqual(['outdoors, music']);
    expect(Array.from(before.querySelectorAll('p')[0].querySelectorAll('del.studio-diff')).map((e) => e.textContent?.trim())).toEqual(['outdoors']);
    expect(before.querySelectorAll('p')[1].classList.contains('studio-diff-removed')).toBe(true);
    expect(after.querySelectorAll('p')[2].classList.contains('studio-diff-added')).toBe(true);
    expect(after.querySelectorAll('img')[1].classList.contains('studio-diff-added')).toBe(true);
    // a text with markup inside: word by word around the markup, which stays
    const contact = after.querySelectorAll('p')[1];
    expect(contact.classList.contains('studio-diff-changed')).toBe(true);
    expect(contact.querySelector('b')?.textContent).toBe('us');
    expect(contact.querySelector('ins')?.textContent?.trim()).toBe('tomorrow');
    expect(contact.textContent).toBe('Contact us tomorrow');
    expect(before.querySelectorAll('p')[2].querySelector('del')?.textContent?.trim()).toBe('today');
    expect(after.getElementById('studio-diff-style')).not.toBeNull();

    unhighlight(after);
    expect(after.querySelector('.studio-diff-changed, .studio-diff-added, ins, .studio-diff-words')).toBeNull();
    expect(after.querySelectorAll('p')[1].innerHTML).toBe('Contact <b>us</b> tomorrow');
    expect(after.querySelectorAll('p')[0].textContent).toBe('WKND is a collective of outdoors, music and travel enthusiasts.');
    expect(after.getElementById('studio-diff-style')).toBeNull();
  });

  it('takes a replaced text in the same place as changed', () => {
    const before = page('<nav><a>FAQs</a></nav><h1>FAQs</h1><p>Same</p>');
    const after = page('<nav><a>Help</a></nav><h1>Help</h1><p>Same</p>');
    expect(highlight(before, after)).toEqual({ changed: 2, added: 0, removed: 0, revealed: 0 });
    expect(before.querySelector('h1 del')?.textContent).toBe('FAQs');
    expect(after.querySelector('h1 ins')?.textContent).toBe('Help');

    // an image taken out in between does not keep the titles from pairing
    const withImage = page('<nav><a>FAQs</a></nav><h1>FAQs</h1><img src="/a/hero.jpeg" alt=""><p>Same</p>');
    expect(highlight(withImage, page('<nav><a>Help</a></nav><h1>Help</h1><p>Same</p>'))).toEqual({ changed: 2, added: 0, removed: 1, revealed: 0 });
    expect(withImage.querySelector('img')?.classList.contains('studio-diff-removed')).toBe(true);
  });

  it('tells a replaced upload from the same image by the time in its URL', () => {
    const img = (src: string) => page(`<h1>Title</h1><img src="${src}" alt="">`);
    const preview = '/bin/studio/versionpreview.html?path=%2Fc&version=v&res=/c/jcr:content/image.coreimg.png/1572044551787/photo.png';
    expect(highlight(img(preview), img('/c/_jcr_content/image.coreimg.60.640.png/1572044551787/photo.png'))).toEqual({ changed: 0, added: 0, removed: 0, revealed: 0 });
    expect(highlight(img(preview), img('/c/_jcr_content/image.coreimg.png/1792324794491/photo.png'))).toEqual({ changed: 0, added: 1, removed: 1, revealed: 0 });
  });

  it('opens hidden parts that hold changes, and closes them again', () => {
    // as in the dialog: documents in frames, which have a window to compute styles with
    const framed = (answer: string, menu: string) => {
      const frame = document.createElement('iframe');
      document.body.append(frame);
      const d = frame.contentDocument!;
      d.open();
      d.write(`<!doctype html><html><head><style>.panel--hidden{display:none}</style></head><body>
        <nav><ul style="display:none"><li><a>${menu}</a></li></ul></nav>
        <h1>FAQ</h1><div class="panel panel--hidden" style="color: red"><p>${answer}</p></div><div hidden><p>Same here</p></div>
        <details><summary>More</summary><p>${answer} too</p></details></body></html>`);
      d.close();
      return d;
    };
    const before = framed('We were founded in 2019.', 'FAQs');
    const after = framed('We were founded in 2020.', 'Help');
    expect(highlight(before, after)).toEqual({ changed: 3, added: 0, removed: 0, revealed: 4 });
    const panel = after.querySelector<HTMLElement>('.panel')!;
    expect(panel.classList.contains('studio-diff-revealed')).toBe(true);
    expect(after.defaultView!.getComputedStyle(panel).display).toBe('block');
    expect(after.querySelector('details')!.open).toBe(true);
    expect(after.querySelector('[hidden]')).not.toBeNull(); // nothing changed in there: stays hidden
    expect(after.querySelector<HTMLElement>('nav ul')!.style.display).toBe('none'); // the menu stays closed

    unhighlight(after);
    expect(panel.getAttribute('style')).toBe('color: red');
    expect(after.defaultView!.getComputedStyle(panel).display).toBe('none');
    expect(after.querySelector('details')!.open).toBe(false);
    expect(after.querySelector('[data-studio-diff-revealed], .studio-diff-revealed')).toBeNull();
  });

  it('tells which content changes show on the pages', () => {
    const before = page('<h1>Old title</h1><p>WKND is a collective of outdoors and travel enthusiasts.</p><img src="/x/_jcr_content/i.coreimg.jpeg/1/hero.jpeg">');
    const after = page('<h1>New title</h1><p>WKND is a collective\n of outdoors, music and travel enthusiasts.</p>');
    const shown = (c: Parameters<typeof shownOnPage>[0]) => shownOnPage(c, before, after);
    expect(shown({ node: '', kind: 'changed', property: 'jcr:title', before: 'Old title', after: 'New title' })).toBe(true);
    expect(shown({ node: 'root/text', kind: 'changed', property: 'text', before: '<p>x</p>', after: '<p>WKND is a collective of outdoors, music and travel enthusiasts.</p>' })).toBe(true);
    expect(shown({ node: 'root/image', kind: 'removed', properties: { fileReference: '/content/dam/wknd/hero.jpeg', isDecorative: 'false' } })).toBe(true);
    // settings that render nothing
    expect(shown({ node: '', kind: 'changed', property: 'cq:tags', before: 'wknd:a', after: 'wknd:a,wknd:b' })).toBe(false);
    expect(shown({ node: 'root/teaser', kind: 'changed', property: 'linkURL', before: '/content/wknd/a', after: '/content/wknd/b' })).toBe(false);
    expect(shown({ node: 'root/image', kind: 'propertyAdded', property: 'displayPopupTitle', after: 'true' })).toBe(false);
  });

  it('finds nothing to mark in the same page', () => {
    expect(highlight(page(VERSION), page(VERSION))).toEqual({ changed: 0, added: 0, removed: 0, revealed: 0 });
  });
});
