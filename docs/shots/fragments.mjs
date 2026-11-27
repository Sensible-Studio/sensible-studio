// The pictures of Content Fragments and their models (author/content-fragments.md).
const DIR = '/cf.html/content/dam/weekender-shared/en/magazine/arctic-surfing';
const FRAGMENT = '/editor.html/content/dam/weekender-shared/en/magazine/arctic-surfing/aloha-spirits-in-northern-norway';
const MODELS = '/cf.html/conf/weekender-shared';
const MODEL = '/editor.html/conf/weekender-shared/settings/dam/cfm/models/article';
const label = (name) => `[aria-label="${name}"]`;
const button = (text) => ({ click: { text, closest: 'button' } });
const select = (title) => ({ click: 'label:has(input[aria-label="Select ' + title + '"])' });
const tab = (name) => ({ click: { text: name, closest: '[role="tab"],button' } });
const editor = [{ goto: FRAGMENT }, { wait: 4000 }];
const model = [{ goto: MODEL }, { wait: 4000 }];

export default [
  { name: 'console', steps: [
    { goto: DIR }, { wait: 2500 }, { shot: 'fragments-console' },
    select('Surfing under the Northern Lights'), { wait: 1200 }, { shot: 'fragments-selected' },
  ] },
  { name: 'create', steps: [{ goto: DIR }, { wait: 2500 }, button('Create content fragment'), { wait: 2000 }, { shot: 'fragments-create' }] },
  { name: 'editor', steps: [
    ...editor,
    { mark: [label('Create variant'), { text: 'Master', closest: 'button,li,[role="option"]' }, { text: 'Rich text', contains: true, closest: 'button' }, { text: 'Save', closest: 'button' }, tab('Details').click, label('Close inspector')] },
    { shot: 'fragments-editor' }, { mark: [] },
    { scroll: [600, 600, 700] }, { wait: 700 }, { shot: 'fragments-editor-2' },
  ] },
  { name: 'references', steps: [...editor, tab('References'), { wait: 1500 }, { shot: 'fragments-references' }] },
  { name: 'versions', steps: [...editor, tab('Versions'), { wait: 1500 }, { shot: 'fragments-versions' }] },
  { name: 'languages', steps: [...editor, tab('Languages'), { wait: 1500 }, { shot: 'fragments-languages' }] },
  { name: 'variant', steps: [...editor, { click: label('Create variant') }, { wait: 1500 }, { shot: 'fragments-create-variant' }] },
  { name: 'format', steps: [...editor, { click: label('Main format') }, { wait: 700 }, { shot: 'fragments-format', keepPointer: true }] },
  { name: 'models', steps: [
    { goto: MODELS }, { wait: 2500 }, { shot: 'models-console' },
    select('Article'), { wait: 1200 }, { shot: 'models-selected' },
  ] },
  { name: 'model-create', steps: [{ goto: MODELS }, { wait: 2500 }, button('Create model'), { wait: 2000 }, { shot: 'models-create' }] },
  { name: 'model', steps: [
    ...model, { shot: 'models-editor' },
    { click: { text: 'Featured Image' } }, { wait: 1500 }, { shot: 'models-field' },
  ] },
  { name: 'model-actions', steps: [...model, { click: label('Actions for Title') }, { wait: 700 }, { shot: 'models-field-actions', keepPointer: true }] },
  { name: 'model-text', steps: [...model, { click: { text: 'Main' } }, { wait: 1500 }, { shot: 'models-field-text' }] },
];
