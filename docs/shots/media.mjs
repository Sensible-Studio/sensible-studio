// The pictures of Media (author/media.md).
const DIR = '/assets.html/content/dam/weekender-shared/en/magazine/arctic-surfing';
const UP = '/assets.html/content/dam/weekender-shared/en/magazine';
const FILE = '/assetdetails.html/content/dam/weekender-shared/en/magazine/arctic-surfing/arctic-surfing-4.jpg';
// two photos for the upload pictures: SHOTS_UPLOADS is their folder (one is a copy of a file of the demo, one is not)
const UPLOADS = process.env.SHOTS_UPLOADS || '';
const label = (name) => `[aria-label="${name}"]`;
const button = (text) => ({ click: { text, closest: 'button' } });
const open = [{ goto: DIR }, { wait: 2500 }];
const CARD = [220, 220];   // a point on the first card: the buttons of a card show when the pointer is on it
const selected = [...open, { moveAt: CARD }, { click: label('Select') }, { wait: 1000 }];
const panel = (name, file) => ({ name: 'file-' + file, steps: [{ goto: FILE }, { wait: 3000 }, { click: label(name) }, { wait: 1500 }, { shot: 'media-file-' + file }] });
const tab = (name, file) => ({ name: 'properties-' + file, steps: [
  ...open, { moveAt: CARD }, { click: label('Properties') }, { wait: 2500 }, { click: { text: name, closest: 'button,[role="tab"]' } }, { wait: 1200 }, { shot: 'media-properties-' + file },
] });

export default [
  { name: 'console', steps: [
    ...open,
    { mark: [label('Filters'), label('Copy path'), label('Metadata'), label('View'), 'input[type="search"]', { text: 'Select All', closest: 'button' }, label('Ascending'), { text: 'Create', closest: 'button' }] },
    { shot: 'media-console' }, { mark: [] },
    { click: label('View') }, { wait: 500 }, { click: { text: 'List View', last: true } }, { wait: 1500 }, { shot: 'media-list' },
    { click: label('View') }, { wait: 500 }, { click: { text: 'Column View', last: true } }, { wait: 1500 }, { shot: 'media-columns' },
  ] },
  { name: 'filters', steps: [...open, { click: label('Filters') }, { wait: 1200 }, { click: { text: 'Images', closest: 'label' } }, { wait: 1200 }, { shot: 'media-filters' }] },
  { name: 'create', steps: [...open, button('Create'), { wait: 500 }, { shot: 'media-create-menu', clip: '[role="menu"]', pad: 48, keepPointer: true }] },
  { name: 'folders', steps: [{ goto: UP }, { wait: 2500 }, { shot: 'media-folders' }] },
  { name: 'selected', steps: [
    ...selected, { shot: 'media-selected' },
    button('More'), { wait: 500 }, { shot: 'media-more-menu', keepPointer: true }, { key: 'Escape' },
    { clickAt: [150, 150], button: 'right', settle: 600 }, { shot: 'media-context-menu', keepPointer: true },
  ] },
  { name: 'file', steps: [{ goto: FILE }, { wait: 3000 }, { shot: 'media-file' }] },
  panel('Renditions', 'renditions'),
  panel('Timeline', 'timeline'),
  panel('Related', 'related'),
  panel('Similar', 'similar'),
  panel('References', 'references'),
  { name: 'file-edit', steps: [{ goto: FILE }, { wait: 3000 }, button('Edit'), { wait: 2500 }, { shot: 'media-image-editor' }] },
  { name: 'properties', steps: [...open, { moveAt: CARD }, { click: label('Properties') }, { wait: 2500 }, { shot: 'media-properties' },
    { scroll: [720, 600, 700] }, { wait: 600 }, { shot: 'media-properties-2' }] },
  tab('Advanced', 'advanced'),
  tab('Camera Data', 'camera'),
  tab('All Metadata', 'all'),
  { name: 'folder-properties', steps: [{ goto: UP }, { wait: 2500 }, { moveAt: CARD }, { click: label('Properties') }, { wait: 2500 }, { shot: 'media-folder-properties' },
    { scroll: [720, 600, 700] }, { wait: 600 }, { shot: 'media-folder-properties-2' }] },
  // --- dialogs of the actions ---
  { name: 'move', steps: [...selected, button('More'), { wait: 400 }, { click: { text: 'Move', last: true } }, { wait: 1500 }, { shot: 'media-move' }] },
  { name: 'share', steps: [...selected, button('Share Link'), { wait: 1500 }, { shot: 'media-share-link' }] },
  { name: 'collection', steps: [...selected, button('Add to Collection'), { wait: 1500 }, { shot: 'media-add-to-collection' }] },
  { name: 'similar', steps: [...selected, button('Find Similar'), { wait: 2500 }, { shot: 'media-find-similar' }] },
  { name: 'flow', steps: [...selected, button('More'), { wait: 400 }, { click: { text: 'Start Flow', last: true } }, { wait: 1500 }, { shot: 'media-start-flow' }] },
  { name: 'languages', steps: [...selected, button('More'), { wait: 400 }, { click: { text: 'Language versions', last: true } }, { wait: 1500 }, { shot: 'media-language-versions' }] },
  { name: 'annotate', steps: [...selected, button('Annotate'), { wait: 3000 }, { shot: 'media-annotate' }] },
  { name: 'two', steps: [
    ...selected, { moveAt: [560, 220] }, { click: label('Select') }, { wait: 900 }, { shot: 'media-selected-two' },
    button('Download'), { wait: 1500 }, { shot: 'media-download' }, { key: 'Escape' }, { wait: 500 },
    button('Properties'), { wait: 2500 }, { shot: 'media-properties-several' },
  ] },
  { name: 'image-set', steps: [
    ...selected, { moveAt: [560, 220] }, { click: label('Select') }, { wait: 900 },
    button('More'), { wait: 400 }, { click: { text: 'Image Set', last: true } }, { wait: 1500 }, { shot: 'media-image-set' },
  ] },
  // --- upload: a file is given to the console's own file field, into a folder made for it ---
  { name: 'upload', steps: [
    { goto: '/assets.html/content/dam/docs-uploads' }, { wait: 2500 },
    { files: { selector: 'input[type="file"]', paths: [UPLOADS + '/shore-at-dusk.jpg'] }, settle: 250 }, { shot: 'media-upload', keepPointer: true },
    { wait: 7000 }, { shot: 'media-upload-done' },
    // the same name again: what to do with it
    { goto: '/assets.html/content/dam/docs-uploads' }, { wait: 2500 },
    { files: { selector: 'input[type="file"]', paths: [UPLOADS + '/shore-at-dusk.jpg'] } }, { wait: 2000 }, { click: { text: 'Upload anyway' } }, { wait: 1800 }, { shot: 'media-upload-conflict' },
    { key: 'Escape' }, { goto: '/assets.html/content/dam/docs-uploads' }, { wait: 2500 },
    // a file that is in Media already, under another name
    { files: { selector: 'input[type="file"]', paths: [UPLOADS + '/northern-shore.jpg'] } }, { wait: 2500 }, { shot: 'media-upload-duplicate' },
  ] },
];
