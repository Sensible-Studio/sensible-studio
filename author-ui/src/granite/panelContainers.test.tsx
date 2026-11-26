import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createDefaultRegistry, GraniteForm, useGraniteForm, type ContentNode, type GraniteEnv, type GraniteFormModel, type GraniteNode } from './index';

const registry = createDefaultRegistry();
const dialog = (name: string) =>
  JSON.parse(readFileSync(join(__dirname, `../../fixtures/dialogs/wknd_components_${name}.json`), 'utf8')).root as GraniteNode;

const COMPONENTS = {
  'wknd/components/teaser': { resourceType: 'wknd/components/teaser', title: 'Teaser', group: 'WKND.Content' },
  'wknd/components/text': { resourceType: 'wknd/components/text', title: 'Text', group: 'WKND.Content' },
} as const;

function mount(root: GraniteNode, content: ContentNode, design: Record<string, unknown> = {}) {
  let form!: GraniteFormModel;
  const env: GraniteEnv = {
    contentPath: '/content/wknd/us/en/jcr:content/root/c',
    design,
    componentInfo: (rt) => COMPONENTS[rt as keyof typeof COMPONENTS],
    allowedComponents: () => Object.values(COMPONENTS),
  };
  function Harness() {
    form = useGraniteForm(root, content, registry, env);
    return <GraniteForm form={form} registry={registry} env={env} />;
  }
  render(<Harness />);
  return () => form;
}

const entries = (f: GraniteFormModel, name: string) => f.serialize().filter((e) => e.name === name).map((e) => e.value);
const optionTexts = (select: HTMLElement) => [...(select as HTMLSelectElement).options].map((o) => o.textContent);

const panels: ContentNode = {
  'sling:resourceType': 'wknd/components/tabs',
  activeItem: 'item_2',
  item_1: { 'sling:resourceType': 'wknd/components/teaser', 'cq:panelTitle': 'Overview' },
  item_2: { 'sling:resourceType': 'wknd/components/text', 'cq:panelTitle': 'Itinerary' },
  item_3: { 'sling:resourceType': 'wknd/components/text' },
};

afterEach(cleanup);

describe('Tabs and Carousel: Active Item', () => {
  for (const kind of ['tabs', 'carousel']) {
    it(`${kind}: lists the items of the Items tab and posts ./activeItem`, () => {
      const form = mount(dialog(kind), { ...panels, 'sling:resourceType': `wknd/components/${kind}` });
      const select = screen.getByLabelText('Active Item', { selector: 'select' }) as HTMLSelectElement;
      expect(optionTexts(select)).toEqual(['Default', 'Overview', 'Itinerary', 'Text']);
      expect(select.value).toBe('item_2');
      expect(entries(form(), './activeItem')).toEqual(['item_2']);

      // renaming an item in the Items tab renames its option
      act(() => {
        fireEvent.change(screen.getAllByRole('textbox', { name: /Title of item/, hidden: true })[0], { target: { value: 'Story' } });
      });
      expect(optionTexts(select)).toEqual(['Default', 'Story', 'Itinerary', 'Text']);

      act(() => {
        fireEvent.change(select, { target: { value: '' } });
      });
      expect(entries(form(), './activeItem')).toEqual(['']);
    });
  }
});

describe('Accordion: Expanded Items and Heading Element', () => {
  const accordion = { ...panels, 'sling:resourceType': 'wknd/components/accordion', expandedItems: ['item_1', 'item_3'] };

  it('chooses several items, or one with Single item expansion', () => {
    const form = mount(dialog('accordion'), accordion, { allowedHeadingElements: 'h3', headingElement: 'h3' });
    expect(screen.getByRole('list', { name: 'Expanded Items chosen', hidden: true }).textContent).toContain('Overview');
    expect(entries(form(), './expandedItems')).toEqual(['item_1', 'item_3']);
    expect(entries(form(), './expandedItems@TypeHint')).toContain('String[]');

    act(() => {
      fireEvent.click(screen.getByRole('checkbox', { name: /Single item expansion/, hidden: true }));
    });
    const single = screen.getByLabelText('Expanded Item', { selector: 'select' }) as HTMLSelectElement;
    expect(optionTexts(single)).toEqual(['None', 'Overview', 'Itinerary', 'Text']);
    expect(single.value).toBe('item_1');
    expect(entries(form(), './expandedItems')).toEqual(['item_1']);
    act(() => {
      fireEvent.change(single, { target: { value: '' } });
    });
    expect(entries(form(), './expandedItems@Delete')).toEqual(['']);
  });

  it('offers the heading element only when the configuration allows a choice, the configuration default first', () => {
    mount(dialog('accordion'), accordion, { allowedHeadingElements: 'h3', headingElement: 'h3' });
    expect(screen.queryByLabelText('Heading Element', { selector: 'select' })).toBeNull();
    cleanup();
    const form = mount(dialog('accordion'), accordion, { allowedHeadingElements: ['h2', 'h3', 'h4'], headingElement: 'h3' });
    const heading = screen.getByLabelText('Heading Element', { selector: 'select' }) as HTMLSelectElement;
    expect(optionTexts(heading)).toEqual(['h2', 'h3', 'h4']);
    expect(heading.value).toBe('h3');
    expect(entries(form(), './headingElement')).toEqual(['h3']);
  });

  it('shows no Expanded Items while there are no items', () => {
    const form = mount(dialog('accordion'), { 'sling:resourceType': 'wknd/components/accordion' }, {});
    expect(screen.queryByLabelText('Expanded Items', { selector: 'select' })).toBeNull();
    expect(entries(form(), './expandedItems')).toEqual([]);
  });
});

describe('Title: Type / Size from the configuration', () => {
  const title: ContentNode = { 'sling:resourceType': 'wknd/components/title', 'jcr:title': 'Hello' };

  it('offers the allowed types, defaulting to the configuration type', () => {
    const form = mount(dialog('title'), title, { allowedTypes: ['h1', 'h2', 'h3'], type: 'h2' });
    const selects = screen.getAllByLabelText('Type / Size', { selector: 'select' }) as HTMLSelectElement[];
    expect(selects).toHaveLength(1);
    expect(optionTexts(selects[0])).toEqual(['h1', 'h2', 'h3']);
    expect(selects[0].value).toBe('h2');
    expect(entries(form(), './type')).toEqual(['h2']);
  });

  it('uses the static types without a configuration, and none with a single allowed type', () => {
    mount(dialog('title'), title, {});
    expect(optionTexts(screen.getByLabelText('Type / Size', { selector: 'select' }))).toEqual(['H1', 'H2', 'H3', 'H4', 'H5', 'H6']);
    cleanup();
    mount(dialog('title'), title, { allowedTypes: ['h2'] });
    expect(screen.queryByLabelText('Type / Size', { selector: 'select' })).toBeNull();
  });
});

describe('Container: background color swatches', () => {
  it('offers the configuration swatches only', () => {
    const form = mount(dialog('container'), { 'sling:resourceType': 'wknd/components/container' }, {
      backgroundColorEnabled: true,
      backgroundColorSwatchesOnly: true,
      allowedColorSwatches: ['#202020', '#EBEBEB', ''],
    });
    const swatches = screen.getAllByRole('radio', { hidden: true }).filter((r) => r.classList.contains('studio-g-swatches__swatch'));
    expect(swatches.map((s) => s.getAttribute('aria-label'))).toEqual(['#202020', '#EBEBEB', 'No color']);
    act(() => {
      fireEvent.click(swatches[1]);
    });
    expect(entries(form(), './backgroundColor')).toEqual(['#EBEBEB']);
  });
});
