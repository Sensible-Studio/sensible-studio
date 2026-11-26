import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createDefaultRegistry, designFromPolicy, GraniteForm, useGraniteForm, type ContentNode, type DialogResponse, type GraniteEnv, type GraniteFormModel, type GraniteNode } from './index';
import { applySlingPost, resolve } from '../editor/testing/slingPostEmulator';
import { createDemoRepository, DEMO_PAGE } from '../editor/testing/demoRepository';
import { resourceTypeOf } from './node';

const FIXTURES = join(__dirname, '../../fixtures/dialogs');
const registry = createDefaultRegistry();

function loadFixture(file: string): DialogResponse & { root: GraniteNode } {
  return JSON.parse(readFileSync(join(FIXTURES, file), 'utf8'));
}

function Harness({ root, content, env, onForm }: { root: GraniteNode; content: ContentNode; env: GraniteEnv; onForm(f: GraniteFormModel): void }) {
  const form = useGraniteForm(root, content, registry, env);
  onForm(form);
  return <GraniteForm form={form} registry={registry} env={env} />;
}

function mount(file: string, content: ContentNode = {}, design?: Record<string, unknown>) {
  let form!: GraniteFormModel;
  const dialog = loadFixture(file);
  const env: GraniteEnv = { listChildren: async () => [], design };
  render(<Harness root={dialog.root} content={content} env={env} onForm={(f) => (form = f)} />);
  return { form: () => form, dialog };
}

function loadPolicy(file: string): Record<string, unknown> {
  const json = JSON.parse(readFileSync(join(FIXTURES, '../policies', file), 'utf8'));
  return designFromPolicy(json.policy);
}

afterEach(cleanup);

describe('every WKND dialog renders', () => {
  const files = readdirSync(FIXTURES).filter((f) => f.endsWith('.json'));

  it.each(files)('%s', (file) => {
    const { form } = mount(file);
    expect(form().serialize()).toBeInstanceOf(Array);
  });

  it('reports which resource types are still unsupported', () => {
    const unsupported = new Map<string, number>();
    const visit = (n: GraniteNode) => {
      const rt = resourceTypeOf(n);
      if (rt && !registry.component(rt) && !registry.isSilent(rt) && !registry.renderCondition(rt) && !registry.dataSource(rt)) {
        unsupported.set(rt, (unsupported.get(rt) ?? 0) + 1);
      }
      // children of these are data, not UI
      n.children.filter((c) => !['datasource', 'granite:rendercondition', 'granite:data'].includes(c.name)).forEach(visit);
    };
    for (const f of files) visit(loadFixture(f).root);
    // Visible in the test output; widgets specific to AEM services, implemented later.
    console.info('Unsupported resource types:', Object.fromEntries(unsupported));
    expect(unsupported.has('granite/ui/components/coral/foundation/form/textfield')).toBe(false);
  });
});

describe('round trip through the Sling POST emulator', () => {
  const pagePath = `${DEMO_PAGE}/jcr:content/root/container`;

  function saveUnchanged(file: string, component: string) {
    const repo = createDemoRepository();
    const path = `${pagePath}/${component}`;
    const before = structuredClone(resolve(repo, path)!);
    const { form } = mount(file, structuredClone(before));
    const errors = form().validate();
    applySlingPost(repo, path, form().serialize());
    return { before, after: resolve(repo, path)!, errors, entries: form().serialize() };
  }

  it('title: saving without edits keeps the content', () => {
    const { before, after } = saveUnchanged('wknd_components_title.json', 'title');
    expect(after['jcr:title']).toBe(before['jcr:title']);
    expect(after.type).toBe('h1');
  });

  it('byline: keeps name, occupations (String[]) and the image reference', () => {
    const { after, errors } = saveUnchanged('wknd_components_byline.json', 'byline');
    expect(errors).toEqual([]);
    expect(after.name).toBe('Stacey Roswells');
    expect(after.occupations).toEqual(['Photographer', 'Writer']);
    expect(after.fileReference).toBe('/content/dam/wknd-shared/en/contributors/stacey-roswells.jpg');
  });

  it('teaser: composite multifield items survive as item nodes', () => {
    const { after, entries } = saveUnchanged('wknd_components_teaser.json', 'teaser');
    expect(entries.some((e) => e.name === './actions@Delete')).toBe(true);
    const actions = after.actions as ContentNode;
    expect(actions).toBeDefined();
    expect((actions.item0 as ContentNode).link).toBe('/content/wknd/us/en/adventures');
    expect((actions.item0 as ContentNode).text).toBe('See adventures');
  });

  it('image: typed checkboxes are stored as booleans', () => {
    const { after } = saveUnchanged('wknd_components_image.json', 'image');
    expect(after.isDecorative).toBe(false);
    expect(after.alt).toBe('Skiers ascending a ridge');
  });

  it('text: rich text is posted as HTML', () => {
    const { after } = saveUnchanged('wknd_components_text.json', 'text');
    expect(String(after.text)).toContain('<b>Haute Route</b>');
  });
});

