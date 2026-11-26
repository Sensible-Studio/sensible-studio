import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { createDefaultRegistry, GraniteForm, useGraniteForm, type ContentNode, type GraniteEnv, type GraniteFormModel, type GraniteNode } from './index';

afterEach(cleanup);
const registry = createDefaultRegistry();

const n = (name: string, properties: Record<string, unknown>, children: GraniteNode[] = []): GraniteNode =>
  ({ name, properties: { 'jcr:primaryType': 'nt:unstructured', ...properties }, children }) as unknown as GraniteNode;

function Harness({ root, content, env, onForm }: { root: GraniteNode; content: ContentNode; env: GraniteEnv; onForm(f: GraniteFormModel): void }) {
  const form = useGraniteForm(root, content, registry, env);
  onForm(form);
  return <GraniteForm form={form} registry={registry} env={env} />;
}

function mount(field: GraniteNode, content: ContentNode, env: GraniteEnv = {}) {
  let form!: GraniteFormModel;
  const root = n('cq:design_dialog', { 'sling:resourceType': 'cq/gui/components/authoring/dialog' }, [
    n('content', { 'sling:resourceType': 'granite/ui/components/coral/foundation/container' }, [n('items', {}, [field])]),
  ]);
  render(<Harness root={root} content={content} env={env} onForm={(f) => (form = f)} />);
  return () => form;
}

const COMPONENTS = [
  { resourceType: 'site/components/title', path: '/apps/site/components/title', title: 'Title', group: 'Site' },
  { resourceType: 'site/components/text', path: '/apps/site/components/text', title: 'Text', group: 'Site' },
  { resourceType: 'site/components/form', path: '/apps/site/components/form', title: 'Form', group: 'Forms' },
];

describe('configuration fields', () => {
  it('Allowed Components: a whole group is stored as group:<name>, single components by path', async () => {
    const form = mount(n('components', { 'sling:resourceType': 'studio/granite/components/allowedcomponents', name: './components' }), {
      components: ['/apps/site/components/title', 'group:Elsewhere'],
    }, { allComponents: async () => COMPONENTS });
    await screen.findByText('Site');
    expect(screen.getByText('1/2')).toBeTruthy();
    // ticking the group selects all of it, written as the group
    await act(async () => {
      fireEvent.click(screen.getAllByRole('checkbox')[1]); // Site
    });
    const entries = form().serialize().filter((e) => e.name.startsWith('./components'));
    expect(entries).toContainEqual({ name: './components', value: 'group:Site' });
    expect(entries).toContainEqual({ name: './components', value: 'group:Elsewhere' });
    expect(entries.some((e) => e.value === '/apps/site/components/title')).toBe(false);
    expect(entries).toContainEqual({ name: './components@TypeHint', value: 'String[]' });
  });

  it('Default Components: the file to component mappings', async () => {
    const form = mount(n('mappings', { 'sling:resourceType': 'studio/granite/components/assetmappings', name: './cq:authoring/assetToComponentMapping' }), {
      'cq:authoring': { assetToComponentMapping: { mapping_1: { resourceType: 'site/components/image', assetGroup: 'media', assetMimetype: 'image/*', droptarget: 'image' } } },
    }, { allComponents: async () => COMPONENTS });
    await waitFor(() => expect(screen.getAllByRole('combobox').length).toBeGreaterThan(0));
    const entries = form().serialize();
    expect(entries[0]).toEqual({ name: './cq:authoring/assetToComponentMapping@Delete', value: '' });
    expect(entries).toContainEqual({ name: './cq:authoring/assetToComponentMapping/mapping_1/resourceType', value: 'site/components/image' });
    expect(entries).toContainEqual({ name: './cq:authoring/assetToComponentMapping/mapping_1/assetMimetype', value: 'image/*' });
  });

  it('a simple multifield whose field is a container posts the field inside it (Core page configuration client libraries)', () => {
    const field = n('clientlibs', { 'sling:resourceType': 'granite/ui/components/coral/foundation/form/multifield', composite: false, fieldLabel: 'Client Libraries' }, [
      n('field', { 'sling:resourceType': 'granite/ui/components/coral/foundation/container', name: './clientlibs' }, [
        n('items', {}, [n('category', { 'sling:resourceType': 'granite/ui/components/coral/foundation/form/autocomplete', name: './clientlibs', multiple: false }, [
          n('datasource', { 'sling:resourceType': 'core/wcm/components/commons/datasources/clientlibrarycategories/v1' }),
        ])]),
      ]),
    ]);
    const form = mount(field, { clientlibs: ['site.base', 'site.theme'] });
    expect(screen.getByText('site.base')).toBeTruthy();
    expect(screen.getByText('site.theme')).toBeTruthy();
    const values = form().serialize().filter((e) => e.name === './clientlibs').map((e) => e.value);
    expect(values).toEqual(['site.base', 'site.theme']);
  });
});
