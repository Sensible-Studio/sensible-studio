// The pictures of the page editor (author/page-editor.md). The page of the language master is used:
// a linked copy shows only "Cancel inheritance" on its components.
const ED = '/editor.html/content/weekender/language-masters/en/magazine/arctic-surfing.html';
const open = [{ goto: ED }, { wait: 3500 }];
const label = (name) => `[aria-label="${name}"]`;
const IMAGE = [760, 520];   // a point on the article's first image
const toolbar = `'TOOLBAR: ' + [...document.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.x>330&&r.y>150}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,22))).join(' | ')`;
const menu = `'MENU: ' + [...document.querySelectorAll('[role=menuitem],[role=menuitemradio],[role=menuitemcheckbox],[role=option]')].map(e=>e.textContent.trim().replace(/\\s+/g,' ')).join(' | ')`;
const mode = (name, item, more = []) => ({ name, steps: [
  ...open, { click: label('Select another mode') }, { wait: 400 }, { click: { text: item, last: true } }, { wait: 1800 }, ...more, { shot: 'editor-' + name },
] });

export default [
  { name: 'window', steps: [
    ...open,
    { mark: [label('Content library'), label('Page menu'), label('Undo'), label('Viewport controls'), label('Edit mode'), label('Select another mode'),
      { text: 'Preview', closest: 'button' }, label('Add annotation'), { text: 'Publish', closest: 'button' }, label('Close editor'),
      label('Components'), '.studio-frame'] },
    { shot: 'editor-window' },
  ] },
  { name: 'panel', steps: [
    ...open, { click: label('Media') }, { wait: 1500 }, { shot: 'editor-panel-media' },
    { click: label('Content Tree') }, { wait: 1200 }, { shot: 'editor-panel-layers' },
    { click: label('Content library') }, { wait: 800 }, { shot: 'editor-panel-hidden' },
  ] },
  { name: 'page-menu', steps: [...open, { click: label('Page menu') }, { wait: 500 }, { shot: 'editor-page-menu', keepPointer: true }] },
  { name: 'mode-menu', steps: [...open, { click: label('Select another mode') }, { wait: 500 }, { shot: 'editor-mode-menu', keepPointer: true }] },
  { name: 'viewport', steps: [...open, { click: label('Viewport controls') }, { wait: 700 }, { shot: 'editor-viewport', keepPointer: true }] },
  { name: 'select', steps: [
    ...open, { clickAt: IMAGE }, { wait: 900 }, { click: label('Configure') }, { wait: 1500 }, { shot: 'editor-dialog' },
  ] },
  // a component inside a container has the whole toolbar; it is selected from the Layers tab
  { name: 'toolbar', steps: [
    ...open, { click: label('Content Tree') }, { wait: 900 }, { click: { text: 'Title: By Jacob Wester' } }, { wait: 1200 },
    { log: toolbar }, { shot: 'editor-toolbar', keepPointer: true },
    { click: label('Edit') }, { wait: 1200 }, { shot: 'editor-inline' }, { key: 'Escape' },
  ] },
  { name: 'insert', steps: [
    ...open, { click: label('Content Tree') }, { wait: 900 }, { click: { text: 'Title: By Jacob Wester' } }, { wait: 1200 },
    { click: label('Insert component') }, { wait: 1200 }, { shot: 'editor-insert' },
  ] },
  { name: 'annotate', steps: [...open, { clickAt: IMAGE }, { wait: 800 }, { click: label('Add annotation') }, { wait: 1200 }, { shot: 'editor-annotation' }] },
  mode('layout', 'Layout', [{ clickAt: IMAGE }, { wait: 1000 }]),
  mode('history', 'History'),
  mode('developer', 'Developer', [{ click: { text: 'Title: By Jacob Wester' } }, { wait: 1200 }]),
  mode('personalize', 'Personalize'),
  { name: 'preview', steps: [...open, { click: { text: 'Preview', closest: 'button' } }, { wait: 1800 }, { shot: 'editor-preview' },
    { click: { text: 'Visitor', closest: 'button' } }, { wait: 1200 }, { shot: 'editor-visitor' }] },
  // --- what a few components add ---
  { name: 'styles', steps: [
    ...open, { click: label('Content Tree') }, { wait: 900 }, { click: { text: 'Title: By Jacob Wester' } }, { wait: 1200 },
    { click: label('Styles') }, { wait: 900 }, { shot: 'editor-styles', keepPointer: true },
  ] },
  { name: 'image-editor', steps: [...open, { clickAt: IMAGE }, { wait: 900 }, { click: label('Edit') }, { wait: 2500 }, { shot: 'editor-image-editor' }] },
  { name: 'layout-selected', steps: [
    ...open, { click: label('Select another mode') }, { wait: 400 }, { click: { text: 'Layout', last: true } }, { wait: 1800 },
    { click: label('Content Tree') }, { wait: 900 }, { click: { text: 'Title: By Jacob Wester' } }, { wait: 1200 },
    { shot: 'editor-layout', keepPointer: true },
  ] },
  { name: 'publish', steps: [...open, { click: { text: 'Publish', closest: 'button' } }, { wait: 1800 }, { shot: 'editor-publish' }] },
];
