import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { pressAction } from '../testing/selection';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { confOf, templatesRoute, type TemplateConf, type TemplatesClient, type TemplateStatus, type TemplateSummary, type TemplatePolicy, type TemplateType, type TemplateUsage } from './api';
import type { ReplicationClient, ReplicationStatusInfo } from '../replication/api';
import { CreateTemplateWizard } from './CreateTemplateWizard';
import { TemplateProperties } from './TemplateProperties';
import { TemplatesConsole } from './TemplatesConsole';

afterEach(cleanup);

const T = '/conf/wknd/settings/wcm/templates';

class MemoryTemplatesClient implements TemplatesClient {
  calls: string[] = [];
  confList: TemplateConf[] = [
    { path: '/conf/global', name: 'global', title: 'Global', templates: 0 },
    { path: '/conf/wknd', name: 'wknd', title: 'WKND Site', templates: 2 },
  ];
  list: TemplateSummary[] = [
    { path: `${T}/landing`, name: 'landing', title: 'Landing Page Template', status: 'enabled', templateTypeTitle: 'Empty Page', lastModified: '2026-10-01T10:00:00Z' },
    { path: `${T}/draft`, name: 'draft', title: 'Draft Template', status: 'draft', templateTypeTitle: 'Content Page' },
  ];
  typeList: TemplateType[] = [
    { path: '/conf/wknd/settings/wcm/template-types/content-page', name: 'content-page', title: 'WKND Site Content Page', ranking: 1, source: '/conf/wknd' },
    { path: '/libs/settings/wcm/template-types/html5page', name: 'html5page', title: 'HTML5 Page', ranking: 100, source: '/libs' },
  ];
  used: Record<string, number> = { [`${T}/landing`]: 3 };

  async confs() {
    return this.confList;
  }
  async templates(conf: string) {
    return this.list.filter((t) => t.path.startsWith(`${conf}/`));
  }
  async types() {
    return this.typeList;
  }
  async policies(template: string): Promise<TemplatePolicy[]> {
    return template.endsWith('/draft')
      ? [
          { path: '/conf/wknd/settings/wcm/policies/wknd/components/page/policy_page', title: 'Page configuration', component: 'wknd/components/page' },
          { path: '/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main', title: 'Main', component: 'wknd/components/container' },
        ]
      : [];
  }
  async usage(template: string): Promise<TemplateUsage> {
    const n = this.used[template] ?? 0;
    return { count: n, pages: Array.from({ length: n }, (_, i) => `/content/wknd/p${i}`), complete: true };
  }
  async create(conf: string, type: string, title: string, description?: string) {
    this.calls.push(`create ${conf} ${type} ${title} ${description ?? ''}`.trim());
    const t: TemplateSummary = { path: `${conf}/settings/wcm/templates/new`, name: 'new', title, status: 'draft' };
    this.list.push(t);
    return t;
  }
  async copy(template: string) {
    this.calls.push(`copy ${template}`);
    const src = this.list.find((t) => t.path === template)!;
    const t = { ...src, path: `${template}-copy`, name: `${src.name}-copy`, title: `${src.title} (Copy)`, status: 'draft' };
    this.list.push(t);
    return t;
  }
  async remove(template: string) {
    if (this.used[template]) throw new Error('in use');
    this.calls.push(`delete ${template}`);
    this.list = this.list.filter((t) => t.path !== template);
  }
  async setStatus(template: string, status: TemplateStatus) {
    this.calls.push(`status ${template} ${status}`);
    this.list = this.list.map((t) => (t.path === template ? { ...t, status } : t));
  }
  async saveProperties(template: string, title: string, description: string) {
    this.calls.push(`properties ${template} ${title} ${description}`);
    this.list = this.list.map((t) => (t.path === template ? { ...t, title, description } : t));
    return this.list.find((t) => t.path === template)!;
  }
  async uploadThumbnail(template: string, file: File) {
    this.calls.push(`thumbnail ${template} ${file.name}`);
    this.list = this.list.map((t) => (t.path === template ? { ...t, thumbnail: `${template}/thumbnail.png?ck=1` } : t));
    return this.list.find((t) => t.path === template)!;
  }
  async removeThumbnail(template: string) {
    this.calls.push(`thumbnail ${template} removed`);
    return this.list.find((t) => t.path === template)!;
  }
  async template(path: string) {
    const t = this.list.find((x) => x.path === path);
    if (!t) throw new Error('none');
    return t;
  }
  async currentUser() {
    return 'admin';
  }
}

