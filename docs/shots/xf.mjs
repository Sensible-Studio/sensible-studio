// The pictures of Experience Fragments (author/experience-fragments.md).
const ROOT = '/sites.html/content/experience-fragments/weekender/language-masters/en';
const SITE = ROOT + '/site';
const FRAGMENT = SITE + '/sign-in';
const VARIANT = '/editor.html/content/experience-fragments/weekender/language-masters/en/site/header/master.html';
const PAGE = '/editor.html/content/weekender/language-masters/en/magazine/arctic-surfing.html';
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
// eslint-disable-next-line no-unused-vars
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,28))).filter(Boolean).slice(0,70).join(' | ')`;
const XFPROPS = '/mnt/overlay/wcm/core/content/sites/properties.html?item=/content/experience-fragments/weekender/language-masters/en/site/header';
const VPROPS = XFPROPS + '/master';
const tree = (name) => [{ goto: PAGE }, { wait: 4000 }, { click: label('Content Tree') }, { wait: 1200 }, { click: { text: name } }, { wait: 1200 }];
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,500)`;
export default [
  { name: 'console', steps: [{ goto: SITE }, { wait: 2500 }, { shot: 'xf-console' }, button('Create'), { wait: 600 }, { shot: 'xf-create-menu', keepPointer: true }] },
  { name: 'selected', steps: [{ goto: SITE }, { wait: 2500 }, pick('Header'), { wait: 1500 }, { shot: 'xf-selected' }, { click: { text: 'References', closest: 'button,[role="tab"]' } }, { wait: 2500 }, { shot: 'xf-references' }] },
  { name: 'create', steps: [{ goto: SITE }, { wait: 2500 }, button('Create'), { wait: 600 }, { click: { text: 'Experience Fragment', last: true } }, { wait: 2500 }, { click: { text: 'Experience Fragment Web Variation Template', contains: true } }, { wait: 600 }, { shot: 'xf-create-template' },
    button('Next'), { wait: 2000 }, { type: 'Winter sale' }, { wait: 400 }, { shot: 'xf-create-properties' }] },
  { name: 'variants', steps: [{ goto: FRAGMENT }, { wait: 2500 }, { shot: 'xf-variants' }, button('Create'), { wait: 600 }, { shot: 'xf-variant-menu', keepPointer: true },
    { click: { text: 'Variant', last: true } }, { wait: 2500 }, { click: { text: 'Experience Fragment Web Variation Template', contains: true } }, { wait: 600 }, button('Next'), { wait: 2000 }, { type: 'Sign In (Sidebar)' }, { wait: 400 }, { shot: 'xf-variant-properties' }] },
  { name: 'editor', steps: [{ goto: VARIANT }, { wait: 4000 }, { shot: 'xf-editor' }] },
  { name: 'component', steps: [...tree('Experience Fragment'), { shot: 'xf-on-page' }, { click: label('Configure') }, { wait: 2000 }, { shot: 'xf-component-dialog' }] },
  { name: 'convert', steps: [...tree('Social Media Sharing'), { click: label('Convert to experience fragment variant') }, { wait: 2000 }, { shot: 'xf-convert' }] },
  { name: 'properties', steps: [{ goto: XFPROPS }, { wait: 3500 }, { shot: 'xf-properties' }, { goto: VPROPS }, { wait: 3500 }, { shot: 'xf-variant-props' }] },
  { name: 'variations', steps: [{ goto: '/aem/generate-variations.html' }, { wait: 3500 }, { log: `'TPL ' + [...document.querySelectorAll('select option')].map(o=>o.textContent).join(' | ')` }, { shot: 'xf-generate-variations' }] },
];
