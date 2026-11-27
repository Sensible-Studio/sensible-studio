// The pictures of Personalization (author/personalization.md).
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const info = `'INFO ' + location.pathname + location.search + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=menuitemradio],[role=switch],input,select,textarea,h2,h3')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,110).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,1000)`;
const ED = '/editor.html/content/weekender/language-masters/en/magazine/winter-swim.html';
const personalize = [{ goto: ED }, { wait: 4500 }, { click: label('Select another mode') }, { wait: 600 }, { click: { text: 'Personalize', contains: true, last: true } }, { wait: 2500 }];
const tb = `'TB ' + [...document.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.x>330&&r.y>100}).map(e=>e.getAttribute('aria-label')||e.textContent.trim().slice(0,24)).join(' | ')`;
const fields = `'FIELDS ' + [...document.querySelectorAll('input,textarea,select,button[aria-haspopup]')].filter(e=>e.getBoundingClientRect().width>0).map((e,i)=>i+':'+(e.type||e.tagName)+':'+(e.getAttribute('aria-label')||e.labels?.[0]?.textContent||e.textContent||'').slice(0,26)+'='+String(e.value||'').slice(0,30)).join(' | ')`;
const selected = [...personalize, { scroll: [760, 500, 900] }, { wait: 1200 }, { clickAt: [700, 800] }, { wait: 1500 }];
// On the instance, made once by hand: the first text of the page "A Winter Swim in the Fjord" is personalized, with
// the experiences Default and Winter (the audiences Winter and Summer come with the Weekender demo), goal Clicked.
export default [
  { name: 'tools', steps: [{ goto: '/studio/start.html#tools' }, { wait: 2000 }, { click: { text: 'Audiences', last: true } }, { wait: 1200 }, { shot: 'personalization-tools' }] },
  { name: 'audiences', steps: [{ goto: '/audiences.html/conf/weekender' }, { wait: 3000 }, { shot: 'personalization-audiences' }, { click: { text: 'Edit', closest: 'button' } }, { wait: 2000 }, { shot: 'personalization-audience' },
    { click: { text: 'geolocation/latitude', contains: true, closest: 'button' } }, { wait: 800 }, { shot: 'personalization-audience-properties', keepPointer: true }] },
  { name: 'modes', steps: [{ goto: ED }, { wait: 4500 }, { click: label('Select another mode') }, { wait: 600 }, { shot: 'personalization-modes', keepPointer: true }, { click: { text: 'Personalize', contains: true, last: true } }, { wait: 2500 }, { shot: 'personalization-mode' }] },
  { name: 'panel', steps: [...selected, { shot: 'personalization-panel' }, button('Add Experience'), { wait: 2000 }, { click: { text: 'Winter', closest: 'button', last: true } }, { wait: 600 }, { click: { text: 'Summer', last: true } }, { wait: 600 }, { shot: 'personalization-add-experience' }] },
  { name: 'goal', steps: [...selected, { click: { text: 'No goal', closest: 'button' } }, { wait: 600 }, { shot: 'personalization-goals', keepPointer: true }, { click: { text: 'Clicked', last: true } }, { wait: 1000 }, { shot: 'personalization-goal' },
    { click: { text: 'A/B test', contains: true, closest: 'label' } }, { wait: 1200 }, { shot: 'personalization-abtest' }] },
  { name: 'redirect', steps: [...personalize, button('Add redirect'), { wait: 1500 }, { log: info }, { shot: 'personalization-redirect' }] },
  { name: 'reports', steps: [{ goto: '/activities.html' }, { wait: 3000 }, { shot: 'personalization-reports' }] },
  { name: 'preview', steps: [{ goto: ED }, { wait: 4500 }, button('Preview'), { wait: 2500 }, { click: label('Visitor simulator') }, { wait: 1500 }, { log: info }, { shot: 'personalization-simulator' }] },
  { name: 'contexthub', steps: [{ goto: '/libs/granite/contexthub/content/admin/contexthub.html' }, { wait: 3000 }, { shot: 'personalization-contexthub' }] },
];