// the console opens in the Column view like Sites; these tests read the list
const mount = (ui: ReactElement) => {
  window.localStorage.setItem('studio.templates.view', 'list');
  return render(
    <Provider theme={defaultTheme} colorScheme="light">
      {ui}
    </Provider>,
  );
};

/** An action of the selection, a button or under More (wherever the bar's width puts it). */
async function moreAction(name: RegExp) {
  await pressAction(name);
}

describe('Templates routes', () => {
  it('reads AEM URLs', () => {
    expect(templatesRoute({ pathname: '/libs/wcm/core/content/sites/templates.html/conf', search: '' })).toEqual({ screen: 'console', conf: null });
    expect(templatesRoute({ pathname: '/libs/wcm/core/content/sites/templates.html/conf/wknd', search: '' })).toEqual({ screen: 'console', conf: '/conf/wknd' });
    expect(templatesRoute({ pathname: '/libs/wcm/core/content/sites/createtemplatewizard.html/conf/wknd', search: '' })).toEqual({ screen: 'wizard', conf: '/conf/wknd' });
    expect(templatesRoute({ pathname: '/mnt/overlay/wcm/core/content/sites/templates/properties.html', search: `?item=${encodeURIComponent(`${T}/landing`)}` })).toEqual({
      screen: 'properties',
      template: `${T}/landing`,
    });
    expect(templatesRoute({ pathname: '/mnt/overlay/wcm/core/content/sites/templates/properties.html', search: '?item=/content/wknd' })).toBeNull();
    expect(templatesRoute({ pathname: '/sites.html/content', search: '' })).toBeNull();
    expect(confOf(`${T}/landing`)).toBe('/conf/wknd');
  });
});

