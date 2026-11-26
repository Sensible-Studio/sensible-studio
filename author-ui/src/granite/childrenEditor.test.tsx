import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createDefaultRegistry, GraniteForm, useGraniteForm, type ContentNode, type GraniteEnv, type GraniteFormModel, type GraniteNode } from './index';
import { childItems, newItemName } from './components/fields/children';

const registry = createDefaultRegistry();
const carousel = JSON.parse(readFileSync(join(__dirname, '../../fixtures/dialogs/wknd_components_carousel.json'), 'utf8')).root as GraniteNode;
const PATH = '/content/wknd/us/en/jcr:content/root/carousel';

const content: ContentNode = {
  'sling:resourceType': 'wknd/components/carousel',
  'jcr:primaryType': 'nt:unstructured',
  item_1: { 'sling:resourceType': 'wknd/components/teaser', 'cq:panelTitle': 'Adventures' },
  teaser: { 'sling:resourceType': 'wknd/components/teaser', 'cq:panelTitle': 'Skiing' },
  image: { 'sling:resourceType': 'wknd/components/image' },
  'cq:responsive': { default: { width: '12' } },
};

function Harness({ env, onForm }: { env: GraniteEnv; onForm(f: GraniteFormModel): void }) {
  const form = useGraniteForm(carousel, content, registry, env);
  onForm(form);
  return <GraniteForm form={form} registry={registry} env={env} />;
}

function mount() {
  let form!: GraniteFormModel;
  const components = {
    'wknd/components/teaser': { resourceType: 'wknd/components/teaser', title: 'Teaser', group: 'WKND.Content' },
    'wknd/components/image': { resourceType: 'wknd/components/image', title: 'Image', group: 'WKND.Content' },
  } as const;
  const env: GraniteEnv = {
    contentPath: PATH,
    componentInfo: (rt) => components[rt as keyof typeof components],
    allowedComponents: (p) => (p === PATH ? Object.values(components) : []),
  };
  render(<Harness env={env} onForm={(f) => (form = f)} />);
  return () => form;
}

const entry = (f: GraniteFormModel, name: string) => f.serialize().find((e) => e.name === name)?.value;

afterEach(cleanup);

describe('children editor (Carousel Items tab)', () => {
  it('lists the child components with their panel titles', () => {
    expect(childItems(content).map((i) => i.name)).toEqual(['item_1', 'teaser', 'image']);
    mount();
    const titles = screen.getAllByRole('textbox', { name: /Title of item/, hidden: true }) as HTMLInputElement[];
    expect(titles.map((t) => t.value)).toEqual(['Adventures', 'Skiing', '']);
    expect(titles[2].placeholder).toBe('Image');
  });

  it('posts titles, order and deletions like AEM', () => {
    const form = mount();
    expect(entry(form(), 'itemOrder')).toBe('item_1,teaser,image');
    expect(entry(form(), 'deletedItems')).toBe('');
    act(() => {
      fireEvent.click(screen.getAllByRole('button', { name: 'Move down', hidden: true })[0]);
    });
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Remove Skiing', hidden: true }));
    });
    act(() => {
      fireEvent.change(screen.getAllByRole('textbox', { name: /Title of item/, hidden: true })[0], { target: { value: 'Surf' } });
    });
    expect(entry(form(), 'itemOrder')).toBe('item_1,image');
    expect(entry(form(), 'deletedItems')).toBe('teaser');
    expect(entry(form(), './image/cq:panelTitle')).toBe('');
    expect(entry(form(), './item_1/cq:panelTitle')).toBe('Surf');
    expect(form().isDirty()).toBe(true);
  });

  it('adds a component the container allows', async () => {
    const form = mount();
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Add', hidden: true }));
    });
    const image = await screen.findByRole('button', { name: 'Image', hidden: true });
    act(() => {
      fireEvent.click(image);
    });
    const order = entry(form(), 'itemOrder')!.split(',');
    expect(order).toHaveLength(4);
    const added = order[3];
    expect(added).toMatch(/^item_\d+$/);
    expect(entry(form(), `./${added}/sling:resourceType`)).toBe('wknd/components/image');
  });

  it('new names do not clash', () => {
    expect(newItemName(['item_5', 'item_6'], 5)).toBe('item_7');
  });
});
