import { describe, expect, it } from 'vitest';
import type { ComponentInfo } from '../api/StudioClient';
import type { Editable } from '../model/editables';
import { cleanHtml, cleanText, inplaceEditing, inplaceTarget } from './inplace';

const info = (inplace: ComponentInfo['inplace']): ComponentInfo => ({
  resourceType: 'x/text',
  title: 'Text',
  group: 'G',
  inplace,
});

const editable = (html: string): Editable => {
  const el = document.createElement('div');
  el.innerHTML = html;
  return {
    path: '/p',
    type: 'x',
    title: 'X',
    el,
    parent: null,
    children: [],
    depth: 0,
    dialog: true,
    designDialog: false,
    container: false,
    placeholder: null,
    grid: null,
  };
};

describe('inplaceEditing', () => {
  it('reads the Core text configuration: property, formats and features', () => {
    const e = inplaceEditing(
      info({
        editorType: 'text',
        active: true,
        configPath: 'inplaceEditingConfig',
        inplaceEditingConfig: {
          rtePlugins: {
            format: { features: 'bold,italic' },
            justify: { features: '-' },
            links: { features: 'modifylink,unlink' },
            paraformat: {
              formats: {
                p: { tag: 'p', description: 'Paragraph' },
                h2: { tag: 'h2', description: 'Heading 2' },
                hr: { tag: 'hr', description: 'Rule' },
              },
            },
          },
        },
      }),
    );
    expect(e).toMatchObject({ kind: 'text', property: 'text' });
    expect(e!.features).toEqual({
      bold: true,
      italic: true,
      underline: false,
      justify: false,
      lists: true,
      links: true,
      paraformat: true,
    });
    expect(e!.formats).toEqual([
      { tag: 'p', label: 'Paragraph' },
      { tag: 'h2', label: 'Heading 2' },
    ]);
  });

  it('titles edit jcr:title as plain text; plaintext editors use textPropertyName', () => {
    expect(
      inplaceEditing(
        info({
          editorType: 'title',
          active: true,
          inplaceEditingConfig: { titleTag: ['h1', 'h2'] },
        }),
      ),
    ).toMatchObject({ kind: 'title', property: 'jcr:title' });
    expect(
      inplaceEditing(
        info({
          editorType: 'plaintext',
          active: true,
          textPropertyName: 'caption',
        }),
      ),
    ).toMatchObject({ kind: 'title', property: 'caption' });
  });

  it('is null without a configuration, when inactive, and for editors Sensible Studio opens as a dialog', () => {
    expect(inplaceEditing(info(undefined))).toBeNull();
    expect(inplaceEditing(info({ editorType: 'text', active: 'false' }))).toBeNull();
    expect(inplaceEditing(info({ editorType: 'image', active: true }))).toBeNull();
    expect(inplaceEditing(info({ editorType: 'hybrid', active: true }))).toBeNull();
  });
});

describe('inplaceTarget', () => {
  it('edits the heading of a title and the first child of a text', () => {
    const title = editable('<div class="cmp-title"><h1 class="cmp-title__text">Magazine</h1></div>');
    expect(inplaceTarget(title, inplaceEditing(info({ editorType: 'title' }))!, undefined)?.tagName).toBe('H1');
    const text = editable('<div class="cmp-text"><p>Hello</p></div>');
    expect(inplaceTarget(text, inplaceEditing(info({ editorType: 'text' }))!, undefined)?.className).toBe('cmp-text');
  });

  it('honours editElementQuery', () => {
    const i = info({
      editorType: 'text',
      inplaceEditingConfig: { editElementQuery: '.body' },
    });
    const e = editable('<div class="cmp-x"><div class="body"><p>b</p></div></div>');
    expect(inplaceTarget(e, inplaceEditing(i)!, i)?.className).toBe('body');
  });
});

describe('cleaning', () => {
  it('removes editor artefacts and empty trailing paragraphs from rich text', () => {
    expect(cleanHtml('<p contenteditable="true" spellcheck="true">Hi&nbsp;</p><p><br></p>')).toBe('<p>Hi&nbsp;</p>');
  });

  it('collapses title whitespace', () => {
    expect(cleanText('  Camping in \n Western   Australia ')).toBe('Camping in Western Australia');
  });
});
