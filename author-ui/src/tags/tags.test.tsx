import { defaultTheme, Provider } from '@adobe/react-spectrum';
import { pressAction } from '../testing/selection';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isValidName, languageName, nameFromTitle, TAG_ROOT, tagsPath, type TagDetail, type TagItem, type TagListing, type TagReference, type TagsClient } from './api';
import { TagsConsole } from './TagsConsole';

interface Node {
  title: string;
  description?: string;
  localized: Record<string, string>;
}

/** The tag tree in memory, with the server's rules for IDs. */
class MemoryTags implements TagsClient {
  nodes = new Map<string, Node>();
  calls: string[] = [];

  constructor() {
    for (const [p, t] of [
      ['wknd', 'WKND Shared'],
      ['wknd/activity', 'Activity'],
      ['wknd/activity/hiking', 'Hiking'],
      ['wknd/activity/surfing', 'Surfing'],
      ['wknd/season', 'Season'],
    ]) {
      this.nodes.set(`${TAG_ROOT}/${p}`, { title: t, localized: {} });
    }
  }

  private item(path: string): TagItem {
    const n = this.nodes.get(path)!;
    const rel = path.slice(TAG_ROOT.length + 1);
    const i = rel.indexOf('/');
    return {
      path,
      name: path.slice(path.lastIndexOf('/') + 1),
      tagID: i < 0 ? `${rel}:` : `${rel.slice(0, i)}:${rel.slice(i + 1)}`,
      title: n.title,
      description: n.description,
      namespace: i < 0,
      hasChildren: this.kids(path).length > 0,
    };
  }

  private kids(path: string) {
    return [...this.nodes.keys()].filter((p) => p.startsWith(`${path}/`) && !p.slice(path.length + 1).includes('/'));
  }

  async children(path: string): Promise<TagListing> {
    const trail = [{ path: TAG_ROOT, title: 'Tags' }];
    let p = TAG_ROOT;
    for (const seg of path.slice(TAG_ROOT.length).split('/').filter(Boolean)) {
      p = `${p}/${seg}`;
      trail.push({ path: p, title: this.nodes.get(p)!.title });
    }
    const item = path === TAG_ROOT ? { path, name: 'cq:tags', title: 'Tags', root: true, hasChildren: true } : this.item(path);
    return { item, trail, children: this.kids(path).map((k) => this.item(k)) };
  }

  async tag(path: string): Promise<TagDetail> {
    return { ...this.item(path), localized: { ...this.nodes.get(path)!.localized }, languages: ['en', 'de', 'fr'] };
  }

  async references(path: string): Promise<TagReference[]> {
    return path.endsWith('/hiking')
      ? [{ path: '/content/wknd/en/trip/jcr:content', kind: 'page', owner: '/content/wknd/en/trip', title: 'Trip', tags: ['wknd:activity/hiking'] }]
      : [];
  }

  async create(parent: string, title: string, name: string, description: string) {
    this.calls.push(`create ${parent} ${name}`);
    const path = `${parent}/${name}`;
    this.nodes.set(path, { title, description, localized: {} });
    return this.item(path);
  }

  async update(path: string, title: string, description: string, localized: Record<string, string>) {
    this.calls.push(`update ${path} ${JSON.stringify(localized)}`);
    const n = this.nodes.get(path)!;
    n.title = title;
    n.description = description;
    for (const [l, t] of Object.entries(localized)) {
      if (t) n.localized[l] = t;
      else delete n.localized[l];
    }
    return this.tag(path);
  }

  async move(path: string, dest: string, name: string) {
    this.calls.push(`move ${path} ${dest} ${name}`);
    return { path: `${dest}/${name}`, adjusted: 1 };
  }

  async merge(path: string, target: string) {
    this.calls.push(`merge ${path} ${target}`);
    this.nodes.delete(path);
    return { path: target, adjusted: 1 };
  }

  async remove(paths: string[]) {
    this.calls.push(`delete ${paths.join(',')}`);
    for (const p of paths) for (const k of [...this.nodes.keys()]) if (k === p || k.startsWith(`${p}/`)) this.nodes.delete(k);
    return { deleted: paths, adjusted: 1 };
  }

  async currentUser() {
    return 'admin';
  }
}

let tags: MemoryTags;

