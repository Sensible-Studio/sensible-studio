// The pictures of Publishing (author/publishing.md).
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=switch],input,select,textarea,h2,h3')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,34)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,120).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,1100)`;
const SRC = '/sites.html/content/weekender/language-masters/en/magazine';
const fields = `'FIELDS ' + [...document.querySelectorAll('input,textarea,select,button[aria-haspopup]')].filter(e=>e.getBoundingClientRect().width>0).map((e,i)=>i+':'+(e.type||e.tagName)+':'+(e.getAttribute('aria-label')||e.labels?.[0]?.textContent||e.textContent||'').slice(0,26)+'='+String(e.value||'').slice(0,30)).join(' | ')`;
const setField = (name, value) => ({ eval: `(() => { const e = [...document.querySelectorAll('input,textarea')].find(x => (x.getAttribute('aria-label') || x.labels?.[0]?.textContent || '').trim().startsWith(${JSON.stringify(name)})); const p = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(p, 'value').set.call(e, ${JSON.stringify(value)}); e.dispatchEvent(new Event('input', { bubbles: true })); })()` });
const open = [{ goto: '/replication.html' }, { wait: 4000 }];
// On the instance, made once by hand: the target "Second live site" (id second_site), which points at a port nothing
// listens on, so that what is published waits in its queue; the page "A Winter Swim in the Fjord" was published once
// (three requests). The two targets of the product are disabled on this instance.
export default [
  { name: 'console', steps: [...open, { shot: 'publishing-console' }, { scroll: [400, 400, 380] }, { wait: 600 }, { click: { text: 'Second live site' } }, { wait: 2500 }, { shot: 'publishing-target-blocked' }, { scroll: [400, 400, -2000] }, { wait: 1000 }, { log: info }, { shot: 'publishing-queue' },
    { click: { text: 'History', closest: 'button,[role="tab"]' } }, { wait: 1500 }, { shot: 'publishing-history' }, { click: { text: 'Queue', contains: true, closest: 'button,[role="tab"]' } }, { wait: 1000 }] },
  // takes one request out of the queue: run it only to try the bin (the queue is filled again by publishing a page)
  // { name: 'remove', steps: [...open, { click: { text: 'Second live site' } }, { wait: 2500 }, { scroll: [400, 400, -2000] }, { wait: 800 }, { click: '[aria-label$="winter-swim from the queue"]' }, { wait: 2500 }, { shot: 'publishing-queue-removed' }] },
  { name: 'target', steps: [...open, button('New target'), { wait: 2000 }, { shot: 'publishing-target' }, { click: { text: 'Publish instance (delivers the content)', closest: 'button' } }, { wait: 600 }, { shot: 'publishing-target-kinds', keepPointer: true }] },
  { name: 'unpublished', steps: [...open, { scroll: [700, 600, 1400] }, { wait: 1000 }, { shot: 'publishing-unpublished' }] },
  { name: 'dialog', steps: [{ goto: SRC }, { wait: 2500 }, pick('Ski Touring'), { wait: 1500 }, button('Publish'), { wait: 3500 }, { shot: 'publishing-dialog' }, { click: { text: 'Later', closest: 'label' } }, { wait: 800 }, { shot: 'publishing-dialog-later' }] },
  { name: 'unpublish', steps: [{ goto: SRC }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1500 }, button('More'), { wait: 600 }, { click: { text: 'Unpublish', last: true } }, { wait: 2500 }, { shot: 'publishing-unpublish' }] },
  { name: 'states', steps: [{ goto: SRC }, { wait: 2500 }, pick('A Winter Swim in the Fjord'), { wait: 1800 }, { shot: 'publishing-states' }] },
];
