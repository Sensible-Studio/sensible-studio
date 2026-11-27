// The pictures of "A tour of the interface" (getting-started/tour.md).
const START = '/studio/start.html';
const PAGES = '/sites.html/content/weekender/us/en';
const select = (title) => ({ click: 'label:has(input[aria-label="Select ' + title + '"])' });
// eslint-disable-next-line no-unused-vars
const labels = `[...document.querySelectorAll('button,[role=menuitem],[role=tab]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,24))).filter(Boolean).join(' | ')`;
const palette = (text) => [{ key: 'Meta+k' }, { wait: 400 }, { type: text }, { wait: 900 }];

export default [
  { name: 'home', steps: [
    { goto: START }, { wait: 600 },
    { mark: ['.studio-shellbar__brand', '.studio-shellbar__nav', '.studio-tab', '[aria-label="New tab"]', '[aria-label="Inbox"]',
      '[aria-label="User"]', '[aria-label="Full screen"]', '[aria-label="Hide Recent and Pinned"]'] },
    { shot: 'tour-home' },
  ] },
  { name: 'console', steps: [
    { goto: PAGES }, { wait: 900 },
    { mark: ['[aria-label="Rail: Content Only"]', '[aria-label="Copy path"]', '[aria-label="Switch display format"]',
      { text: 'Select All', closest: 'button' }, { text: 'Create', closest: 'button' }, '[aria-label="Full screen"]'] },
    { shot: 'tour-console' },
  ] },
  { name: 'system', steps: [{ goto: START }, { click: { text: 'Publishing' } }, { wait: 500 }, { shot: 'tour-system' }] },
  { name: 'tabs', steps: [
    { goto: PAGES }, { wait: 600 },
    { key: 'Alt+t' }, { wait: 500 }, { click: { text: 'Media' } }, { wait: 900 },
    { key: 'Alt+t' }, { wait: 500 },
    { shot: 'tour-tabs', clip: '.studio-shellbar', pad: 0 },
    { rightClick: '.studio-tab' }, { shot: 'tour-tab-menu', clip: '[role="menu"]', pad: 40 }, { key: 'Escape' },
  ] },
  { name: 'split', steps: [
    { goto: PAGES }, { wait: 600 }, { key: 'Alt+\\' }, { wait: 700 },
    ...palette('Media'), { key: 'Enter' }, { wait: 1500 },
    { shot: 'tour-split' },
  ] },
  { name: 'sidebar', steps: [
    { goto: PAGES }, { wait: 500 }, { goto: '/assets.html/content/dam/weekender-shared/en/adventures' }, { wait: 500 },
    { goto: START }, { wait: 700 },
    { shot: 'tour-sidebar', clip: 'aside.studio-work', pad: 0 },
  ] },
  { name: 'palette', steps: [{ goto: PAGES }, { wait: 500 }, ...palette('surf'), { shot: 'tour-palette' }, { key: 'Escape' }] },
  { name: 'user', steps: [
    { goto: START }, { click: '[aria-label="User"]' }, { wait: 400 },
    { shot: 'tour-user-menu', clip: { text: 'Theme', closest: '[role="dialog"],[role="menu"],[class*="opover"]' }, pad: 40 },
    { click: { text: 'Ledger', contains: true } }, { wait: 700 }, { key: 'Escape' }, { shot: 'tour-theme-ledger' },
    { click: '[aria-label="User"]' }, { wait: 400 }, { click: { text: 'Night Desk', contains: true } }, { wait: 700 }, { key: 'Escape' },
    { shot: 'tour-theme-night' },
  ] },
  { name: 'select', steps: [
    { goto: PAGES }, { wait: 900 },
    select('Magazine'), { wait: 900 }, { shot: 'tour-select' },
    { rightClick: { text: 'Adventures' } }, { shot: 'tour-context-menu' }, { key: 'Escape' },
  ] },
  { name: 'rail', steps: [{ goto: PAGES }, { wait: 700 }, { key: 'Alt+1' }, { wait: 900 }, { shot: 'tour-rail-tree' },
    { key: 'Alt+2' }, { wait: 900 }, { shot: 'tour-rail-filter' }] },
  { name: 'inspector', steps: [{ goto: PAGES }, { wait: 700 }, select('Magazine'), { key: 'Alt+3' }, { wait: 900 }, { shot: 'tour-inspector' },
    { key: 'Alt+4' }, { wait: 900 }, { shot: 'tour-inspector-timeline' }, { key: 'Alt+5' }, { wait: 900 }, { shot: 'tour-inspector-references' }] },
];
