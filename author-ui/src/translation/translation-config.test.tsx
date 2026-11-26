import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { apiKeyProblem, TranslationConsole } from './TranslationConsole';
import { MemoryTranslationConfigs } from './testing/MemoryTranslationConfigs';

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  window.HTMLElement.prototype.scrollIntoView ??= () => {};
  (globalThis as { CSS?: { escape?: (s: string) => string } }).CSS ??= {};
  globalThis.CSS.escape ??= (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`);
});
afterEach(cleanup);

describe('api key field', () => {
  it('takes only secret references', () => {
    expect(apiKeyProblem('')).toBeNull();
    expect(apiKeyProblem('$[secret:ANTHROPIC_API_KEY]')).toBeNull();
    expect(apiKeyProblem('$[env:MY_KEY]')).toBeNull();
    expect(apiKeyProblem('sk-ant-api03-abc')).toMatch(/not the key itself/);
    expect(apiKeyProblem('$[secret:bad name]')).toMatch(/\$\[secret:NAME\]/);
  });
});

describe('translation cloud services', () => {
  const open = (client: MemoryTranslationConfigs, path = '/conf/wknd-shared') =>
    render(
      <Provider theme={defaultTheme}>
        <TranslationConsole client={client} initialPath={path} />
      </Provider>,
    );

  const field = (dialog: HTMLElement, name: RegExp) => within(dialog).getByRole('textbox', { name });

  it('lists the configuration folders', async () => {
    const client = new MemoryTranslationConfigs();
    open(client, '/conf');
    const table = await screen.findByRole('grid', { name: 'Configuration folders' });
    expect(table.textContent).toContain('Global');
    expect(table.textContent).toContain('wknd-shared');
  });

  it('creates a configuration that refers to a secret; a pasted key never leaves the browser', async () => {
    const client = new MemoryTranslationConfigs();
    open(client);
    await screen.findByRole('grid', { name: /Translation configurations of wknd-shared/ });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create translation configuration' }));
    });
    const dialog = await screen.findByRole('form', { name: 'Translation configuration' });
    act(() => {
      fireEvent.change(field(dialog, /^Title/), { target: { value: 'Claude for WKND' } });
    });
    expect((field(dialog, /^(Secret reference|API key)/) as HTMLInputElement).value).toBe('$[secret:ANTHROPIC_API_KEY]');

    // a key pasted by mistake: explained, Save and Test disabled, nothing sent
    act(() => {
      fireEvent.change(field(dialog, /^(Secret reference|API key)/), { target: { value: 'sk-ant-api03-secret' } });
    });
    expect(within(dialog).getByText(/not the key itself/)).toBeTruthy();
    expect((within(dialog).getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    expect((within(dialog).getByRole('button', { name: /Test connection/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(client.saves).toHaveLength(0);
    expect(client.tests).toHaveLength(0);

    // the reference, with no secret on the instance yet: the test says so
    act(() => {
      fireEvent.change(field(dialog, /^(Secret reference|API key)/), { target: { value: '$[secret:CLAUDE_KEY]' } });
    });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: /Test connection/ }));
    });
    await within(dialog).findByText('No API key');
    expect(within(dialog).getByRole('link', { name: 'Add the secret' }).getAttribute('href')).toContain('SecretEntry');

    client.secrets.add('CLAUDE_KEY');
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: /Test connection/ }));
    });
    await within(dialog).findByText(/Connected/);
    expect(within(dialog).getByText(/Bonjour/)).toBeTruthy();

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(client.saves).toHaveLength(1));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close translation provider' }));
    expect(client.saves[0]).toMatchObject({
      title: 'Claude for WKND',
      connector: 'studio-ai',
      settings: { api: 'anthropic', model: 'claude-sonnet-5-5', apiKey: '$[secret:CLAUDE_KEY]' },
    });
    const table = await screen.findByRole('grid', { name: /Translation configurations of wknd-shared/ });
    await waitFor(() => expect(table.textContent).toContain('Claude for WKND'));
    expect(table.textContent).toContain('Claude · claude-sonnet-5-5');
    expect(table.textContent).toContain('Set (CLAUDE_KEY)');
  });

  it('shows a configuration whose secret is missing', async () => {
    const client = new MemoryTranslationConfigs();
    await client.save({ conf: '/conf/global' }, { title: 'Local Llama', connector: 'studio-ai', settings: { api: 'openai', endpoint: 'http://localhost:11434/v1/chat/completions', model: 'llama3.1' } });
    await client.save({ conf: '/conf/global' }, { title: 'Claude', connector: 'studio-ai', settings: { apiKey: '$[secret:NOT_SET]' } });
    open(client, '/conf/global');
    const table = await screen.findByRole('grid', { name: /Translation configurations of Global/ });
    await waitFor(() => expect(table.textContent).toContain('Missing: secret NOT_SET not set'));
    expect(table.textContent).toContain('Ollama · llama3.1 · localhost:11434');
    expect(table.textContent).toContain('Not needed');
  });

  it('switches to OpenRouter: its key variable, any provider/model id, the account default when empty', async () => {
    const client = new MemoryTranslationConfigs();
    await client.save({ conf: '/conf/global' }, { title: 'Via OpenRouter', connector: 'studio-ai', settings: { api: 'openrouter', model: 'openai/gpt-4o' } });
    open(client, '/conf/global');
    const table = await screen.findByRole('grid', { name: /Translation configurations of Global/ });
    await waitFor(() => expect(table.textContent).toContain('OpenRouter · openai/gpt-4o'));
    expect(table.textContent).toContain('Missing: set OPENROUTER_API_KEY or refer to a secret');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create translation configuration' }));
    });
    const dialog = await screen.findByRole('form', { name: 'Translation configuration' });
    act(() => {
      fireEvent.change(field(dialog, /^Title/), { target: { value: 'OpenRouter default' } });
    });
    const apiPicker = within(dialog).getAllByRole('button').find((b) => b.textContent?.includes('Anthropic (Claude)'))!;
    await act(async () => {
      fireEvent.click(apiPicker);
    });
    await act(async () => {
      fireEvent.click(await screen.findByRole('option', { name: /OpenRouter/ }));
    });
    expect((field(dialog, /^(Secret reference|API key)/) as HTMLInputElement).value).toBe('$[secret:OPENROUTER_API_KEY]');
    client.environment.add('OPENROUTER_API_KEY');
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(client.saves.at(-1)?.title).toBe('OpenRouter default'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close translation provider' }));
    expect(client.saves.at(-1)).toMatchObject({ settings: { api: 'openrouter', apiKey: '$[secret:OPENROUTER_API_KEY]' } });
    expect(client.saves.at(-1)?.settings.model).toBeUndefined();
    await waitFor(() => expect(screen.getByRole('grid', { name: /Translation configurations/ }).textContent).toContain('OpenRouter · default model'));
  });

  it('fills in a provider preset; extra headers carry credentials only as secret references', async () => {
    const client = new MemoryTranslationConfigs();
    open(client);
    await screen.findByRole('grid', { name: /Translation configurations of wknd-shared/ });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create translation configuration' }));
    });
    const dialog = await screen.findByRole('form', { name: 'Translation configuration' });
    act(() => {
      fireEvent.change(field(dialog, /^Title/), { target: { value: 'Azure' } });
    });
    const providerPicker = within(dialog).getAllByRole('button').find((b) => b.textContent?.includes('Anthropic (Claude)'))!;
    await act(async () => {
      fireEvent.click(providerPicker);
    });
    await act(async () => {
      fireEvent.click(await screen.findByRole('option', { name: 'Azure OpenAI' }));
    });
    expect((field(dialog, /^(Secret reference|API key)/) as HTMLInputElement).value).toBe('');
    expect((within(dialog).getByRole('textbox', { name: 'Header 1 name' }) as HTMLInputElement).value).toBe('api-key');
    expect((within(dialog).getByRole('textbox', { name: 'Header 1 value' }) as HTMLInputElement).value).toBe('$[secret:AZURE_OPENAI_API_KEY]');
    // the Azure endpoint is the customer's own: required
    expect(within(dialog).getByText(/Enter your endpoint/)).toBeTruthy();
    expect((within(dialog).getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    act(() => {
      fireEvent.change(field(dialog, /^Endpoint/), { target: { value: 'https://acme.openai.azure.com/openai/v1/chat/completions' } });
    });

    // a literal credential in a header: explained and blocked, nothing sent
    act(() => {
      fireEvent.change(within(dialog).getByRole('textbox', { name: 'Header 1 value' }), { target: { value: 'abc123literal' } });
    });
    expect(within(dialog).getByText(/api-key carries a credential/)).toBeTruthy();
    expect((within(dialog).getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    act(() => {
      fireEvent.change(within(dialog).getByRole('textbox', { name: 'Header 1 value' }), { target: { value: '$[secret:AZURE_OPENAI_API_KEY]' } });
    });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Add header' }));
    });
    act(() => {
      fireEvent.change(within(dialog).getByRole('textbox', { name: 'Header 2 name' }), { target: { value: 'X-Team' } });
      fireEvent.change(within(dialog).getByRole('textbox', { name: 'Header 2 value' }), { target: { value: 'content' } });
    });
    client.secrets.add('AZURE_OPENAI_API_KEY');
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(client.saves).toHaveLength(1));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close translation provider' }));
    expect(client.saves).toHaveLength(1);
    expect(client.saves[0].settings).toMatchObject({
      api: 'openai',
      preset: 'azure',
      endpoint: 'https://acme.openai.azure.com/openai/v1/chat/completions',
      model: 'gpt-4o-mini',
      headers: ['api-key: $[secret:AZURE_OPENAI_API_KEY]', 'X-Team: content'],
    });
    expect(client.saves[0].settings.apiKey).toBeUndefined();
    const table = screen.getByRole('grid', { name: /Translation configurations of wknd-shared/ });
    await waitFor(() => expect(table.textContent).toContain('Azure OpenAI · gpt-4o-mini'));
    expect(table.textContent).toContain('Set in a header (AZURE_OPENAI_API_KEY)');
  });

  it('a key entered in the console is sent once, stored encrypted and never shown', async () => {
    const client = new MemoryTranslationConfigs();
    open(client);
    await screen.findByRole('grid', { name: /Translation configurations of wknd-shared/ });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create translation configuration' }));
    });
    let dialog = await screen.findByRole('form', { name: 'Translation configuration' });
    act(() => {
      fireEvent.change(field(dialog, /^Title/), { target: { value: 'Typed key' } });
      fireEvent.click(within(dialog).getByRole('radio', { name: 'Enter the key' }));
    });
    const key = within(dialog).getByLabelText(/^Key/) as HTMLInputElement;
    expect(key.type).toBe('password');
    act(() => {
      fireEvent.change(key, { target: { value: 'sk test' } });
    });
    expect(within(dialog).getByText('A key is one word, without spaces.')).toBeTruthy();
    act(() => {
      fireEvent.change(key, { target: { value: 'sk-test-typed' } });
    });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(client.saves).toHaveLength(1));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close translation provider' }));
    expect(client.saves[0].settings.apiKeyValue).toBe('sk-test-typed');
    expect(client.saves[0].settings.apiKey).toBeUndefined();
    const table = await screen.findByRole('grid', { name: /Translation configurations of wknd-shared/ });
    await waitFor(() => expect(table.textContent).toContain('Set (stored encrypted)'));
    expect(table.textContent).not.toContain('sk-test-typed');

    // opened again: the key is not shown; saving without typing keeps the stored one
    // the saved configuration is selected
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Properties' }));
    });
    dialog = await screen.findByRole('form', { name: 'Translation configuration' });
    expect((within(dialog).getByRole('radio', { name: 'Enter the key' }) as HTMLInputElement).checked).toBe(true);
    expect((within(dialog).getByLabelText(/^New key/) as HTMLInputElement).value).toBe('');
    expect(dialog.textContent).not.toContain('sk-test-typed');
  });
});
