// The pictures of the Pages console (author/pages.md).
const EN = '/sites.html/content/weekender/us/en';
const MAG = EN + '/magazine';
const ROW = 'Arctic Surfing';
const select = (title) => ({ click: 'label:has(input[aria-label="Select ' + title + '"])' });
const selected = [{ goto: MAG }, { wait: 800 }, select(ROW), { wait: 900 }];
// a right-click on the title of the selected row (the third row of the last column)
const RIGHT = { clickAt: [960, 224], button: 'right', settle: 600 };
const button = (text) => ({ click: { text, closest: 'button' } });
// an entry of the right-click menu of the selected page
const fromMenu = (name, item, more = []) => ({ name, steps: [
  ...selected, RIGHT, { click: { text: item, last: true } }, { wait: 1300 }, ...more, { shot: 'pages-' + name },
] });
const create = (line) => [{ goto: MAG }, { wait: 800 }, button('Create'), { wait: 400 }, { click: { text: line, closest: '[role="menuitem"]' } }, { wait: 1500 }];

export default [
  { name: 'views', steps: [
    { goto: EN }, { wait: 800 }, { shot: 'pages-columns' },
    { click: '[aria-label="Switch display format"]' }, { wait: 400 }, { shot: 'pages-view-menu', clip: '[role="menu"]', pad: 48, keepPointer: true },
    { click: { text: 'Card View' } }, { wait: 1500 }, { shot: 'pages-cards' },
    { click: '[aria-label="Switch display format"]' }, { wait: 400 }, { click: { text: 'List View' } }, { wait: 1500 }, { shot: 'pages-list' },
  ] },
  { name: 'create', steps: [
    { goto: MAG }, { wait: 800 }, button('Create'), { wait: 400 },
    { shot: 'pages-create-menu', clip: '[role="menu"]', pad: 48, keepPointer: true },
    { click: { text: 'From a template allowed here', closest: '[role="menuitem"]' } }, { wait: 1500 }, { shot: 'pages-create-page-template' },
    { click: { text: 'Article Page Template' } }, { wait: 500 }, button('Next'), { wait: 1200 }, { shot: 'pages-create-page-properties' },
  ] },
  { name: 'create-site', steps: [...create('Languages and chapters from a source'), { shot: 'pages-create-site' }] },
  { name: 'create-copy', steps: [...create('Pages that follow a source'), { wait: 600 }, { shot: 'pages-create-linked-copy' }] },
  { name: 'create-csv', steps: [...create('The pages here as a spreadsheet'), { shot: 'pages-csv-report' }] },
  { name: 'selection', steps: [
    ...selected,
    { mark: [{ text: 'Selection', contains: true, closest: 'div' }, button('Create').click, button('Edit').click, button('Restore').click, button('More').click, '[aria-label="Close inspector"]'] },
    { shot: 'pages-selection' }, { mark: [] },
    button('More'), { wait: 500 }, { shot: 'pages-more-menu', clip: '[role="menu"]', pad: 48, keepPointer: true }, { key: 'Escape' },
    button('Restore'), { wait: 500 }, { shot: 'pages-restore-menu', clip: '[role="menu"]', pad: 48, keepPointer: true }, { key: 'Escape' },
    { clickAt: [347, 74] }, { wait: 500 }, { shot: 'pages-create-selection-menu', keepPointer: true }, { key: 'Escape' },
    { key: 'Alt+5' }, { wait: 900 }, { shot: 'pages-inspector-references' },
    { key: 'Alt+6' }, { wait: 900 }, { shot: 'pages-inspector-site' },
  ] },
  { name: 'context', steps: [...selected, RIGHT, { shot: 'pages-context-menu', keepPointer: true }] },
  fromMenu('language-versions', 'Language versions'),
  fromMenu('start-flow', 'Start Flow'),
  // saves a version too, so that the timeline and the restore screens below have something to show
  { name: 'version', steps: [
    ...selected, RIGHT, { click: { text: 'Version', last: true } }, { wait: 1200 }, { type: 'Before the rewrite' }, { shot: 'pages-version' },
    button('Save'), { wait: 1500 },
  ] },
  { name: 'timeline', steps: [...selected, { key: 'Alt+4' }, { wait: 1200 }, { shot: 'pages-timeline' }] },
  fromMenu('release', 'Release'),
  fromMenu('translation-project', 'Translation Project'),
  // from the folder above, so that the page with the saved version is listed
  { name: 'restore-version', steps: [{ goto: EN }, { wait: 800 }, select('Magazine'), { wait: 900 }, button('Restore'), { wait: 400 },
    { click: { text: 'Restore Version', last: true } }, { wait: 1800 }, { shot: 'pages-restore-version' }] },
  { name: 'restore-tree', steps: [{ goto: EN }, { wait: 800 }, select('Magazine'), { wait: 900 }, button('Restore'), { wait: 400 },
    { click: { text: 'Restore Tree', last: true } }, { wait: 1800 }, { shot: 'pages-restore-tree' }] },
  fromMenu('move', 'Move'),
  fromMenu('delete', 'Delete'),
  { name: 'filter', steps: [
    { goto: EN }, { wait: 800 }, { key: 'Alt+2' }, { wait: 900 },
    { click: { text: 'Full text', closest: 'div' } }, { click: 'input[type="search"], input[type="text"]' }, { type: 'surf' }, { key: 'Enter' }, { wait: 1800 },
    { shot: 'pages-filter' },
  ] },
];
