// The pictures of Releases (author/releases.md). The first scene makes the release "Winter campaign" when it is not there.
const SRC = '/sites.html/content/weekender/language-masters/en/magazine';
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=switch],input')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,80).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,900)`;
const open = [{ goto: '/launches.html' }, { wait: 3000 }];
// The release itself was made once, by hand in this console: "Winter campaign", from the page Arctic Surfing of the
// language master, following its source. The scenes below open dialogs and cancel them.
export default [
  { name: 'create', steps: [{ goto: SRC }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1500 }, button('Create'), { wait: 600 }, { shot: 'releases-create-menu', keepPointer: true },
    { click: { text: 'Release', last: true } }, { wait: 2000 }, { type: 'Spring campaign' }, { wait: 400 }, { shot: 'releases-create' }] },
  { name: 'console', steps: [...open, { shot: 'releases-console' }, { click: label('Properties') }, { wait: 1500 }, { shot: 'releases-properties' }, { key: 'Escape' }, { wait: 800 },
    { click: label('Go live') }, { wait: 2500 }, { log: dlg }, { shot: 'releases-go-live' }, { key: 'Escape' }, { wait: 800 },
    { click: label('Delete') }, { wait: 1200 }, { shot: 'releases-delete' }, button('Cancel', true), { wait: 600 }] },
  { name: 'new', steps: [...open, button('Create Release'), { wait: 1500 }, { shot: 'releases-new' }] },
  { name: 'editor', steps: [...open, { click: label('Edit') }, { wait: 5000 }, { shot: 'releases-editor' }, { click: label('Page menu') }, { wait: 800 }, { log: info }, { shot: 'releases-page-menu', keepPointer: true }] },
  { name: 'refs', steps: [{ goto: SRC }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1500 }, { click: { text: 'References', closest: 'button,[role="tab"]' } }, { wait: 2500 }, { scroll: [1250, 600, 600] }, { wait: 800 }, { shot: 'releases-references' },
    { click: { text: 'Timeline', closest: 'button,[role="tab"]' } }, { wait: 2500 }, { shot: 'releases-timeline' }] },
  { name: 'history', steps: [{ goto: '/editor.html/content/weekender/language-masters/en/magazine/arctic-surfing.html' }, { wait: 4000 }, { click: label('Select another mode') }, { wait: 500 }, { log: info }, { click: { text: 'History', contains: true, last: true } }, { wait: 2500 }, { shot: 'releases-history' }] },
];
