// The pictures of the page properties (author/page-properties.md).
const form = (path) => '/mnt/overlay/wcm/core/content/sites/properties.html?item=' + encodeURIComponent(path);
const PAGE = form('/content/weekender/language-masters/en/magazine/arctic-surfing');
const COPY = form('/content/weekender/us/en/magazine/arctic-surfing');
const tab = (name) => ({ click: { text: name, closest: '[role="tab"]' } });
const select = (title) => ({ click: 'label:has(input[aria-label="Select ' + title + '"])' });
// a tab, and what is below the fold of it
const tabShots = (name, file, below = false) => ({ name: file, steps: [
  { goto: PAGE }, { wait: 2000 }, tab(name), { wait: 1200 }, { shot: 'properties-' + file },
  ...(below ? [{ scroll: [720, 600, 760] }, { wait: 600 }, { shot: 'properties-' + file + '-2' }] : []),
] });

export default [
  { name: 'window', steps: [
    { goto: PAGE }, { wait: 2000 },
    { mark: [tab('Basic').click, { text: 'Cancel', closest: 'button' }, { text: 'Save', closest: 'button' }, '[aria-label="Close Page properties"]'] },
    { shot: 'properties-window' },
  ] },
  tabShots('Basic', 'basic', true),
  tabShots('Advanced', 'advanced', true),
  tabShots('Images', 'images'),
  tabShots('Cloud Services', 'cloud-services'),
  tabShots('Permissions', 'permissions'),
  tabShots('Source', 'source'),
  { name: 'linked-copy', steps: [{ goto: COPY }, { wait: 2000 }, tab('Linked copy'), { wait: 1200 }, { shot: 'properties-linked-copy' }] },
  { name: 'several', steps: [
    { goto: '/sites.html/content/weekender/language-masters/en/magazine' }, { wait: 900 }, select('Arctic Surfing'), select('Ski Touring'), { wait: 700 },
    { key: 'p' }, { wait: 2500 }, { shot: 'properties-several' },
  ] },
];