// jsdom has no CSS.escape, which Spectrum menus use
if (!(globalThis as { CSS?: { escape?: unknown } }).CSS?.escape) {
  (globalThis as unknown as { CSS: { escape(s: string): string } }).CSS = { escape: (s: string) => s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`) };
}

function mount(path = TAG_ROOT) {
  window.history.replaceState(null, '', `/tags.html${path}`);
  render(
    <Provider theme={defaultTheme}>
      <TagsConsole client={tags} initialPath={path} />
    </Provider>,
  );
}

beforeEach(() => {
  tags = new MemoryTags();
  try {
    window.localStorage.clear();
  } catch {
    /* ignore */
  }
});
afterEach(cleanup);

/** An action of the selection, a button or under More (wherever the bar's width puts it). */
async function moreAction(name: RegExp) {
  await pressAction(name);
}

describe('tag helpers', () => {
  it('derives names and parses URLs', () => {
    expect(nameFromTitle('Hunting & Fishing')).toBe('hunting-fishing');
    expect(isValidName('a.b-c_1')).toBe(true);
    expect(isValidName('-x')).toBe(false);
    expect(isValidName('a/b')).toBe(false);
    expect(tagsPath('/tags.html')).toBe(TAG_ROOT);
    expect(tagsPath('/tags.html/content/cq:tags/wknd/')).toBe(`${TAG_ROOT}/wknd`);
    expect(tagsPath('/sites.html/content')).toBeNull();
    expect(languageName('de')).toBe('German');
  });
});

describe('Tags console', () => {
  it('shows namespaces and tags in columns, with the references of a tag', async () => {
    mount(`${TAG_ROOT}/wknd/activity`);
    expect(await screen.findByRole('button', { name: /Hiking/ })).toBeTruthy();
    expect(screen.getByRole('list', { name: 'Tags' })).toBeTruthy();
    expect(screen.getByRole('list', { name: 'WKND Shared' })).toBeTruthy();
    act(() => {
      fireEvent.click(screen.getByRole('checkbox', { name: 'Select Hiking' }));
    });
    const details = await screen.findByRole('complementary', { name: 'Details of Hiking' });
    expect(within(details).getByText('wknd:activity/hiking')).toBeTruthy();
    expect(await within(details).findByText('Trip')).toBeTruthy();
    expect(screen.getByRole('toolbar', { name: 'Selection actions' })).toBeTruthy();
  });

  it('creates a tag with a name derived from the title', async () => {
    mount(`${TAG_ROOT}/wknd/activity`);
    await screen.findByRole('button', { name: /Hiking/ });
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    });
    act(() => {
      fireEvent.click(screen.getByRole('menuitem', { name: 'Create Tag' }));
    });
    fireEvent.change(screen.getByRole('textbox', { name: /Title/ }), { target: { value: 'Rock Climbing' } });
    expect((screen.getByRole('textbox', { name: /Name/ }) as HTMLInputElement).value).toBe('rock-climbing');
    expect((screen.getByRole('textbox', { name: 'Tag Path' }) as HTMLInputElement).value).toBe(`${TAG_ROOT}/wknd/activity`);
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(tags.calls).toContain(`create ${TAG_ROOT}/wknd/activity rock-climbing`));
    fireEvent.click(screen.getByRole('button', { name: 'Close tag' }));
    expect(await screen.findByRole('button', { name: /Rock Climbing/ })).toBeTruthy();
  });

  it('edits localized titles', async () => {
    tags.nodes.get(`${TAG_ROOT}/wknd/activity/hiking`)!.localized = { fr: 'Randonnée' };
    mount(`${TAG_ROOT}/wknd/activity`);
    const select = await screen.findByRole('checkbox', { name: 'Select Hiking' });
    act(() => {
      fireEvent.click(select);
    });
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: /Edit/ }));
    });
    const german = await screen.findByRole('textbox', { name: 'French' });
    expect((german as HTMLInputElement).value).toBe('Randonnée');
    // remove French, keep the rest
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Remove French' }));
    });
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    });
    await waitFor(() => expect(tags.calls).toContain(`update ${TAG_ROOT}/wknd/activity/hiking {"fr":""}`));
  });

  it('asks for Delete anyway when the tag is used', async () => {
    mount(`${TAG_ROOT}/wknd/activity`);
    const select = await screen.findByRole('checkbox', { name: 'Select Hiking' });
    act(() => {
      fireEvent.click(select);
    });
    await moreAction(/Delete/);
    const force = await screen.findByRole('button', { name: 'Delete anyway' });
    await waitFor(() => expect((force as HTMLButtonElement).disabled).toBe(false));
    act(() => {
      fireEvent.click(force);
    });
    await waitFor(() => expect(tags.calls).toContain(`delete ${TAG_ROOT}/wknd/activity/hiking`));
  });
});
