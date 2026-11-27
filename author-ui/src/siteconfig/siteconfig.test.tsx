import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { encodeValues, type ConfigDetail, type ConfigList, type ConfigProperty, type SiteConfigClient } from './api';
import { ownValue, SiteConfigConsole, valuesOf } from './SiteConfigConsole';

const prop = (over: Partial<ConfigProperty>): ConfigProperty => ({ name: 'p', label: 'P', type: 'string', multiple: false, nested: false, default: null, value: null, effectiveValue: null, isDefault: false, inherited: false, overridden: false, ...over });

const LIST: ConfigList = {
  path: '/content/wknd/en',
  contextPath: '/content/wknd',
  contextPaths: ['/content/wknd'],
  configs: [
    { name: 'com.acme.Seo', label: 'SEO', collection: false, defined: true, items: 0, set: true, inherited: false, resourcePath: '/conf/wknd/sling:configs/com.acme.Seo' },
    { name: 'com.acme.Social', label: 'Social links', collection: true, defined: true, items: 0, set: false, inherited: false },
  ],
};
const DETAIL: ConfigDetail = {
  name: 'com.acme.Seo',
  label: 'SEO',
  path: '/content/wknd/en',
  contextPath: '/content/wknd',
  collection: false,
  resourcePath: '/conf/wknd/sling:configs/com.acme.Seo',
  inherited: false,
  properties: [
    prop({ name: 'siteName', label: 'Site name', value: 'WKND', effectiveValue: 'WKND' }),
    prop({ name: 'titleSuffix', label: 'Title suffix', value: ' | Parent', effectiveValue: ' | Parent', inherited: true }),
    prop({ name: 'maxLinks', label: 'Max links', type: 'integer', default: 10, value: 10, effectiveValue: 10, isDefault: true }),
  ],
};

function client(over: Partial<SiteConfigClient> = {}): SiteConfigClient {
  return {
    list: vi.fn(() => Promise.resolve(LIST)),
    get: vi.fn(() => Promise.resolve(DETAIL)),
    save: vi.fn(() => Promise.resolve(DETAIL)),
    saveItems: vi.fn(() => Promise.resolve(DETAIL)),
    remove: vi.fn(() => Promise.resolve(DETAIL)),
    ...over,
  };
}

afterEach(cleanup);

describe('site configurations', () => {
  it('takes as set here only what is neither inherited nor the default', () => {
    expect(ownValue(DETAIL.properties[0])).toBe('WKND');
    expect(ownValue(DETAIL.properties[1])).toBeNull();
    expect(ownValue(DETAIL.properties[2])).toBeNull();
    expect(valuesOf(DETAIL.properties)).toEqual({ siteName: 'WKND', titleSuffix: null, maxLinks: null });
  });

  it('posts one parameter per value and nothing for what is not set', () => {
    const body = new URLSearchParams();
    encodeValues(body, 'p.', { a: 'x', b: null, c: ['1', '', '2'], d: false, e: 0 });
    expect(body.toString()).toBe('p.a=x&p.c=1&p.c=2&p.d=false&p.e=0');
  });

  it('names the site, lists the configurations and saves what was typed', async () => {
    const c = client();
    render(
      <Provider theme={defaultTheme}>
        <SiteConfigConsole client={c} path="/content/wknd/en" onPath={() => undefined} />
      </Provider>,
    );
    expect(await screen.findByText('/content/wknd')).toBeTruthy();
    expect(screen.getByText('set here')).toBeTruthy();
    const field = (await screen.findByLabelText('Site name')) as HTMLInputElement;
    expect(field.value).toBe('WKND');
    // inherited: the field is empty and says what applies
    expect((screen.getByLabelText('Title suffix') as HTMLInputElement).value).toBe('');
    expect(screen.getByText(/Inherited: \| Parent/)).toBeTruthy();
    fireEvent.change(field, { target: { value: 'WKND Adventures' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(c.save).toHaveBeenCalledWith('/content/wknd/en', 'com.acme.Seo', { siteName: 'WKND Adventures', titleSuffix: null, maxLinks: null }));
  });

  it('cannot save where no site configuration applies', async () => {
    const c = client({ list: vi.fn(() => Promise.resolve({ ...LIST, contextPath: null, contextPaths: [] })) });
    render(
      <Provider theme={defaultTheme}>
        <SiteConfigConsole client={c} path="/content" onPath={() => undefined} />
      </Provider>,
    );
    expect(await screen.findByText(/No site configuration applies here/)).toBeTruthy();
    const field = (await screen.findByLabelText('Site name')) as HTMLInputElement;
    fireEvent.change(field, { target: { value: 'x' } });
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('says whether a configuration is on the live site and publishes it', async () => {
    const off = { path: DETAIL.resourcePath!, published: false, pending: false };
    const on = { ...off, published: true, activated: true };
    const replication = { status: vi.fn(() => Promise.resolve([off])), activate: vi.fn(() => Promise.resolve([on])), deactivate: vi.fn(() => Promise.resolve([off])) };
    render(
      <Provider theme={defaultTheme}>
        <SiteConfigConsole client={client()} path="/content/wknd/en" onPath={() => undefined} replication={replication} />
      </Provider>,
    );
    expect(await screen.findByText(/Not published from this author/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Unpublish' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    await waitFor(() => expect(replication.activate).toHaveBeenCalledWith(['/conf/wknd/sling:configs/com.acme.Seo']));
    expect(await screen.findByText(/On the live site as it is here/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Publish' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('says a configuration is being taken off the live site, and asks again until it is', async () => {
    const on = { path: DETAIL.resourcePath!, published: true, activated: true, pending: false };
    const leaving = { ...on, published: false, activated: false, pending: true };
    const off = { ...leaving, pending: false };
    const status = vi.fn().mockResolvedValueOnce([on]).mockResolvedValue([off]);
    const replication = { status, activate: vi.fn(), deactivate: vi.fn(() => Promise.resolve([leaving])) };
    render(
      <Provider theme={defaultTheme}>
        <SiteConfigConsole client={client()} path="/content/wknd/en" onPath={() => undefined} replication={replication} />
      </Provider>,
    );
    expect(await screen.findByText(/On the live site as it is here/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Unpublish' }));
    expect(await screen.findByText(/Being taken off the live site/)).toBeTruthy();
    expect(await screen.findByText(/Not published from this author/, undefined, { timeout: 4000 })).toBeTruthy();
    expect(status).toHaveBeenCalledTimes(2);
  });

  it('offers no publishing for what is inherited', async () => {
    const replication = { status: vi.fn(() => Promise.resolve([])), activate: vi.fn(), deactivate: vi.fn() };
    render(
      <Provider theme={defaultTheme}>
        <SiteConfigConsole client={client({ get: vi.fn(() => Promise.resolve({ ...DETAIL, inherited: true })) })} path="/content/wknd/en" onPath={() => undefined} replication={replication} />
      </Provider>,
    );
    await screen.findByLabelText('Site name');
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull();
    expect(replication.status).not.toHaveBeenCalled();
  });
});
