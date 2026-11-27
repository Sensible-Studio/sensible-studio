// The pictures of Projects (author/projects.md).
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=switch],input,select,textarea,a[href]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,90).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,900)`;
const setField = (name, value) => ({ eval: `(() => { const e = [...document.querySelectorAll('input,textarea')].find(x => (x.getAttribute('aria-label') || x.labels?.[0]?.textContent || '').trim().startsWith(${JSON.stringify(name)})); const p = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(p, 'value').set.call(e, ${JSON.stringify(value)}); e.dispatchEvent(new Event('input', { bubbles: true })); })()` });
const P = '/studio/projects.html/winter-campaign';
const fields = `'FIELDS ' + [...document.querySelectorAll('input,textarea,select,button[aria-haspopup]')].filter(e=>e.getBoundingClientRect().width>0).map((e,i)=>i+':'+(e.type||e.tagName)+':'+(e.getAttribute('aria-label')||e.labels?.[0]?.textContent||e.textContent||'').slice(0,26)+'='+String(e.value||'').slice(0,30)).join(' | ')`;
const task = (title) => [button('Add a task'), { wait: 1500 }, setField('What is to be done', title), { wait: 300 }];
// On the instance, made once by hand with these screens: the project "Winter campaign" with three tasks (the first
// done, the third for translation-reviewers, due 4.12, high priority), translation-reviewers in the team as editor,
// the second for admin, due 2.12; and the page "A Winter Swim in the Fjord" as content.
export default [
  { name: 'task', steps: [{ goto: P }, { wait: 3000 }, { click: { text: 'Review the French versions' } }, { wait: 1500 }, { shot: 'projects-task' }] },
  { name: 'list', steps: [{ goto: '/studio/projects.html' }, { wait: 3000 }, { shot: 'projects-list' }, button('Create project'), { wait: 1500 }, setField('Title', 'Spring relaunch'), setField('What it is about', 'The new adventures section.'), { wait: 400 }, { shot: 'projects-create' }] },
  { name: 'project', steps: [{ goto: P }, { wait: 3000 }, { shot: 'projects-project' }, button('Add a task'), { wait: 1500 }, setField('What is to be done', 'Publish the campaign pages'), { wait: 400 }, { shot: 'projects-add-task' }, { key: 'Escape' }, { wait: 600 },
    { click: label('Role of translation-reviewers') }, { wait: 600 }, { shot: 'projects-roles', keepPointer: true }, { key: 'Escape' }, { wait: 500 }, button('Details'), { wait: 1500 }, { shot: 'projects-details' }] },
  { name: 'home', steps: [{ goto: '/studio/start.html' }, { wait: 3500 }, { shot: 'projects-home' }] },
];
