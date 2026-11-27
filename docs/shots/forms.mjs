// The pictures of Forms (author/forms.md). On the instance: the sample forms of the product (Forms > Sample forms)
// and three submissions of "Contact us", posted once to /bin/studio/forms/submit.
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
const info = `'INFO ' + location.pathname + location.search + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=switch],input,select,textarea,h2,h3')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,110).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,900)`;
const setField = (name, value) => ({ eval: `(() => { const e = [...document.querySelectorAll('input,textarea')].find(x => (x.getAttribute('aria-label') || x.labels?.[0]?.textContent || '').trim().startsWith(${JSON.stringify(name)})); const p = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(p, 'value').set.call(e, ${JSON.stringify(value)}); e.dispatchEvent(new Event('input', { bubbles: true })); })()` });
const FORMS = '/sites.html/content/forms';
const SAMPLES = FORMS + '/samples';
const ED = '/editor.html/content/forms/samples/contact.html';
const tree = (name) => [{ goto: ED }, { wait: 4500 }, { click: label('Content Tree') }, { wait: 1200 }, { click: { text: name } }, { wait: 1200 }, { click: label('Configure') }, { wait: 2000 }];
const tabs = `'TABS ' + [...document.querySelectorAll('[role=tab]')].map(e=>e.textContent.trim()).join(' | ') + ' || ' + (document.querySelector('[role=dialog],.studio-dialog,[class*=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,600)`;
const tab = (name) => ({ click: { text: name, closest: '[role="tab"]' } });
export default [
  { name: 'tools', steps: [{ goto: '/studio/start.html#tools' }, { wait: 2000 }, { click: { text: 'Forms', last: true } }, { wait: 1200 }, { shot: 'forms-tools' }] },
  { name: 'console', steps: [{ goto: SAMPLES }, { wait: 3000 }, { shot: 'forms-console' }, pick('Contact us'), { wait: 1500 }, { log: info }, { shot: 'forms-selected' }] },
  { name: 'create', steps: [{ goto: SAMPLES }, { wait: 3000 }, button('Create'), { wait: 700 }, { shot: 'forms-create-menu', keepPointer: true }, { click: { text: 'Form', last: true } }, { wait: 2500 }, { shot: 'forms-create' }] },
  { name: 'editor', steps: [{ goto: ED }, { wait: 4500 }, { shot: 'forms-editor' }] },
  { name: 'field', steps: [...tree('Dropdown'), { shot: 'forms-field' }, tab('Validation'), { wait: 1000 }, { shot: 'forms-field-validation' }, tab('Rules'), { wait: 1000 }, { shot: 'forms-field-rules' }] },
  { name: 'rule', steps: [...tree('Number'), tab('Rules'), { wait: 1000 }, { shot: 'forms-rule' }] },
  { name: 'form', steps: [...tree('Form'), { shot: 'forms-form-steps' }, tab('After submit'), { wait: 1000 }, { shot: 'forms-form-after' }, tab('Content Fragment'), { wait: 1000 }, { shot: 'forms-form-fragment' }, tab('Actions'), { wait: 1000 }, { shot: 'forms-form-actions' }] },
  { name: 'preview', steps: [{ goto: ED }, { wait: 4500 }, button('Preview'), { wait: 2500 }, { shot: 'forms-preview' }] },
  { name: 'submissions', steps: [{ goto: SAMPLES }, { wait: 3000 }, pick('Contact us'), { wait: 1500 }, { click: { text: 'Submissions', contains: true, closest: 'button' } }, { wait: 3000 }, { shot: 'forms-submissions' }, { click: { text: 'Elena Marin' } }, { wait: 2000 }, { log: info }, { shot: 'forms-submission' }] },
  { name: 'mail', steps: [{ goto: '/system/mail.html' }, { wait: 3000 }, { shot: 'forms-mail' }] },
];
