import { describe, expect, it } from 'vitest';
import { parsePlace, urlOf } from '../shell/places';
import { comparable, contextFor, contextProblems, newContext, ruleEditorUrl, RULES_URL, type TranslationContext } from './rules';

const ctx = (path: string, builtIn = false): TranslationContext => ({ ...newContext(null, path), builtIn, properties: ['jcr:title'] });

describe('translation configuration', () => {
  it('picks the most specific context above a page', () => {
    const all = [ctx('/content', true), ctx('/content/wknd'), ctx('/content/wknd/us')];
    expect(contextFor(all, '/content/wknd/us/en/faqs')?.path).toBe('/content/wknd/us');
    expect(contextFor(all, '/content/wknd/ca/en')?.path).toBe('/content/wknd');
    expect(contextFor(all, '/content/wkndx/en')?.path).toBe('/content');
    expect(contextFor(all, '/conf/x')).toBeNull();
  });

  it('checks a context before saving', () => {
    const all = [ctx('/content', true), ctx('/content/wknd')];
    expect(contextProblems(ctx('/content/wknd'), all)).toEqual(['There is a context for /content/wknd already.']);
    expect(contextProblems(ctx('/content'), all)).toEqual([]); // the built-in one can be stored
    expect(contextProblems(ctx('/etc/x'), all)).toHaveLength(1);
    expect(contextProblems({ ...ctx('/content/a'), resourceTypes: [{ resourceType: 'a/b', properties: [] }, { resourceType: 'a/b', properties: [] }] }, all)).toEqual([
      'A component is listed twice.',
    ]);
  });

  it('a new context copies the rules it starts from', () => {
    const from = { ...ctx('/content', true), resourceTypes: [{ resourceType: 'a/b', properties: ['x'] }] };
    const fresh = newContext(from, '/content/wknd');
    fresh.resourceTypes[0].properties.push('y');
    expect(from.resourceTypes[0].properties).toEqual(['x']);
    expect(comparable(fresh)).not.toBe(comparable(from));
  });

  it('routes the list and the editor at AEM’s addresses', () => {
    expect(parsePlace({ pathname: RULES_URL, search: '', hash: '' })).toEqual({ kind: 'translation-rules' });
    const url = ruleEditorUrl('/content/wknd');
    const [pathname, search] = url.split('?');
    expect(parsePlace({ pathname, search: `?${search}`, hash: '' })).toEqual({ kind: 'translation-rule', context: '/content/wknd' });
    expect(urlOf({ kind: 'translation-rule', context: null })).toBe('/libs/cq/translation/translationrules/contextproperties.html');
  });
});
