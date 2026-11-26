import { describe, expect, it } from 'vitest';
import { evaluate, evaluateBoolean } from './el';

const ctx = { cqDesign: { 'jcr:description': 'From configuration', showTitleType: true, allowedTypes: [], delay: 3000 }, param: {} };

describe('Granite EL', () => {
  it('reads configuration values, including _ns_ escaped names', () => {
    expect(evaluate('${cqDesign._jcr_description}', ctx)).toBe('From configuration');
    expect(evaluate('${cqDesign.missing}', ctx)).toBeUndefined();
  });

  it('handles the operators Core Components dialogs use', () => {
    expect(evaluateBoolean('${!cqDesign.showTitleType or (empty cqDesign.allowedHeadingElements and empty cqDesign.allowedTypes)}', ctx)).toBe(true);
    expect(evaluateBoolean('${!cqDesign.showTitleType or not empty cqDesign.allowedTypes}', ctx)).toBe(false);
    expect(evaluate('${not empty cqDesign.delay ? cqDesign.delay : 5000}', ctx)).toBe(3000);
    expect(evaluate('${not empty cqDesign.altValueFromDAM ? cqDesign.altValueFromDAM : true}', ctx)).toBe(true);
    expect(evaluateBoolean('${(cqDesign.a && cqDesign.b) || cqDesign.showTitleType}', ctx)).toBe(true);
    expect(evaluate("${cqDesign['jcr:description'] == 'From configuration'}", ctx)).toBe(true);
  });

  it('leaves plain strings alone and returns undefined for what it cannot parse', () => {
    expect(evaluate('plain', ctx)).toBe('plain');
    expect(evaluate('${a +}', ctx)).toBeUndefined();
  });
});
