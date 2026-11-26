import { describe, expect, it } from 'vitest';
import { matchLocation, matchPlace, parsePlace, placeOfUrl, samePlace, titleOf, urlOf, type Place } from './places';

const loc = (url: string) => {
  const a = new URL(url, 'http://studio.local');
  return { pathname: a.pathname, search: a.search, hash: a.hash };
};

/** Every kind, with the URL main.tsx accepted for it before the shell existed. */
const CASES: Array<[string, Place]> = [
  ['/studio/start.html', { kind: 'start' }],
  ['/sites.html/content/wknd/language-masters/en', { kind: 'sites', path: '/content/wknd/language-masters/en' }],
  ['/editor.html/content/wknd/language-masters/en/magazine.html', { kind: 'editor', path: '/content/wknd/language-masters/en/magazine' }],
  ['/editor.html/conf/wknd/settings/wcm/templates/content-page-template/structure.html', { kind: 'editor', path: '/conf/wknd/settings/wcm/templates/content-page-template/structure' }],
  ['/mnt/overlay/wcm/core/content/sites/properties.html?item=%2Fcontent%2Fwknd%2Fus%2Fen', { kind: 'page-properties', item: '/content/wknd/us/en' }],
  ['/assets.html/content/dam/wknd-shared/en/adventures', { kind: 'assets', route: { kind: 'browse', path: '/content/dam/wknd-shared/en/adventures' } }],
  ['/assetdetails.html/content/dam/wknd-shared/en/adventures/a.jpg#properties', { kind: 'assets', route: { kind: 'details', path: '/content/dam/wknd-shared/en/adventures/a.jpg', properties: true } }],
  ['/mnt/overlay/dam/gui/content/processingprofilepage.html/conf/global/settings/dam/processing', { kind: 'assets', route: { kind: 'profiles' } }],
  ['/cf.html/content/dam/wknd-shared/en/contributors', { kind: 'cf', path: '/content/dam/wknd-shared/en/contributors' }],
  ['/editor.html/content/dam/wknd-shared/en/contributors/stacey-roswells', { kind: 'cf-editor', path: '/content/dam/wknd-shared/en/contributors/stacey-roswells' }],
  ['/cf.html/conf/wknd-shared', { kind: 'cf-models', conf: '/conf/wknd-shared' }],
  ['/editor.html/conf/wknd-shared/settings/dam/cfm/models/adventure', { kind: 'cf-model-editor', path: '/conf/wknd-shared/settings/dam/cfm/models/adventure' }],
  ['/libs/wcm/core/content/sites/templates.html/conf/wknd', { kind: 'templates', route: { screen: 'console', conf: '/conf/wknd' } }],
  ['/libs/wcm/core/content/sites/createtemplatewizard.html/conf/wknd', { kind: 'templates', route: { screen: 'wizard', conf: '/conf/wknd' } }],
  ['/mnt/overlay/wcm/core/content/sites/templates/properties.html?item=%2Fconf%2Fwknd%2Fsettings%2Fwcm%2Ftemplates%2Fcontent-page-template', { kind: 'templates', route: { screen: 'properties', template: '/conf/wknd/settings/wcm/templates/content-page-template' } }],
  ['/workflow/inbox.html', { kind: 'workflow', route: { kind: 'inbox' } }],
  ['/editor.html/conf/global/settings/workflow/models/request_for_review.html', { kind: 'workflow', route: { kind: 'model', path: '/conf/global/settings/workflow/models/request_for_review' } }],
  ['/replication.html', { kind: 'replication' }],
  ['/etc/linkchecker.html', { kind: 'linkchecker' }],
  ['/studio/health.html', { kind: 'health' }],
  ['/studio/activity.html', { kind: 'activity' }],
  ['/studio/siteconfig.html/content/wknd', { kind: 'siteconfig', path: '/content/wknd' }],
  ['/studio/projects.html', { kind: 'projects', name: null }],
  ['/studio/projects.html/spring-campaign', { kind: 'projects', name: 'spring-campaign' }],
  ['/launches.html', { kind: 'launches' }],
  ['/forms/submissions.html/content/forms/wknd/contact', { kind: 'form-submissions', form: '/content/forms/wknd/contact' }],
  ['/system/mail.html', { kind: 'mail' }],
  ['/audiences.html/conf/wknd', { kind: 'audiences', conf: '/conf/wknd' }],
  ['/activities.html/content/wknd', { kind: 'activities', root: '/content/wknd' }],
  ['/tags.html/content/cq:tags/wknd-shared', { kind: 'tags', path: '/content/cq:tags/wknd-shared' }],
  ['/security/users.html', { kind: 'security', route: 'users', id: null, principal: null }],
  ['/security/user.html?id=admin', { kind: 'security', route: 'user', id: 'admin', principal: null }],
  ['/security/permissions.html?principal=everyone', { kind: 'security', route: 'permissions', id: null, principal: 'everyone' }],
  ['/cloudservices/translation.html/conf/wknd', { kind: 'translation-configs', path: '/conf/wknd' }],
  ['/translation/review.html', { kind: 'translation-review', path: null }],
  ['/translation/review.html/content/wknd/language-masters/ro/magazine', { kind: 'translation-review', path: '/content/wknd/language-masters/ro/magazine' }],
  ['/translation/projects.html', { kind: 'translation-projects', name: null }],
  ['/translation/projects.html/Riverside%20camping%20RO', { kind: 'translation-projects', name: 'Riverside camping RO' }],
];

