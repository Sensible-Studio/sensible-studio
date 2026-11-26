import { describe, expect, it } from 'vitest';
import { parsePlace, urlOf } from '../shell/places';
import { blueprintName, blueprintProblems, BLUEPRINTS_URL } from './BlueprintsConsole';

describe('site sources (sources)', () => {
  it('names a configuration after its title, as AEM names wknd_blueprint', () => {
    expect(blueprintName('WKND Site')).toBe('wknd_site_blueprint');
    expect(blueprintName('Brand Blueprint')).toBe('brand_blueprint');
    expect(blueprintName('Șantier – România')).toBe('santier_romania_blueprint');
    expect(blueprintName('  ')).toBe('');
  });

  it('checks the form', () => {
    expect(blueprintProblems({ title: 'WKND', sitePath: '/content/wknd/language-masters', name: 'wknd_blueprint' }, [])).toEqual([]);
    expect(blueprintProblems({ title: 'WKND', sitePath: '/content/wknd/language-masters', name: 'wknd_blueprint' }, ['wknd_blueprint'])).toEqual([
      'A configuration named wknd_blueprint exists already.',
    ]);
    expect(blueprintProblems({ title: '', sitePath: '/etc/x' }, [])).toHaveLength(2);
    expect(blueprintProblems({ title: 'A', sitePath: '/content/a', name: 'Bad Name' }, [])).toEqual(['The name uses a-z, 0-9, _ and -.']);
  });

  it('is at AEM’s address', () => {
    expect(parsePlace({ pathname: BLUEPRINTS_URL, search: '', hash: '' })).toEqual({ kind: 'blueprints' });
    expect(urlOf({ kind: 'blueprints' })).toBe('/libs/wcm/msm/gui/content/blueprintconfig.html');
  });
});
