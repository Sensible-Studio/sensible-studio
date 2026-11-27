// The pictures of "Several sites" (author/sync.md): sources, linked copies, sync.
const COPY = '/sites.html/content/weekender/us/en/magazine';
const SRC = '/sites.html/content/weekender/language-masters/en/magazine';
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
// eslint-disable-next-line no-unused-vars
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30))).filter(Boolean).slice(0,80).join(' | ')`;
const PROPS = '/mnt/overlay/wcm/core/content/sites/properties.html?item=';
// eslint-disable-next-line no-unused-vars
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,700)`;
export default [
  { name: 'copy', steps: [{ goto: COPY }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1800 }, { shot: 'sync-console-copy' }, { scroll: [1250, 600, 500] }, { wait: 800 }, { shot: 'sync-inspector-copy', clip: 'aside:last-of-type', pad: 0 }] },
  { name: 'source', steps: [{ goto: SRC }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1800 }, { shot: 'sync-console-source' }, button('More'), { wait: 600 }, { shot: 'sync-source-more', keepPointer: true }] },
  { name: 'create', steps: [{ goto: COPY }, { wait: 2500 }, button('Create'), { wait: 600 }, { shot: 'sync-create-menu', keepPointer: true }, { click: { text: 'Linked copy', last: true } }, { wait: 2500 }, { type: 'Camping' }, { wait: 1200 }, { shot: 'sync-create-source', keepPointer: true },
    { click: { text: 'language-masters/en/adventures/riverside-camping-australia', contains: true, last: true } }, { wait: 1000 }, button('Next'), { wait: 2000 }, { log: info }, { shot: 'sync-create-properties' }] },
  { name: 'site', steps: [{ goto: '/sites.html/content' }, { wait: 2500 }, button('Create'), { wait: 600 }, { click: { text: 'Site', last: true } }, { wait: 2500 }, { shot: 'sync-site-source' }, { click: { text: 'Weekender Site', last: true } }, { wait: 800 }, button('Next'), { wait: 2500 }, { shot: 'sync-site-properties' }] },
  { name: 'sync', steps: [{ goto: SRC }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1500 }, button('Sync changes'), { wait: 2500 }, { shot: 'sync-changes' }] },
  { name: 'synchronize', steps: [{ goto: COPY }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1500 }, button('Synchronize'), { wait: 2500 }, { shot: 'sync-synchronize' }] },
  { name: 'overview', steps: [{ goto: SRC }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1500 }, button('More'), { wait: 600 }, { click: { text: 'Linked copy Overview', last: true } }, { wait: 3500 }, { shot: 'sync-overview' }] },
  { name: 'editor', steps: [{ goto: '/editor.html/content/weekender/us/en/magazine/arctic-surfing.html' }, { wait: 4000 }, { clickAt: [760, 520] }, { wait: 1000 }, { shot: 'sync-editor' }, button('Linked copy Status'), { wait: 1500 }, { shot: 'sync-editor-status' }] },
  { name: 'props', steps: [{ goto: PROPS + '/content/weekender/us/en/magazine/arctic-surfing' }, { wait: 3500 }, { click: { text: 'Linked copy', closest: '[role="tab"],button' } }, { wait: 1500 }, { shot: 'sync-props-copy' },
    { goto: PROPS + '/content/weekender/language-masters/en/magazine/arctic-surfing' }, { wait: 3500 }, { click: { text: 'Source', closest: '[role="tab"],button' } }, { wait: 1500 }, { shot: 'sync-props-source' }] },
  { name: 'sources', steps: [{ goto: '/libs/wcm/msm/gui/content/blueprintconfig.html' }, { wait: 3000 }, { shot: 'sync-site-sources' }, { click: label('Properties of Weekender Site') }, { wait: 2000 }, { shot: 'sync-site-source-edit' }] },
];
