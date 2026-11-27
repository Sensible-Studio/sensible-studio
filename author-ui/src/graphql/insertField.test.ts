import { describe, expect, it } from 'vitest';
import { insertField } from './QueryEditor';

const START = '# Write a query and run it with Run.\n{\n  __typename\n}\n';

describe('a field from the Schema panel', () => {
  it('goes inside the query when the caret is outside it', () => {
    const { text } = insertField(START, 0, 0, 'articleList {\n  \n}');
    expect(text).toBe('# Write a query and run it with Run.\n{\n  __typename\n  articleList {\n    \n  }\n}\n');
  });
  it('goes at the caret when that is inside the braces', () => {
    const at = START.indexOf('__typename');
    const { text, caret } = insertField(START, at, at + '__typename'.length, '_path');
    expect(text).toContain('{\n  _path\n}');
    expect(caret).toBe(at + '_path'.length);
  });
  it('makes braces of its own in an empty editor, and is not fooled by a brace in a comment', () => {
    expect(insertField('', 0, 0, 'authorList').text).toBe('{\n  authorList\n}\n');
    expect(insertField('# like { this }\n', 0, 0, 'authorList').text).toBe('# like { this }\n{\n  authorList\n}\n');
  });
});