describe('Templates console', () => {
  it('lists the configurations, then the templates of one with their status', async () => {
    const client = new MemoryTemplatesClient();
    mount(<TemplatesConsole client={client} initialConf={null} go={() => {}} />);
    fireEvent.click(await screen.findByText('WKND Site'));
    expect(await screen.findByText('Landing Page Template')).toBeTruthy();
    expect(screen.getByText('Enabled')).toBeTruthy();
    expect(screen.getByText('Draft')).toBeTruthy();
    expect(window.location.pathname).toBe('/libs/wcm/core/content/sites/templates.html/conf/wknd');
  });

  it('opens a template in the editor and Create in the wizard', async () => {
    const go = vi.fn();
    mount(<TemplatesConsole client={new MemoryTemplatesClient()} initialConf="/conf/wknd" go={go} />);
    fireEvent.click(await screen.findByText('Draft Template'));
    expect(go).toHaveBeenCalledWith(`/editor.html${T}/draft/structure.html`);
    fireEvent.click(screen.getByRole('button', { name: 'Create template' }));
    expect(go).toHaveBeenCalledWith('/libs/wcm/core/content/sites/createtemplatewizard.html/conf/wknd');
  });

  it('enables a draft and copies it', async () => {
    const client = new MemoryTemplatesClient();
    const go = vi.fn();
    mount(<TemplatesConsole client={client} initialConf="/conf/wknd" go={go} />);
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Draft Template' }));
    expect(screen.queryByRole('button', { name: 'Disable' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /^Properties/ }));
    expect(go).toHaveBeenCalledWith(`/mnt/overlay/wcm/core/content/sites/templates/properties.html?item=${encodeURIComponent(`${T}/draft`)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Enable' }));
    const dialog = (await screen.findByText(/Enable “Draft Template”/)).closest('[role="alertdialog"]') as HTMLElement;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Enable' }));
    await waitFor(() => expect(client.calls).toContain(`status ${T}/draft enabled`));
    await waitFor(() => expect(screen.getAllByText('Enabled')).toHaveLength(2));
    fireEvent.click(screen.getByRole('button', { name: /^Copy/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear selection' }));
    fireEvent.click(await screen.findByRole('button', { name: /^Paste/ }));
    expect(await screen.findByText('Draft Template (Copy)')).toBeTruthy();
    expect(client.calls).toContain(`copy ${T}/draft`);
  });

  it('does not delete a template pages use, deletes one they do not', async () => {
    const client = new MemoryTemplatesClient();
    mount(<TemplatesConsole client={client} initialConf="/conf/wknd" go={() => {}} />);
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Landing Page Template' }));
    await moreAction(/^Delete/);
    let dialog = (await screen.findByText(/used by 3 pages/)).closest('[role="alertdialog"]') as HTMLElement;
    expect((within(dialog).getByRole('button', { name: 'Delete' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByText(/used by 3 pages/)).toBeNull());

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Landing Page Template' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Draft Template' }));
    await moreAction(/^Delete/);
    dialog = (await screen.findByText(/No page uses it/)).closest('[role="alertdialog"]') as HTMLElement;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(client.calls).toContain(`delete ${T}/draft`));
    await waitFor(() => expect(screen.queryByText('Draft Template')).toBeNull());
  });
});

describe('Create Template', () => {
  it('creates a draft from a template type and offers to open it', async () => {
    const client = new MemoryTemplatesClient();
    const go = vi.fn();
    mount(<CreateTemplateWizard client={client} conf="/conf/wknd" go={go} />);
    const next = screen.getByRole('button', { name: 'Next' }) as HTMLButtonElement;
    expect(next.disabled).toBe(true);
    fireEvent.click(await screen.findByText('WKND Site Content Page'));
    expect(screen.getByText('Product')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.change(screen.getByRole('textbox', { name: /Template Title/ }), { target: { value: 'Event Page' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Description' }), { target: { value: 'For events' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(client.calls).toEqual(['create /conf/wknd /conf/wknd/settings/wcm/template-types/content-page Event Page For events']);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Open' }));
    expect(go).toHaveBeenCalledWith(`/editor.html${T}/new/structure.html`);
  });
});

describe('Template properties', () => {
  it('saves the title, description and a new thumbnail', async () => {
    const client = new MemoryTemplatesClient();
    const go = vi.fn();
    const createObjectURL = vi.fn(() => 'blob:preview');
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    mount(<TemplateProperties client={client} template={`${T}/draft`} go={go} />);
    const title = (await screen.findByRole('textbox', { name: /Template Title/ })) as HTMLInputElement;
    expect(title.value).toBe('Draft Template');
    fireEvent.change(title, { target: { value: 'Event' } });
    const file = new File(['png'], 'thumb.png', { type: 'image/png' });
    await act(async () => {
      fireEvent.change(screen.getByTestId('thumbnail-file'), { target: { files: [file] } });
    });
    expect((screen.getByAltText('Thumbnail of Draft Template') as HTMLImageElement).src).toBe('blob:preview');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(client.calls).toHaveLength(2));
    expect(go).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Close template properties' }));
    expect(go).toHaveBeenCalledWith('/libs/wcm/core/content/sites/templates.html/conf/wknd');
    expect(client.calls).toEqual([`properties ${T}/draft Event `, `thumbnail ${T}/draft thumb.png`]);
  });

  it('refuses an empty title and a file that is not an image', async () => {
    const client = new MemoryTemplatesClient();
    mount(<TemplateProperties client={client} template={`${T}/draft`} go={() => {}} />);
    fireEvent.change(await screen.findByRole('textbox', { name: /Template Title/ }), { target: { value: ' ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('The template needs a title')).toBeTruthy();
    await act(async () => {
      fireEvent.change(screen.getByTestId('thumbnail-file'), { target: { files: [new File(['x'], 'a.txt', { type: 'text/plain' })] } });
    });
    expect(screen.getByText(/must be an image/)).toBeTruthy();
    expect(client.calls).toEqual([]);
  });
});

class MemoryReplication implements Pick<ReplicationClient, 'status' | 'activate' | 'deactivate'> {
  calls: string[] = [];
  state: Record<string, ReplicationStatusInfo> = {
    [`${T}/landing`]: { path: `${T}/landing`, published: true, activated: true, pending: false, lastReplicated: '2026-10-18T09:00:00Z', lastReplicationAction: 'Activate' },
    '/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main': {
      path: '/conf/wknd/settings/wcm/policies/wknd/components/container/policy_main',
      published: true,
      activated: true,
      pending: false,
      lastReplicationAction: 'Activate',
    },
  };
  async status(paths: string[]) {
    return paths.map((p) => this.state[p] ?? { path: p, published: false, pending: false });
  }
  async activate(paths: string[], options: { deep?: boolean } = {}) {
    this.calls.push(`activate ${paths.join(',')}${options.deep ? ' deep' : ''}`);
    return [];
  }
  async deactivate(paths: string[], options: { deep?: boolean } = {}) {
    this.calls.push(`deactivate ${paths.join(',')}${options.deep ? ' deep' : ''}`);
    return [];
  }
}

describe('Publishing templates', () => {
  it('shows the Published column and publishes a template with the configurations publish lacks', async () => {
    const client = new MemoryTemplatesClient();
    const replication = new MemoryReplication();
    mount(<TemplatesConsole client={client} replication={replication as unknown as ReplicationClient} initialConf="/conf/wknd" go={() => {}} />);
    expect(await screen.findByText(/^Published /)).toBeTruthy();
    expect(screen.getByText('Not published')).toBeTruthy();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Draft Template' }));
    expect(screen.queryByRole('button', { name: /^Unpublish/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /^Publish/ }));
    expect(await screen.findByText('Page configuration')).toBeTruthy();
    expect(screen.queryByText('Main')).toBeNull();
    const dialog = screen.getByText('Page configuration').closest('[role="dialog"]') as HTMLElement;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    await waitFor(() =>
      expect(replication.calls).toEqual(['activate /conf/wknd/settings/wcm/policies/wknd/components/page/policy_page', `activate ${T}/draft deep`]),
    );
  });

  it('unpublishes a published template with its parts', async () => {
    const replication = new MemoryReplication();
    mount(<TemplatesConsole client={new MemoryTemplatesClient()} replication={replication as unknown as ReplicationClient} initialConf="/conf/wknd" go={() => {}} />);
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Landing Page Template' }));
    await screen.findByRole('toolbar', { name: 'Selection actions' });
    await moreAction(/^Unpublish/);
    const dialog = (await screen.findByText(/Unpublish “Landing Page Template”/)).closest('[role="alertdialog"]') as HTMLElement;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Unpublish' }));
    await waitFor(() => expect(replication.calls).toEqual([`deactivate ${T}/landing deep`]));
  });
});

describe('Template thumbnail', () => {
  it('generates a preview from the structure', async () => {
    const client = new MemoryTemplatesClient();
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:generated'), revokeObjectURL: vi.fn() });
    const preview = vi.fn(async () => new File(['png'], 'thumbnail.png', { type: 'image/png' }));
    const go = vi.fn();
    mount(<TemplateProperties client={client} template={`${T}/draft`} preview={preview} go={go} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Generate Preview' }));
    expect(await screen.findByAltText('Thumbnail of Draft Template')).toBeTruthy();
    expect(preview).toHaveBeenCalledWith(`${T}/draft/structure.html?wcmmode=disabled`);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(client.calls).toContain(`thumbnail ${T}/draft thumbnail.png`));
  });
});

describe('Properties of several templates', () => {
  it('saves only the templates that change, with their own titles and the shared description', async () => {
    const { templateChanges, BulkTemplatePropertiesDialog } = await import('./BulkTemplateProperties');
    const list: TemplateSummary[] = [
      { path: `${T}/a`, name: 'a', title: 'A', description: 'old', status: 'enabled' },
      { path: `${T}/b`, name: 'b', title: 'B', status: 'draft' },
    ];
    expect(templateChanges(list, {}, null)).toEqual([]);
    expect(templateChanges(list, { [`${T}/b`]: 'B2' }, null)).toEqual([{ path: `${T}/b`, title: 'B2', description: '' }]);
    expect(templateChanges(list, {}, 'new').map((c) => c.description)).toEqual(['new', 'new']);

    const saveProperties = vi.fn(async (path: string, title: string, description: string) => ({ ...list[0], path, title, description }));
    const done = vi.fn();
    render(
      <Provider theme={defaultTheme}>
        <BulkTemplatePropertiesDialog client={{ saveProperties }} templates={list} onDone={done} close={() => undefined} />
      </Provider>,
    );
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Title of a'), { target: { value: 'Alpha' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(done).toHaveBeenCalledWith(1));
    expect(saveProperties).toHaveBeenCalledTimes(1);
    expect(saveProperties).toHaveBeenCalledWith(`${T}/a`, 'Alpha', 'old');
  });
});
