// The pictures of Users, groups and permissions (author/users.md).
const label = (name) => `[aria-label="${name}"]`;
const button = (text, last) => ({ click: { text, closest: 'button', last } });
const info = `'INFO ' + location.pathname + location.search + ' | ' + [...document.querySelectorAll('button,[role=tab],[role=menuitem],[role=switch],input,select,textarea,h2,h3')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.y>44}).map(e=>(e.getAttribute('aria-label')||e.textContent.trim().slice(0,30)||e.labels?.[0]?.textContent||'')).filter(Boolean).slice(0,110).join(' | ')`;
const dlg = `'DLG ' + (document.querySelector('[role=dialog]')?.innerText||'').replace(/\s+/g,' ').slice(0,900)`;
// On the instance, made once: the users maria.ionescu, tom.becker and ana.dumitru, and the groups weekender-authors
// (all three) and weekender-publishers (Maria); Ana is also in translation-reviewers.
export default [
  { name: 'tools', steps: [{ goto: '/studio/start.html#tools' }, { wait: 2000 }, { click: { text: 'Users' } }, { wait: 1200 }, { shot: 'users-tools' }] },
  { name: 'users', steps: [{ goto: '/security/users.html' }, { wait: 3000 }, { shot: 'users-list' }, { click: 'label:has(input[aria-label*="Maria"]), label:has(input[aria-label*="maria"])' }, { wait: 1200 }, { log: info }, { shot: 'users-selected' }] },
  { name: 'create', steps: [{ goto: '/security/users.html' }, { wait: 3000 }, button('Create user'), { wait: 2500 }, { shot: 'users-create' }] },
  { name: 'user', steps: [{ goto: '/security/user.html?id=maria.ionescu' }, { wait: 3000 }, { shot: 'users-user' }, { click: { text: 'Groups', closest: '[role="tab"],button' } }, { wait: 1500 }, { shot: 'users-user-groups' }, { click: { text: 'Impersonators', closest: '[role="tab"],button' } }, { wait: 1500 }, { shot: 'users-user-impersonators' }] },
  { name: 'groups', steps: [{ goto: '/security/groups.html' }, { wait: 3000 }, { shot: 'users-groups' }, { goto: '/security/group.html?id=weekender-authors' }, { wait: 3000 }, { shot: 'users-group' }, { click: { text: 'Members', closest: '[role="tab"],button' } }, { wait: 1500 }, { shot: 'users-group-members' }] },
  { name: 'permissions', steps: [{ goto: '/security/permissions.html?principal=weekender-authors' }, { wait: 3500 }, { shot: 'users-permissions' }, { click: { text: 'Entries', closest: 'button,[role="tab"],label' } }, { wait: 1500 }, { log: info }, { shot: 'users-permissions-entries' }, button('Add permission'), { wait: 2000 }, { shot: 'users-permission-add' }, { key: 'Escape' }, { wait: 600 }, button('Check Permissions'), { wait: 2000 }, { shot: 'users-permission-check' }] },
  { name: 'oauth', steps: [{ goto: '/security/oauth.html' }, { wait: 3000 }, { shot: 'users-oauth' }, button('New client'), { wait: 2000 }, { shot: 'users-oauth-new' }] },
  { name: 'activity', steps: [{ goto: '/studio/activity.html' }, { wait: 3500 }, { log: info }, { shot: 'users-activity' }] },
  { name: 'page-permissions', steps: [{ goto: '/mnt/overlay/wcm/core/content/sites/properties.html?item=/content/weekender/language-masters/en/magazine/members-only' }, { wait: 3500 }, { click: { text: 'Permissions', closest: '[role="tab"],button' } }, { wait: 2000 }, { log: info }, { shot: 'users-page-permissions' }] },
];
