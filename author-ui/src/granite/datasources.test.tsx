import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createDefaultRegistry, GraniteForm, useGraniteForm, type ContentNode, type DialogResponse, type GraniteEnv, type GraniteFormModel, type GraniteNode } from './index';
import { expandDialogDataSources } from './expand';

const registry = createDefaultRegistry();
const fixture = (name: string) => JSON.parse(readFileSync(join(__dirname, `../../fixtures/dialogs/wknd_components_${name}.json`), 'utf8')) as DialogResponse;

function mount(root: GraniteNode, content: ContentNode, env: GraniteEnv) {
  let form!: GraniteFormModel;
  function Harness() {
    form = useGraniteForm(root, content, registry, env);
    return <GraniteForm form={form} registry={registry} env={env} />;
  }
  render(<Harness />);
  return () => form;
}
const entries = (f: GraniteFormModel, name: string) => f.serialize().filter((e) => e.name === name).map((e) => e.value);
const texts = (el: HTMLElement) => [...(el as HTMLSelectElement).options].map((o) => o.textContent);

afterEach(cleanup);

const YOUTUBE = 'core/wcm/components/embed/v1/embed/embeddable/youtube';
const youtubeDialog: GraniteNode = {
  name: 'cq:dialog',
  properties: { 'sling:resourceType': 'granite/ui/components/coral/foundation/container', 'jcr:title': 'YouTube' },
  children: [
    {
      name: 'items',
      properties: {},
      children: [{ name: 'videoId', properties: { 'sling:resourceType': 'granite/ui/components/coral/foundation/form/textfield', name: './youtubeVideoId', fieldLabel: 'Video ID' }, children: [] }],
    },
  ],
};
const sources = {
  dialog: async (rt: string, name: string) => (rt === YOUTUBE ? (name === 'cq:dialog' ? youtubeDialog : { ...youtubeDialog, name: 'cq:design_dialog' }) : null),
  embeddables: async () => [{ resourceType: YOUTUBE, title: 'YouTube' }],
};

describe('Embed: structural datasources', () => {
  it('offers the embeddables the configuration allows and shows the options of the chosen one', async () => {
    const policy: GraniteNode = { name: 'policy', properties: { allowedEmbeddables: [YOUTUBE] }, children: [] };
    const expanded = await expandDialogDataSources({ ...fixture('embed'), policy }, sources);
    const form = mount(expanded.root!, { 'sling:resourceType': 'wknd/components/embed', type: 'embeddable' }, { design: { allowedEmbeddables: [YOUTUBE] } });
    const select = screen.getByLabelText('Embeddable', { selector: 'select' });
    expect(texts(select)).toEqual(['Select', 'YouTube']);
    expect(screen.queryByLabelText('Video ID')).toBeNull();
    act(() => {
      fireEvent.change(select, { target: { value: YOUTUBE } });
    });
    act(() => {
      fireEvent.change(screen.getByLabelText('Video ID'), { target: { value: 'abc' } });
    });
    expect(entries(form(), './youtubeVideoId')).toEqual(['abc']);
  });

  it('adds a configuration tab per embeddable and lists all embeddables', async () => {
    const expanded = await expandDialogDataSources(fixture('embed.design'), sources);
    mount(expanded.root!, {}, {});
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Embeddable Types', 'YouTube', 'Styles']);
    expect(texts(screen.getByLabelText('Allowed Embeddables', { selector: 'select' }))).toContain('YouTube');
  });
});

describe('Content Fragment: elements and variants of the chosen content fragment', () => {
  const env: GraniteEnv = {
    contentFragments: {
      models: async () => [{ path: '/conf/m/adventure', title: 'Adventure' }],
      elements: async () => [
        { name: 'title', title: 'Title', kind: 'text' },
        { name: 'price', title: 'Price', kind: 'number' },
        { name: 'type', title: 'Type', kind: 'enumeration' },
      ],
      fragment: async () => ({ model: '/conf/m/adventure', variations: [{ name: 'teaser', title: 'Teaser' }] }),
    },
  };

  it('lists the variants (Master first) and the elements, single or multiple', async () => {
    const content = { 'sling:resourceType': 'wknd/components/contentfragment', fragmentPath: '/content/dam/f', displayMode: 'singleText' };
    const form = mount(fixture('contentfragment').root!, content, env);
    const variation = screen.getByLabelText('Variation', { selector: 'select' });
    await waitFor(() => expect(texts(variation)).toEqual(['Master', 'Teaser']));
    await waitFor(() => expect(entries(form(), './variationName')).toEqual(['master']));
    const element = screen.getByLabelText(/^Element/, { selector: 'select' });
    await waitFor(() => expect(texts(element)).toEqual(['Select', 'Title', 'Price', 'Type']));
    act(() => {
      fireEvent.change(element, { target: { value: 'price' } });
    });
    expect(entries(form(), './elementNames')).toEqual(['price']);

    act(() => {
      fireEvent.click(screen.getByRole('radio', { name: 'Multiple Elements', hidden: true }));
    });
    expect(screen.getByLabelText('Element 1', { selector: 'select' })).toBeTruthy();
    expect(entries(form(), './elementNames@TypeHint')).toEqual(['String[]']);
  });

  it('orders a list by Created, Last Modified and the model’s text and number elements', async () => {
    const form = mount(fixture('contentfragmentlist').root!, { modelPath: '/conf/m/adventure' }, env);
    const models = screen.getByLabelText('Model', { selector: 'select' });
    await waitFor(() => expect(texts(models)).toContain('Adventure'));
    const orderBy = screen.getByLabelText('Order By', { selector: 'select' });
    await waitFor(() => expect(texts(orderBy)).toEqual(['Created', 'Last Modified', 'Title', 'Price']));
    expect(entries(form(), './modelPath')).toEqual(['/conf/m/adventure']);
  });
});

describe('Page configuration: client library categories', () => {
  it('suggests the categories', async () => {
    const env: GraniteEnv = { clientLibraryCategories: async () => ['wknd.base', 'wknd.site'] };
    mount(fixture('page.design').root!, { clientlibs: ['wknd.base'] }, env);
    await waitFor(() => expect(document.querySelectorAll('datalist option').length).toBeGreaterThan(0));
    expect([...document.querySelectorAll('datalist option')].map((o) => (o as HTMLOptionElement).value)).toContain('wknd.site');
  });
});
