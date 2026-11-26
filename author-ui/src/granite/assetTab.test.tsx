import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createDefaultRegistry, GraniteForm, useGraniteForm, type ContentNode, type GraniteEnv, type GraniteFormModel, type GraniteNode } from './index';
import { holds } from './prepare';
import { pageOfLink } from './components/fields/pageimage';

const registry = createDefaultRegistry();
const teaser = JSON.parse(readFileSync(join(__dirname, '../../fixtures/dialogs/wknd_components_teaser.json'), 'utf8')).root as GraniteNode;
const PAGE = '/content/wknd/us/en/magazine';
const ASSET = '/content/dam/wknd-shared/en/magazine/la-skateparks/ostTraining.jpg';

function Harness({ content, env, onForm }: { content: ContentNode; env: GraniteEnv; onForm(f: GraniteFormModel): void }) {
  const form = useGraniteForm(teaser, content, registry, env);
  onForm(form);
  return <GraniteForm form={form} registry={registry} env={env} />;
}

function mount(content: ContentNode) {
  let form!: GraniteFormModel;
  const env: GraniteEnv = {
    contentPath: `${PAGE}/jcr:content/root/teaser`,
    listChildren: async () => [],
    assetThumbnail: (p) => `${p}/thumb.png`,
    assetInfo: async (p) => ({ path: p, description: 'Skaters at the park' }),
    pageImage: async (p) => (p === PAGE ? { fileReference: '/content/dam/featured.jpg' } : null),
  };
  render(<Harness content={content} env={env} onForm={(f) => (form = f)} />);
  return () => form;
}

const hidden = (node: string) => document.querySelector(`[data-node="${node}"]`)?.hasAttribute('hidden');
const checkbox = (text: string) => screen.getByRole('checkbox', { name: text, hidden: true }) as HTMLInputElement;

afterEach(cleanup);

describe('teaser Asset tab (image v3 editor rules)', () => {
  it('inheriting the page image shows the page image and the page alt option', async () => {
    mount({ imageFromPageImage: 'true' });
    expect(hidden('file')).toBe(true);
    expect(hidden('pageImageThumbnail')).toBe(false);
    expect(hidden('altValueFromPageImage')).toBe(false);
    expect(hidden('altValueFromDAM')).toBe(true);
    await waitFor(() => expect(document.querySelector('.studio-g-pageimage img')?.getAttribute('src')).toBe('/content/dam/featured.jpg/thumb.png'));
  });

  it('an own file shows the drop zone, and the alt text inherited from its description', async () => {
    const form = mount({ imageFromPageImage: 'false', fileReference: ASSET, altValueFromDAM: 'true' });
    expect(hidden('file')).toBe(false);
    expect(hidden('pageImageThumbnail')).toBe(true);
    expect(hidden('altValueFromPageImage')).toBe(true);
    expect(document.querySelector('.studio-g-upload img')?.getAttribute('src')).toBe(`${ASSET}/thumb.png`);
    const alt = screen.getByLabelText(/Alternative text/, { selector: 'input' }) as HTMLInputElement;
    expect(alt.disabled).toBe(true);
    await waitFor(() => expect(alt.value).toBe('Skaters at the park'));
    // the inherited text is only shown: alt is not posted while disabled
    expect(form().serialize().some((e) => e.name === './alt')).toBe(false);

    act(() => {
      fireEvent.click(checkbox('Inherit from description of asset'));
    });
    expect(alt.disabled).toBe(false);
    expect(alt.value).toBe('');
  });

  it('content with its own file but no inherit flag opens with the file, like AEM', () => {
    mount({ fileReference: ASSET });
    expect(checkbox('Inherit featured image from page').checked).toBe(false);
    expect(hidden('file')).toBe(false);
  });

  it('a new teaser inherits the page image by default', () => {
    mount({});
    expect(checkbox('Inherit featured image from page').checked).toBe(true);
  });

  it('a checked but hidden option does not lock the alt text', () => {
    mount({ imageFromPageImage: 'false', altValueFromPageImage: 'true', altValueFromDAM: 'false' });
    const alt = screen.getByLabelText(/Alternative text/, { selector: 'input' }) as HTMLInputElement;
    expect(alt.disabled).toBe(false);
  });

  it('a file dropped from the Media panel becomes the reference', () => {
    const form = mount({ imageFromPageImage: 'false' });
    const zone = document.querySelector('.studio-g-upload') as HTMLElement;
    const data: Record<string, string> = { 'application/x-studio-asset': ASSET };
    const dataTransfer = { types: Object.keys(data), getData: (t: string) => data[t] ?? '', dropEffect: 'none' };
    act(() => {
      fireEvent.dragOver(zone, { dataTransfer });
      fireEvent.drop(zone, { dataTransfer });
    });
    expect(form().serialize()).toContainEqual({ name: './fileReference', value: ASSET });
    expect(document.querySelector('.studio-g-upload img')?.getAttribute('src')).toBe(`${ASSET}/thumb.png`);
  });

  it('Clear removes the file reference', () => {
    const form = mount({ imageFromPageImage: 'false', fileReference: ASSET });
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Clear', hidden: true }));
    });
    expect(form().serialize()).toContainEqual({ name: './fileReference', value: '' });
  });

  it('help texts are info icons whose text describes the control', () => {
    mount({});
    const box = checkbox('Inherit featured image from page');
    const desc = document.getElementById(box.getAttribute('aria-describedby')!.split(' ')[0]);
    expect(desc?.textContent).toMatch(/featured image defined in the properties/);
    expect(screen.getAllByRole('button', { name: 'Help', hidden: true }).length).toBeGreaterThan(3);
  });
});

describe('rules and links', () => {
  it('holds: any of, with all-of groups and negation', () => {
    const values: Record<string, unknown> = { a: true, b: 'x' };
    const get = (f: string) => values[f];
    expect(holds([{ field: 'a', is: true }], get)).toBe(true);
    expect(holds([{ all: [{ field: 'a', is: true }, { field: 'b', is: 'y' }] }], get)).toBe(false);
    expect(holds([{ field: 'c', is: true, not: true }], get)).toBe(true);
  });

  it('pageOfLink keeps content pages only', () => {
    expect(pageOfLink('/content/wknd/us/en/faqs.html#top')).toBe('/content/wknd/us/en/faqs');
    expect(pageOfLink('https://example.com')).toBeNull();
  });
});