describe('places', () => {
  it.each(CASES)('parses %s', (url, place) => {
    expect(parsePlace(loc(url))).toEqual(place);
  });

  it.each(CASES)('round-trips the place of %s', (_url, place) => {
    expect(parsePlace(loc(urlOf(place)))).toEqual(place);
  });

  it('gives every kind a URL that parses back to the same kind', () => {
    for (const [, place] of CASES) expect(placeOfUrl(urlOf(place)).kind).toBe(place.kind);
  });

  it('keeps the defaults of the old routing', () => {
    expect(parsePlace(loc('/sites.html'))).toEqual({ kind: 'sites', path: '/content' });
    expect(parsePlace(loc('/sites.html/content/'))).toEqual({ kind: 'sites', path: '/content' });
    expect(parsePlace(loc('/cf.html'))).toEqual({ kind: 'cf', path: '/content/dam' });
    expect(parsePlace(loc('/tags.html'))).toEqual({ kind: 'tags', path: '/content/cq:tags' });
    expect(parsePlace(loc('/cloudservices/translation.html'))).toEqual({ kind: 'translation-configs', path: '/conf' });
    expect(parsePlace(loc('/libs/wcm/core/content/sites/templates.html/conf'))).toEqual({ kind: 'templates', route: { screen: 'console', conf: null } });
    expect(parsePlace(loc('/audiences.html'))).toEqual({ kind: 'audiences', conf: null });
    expect(parsePlace(loc('/assets.html'))).toEqual({ kind: 'assets', route: { kind: 'browse', path: '/content/dam' } });
    expect(parsePlace(loc('/'))).toEqual({ kind: 'start' });
    expect(parsePlace(loc('/anything/else.html'))).toEqual({ kind: 'start' });
  });

  it('opens the editor on the hash page or the default page, as before', () => {
    expect(parsePlace(loc('/editor.html#/content/wknd/us/en/faqs'))).toEqual({ kind: 'editor', path: '/content/wknd/us/en/faqs' });
    expect(parsePlace(loc('/editor.html')).kind).toBe('editor');
  });

  it('keeps the precedence between editor-like URLs', () => {
    // the fragment editor, the model editors and the template editor all live under /editor.html
    expect(parsePlace(loc('/editor.html/content/dam/x/y')).kind).toBe('cf-editor');
    expect(parsePlace(loc('/editor.html/conf/x/settings/dam/cfm/models/m')).kind).toBe('cf-model-editor');
    expect(parsePlace(loc('/editor.html/conf/x/settings/workflow/models/m.html')).kind).toBe('workflow');
    expect(parsePlace(loc('/editor.html/conf/x/settings/wcm/templates/t/initial.html')).kind).toBe('editor');
    expect(parsePlace(loc('/editor.html/content/x/y.html')).kind).toBe('editor');
  });

  it('decodes and encodes paths with spaces and colons', () => {
    const place: Place = { kind: 'sites', path: '/content/my site/jcr:content' };
    expect(urlOf(place)).toBe('/sites.html/content/my%20site/jcr:content');
    expect(parsePlace(loc(urlOf(place)))).toEqual(place);
  });

  it('tells screens of Studio from other URLs', () => {
    expect(matchLocation(loc('/system/console/bundles'))).toBeNull();
    expect(matchLocation(loc('/content/wknd/us/en.html?wcmmode=disabled'))).toBeNull();
    expect(matchLocation(loc('/anything/else.html'))).toBeNull();
    expect(matchPlace('/sites.html/content/wknd')).toEqual({ kind: 'sites', path: '/content/wknd' });
    expect(matchPlace(`${window.location.origin}/sites.html/content/wknd`)).toEqual({ kind: 'sites', path: '/content/wknd' });
    expect(matchPlace('https://example.com/sites.html/content')).toBeNull();
    expect(matchPlace('/system/sling/logout')).toBeNull();
  });

  it('keeps the Start screen sub-view in the hash', () => {
    expect(parsePlace(loc('/studio/start.html#tools/deployment'))).toEqual({ kind: 'start', view: 'tools/deployment' });
    expect(urlOf({ kind: 'start', view: 'tools/deployment' })).toBe('/studio/start.html#tools/deployment');
    expect(parsePlace(loc('/'))).toEqual({ kind: 'start' });
    expect(urlOf({ kind: 'start' })).toBe('/studio/start.html');
  });

  it('compares places by URL', () => {
    expect(samePlace({ kind: 'sites', path: '/content' }, parsePlace(loc('/sites.html')))).toBe(true);
    expect(samePlace({ kind: 'sites', path: '/content' }, { kind: 'cf', path: '/content/dam' })).toBe(false);
  });

  it('names places', () => {
    expect(titleOf({ kind: 'sites', path: '/content/wknd/us/en' })).toBe('Pages · en');
    expect(titleOf({ kind: 'editor', path: '/content/wknd/us/en/magazine' })).toBe('magazine');
    expect(titleOf({ kind: 'workflow', route: { kind: 'inbox' } })).toBe('Inbox');
    expect(titleOf({ kind: 'assets', route: { kind: 'browse', path: '/content/dam' } })).toBe('Media');
    expect(titleOf({ kind: 'security', route: 'user', id: 'admin', principal: null })).toBe('User · admin');
  });
});
