import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { PageLanguagesDialog } from './PageLanguagesDialog';
import {
  copyState,
  PageTranslationError,
  type PageLanguages,
  type PageTranslateRequest,
  type PageTranslateResult,
  type PageTranslationClient,
  type TranslationProvider,
} from './pages';

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

const EN = '/content/wknd/language-masters/en';
const PAGE = `${EN}/magazine`;
const CF = '/content/dam/wknd-shared/en/magazine/story';

class MemoryPages implements PageTranslationClient {
  async approveAll(paths: string[]) {
    return { approved: paths, skipped: [], failed: [] };
  }
  calls: { op: string; arg: unknown }[] = [];
  failWith: PageTranslationError | null = null;
  info: PageLanguages = {
    path: PAGE,
    title: 'Magazine',
    liveCopy: false,
    languageRoot: EN,
    language: 'en',
    copies: [
      { language: 'de', name: 'German', root: '/content/wknd/language-masters/de', path: '/content/wknd/language-masters/de/magazine', exists: true, liveCopy: false },
      {
        language: 'fr',
        name: 'French',
        root: '/content/wknd/language-masters/fr',
        path: '/content/wknd/language-masters/fr/magazine',
        exists: true,
        liveCopy: false,
        translation: { sourcePath: PAGE, sourceLanguage: 'en', targetLanguage: 'fr', status: 'TRANSLATED', failed: [], outdated: false },
      },
      {
        language: 'it',
        name: 'Italian',
        root: '/content/wknd/language-masters/it',
        path: '/content/wknd/language-masters/it/magazine',
        exists: true,
        liveCopy: false,
        translation: { sourcePath: PAGE, sourceLanguage: 'en', targetLanguage: 'it', status: 'APPROVED', failed: [], outdated: true },
      },
      { language: 'es', name: 'Spanish', root: '/content/wknd/language-masters/es', path: '/content/wknd/language-masters/es/magazine', exists: false, liveCopy: true },
    ],
  };

  async languages(path: string) {
    this.calls.push({ op: 'languages', arg: path });
    return structuredClone(this.info);
  }
  async pages(path: string, deep: boolean) {
    this.calls.push({ op: 'pages', arg: { path, deep } });
    return deep ? [path, `${path}/surf`] : [path];
  }
  async translate(request: PageTranslateRequest): Promise<PageTranslateResult[]> {
    this.calls.push({ op: 'translate', arg: structuredClone(request) });
    if (this.failWith) throw this.failWith;
    const refs: PageTranslateResult[] =
      request.includeReferences && !request.skipReferences?.includes(CF)
        ? [{ source: CF, path: CF.replace('/en/', '/fr/'), language: 'fr', status: 'TRANSLATED', reference: true, created: true, translated: 3, failed: [] }]
        : [];
    return refs.concat(request.languages.map((l) => ({
      source: request.path,
      path: request.path.replace('/en', `/${l}`),
      language: l,
      status: 'TRANSLATED' as const,
      created: l === 'pt_br',
      translated: 5,
      unchanged: 0,
      kept: 0,
      failed: [],
    })));
  }
  async approve(path: string) {
    this.calls.push({ op: 'approve', arg: path });
    return structuredClone(this.info);
  }
  queue = async () => [];
  review = async (): Promise<never> => {
    throw new Error('not used');
  };
  saveReview = this.review;
  retranslate = this.review;
  async providers(): Promise<TranslationProvider[]> {
    return [
      { name: '/conf/wknd/settings/cloudconfigs/translation/claude', label: 'Claude for WKND · Claude (claude-sonnet-5-5)', available: true, default: true, cloudConfig: true },
      { name: 'studio-pseudo', label: 'Pseudo translation (testing)', available: true, default: false },
    ];
  }
}

const open = (client: MemoryPages) =>
  render(
    <Provider theme={defaultTheme}>
      <PageLanguagesDialog client={client} path={PAGE} onClose={() => {}} />
    </Provider>,
  );

