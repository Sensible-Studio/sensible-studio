// The pictures of Templates (author/templates.md).
const TPL = '/libs/wcm/core/content/sites/templates.html/conf/weekender';
const NAME = 'Article Page Template';
const select = (title) => ({ click: 'label:has(input[aria-label="Select ' + title + '"])' });
const button = (text) => ({ click: { text, closest: 'button' } });
const label = (name) => `[aria-label="${name}"]`;
const bar = `'BAR: ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<200}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,26))).join(' | ')`;
const menu = `'MENU: ' + [...document.querySelectorAll('[role=menuitem],[role=menuitemradio],[role=option]')].map(e=>e.textContent.trim().replace(/\\s+/g,' ')).join(' | ')`;
const toolbar = `'TOOLBAR: ' + [...document.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.x>330&&r.y>150}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,22))).join(' | ')`;
const layers = `'LAYERS: ' + [...document.querySelectorAll('[role=treeitem],[role=row]')].map(e=>e.textContent.trim().slice(0,40)).join(' | ')`;
const selected = [{ goto: TPL }, { wait: 1200 }, select(NAME), { wait: 900 }];
const editor = [...selected, button('Edit'), { wait: 4500 }];

export default [
  { name: 'console', steps: [
    { goto: TPL }, { wait: 1200 }, { shot: 'templates-console' },
    { click: label('Switch display format') }, { wait: 400 }, { click: { text: 'Card View' } }, { wait: 1500 }, { shot: 'templates-cards' },
    { click: label('Switch display format') }, { wait: 400 }, { click: { text: 'List View' } }, { wait: 1500 }, { shot: 'templates-list' },
  ] },
  { name: 'selection', steps: [...selected, { shot: 'templates-selection' }] },
  { name: 'create', steps: [
    { goto: TPL }, { wait: 2500 }, { click: { text: 'Create template', closest: 'button' } }, { wait: 1800 }, { shot: 'templates-create-type' },
  ] },
  { name: 'properties', steps: [...selected, button('Properties'), { wait: 2000 }, { shot: 'templates-properties' }] },
  { name: 'container', steps: [
    ...editor, { click: label('Content Tree') }, { wait: 900 }, { click: { text: 'Container', last: true } }, { wait: 1200 },
    { log: `'TB: ' + [...document.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>770&&r.y<830&&r.x>340&&r.x<600}).map(e=>Math.round(e.getBoundingClientRect().x)+':'+(e.getAttribute('aria-label')||e.textContent.trim())).join(' | ')` },
    { shot: 'templates-container', keepPointer: true },
    { clickAt: [443, 800] }, { wait: 1800 }, { shot: 'templates-policy' },
  ] },
  { name: 'initial', steps: [
    ...editor, { click: label('Select another mode') }, { wait: 500 }, { click: { text: 'Initial Content', last: true } }, { wait: 2500 }, { shot: 'templates-initial' },
  ] },
  { name: 'editor', steps: [
    ...editor, { shot: 'templates-editor' },
    { click: label('Select another mode') }, { wait: 500 }, { shot: 'templates-mode-menu', keepPointer: true }, { key: 'Escape' },
    { click: label('Page menu') }, { wait: 500 }, { shot: 'templates-page-menu', keepPointer: true }, { key: 'Escape' },
    { click: label('Content Tree') }, { wait: 900 }, { shot: 'templates-layers' },
  ] },
  { name: 'create-details', steps: [
    { goto: TPL }, { wait: 2500 }, { click: { text: 'Create template', closest: 'button' } }, { wait: 1800 },
    { click: { text: 'Weekender Site Empty Page' } }, { wait: 500 }, button('Next'), { wait: 1500 }, { shot: 'templates-create-details' },
  ] },
  { name: 'layout', steps: [
    ...editor, { click: label('Select another mode') }, { wait: 500 }, { click: { text: 'Layout', last: true } }, { wait: 2500 }, { shot: 'templates-layout' },
  ] },
  { name: 'component-configuration', steps: [
    ...editor, { click: label('Configuration of Title in this container') }, { wait: 2000 }, { shot: 'templates-component-configuration' },
  ] },
  { name: 'initial-properties', steps: [
    ...editor, { click: label('Page menu') }, { wait: 500 }, { click: { text: 'Initial Page Properties', last: true } }, { wait: 2200 }, { shot: 'templates-initial-properties' },
  ] },
  { name: 'page-configuration', steps: [
    ...editor, { click: label('Page menu') }, { wait: 500 }, { click: { text: 'Page Configuration', last: true } }, { wait: 2200 }, { shot: 'templates-page-configuration' },
  ] },
];
