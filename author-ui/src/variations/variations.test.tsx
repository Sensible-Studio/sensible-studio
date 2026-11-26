import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { placeOfUrl, urlOf } from '../shell/places';
import { GenerateVariations, inputProblems, EMPTY_INPUT } from './GenerateVariations';
import { kindOf, partsOf, snippet, variationText, xfFolderFor, VARIATIONS_URL, type Generation, type VariationsClient, type VariationsInput, type VariationsSetup } from './api';

class FakeVariations implements VariationsClient {
  calls: string[] = [];
  items: Generation[] = [];
  providers = [{ path: '/conf/global/settings/cloudconfigs/translation/global', title: 'Global', engine: 'OpenRouter (test/model)' }];
  fail: string | null = null;

  async setup(): Promise<VariationsSetup> {
    return {
      templates: [
        { id: 'hero', title: 'Hero', description: 'Banner', fields: [{ key: 'title', label: 'Title' }, { key: 'subtitle', label: 'Subtitle' }, { key: 'cta', label: 'Call to action' }] },
        { id: 'headline', title: 'Headline', description: 'A headline', fields: [{ key: 'headline', label: 'Headline' }] },
      ],
      tones: ['Friendly', 'Playful'],
      maxCount: 10,
      providers: this.providers,
    };
  }

  async history() {
    return this.items.map(({ variations: _v, ...h }) => h);
  }

  async generation(id: string) {
    const g = this.items.find((i) => i.id === id);
    if (!g) throw new Error('No such generation');
    return structuredClone(g);
  }

  async generate(input: VariationsInput) {
    this.calls.push(`generate ${JSON.stringify(input)}`);
    if (this.fail) throw new Error(this.fail);
    const g: Generation = {
      id: `1-${String(this.items.length).padStart(8, '0')}`,
      created: '2026-11-24T12:00:00Z',
      template: input.template,
      engine: 'OpenRouter (test/model)',
      input,
      favorites: [],
      count: 2,
      variations: [
        { title: 'Ride Costa Rica', subtitle: 'Two weeks of waves.', cta: 'Book now' },
        { title: 'Learn to surf', subtitle: 'All levels welcome.', cta: 'Join us' },
      ],
    };
    this.items.unshift(g);
    return structuredClone(g);
  }

  async favorite(id: string, index: number, on: boolean) {
    this.calls.push(`favorite ${id} ${index} ${on}`);
    const g = this.items.find((i) => i.id === id)!;
    g.favorites = on ? [...g.favorites, index] : g.favorites.filter((f) => f !== index);
    return structuredClone(g);
  }

  async remove(id: string) {
    this.calls.push(`remove ${id}`);
    this.items = this.items.filter((i) => i.id !== id);
  }

  async xfTemplates() {
    return [
      { path: '/conf/other/settings/wcm/templates/xf', title: 'Other XF' },
      { path: '/conf/wknd/settings/wcm/templates/experience-fragment-web-variation-template', title: 'Web Variation' },
    ];
  }

  async exportToXf(o: { parent: string; title: string; template: string; parts: string[] }) {
    this.calls.push(`exportToXf ${JSON.stringify(o)}`);
    const n = new Set(o.parts.map((p) => p.split(':')[0])).size;
    return { fragment: `${o.parent}/x`, variations: Array.from({ length: n }, (_, i) => `${o.parent}/x/${i ? `variation-${i + 1}` : 'master'}`) };
  }
}

const show = (client: VariationsClient) =>
  render(
    <Provider theme={defaultTheme}>
      <GenerateVariations client={client} />
    </Provider>,
  );