describe('page language versions', () => {
  it('describes the state of every copy', () => {
    const [de, fr, it_, es] = new MemoryPages().info.copies!;
    expect(copyState(de).text).toBe('Not translated by Sensible Studio');
    expect(copyState(fr).text).toBe('Translated, to review');
    expect(copyState(it_).text).toBe('Outdated: the source changed');
    expect(copyState(es).text).toMatch(/Linked copy/);
  });

  it('lists the copies and approves one', async () => {
    const client = new MemoryPages();
    open(client);
    const list = await screen.findByRole('list', { name: 'Language versions' });
    expect(list.textContent).toContain('German (de)');
    expect(list.textContent).toContain('Translated, to review');
    expect(list.textContent).toContain('Outdated: the source changed');
    // a live copy cannot be chosen as a target
    expect(screen.queryByRole('checkbox', { name: 'Spanish (es)' })).toBeNull();
    await act(async () => {
      fireEvent.click(within(list).getByRole('button', { name: 'Approve' }));
    });
    expect(client.calls).toContainEqual({ op: 'approve', arg: '/content/wknd/language-masters/fr/magazine' });
  });

  it('translates the page and the pages below into chosen and new languages', async () => {
    const client = new MemoryPages();
    open(client);
    await screen.findByRole('list', { name: 'Language versions' });
    await act(async () => {
      fireEvent.click(screen.getByRole('checkbox', { name: 'French (fr)' }));
      fireEvent.change(screen.getByRole('textbox', { name: /Another language/ }), { target: { value: 'pt-br' } });
      fireEvent.click(screen.getByRole('checkbox', { name: 'Include the pages below' }));
    });
    expect(screen.getByText(/Brazilian Portuguese: a new language root is created/)).toBeTruthy();
    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Translate' }));
    });
    const results = await screen.findByRole('list', { name: 'Results' });
    await waitFor(() => expect(results.querySelectorAll('li')).toHaveLength(5));
    const translates = client.calls.filter((c) => c.op === 'translate').map((c) => c.arg);
    expect(client.calls).toContainEqual({ op: 'pages', arg: { path: PAGE, deep: true } });
    const common = { languages: ['fr', 'pt_br'], provider: '/conf/wknd/settings/cloudconfigs/translation/claude', force: false, batch: [PAGE, `${PAGE}/surf`], includeReferences: true };
    expect(translates).toEqual([
      { path: PAGE, ...common, skipReferences: [] },
      // the fragment the first page translated is not translated again
      { path: `${PAGE}/surf`, ...common, skipReferences: [CF] },
    ]);
    expect(results.textContent).toContain('used by the page · created · 3 translated');
    expect(results.textContent).toContain('created · 5 translated');
  });

  it('explains a connector without credentials', async () => {
    const client = new MemoryPages();
    client.failWith = new PageTranslationError('No API key for the cloud configuration "Claude for WKND"', 'MISSING_CREDENTIALS');
    open(client);
    await screen.findByRole('list', { name: 'Language versions' });
    await act(async () => {
      fireEvent.click(screen.getByRole('checkbox', { name: 'German (de)' }));
    });
    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Translate' }));
    });
    await screen.findByText('The connector is not set up');
    expect(screen.getByRole('link', { name: /Cloud Services → Translation/ }).getAttribute('href')).toBe('/cloudservices/translation.html/conf');
  });

  it('translates several selected pages together', async () => {
    const client = new MemoryPages();
    render(
      <Provider theme={defaultTheme}>
        <PageLanguagesDialog client={client} path={PAGE} more={[`${EN}/adventures`]} onClose={() => {}} />
      </Provider>,
    );
    expect(await screen.findByText('Language versions: 2 pages')).toBeTruthy();
    // the state of one page's copies is not shown for a selection
    expect(screen.queryByRole('list', { name: 'Language versions' })).toBeNull();
    fireEvent.click(await screen.findByRole('checkbox', { name: 'German (de)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Translate' }));
    await waitFor(() => expect(client.calls.filter((c) => c.op === 'translate').length).toBe(2));
    const sent = client.calls.filter((c) => c.op === 'translate').map((c) => c.arg as PageTranslateRequest);
    expect(sent.map((r) => r.path)).toEqual([PAGE, `${EN}/adventures`]);
    expect(sent[0].batch).toEqual([PAGE, `${EN}/adventures`]);
    expect(sent[0].languages).toEqual(['de']);
  });
});