describe('editing', () => {
  it('required fields block saving and show an error', () => {
    const { form } = mount('wknd_components_byline.json', {});
    const errors = form().validate();
    expect(errors.map((e) => e.name)).toContain('./name');
  });

  it('typing updates the posted value', () => {
    const { form } = mount('wknd_components_title.json', { 'jcr:title': 'Old' });
    const input = screen.getByLabelText('Title') as HTMLInputElement;
    expect(input.value).toBe('Old');
    act(() => {
      fireEvent.change(input, { target: { value: 'New title' } });
    });
    expect(form().serialize()).toContainEqual({ name: './jcr:title', value: 'New title' });
    expect(form().isDirty()).toBe(true);
  });

  it('title: the datasource-only variant of ./type is dropped in favour of the static one', () => {
    const { form } = mount('wknd_components_title.json', { type: 'h3' });
    const typeEntries = form().serialize().filter((e) => e.name === './type');
    expect(typeEntries).toEqual([{ name: './type', value: 'h3' }]);
  });

  it('simple multifield: adding an item posts it', () => {
    const { form } = mount('wknd_components_byline.json', { name: 'A', occupations: ['One'] });
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Add', hidden: true }));
    });
    const inputs = screen.getAllByPlaceholderText('Enter an occupation') as HTMLInputElement[];
    act(() => {
      fireEvent.change(inputs[1], { target: { value: 'Two' } });
    });
    const occ = form()
      .serialize()
      .filter((e) => e.name.startsWith('./occupations'));
    expect(occ).toEqual([
      { name: './occupations', value: 'One' },
      { name: './occupations', value: 'Two' },
      { name: './occupations@TypeHint', value: 'String[]' },
    ]);
  });
});

describe('Sensible Studio authoring content (page properties, Style System)', () => {
  it('page properties: the standard page fields are real fields, PWA is hidden', () => {
    const { form } = mount('wknd_components_page.json', { 'jcr:title': 'Ski Touring', hideInNav: 'true', 'cq:tags': ['wknd-shared:activity/skiing'] });
    expect(screen.getAllByRole('tab', { hidden: true }).map((t) => t.textContent)).toEqual(['Basic', 'Advanced', 'Images']);
    const entries = form().serialize();
    expect(entries).toContainEqual({ name: './jcr:title', value: 'Ski Touring' });
    expect(entries).toContainEqual({ name: './hideInNav', value: 'true' });
    expect(entries).toContainEqual({ name: './cq:tags', value: 'wknd-shared:activity/skiing' });
    expect(form().validate()).toEqual([]);
    for (const name of ['./pageTitle', './navTitle', './onTime', './jcr:language', './cq:redirectTarget', './sling:alias']) {
      expect(form().store.fieldNames()).toContain(name);
    }
  });

  it('Styles tab appears only when the configuration defines styles', () => {
    mount('wknd_components_teaser.json', {});
    expect(screen.queryByRole('tab', { name: 'Styles', hidden: true })).toBeNull();
    cleanup();
    mount('wknd_components_teaser.json', {}, loadPolicy('wknd_components_teaser.json'));
    expect(screen.getByRole('tab', { name: 'Styles', hidden: true })).toBeDefined();
  });

  it('choosing a style posts cq:styleIds and keeps unknown IDs', () => {
    const design = loadPolicy('wknd_components_teaser.json');
    const { form } = mount('wknd_components_teaser.json', { 'cq:styleIds': ['legacy-id'] }, design);
    const select = screen.getByLabelText('Style', { selector: 'select' }) as HTMLSelectElement;
    const featured = [...select.options].find((o) => o.text === 'Featured')!;
    act(() => {
      fireEvent.change(select, { target: { value: featured.value } });
    });
    const ids = form()
      .serialize()
      .filter((e) => e.name.startsWith('./cq:styleIds'));
    expect(ids).toEqual([
      { name: './cq:styleIds', value: 'legacy-id' },
      { name: './cq:styleIds', value: featured.value },
      { name: './cq:styleIds@TypeHint', value: 'String[]' },
    ]);
  });

  it('component configuration: nested composite multifields write style groups', () => {
    const repo: ContentNode = {};
    const { form } = mount('wknd_components_teaser.design.json', {});
    expect(form().store.fieldNames()).toContain('./cq:styleGroups');
    applySlingPost(repo, '/policy', form().serialize());
    expect(resolve(repo, '/policy')).toBeDefined();
  });
});
