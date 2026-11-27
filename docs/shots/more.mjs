// What the second pass over Media, Content Fragments and Templates added.
// The Media scenes use /content/dam/docs-uploads on the instance, with three files uploaded once from
// SHOTS_UPLOADS: shore-at-dusk.jpg, harbour-morning.jpg (a photo with camera data) and trip-photos.zip;
// shore-at-dusk.jpg and harbour-morning.jpg were related to each other with More > Relate.
const UP = '/assets.html/content/dam/docs-uploads';
const U = process.env.SHOTS_UPLOADS || '';
const label = (name) => `[aria-label="${name}"]`;
const button = (text) => ({ click: { text, closest: 'button' } });
const select = (title) => ({ click: 'label:has(input[aria-label="Select ' + title + '"])' });
const tab = (name) => ({ click: { text: name, closest: '[role="tab"],button' } });
const MODEL = '/editor.html/conf/weekender-shared/settings/dam/cfm/models/article';
const FRAGMENT = '/editor.html/content/dam/weekender-shared/en/magazine/arctic-surfing/aloha-spirits-in-northern-norway';
const TEMPLATE = '/libs/wcm/core/content/sites/templates.html/conf/weekender';
// a data type clicked in the model editor adds an element and shows its settings; nothing is saved
const type = (name, file) => ({ name: 'type-' + file, steps: [{ goto: MODEL }, { wait: 3500 }, { click: { text: name } }, { wait: 1200 }, { shot: 'models-type-' + file }] });

export default [
  { name: 'camera', steps: [{ goto: '/assetdetails.html/content/dam/docs-uploads/harbour-morning.jpg' }, { wait: 3000 }, { key: 'p' }, { wait: 2500 }, tab('Camera Data'), { wait: 1200 }, { shot: 'media-properties-camera' }] },
  { name: 'related', steps: [{ goto: '/assetdetails.html/content/dam/docs-uploads/shore-at-dusk.jpg' }, { wait: 3000 }, { click: label('Related') }, { wait: 1500 }, { shot: 'media-file-related' }] },
  { name: 'extract', steps: [{ goto: UP }, { wait: 2500 }, { moveAt: [944, 220] }, { click: label('Select') }, { wait: 1000 }, button('Extract Archive'), { wait: 1500 }, { shot: 'media-extract-archive' }] },
  { name: 'export', steps: [{ goto: UP }, { wait: 2500 }, { click: label('Metadata') }, { wait: 400 }, { click: { text: 'Export Metadata (CSV)', last: true } }, { wait: 1500 }, { shot: 'media-export-metadata' }] },
  { name: 'import', steps: [{ goto: UP }, { wait: 2500 }, { click: label('Metadata') }, { wait: 400 }, { click: { text: 'Import Metadata (CSV)', last: true } }, { wait: 1500 },
    { files: { selector: '[role="dialog"] input[type="file"]', paths: [U + '/metadata.csv'] } }, { wait: 1200 }, { shot: 'media-import-metadata' },
    button('Import'), { wait: 3000 }, { shot: 'media-import-result' }] },
  { name: 'fragment-dialogs', steps: [
    { goto: '/cf.html/content/dam/weekender-shared/en/magazine/arctic-surfing' }, { wait: 2500 }, select('Aloha Spirits in Northern Norway'), { wait: 1000 },
    button('Properties'), { wait: 1500 }, { shot: 'fragments-properties' }, { key: 'Escape' }, { wait: 500 },
    button('Move'), { wait: 1500 }, { shot: 'fragments-move' }, { key: 'Escape' }, { wait: 500 },
    button('Translate'), { wait: 1500 }, { shot: 'fragments-translate' },
  ] },
  type('Number', 'number'), type('Boolean', 'boolean'), type('Date and time', 'date'), type('Enumeration', 'enumeration'), type('Tags', 'tags'),
  type('Fragment Reference', 'fragment'), type('JSON Object', 'json'), type('Tab Placeholder', 'tab'),
  { name: 'versions', steps: [
    { goto: FRAGMENT }, { wait: 4000 }, tab('Versions'), { wait: 1200 }, { click: label('Actions for Before the rewrite') }, { wait: 600 }, { shot: 'fragments-versions', keepPointer: true },
    { click: { text: 'Compare with current', last: true } }, { wait: 2500 }, { shot: 'fragments-compare' },
  ] },
  { name: 'component', steps: [
    { goto: '/editor.html/content/weekender/language-masters/en/magazine/arctic-surfing.html' }, { wait: 3500 }, { click: label('Content Tree') }, { wait: 900 },
    { click: { text: 'Content Fragment' } }, { wait: 1200 }, { click: label('Configure') }, { wait: 1800 }, { shot: 'fragments-component' },
  ] },
  { name: 'graphql', steps: [{ goto: '/cfm/graphql.html' }, { wait: 3000 }, { shot: 'graphql-endpoints' },
    { click: { text: 'Open in Query Editor', closest: 'button', last: true } }, { wait: 3500 }, { shot: 'graphql-editor' }] },
  { name: 'initial-properties', steps: [
    { goto: TEMPLATE }, { wait: 1500 }, select('Article Page Template'), { wait: 900 }, button('Edit'), { wait: 4500 },
    { click: label('Page menu') }, { wait: 500 }, { click: { text: 'Initial Page Properties', last: true } }, { wait: 2500 }, { shot: 'templates-initial-properties' },
  ] },
];
