// The pictures of "Images and delivery" (author/media-delivery.md, delivery-*) and of "Media settings"
// (author/media-settings.md, mediaadmin-*). They need, on the instance: the processing profile "Web sizes" and the image
// profile Web chosen on the folder arctic-surfing (then Reprocess), a Responsive Image first in the article of
// /content/weekender/us/en/magazine/arctic-surfing, a report "Files report", the collection "Arctic picks", one shared link.
const info = `'INFO ' + location.pathname + location.hash + ' | ' + [...document.querySelectorAll('button,[role=tab]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,24))).filter(Boolean).slice(0,50).join(' | ')`;
const FILE = '/assetdetails.html/content/dam/weekender-shared/en/magazine/arctic-surfing/arctic-surfing-4.jpg';
const label = (name) => `[aria-label="${name}"]`;
const open = (name) => [{ goto: '/studio/start.html#tools/media' }, { wait: 1500 }, { click: { text: name } }, { wait: 3500 }];
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const field = (n, text) => [{ eval: `document.querySelectorAll('input[type=text],input:not([type]),textarea')[${n}].focus()` }, { type: text }, { wait: 300 }];
const fields = `'FIELDS ' + [...document.querySelectorAll('input,textarea,select')].map((e,i)=>i+':'+(e.type||e.tagName)+':'+(e.getAttribute('aria-label')||e.labels?.[0]?.textContent||'').slice(0,20)+'='+String(e.value).slice(0,14)).join(' | ')`;
const card = (title, file) => ({ name: 'conf-' + file, steps: [...open('Media configurations'), { click: { text: title } }, { wait: 2500 }, { shot: 'mediaadmin-conf-' + file }] });
const DIR = '/assets.html/content/dam/weekender-shared/en/magazine/arctic-surfing';
const selected = [{ goto: DIR }, { wait: 2500 }, { moveAt: [220, 220] }, { click: label('Select') }, { wait: 1000 }];
export default [
  { name: 'tools', steps: [{ goto: '/studio/start.html#tools/media' }, { wait: 1500 }, { shot: 'delivery-tools' }] },
  { name: 'renditions', steps: [{ goto: FILE }, { wait: 3000 }, { click: label('Renditions') }, { wait: 1500 }, { shot: 'delivery-renditions' },
    { click: { text: 'Banner' } }, { wait: 2000 }, { shot: 'delivery-crop-shown' },
    { click: label('Edit focus crops') }, { wait: 2500 }, { log: info }, { shot: 'delivery-crop-editor' }] },
  { name: 'custom-size', steps: [{ goto: FILE }, { wait: 3000 }, { click: label('Renditions') }, { wait: 1500 }, { scroll: [70, 500, 3000] }, { wait: 800 }, { shot: 'delivery-custom-size' }] },
  { name: 'responsive', steps: [{ goto: '/editor.html/content/weekender/us/en/magazine/arctic-surfing.html' }, { wait: 4000 }, { scroll: [760, 500, 900] }, { wait: 1200 }, { clickAt: [880, 790] }, { wait: 1200 }, { shot: 'delivery-responsive-page' }, { click: label('Configure') }, { wait: 2000 }, { log: info }, { shot: 'delivery-responsive-dialog' },
    { click: { text: 'Crops', closest: 'button,[role="tab"]' } }, { wait: 1200 }, { shot: 'delivery-responsive-crops' },
    { click: { text: 'Gallery', closest: 'button,[role="tab"]' } }, { wait: 1200 }, { shot: 'delivery-responsive-gallery' }] },
  { name: 'processing', steps: [...open('Processing Profiles'), { log: info }, { shot: 'delivery-processing' }, { click: '[aria-label^="Edit Web"]' }, { wait: 2000 }, { shot: 'delivery-processing-editor' }] },
  { name: 'watermark', steps: [...open('Processing Profiles'), button('Edit'), { wait: 2000 }, { shot: 'delivery-watermark' }] },
  { name: 'image-profiles', steps: [...open('Image Profiles'), { shot: 'delivery-image-profiles' }, { click: label('Edit Web') }, { wait: 2000 }, { shot: 'delivery-image-profile-edit' }] },
  { name: 'presets', steps: [...open('Image Presets'), { shot: 'delivery-presets' }, { click: '[aria-label^="Edit Hero"]' }, { wait: 3500 }, { shot: 'delivery-preset-editor' }] },
  { name: 'gallery', steps: [...open('Image Presets'), button('Create', true), { wait: 2000 }, { shot: 'delivery-gallery-preset' }] },
  { name: 'auto-tags', steps: [...open('Auto tags'), { shot: 'delivery-auto-tags' }, { scroll: [700, 600, 900] }, { wait: 800 }, { shot: 'delivery-auto-tags-more' }] },
  { name: 'configurations', steps: [...open('Media configurations'), { shot: 'mediaadmin-configurations' }] },
  card('Upload Restrictions', 'upload'), card('Usage rights', 'rights'), card('Search', 'search'), card('Duplicate check', 'duplicates'), card('Link Share', 'share'),
  { name: 'schemas', steps: [...open('Metadata Schemas'), button('Create'), { wait: 2000 }, ...field(0, 'Press photos'), { shot: 'mediaadmin-schema-editor' },
    { click: { text: 'Single Line Text', closest: 'button' } }, { wait: 600 }, { shot: 'mediaadmin-schema-types', keepPointer: true }] },
  { name: 'schema-dropdown', steps: [...open('Metadata Schemas'), button('Create'), { wait: 2000 }, ...field(0, 'Press photos'), { click: { text: 'Single Line Text', closest: 'button', last: true } }, { wait: 600 }, { click: { text: 'Dropdown', last: true } }, { wait: 900 }, { shot: 'mediaadmin-schema-dropdown' }] },
  { name: 'search-forms-add', steps: [...open('Search Forms'), { scroll: [400, 600, 1500] }, { wait: 600 }, { click: label('Remove Color') }, { wait: 600 }, button('Add filter'), { wait: 700 }, { shot: 'mediaadmin-search-forms-add', keepPointer: true }] },
  { name: 'metadata-profiles', steps: [...open('Metadata Profiles'), button('Create'), { wait: 2000 }, ...field(0, 'Press photos'), { scroll: [700, 600, 700] }, { wait: 800 }, { log: fields }, { shot: 'mediaadmin-metadata-profile' }] },
  { name: 'folder-schemas', steps: [...open('Folder Metadata Schemas'), button('Create'), { wait: 2000 }, ...field(0, 'Campaign folders'), { shot: 'mediaadmin-folder-schema' }] },
  { name: 'search-forms', steps: [...open('Search Forms'), { shot: 'mediaadmin-search-forms' }, { log: `'FORMS ' + [...document.querySelectorAll('select option')].slice(0,6).map(o=>o.textContent).join(' | ')` }] },
  { name: 'reports', steps: [...open('Reports'), button('Create'), { wait: 2000 }, { shot: 'mediaadmin-report-types' }, { click: { text: 'Files' } }, { wait: 2000 }, { shot: 'mediaadmin-report-wizard' },
    { key: 'Escape' }, { wait: 800 }, { shot: 'mediaadmin-reports' }, { click: { text: 'Files report' } }, { wait: 3500 }, { shot: 'mediaadmin-report' }] },
  { name: 'collections', steps: [...selected, button('Add to Collection'), { wait: 3000 }, { log: `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,300)` },
    { click: { text: 'Arctic picks', contains: true, closest: 'label' } }, { wait: 500 }, button('Add', true), { wait: 2500 },
    ...open('Collections'), { shot: 'mediaadmin-collections' }, { click: { text: 'Arctic picks' } }, { wait: 3000 }, { log: info }, { shot: 'mediaadmin-collection' }] },
  { name: 'shared-links', steps: [...open('Shared Links'), { shot: 'mediaadmin-shared-links' }] },
];
