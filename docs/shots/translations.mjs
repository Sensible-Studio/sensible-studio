// The pictures of Translations (author/translations.md).
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=switch],input,select,textarea')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,90).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,900)`;
const fields = `'FIELDS ' + [...document.querySelectorAll('input,textarea,select,button[aria-haspopup]')].filter(e=>e.getBoundingClientRect().width>0).map((e,i)=>i+':'+(e.type||e.tagName)+':'+(e.getAttribute('aria-label')||e.labels?.[0]?.textContent||e.textContent||'').slice(0,26)+'='+String(e.value||'').slice(0,30)).join(' | ')`;
// sets a text field the way typing would, replacing what is in it (label: the start of the field's label)
const setField = (name, value) => ({ eval: `(() => { const e = [...document.querySelectorAll('input,textarea')].find(x => (x.getAttribute('aria-label') || x.labels?.[0]?.textContent || '').trim().startsWith(${JSON.stringify(name)})); const p = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(p, 'value').set.call(e, ${JSON.stringify(value)}); e.dispatchEvent(new Event('input', { bubbles: true })); })()` });
const SRC = '/sites.html/content/weekender/language-masters/en/magazine';
const languages = [{ goto: SRC }, { wait: 2500 }, pick('A Winter Swim in the Fjord'), { wait: 1500 }, button('More'), { wait: 600 }, { click: { text: 'Language versions', last: true } }, { wait: 2500 }];
const REVIEW = '/translation/review.html';
const ITEM = REVIEW + '/content/weekender/language-masters/fr/magazine/winter-swim';
const PROJECT = '/translation/projects.html/winter-stories-in-french';
const CONF = '/cloudservices/translation.html/conf';
const dialogOf = (title) => [{ goto: SRC }, { wait: 2500 }, pick(title), { wait: 1500 }, button('More'), { wait: 600 }, { click: { text: 'Language versions', last: true } }, { wait: 2500 }];
// On the instance, made once by hand with these same screens: the page "A Winter Swim in the Fjord" in the English
// language master, translated to French; the provider "Local model" in /conf/weekender (a stand-in for a model that
// answers from prepared translations, instances/weekender-sheets/stub/llm.py, on port 8765: it must be running);
// the translation project "Winter stories in French".
export default [
  { name: 'tools', steps: [{ goto: '/studio/start.html#tools/translations' }, { wait: 2000 }, { shot: 'translations-tools' }] },
  { name: 'languages', steps: [...dialogOf('Arctic Surfing'), { shot: 'translations-languages' }, { click: { text: 'French (fr)', closest: 'label' } }, { wait: 400 }, { click: { text: 'Local model', contains: true, closest: 'button' } }, { wait: 600 }, { shot: 'translations-languages-provider', keepPointer: true }] },
  { name: 'languages-done', steps: [...dialogOf('A Winter Swim in the Fjord'), { shot: 'translations-languages-done' }] },
  { name: 'fr-page', steps: [{ goto: '/editor.html/content/weekender/language-masters/fr/magazine/winter-swim.html' }, { wait: 5000 }, { scroll: [760, 500, 1400] }, { wait: 1200 }, { shot: 'translations-fr-page' }] },
  { name: 'review', steps: [{ goto: REVIEW }, { wait: 3000 }, { shot: 'translations-review' }, { click: { text: 'To review', contains: true, closest: 'button' } }, { wait: 600 }, { shot: 'translations-review-states', keepPointer: true }] },
  { name: 'review-item', steps: [{ goto: ITEM }, { wait: 3500 }, { click: label('Select Text · Text') }, { wait: 600 }, { shot: 'translations-review-item' }] },
  { name: 'projects', steps: [{ goto: '/translation/projects.html' }, { wait: 3000 }, { shot: 'translations-projects' }, button('Create project'), { wait: 2000 }, setField('Title', 'Spring stories in German'), setField('What to translate', '/content/weekender/language-masters/en/magazine'), setField('Target languages', 'de'), { wait: 500 }, { shot: 'translations-project-create' }] },
  { name: 'project', steps: [{ goto: PROJECT }, { wait: 3000 }, { shot: 'translations-project' }, button('Run on the server'), { wait: 15000 }, { log: info }, { shot: 'translations-project-done' }] },
  { name: 'providers', steps: [{ goto: CONF }, { wait: 3000 }, { shot: 'translations-providers' }, { click: { text: 'weekender' } }, { wait: 2000 }, { log: info }, { shot: 'translations-providers-conf' }, { click: { text: 'Local model', contains: true } }, { wait: 2500 }, { log: info }, { shot: 'translations-provider-form' },
    button('Test connection'), { wait: 4000 }, { scroll: [700, 600, 900] }, { wait: 600 }, { shot: 'translations-provider-test' }] },
  { name: 'provider-presets', steps: [{ goto: CONF + '/weekender' }, { wait: 3000 }, { click: { text: 'Create', contains: true, closest: 'button' } }, { wait: 2500 }, { click: { text: 'Anthropic (Claude)', closest: 'button' } }, { wait: 600 }, { shot: 'translations-provider-presets', keepPointer: true }] },
  { name: 'rules', steps: [{ goto: '/libs/cq/translation/translationrules/contexts.html' }, { wait: 3000 }, { shot: 'translations-rules' }, button('Add context'), { wait: 2000 }, { log: info }, { shot: 'translations-rules-add' }] },
];
