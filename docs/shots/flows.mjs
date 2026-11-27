// The pictures of Flows and the Inbox (author/flows.md).
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=switch],input,select,textarea,a[href]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,90).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,900)`;
const SRC = '/sites.html/content/weekender/language-masters/en/magazine';
const top = `'TOP ' + [...document.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y<44}).map(e=>e.getAttribute('aria-label')||e.textContent.trim().slice(0,20)).join(' | ')`;
const setField = (name, value) => ({ eval: `(() => { const e = [...document.querySelectorAll('input,textarea')].find(x => (x.getAttribute('aria-label') || x.labels?.[0]?.textContent || '').trim().startsWith(${JSON.stringify(name)})); const p = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(p, 'value').set.call(e, ${JSON.stringify(value)}); e.dispatchEvent(new Event('input', { bubbles: true })); })()` });
const row = (start) => ({ click: `label:has(input[aria-label^="Select ${start}"])` });
// On the instance, started once by hand: the flow "Ski Touring: review before the weekend" (Request for Review on
// the page Ski Touring), and the review of the French translation of "A Winter Swim in the Fjord" (translations.mjs).
export default [
  { name: 'tools', steps: [{ goto: '/studio/start.html#tools/flows' }, { wait: 2000 }, { shot: 'flows-tools' }] },
  { name: 'start', steps: [{ goto: SRC }, { wait: 2500 }, pick('Arctic Surfing'), { wait: 1500 }, button('Create'), { wait: 600 }, { shot: 'flows-create-menu', keepPointer: true }, { click: { text: 'Start Flow', contains: true, last: true } }, { wait: 2000 }, { click: { text: 'Select…', closest: 'button' } }, { wait: 600 }, { shot: 'flows-start-models', keepPointer: true }, { click: { text: 'Request for Review', last: true } }, { wait: 600 },
    setField('Title', 'Arctic Surfing: new pictures'), setField('Comment', 'Please check the captions.'), { wait: 400 }, { shot: 'flows-start' }] },
  { name: 'page', steps: [{ goto: '/editor.html/content/weekender/language-masters/en/magazine/ski-touring.html' }, { wait: 4500 }, { shot: 'flows-editor' }] },
  { name: 'inbox', steps: [{ goto: '/workflow/inbox.html' }, { wait: 3000 }, { shot: 'flows-inbox' }, { shot: 'flows-inbox-tasks', clip: 'section[aria-label="Project tasks"]' }, row('Review content'), { wait: 1200 }, { shot: 'flows-inbox-selected' },
    button('Complete'), { wait: 1500 }, { shot: 'flows-complete' }, { key: 'Escape' }, { wait: 600 }, button('Delegate'), { wait: 1500 }, { shot: 'flows-delegate' }, { key: 'Escape' }, { wait: 600 },
    button('Flow History'), { wait: 2000 }, { shot: 'flows-history' }] },
  { name: 'instances', steps: [{ goto: '/workflow/instances.html' }, { wait: 3000 }, { shot: 'flows-instances' }, row('Ski Touring'), { wait: 1200 }, { shot: 'flows-instances-selected' }] },
  { name: 'timeline', steps: [{ goto: SRC }, { wait: 2500 }, pick('Ski Touring'), { wait: 1500 }, { click: { text: 'Timeline', closest: 'button,[role="tab"]' } }, { wait: 2500 }, { shot: 'flows-timeline' }] },
  { name: 'models', steps: [{ goto: '/workflow/models.html' }, { wait: 3000 }, { shot: 'flows-models' }, pick('Request for Review'), { wait: 1200 }, { shot: 'flows-models-selected' }, { click: { text: 'Edit', contains: true, closest: 'button' } }, { wait: 4500 }, { log: info }, { shot: 'flows-model-editor' }] },
  // Edit makes an editable copy of the default model under /conf/global (once; afterwards the model opens editable)
  { name: 'model-edit', steps: [{ goto: '/workflow/models.html' }, { wait: 3000 }, pick('Request for Review'), { wait: 1200 }, { click: { text: 'Edit', contains: true, closest: 'button' } }, { wait: 4500 }, { click: { text: 'Edit', closest: 'button' } }, { wait: 4000 }, { log: info }, { shot: 'flows-model-edit' },
    { click: { text: 'Review content' } }, { wait: 1200 }, { log: info }, { shot: 'flows-step-selected' }, { dblClick: { text: 'Review content' } }, { wait: 2000 }, { log: dlg }, { shot: 'flows-step' }] },
  { name: 'model-create', steps: [{ goto: '/workflow/models.html' }, { wait: 3000 }, { click: label('Create') }, { wait: 1500 }, { shot: 'flows-model-create' }] },
  { name: 'launchers', steps: [{ goto: '/workflow/launchers.html' }, { wait: 3000 }, { shot: 'flows-triggers' }, button('Create trigger'), { wait: 2000 }, { shot: 'flows-trigger-create' }] },
  { name: 'autoassign', steps: [{ goto: '/workflow/autoassign.html' }, { wait: 3000 }, { shot: 'flows-autoassign' }, button('Add'), { wait: 2000 }, { log: dlg }, { shot: 'flows-autoassign-add' }] },
];
