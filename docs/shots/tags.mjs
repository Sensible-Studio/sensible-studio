// The pictures of Tags (author/tags.md).
const ROOT = '/tags.html/content/cq:tags';
const ACTIVITY = ROOT + '/weekender-shared/activity';
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const pick = (title) => ({ click: `label:has(input[aria-label="Select ${title}"])` });
// eslint-disable-next-line no-unused-vars
const info = `'INFO ' + location.pathname + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem]')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44&&r.y<900}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,28))).filter(Boolean).slice(0,70).join(' | ')`;
const open = [{ goto: ACTIVITY }, { wait: 2500 }];
const surfing = [...open, pick('Surfing'), { wait: 1500 }];
const act = (name, file, more = []) => ({ name: file, steps: [...surfing, button(name), { wait: 1800 }, ...more, { shot: 'tags-' + file }] });
export default [
  { name: 'console', steps: [{ goto: ROOT }, { wait: 2500 }, { shot: 'tags-root' }, ...open, { shot: 'tags-console' },
    button('Create'), { wait: 600 }, { shot: 'tags-create-menu', keepPointer: true }] },
  { name: 'selected', steps: [...surfing, { shot: 'tags-selected' }, { clickAt: [760, 300], button: 'right', settle: 600 }, { shot: 'tags-context-menu', keepPointer: true }] },
  act('Create Tag', 'create', [{ type: 'Kitesurfing' }, { wait: 400 }]),
  act('Edit', 'edit', [{ click: { text: 'Add Language', closest: 'button' } }, { wait: 600 }, { shot: 'tags-edit-languages', keepPointer: true }, { click: { text: 'French', contains: true, last: true } }, { wait: 800 },
    { log: `'FIELDS ' + [...document.querySelectorAll('input')].map(e=>e.getAttribute('aria-label')||e.labels?.[0]?.textContent||'?').join(' | ')` }]), act('Move', 'move'), act('Merge', 'merge'), act('Publish', 'publish'), act('Delete', 'delete'),
  { name: 'picker', steps: [{ goto: '/mnt/overlay/wcm/core/content/sites/properties.html?item=/content/weekender/language-masters/en/adventures/bali-surf-camp' }, { wait: 3500 }, { click: label('Browse tags') }, { wait: 2000 }, { click: label('Open Weekender Shared') }, { wait: 900 }, { click: label('Open Activity') }, { wait: 900 }, { click: { text: 'Surfing', last: true } }, { wait: 600 }, { shot: 'tags-picker' }] },
  { name: 'namespace', steps: [{ goto: ROOT }, { wait: 2500 }, button('Create'), { wait: 600 }, { click: { text: 'Create Namespace', last: true } }, { wait: 1800 }, { shot: 'tags-create-namespace' }] },
];