describe('generate variations', () => {
  afterEach(cleanup);

  it('has its own place', () => {
    expect(placeOfUrl(VARIATIONS_URL)).toEqual({ kind: 'variations' });
    expect(urlOf({ kind: 'variations' })).toBe('/aem/generate-variations.html');
  });

  it('checks the request before sending it', () => {
    expect(inputProblems({ ...EMPTY_INPUT, request: 'x' })).toEqual([]);
    expect(inputProblems(EMPTY_INPUT)).toEqual(['Say what to write about.']);
    expect(inputProblems({ ...EMPTY_INPUT, request: 'x', count: 11 })).toEqual(['Between 1 and 10 variations.']);
    expect(inputProblems({ ...EMPTY_INPUT, request: 'x', page: '/etc/x' })[0]).toMatch(/under \/content/);
  });

  it('copies a variation as labelled lines', () => {
    const fields = [{ key: 'title', label: 'Title' }, { key: 'cta', label: 'Call to action' }];
    expect(variationText({ title: 'Hi', cta: 'Go' }, fields)).toBe('Title: Hi\nCall to action: Go');
    expect(variationText({ headline: 'Hi' }, [{ key: 'headline', label: 'Headline' }])).toBe('Hi');
    expect(snippet('a  b\nc', 4)).toBe('a b…');
  });

  it('writes variations, stars one and keeps them in Recent and Favorites', async () => {
    const client = new FakeVariations();
    show(client);
    await screen.findByText('Written by OpenRouter (test/model), the model of the translation provider Global.');
    fireEvent.click(screen.getByRole('button', { name: 'Generate' }));
    expect(await screen.findByText('Say what to write about.')).toBeTruthy();
    expect(client.calls).toEqual([]);

    fireEvent.change(screen.getByRole('textbox', { name: /What is it about/ }), { target: { value: 'Our surf camp in Costa Rica' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate' }));
    expect(await screen.findByText('Ride Costa Rica')).toBeTruthy();
    expect(client.calls[0]).toContain('"request":"Our surf camp in Costa Rica"');
    expect(client.calls[0]).toContain('"provider":"/conf/global/settings/cloudconfigs/translation/global"');

    fireEvent.click(screen.getByRole('button', { name: 'Star variation 2' }));
    await waitFor(() => expect(client.calls).toContain('favorite 1-00000000 1 true'));
    expect(await screen.findByRole('button', { name: 'Unstar variation 2' })).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: 'Favorites' }));
    const favorites = await screen.findByRole('list');
    fireEvent.click(within(favorites).getByText('Our surf camp in Costa Rica'));
    expect(await screen.findByText('Learn to surf')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: 'Recent' }));
    const list = await screen.findByRole('list');
    fireEvent.click(within(list).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(client.calls).toContain('remove 1-00000000'));
    expect(await screen.findByText('Nothing generated yet')).toBeTruthy();
  });

  it('turns chosen variations into the parts of an experience fragment', () => {
    const fields = [{ key: 'title', label: 'Title' }, { key: 'subtitle', label: 'Subtitle' }, { key: 'cta', label: 'Call to action' }];
    const vs = [{ title: 'A', subtitle: 'a', cta: 'Go' }, { title: 'B', subtitle: '', cta: 'Join' }, { title: 'C', subtitle: 'c', cta: 'Book' }];
    expect(partsOf(vs, [2, 1], fields)).toEqual(['0:title:B', '0:button:Join', '1:title:C', '1:text:c', '1:button:Book']);
    expect([kindOf('headline'), kindOf('answer'), kindOf('cta')]).toEqual(['title', 'text', 'button']);
    expect(xfFolderFor('/content/wknd/us/en/adventures/x')).toBe('/content/experience-fragments/wknd');
    expect(xfFolderFor(undefined)).toBe('/content/experience-fragments');
  });

  it('exports the starred variations to an experience fragment', async () => {
    const client = new FakeVariations();
    show(client);
    await screen.findByText(/Written by/);
    fireEvent.change(screen.getByRole('textbox', { name: /What is it about/ }), { target: { value: 'Surf camp' } });
    fireEvent.change(screen.getByRole('textbox', { name: /Page to take facts from/ }), { target: { value: '/content/wknd/us/en/adventures/surf' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate' }));
    await screen.findByText('Ride Costa Rica');
    fireEvent.click(screen.getByRole('button', { name: 'Star variation 2' }));
    await screen.findByRole('button', { name: 'Unstar variation 2' });
    fireEvent.click(screen.getByRole('button', { name: 'Export to Experience Fragment' }));
    const dialog = await screen.findByRole('dialog');
    // the starred one is chosen; the folder is the site's; the site's template
    expect(within(dialog).getByRole('checkbox', { name: /2\. Learn to surf/ })).toHaveProperty('checked', true);
    expect(within(dialog).getByRole('checkbox', { name: /1\. Ride Costa Rica/ })).toHaveProperty('checked', false);
    expect(within(dialog).getByRole('textbox', { name: /Folder/ })).toHaveProperty('value', '/content/experience-fragments/wknd');
    expect(within(dialog).getByRole('textbox', { name: /Title/ })).toHaveProperty('value', 'Learn to surf');
    await waitFor(() => expect(within(dialog).getByRole('button', { name: /Web Variation/ })).toBeTruthy());
    fireEvent.click(within(dialog).getByRole('button', { name: 'Export' }));
    await waitFor(() => expect(client.calls.some((c) => c.startsWith('exportToXf'))).toBe(true));
    const call = JSON.parse(client.calls.find((c) => c.startsWith('exportToXf'))!.slice('exportToXf '.length));
    expect(call).toEqual({
      parent: '/content/experience-fragments/wknd',
      title: 'Learn to surf',
      template: '/conf/wknd/settings/wcm/templates/experience-fragment-web-variation-template',
      parts: ['0:title:Learn to surf', '0:text:All levels welcome.', '0:button:Join us'],
    });
  });

  it('says when there is no model, or the model fails', async () => {
    const client = new FakeVariations();
    client.providers = [];
    show(client);
    expect(await screen.findByText(/No model to write with/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Generate' })).toHaveProperty('disabled', true);
    cleanup();

    const failing = new FakeVariations();
    failing.fail = 'The model API wants payment (HTTP 402)';
    show(failing);
    await screen.findByText(/Written by/);
    fireEvent.change(screen.getByRole('textbox', { name: /What is it about/ }), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate' }));
    expect(await screen.findByText('The model API wants payment (HTTP 402)')).toBeTruthy();
  });
});
