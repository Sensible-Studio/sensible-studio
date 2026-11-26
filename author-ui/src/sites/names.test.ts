import { describe, expect, it } from 'vitest';
import { nameFromTitle } from './names';

describe('nameFromTitle', () => {
  it('makes URL-safe page names like AEM', () => {
    expect(nameFromTitle('Summer Camps 2027')).toBe('summer-camps-2027');
    expect(nameFromTitle('  Ski Touring: Mont Blanc! ')).toBe('ski-touring-mont-blanc');
    expect(nameFromTitle('Café Brașov')).toBe('cafe-brasov');
    expect(nameFromTitle('***')).toBe('');
  });
});
