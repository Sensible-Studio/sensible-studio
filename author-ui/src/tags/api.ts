/** Client of `/bin/studio/tags.json` (studio-tagging bundle). */

export const TAG_ROOT = '/content/cq:tags';

export interface TagItem {
  path: string;
  name: string;
  /** `ns:a/b`, `ns:` for a namespace; absent on the root */
  tagID?: string;
  title: string;
  description?: string;
  namespace?: boolean;
  root?: boolean;
  hasChildren: boolean;
  created?: string;
  lastModified?: string;
  lastModifiedBy?: string;
}

export interface TagListing {
  item: TagItem;
  trail: Array<{ path: string; title: string }>;
  children: TagItem[];
}

export interface TagDetail extends TagItem {
  /** language -> localized title (`jcr:title.<lang>`) */
  localized: Record<string, string>;
  /** languages offered for localized titles */
  languages: string[];
}

export interface TagReference {
  /** the resource holding `cq:tags` */
  path: string;
  kind: 'page' | 'asset' | 'content';
  /** the page or asset it belongs to */
  owner: string;
  title: string;
  /** the values that refer to the tag */
  tags: string[];
}

export interface TagsClient {
  children(path: string): Promise<TagListing>;
  tag(path: string): Promise<TagDetail>;
  references(path: string): Promise<TagReference[]>;
  create(parent: string, title: string, name: string, description: string): Promise<TagItem>;
  update(path: string, title: string, description: string, localized: Record<string, string>): Promise<TagDetail>;
  move(path: string, dest: string, name: string): Promise<{ path: string; adjusted: number }>;
  merge(path: string, target: string): Promise<{ path: string; adjusted: number }>;
  remove(paths: string[]): Promise<{ deleted: string[]; adjusted: number }>;
  currentUser(): Promise<string>;
}

const API = '/bin/studio/tags.json';

async function call<T>(method: 'GET' | 'POST', params: Array<[string, string]>): Promise<T> {
  const qs = new URLSearchParams(params);
  const res =
    method === 'GET'
      ? await fetch(`${API}?${qs}`, { credentials: 'same-origin' })
      : await fetch(API, {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
          body: qs,
        });
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error((body as { error?: string }).error || `HTTP ${res.status}`);
  return body;
}

export class HttpTagsClient implements TagsClient {
  children(path: string) {
    return call<TagListing>('GET', [['op', 'children'], ['path', path]]);
  }

  tag(path: string) {
    return call<TagDetail>('GET', [['op', 'tag'], ['path', path]]);
  }

  references(path: string) {
    return call<TagReference[]>('GET', [['op', 'references'], ['path', path]]);
  }

  create(parent: string, title: string, name: string, description: string) {
    return call<TagItem>('POST', [['op', 'create'], ['parent', parent], ['title', title], ['name', name], ['description', description]]);
  }

  update(path: string, title: string, description: string, localized: Record<string, string>) {
    return call<TagDetail>('POST', [
      ['op', 'update'],
      ['path', path],
      ['title', title],
      ['description', description],
      ...Object.entries(localized).map(([lang, t]): [string, string] => [`title.${lang}`, t]),
    ]);
  }

  move(path: string, dest: string, name: string) {
    return call<{ path: string; adjusted: number }>('POST', [['op', 'move'], ['path', path], ['dest', dest], ['name', name]]);
  }

  merge(path: string, target: string) {
    return call<{ path: string; adjusted: number }>('POST', [['op', 'merge'], ['path', path], ['target', target]]);
  }

  remove(paths: string[]) {
    return call<{ deleted: string[]; adjusted: number }>('POST', [['op', 'delete'], ...paths.map((p): [string, string] => ['path', p])]);
  }

  async currentUser() {
    const res = await fetch('/system/sling/info.sessionInfo.json', { credentials: 'same-origin' });
    const info = (await res.json()) as { userID?: string };
    return info.userID ?? 'anonymous';
  }
}

/** `/tags.html/content/cq:tags/...` */
export const tagsUrl = (path: string = TAG_ROOT) => `/tags.html${path}`;

/** The tag path a console URL points at, or null when it is not the Tags console. */
export function tagsPath(pathname: string): string | null {
  const m = pathname.match(/^\/tags\.html(\/content\/cq:tags(?:\/.*)?)?$/);
  if (!m) return null;
  return m[1] ? decodeURI(m[1]).replace(/\/$/, '') || TAG_ROOT : TAG_ROOT;
}

export const parentOf = (path: string) => path.slice(0, path.lastIndexOf('/')) || '/';
export const nameOf = (path: string) => path.slice(path.lastIndexOf('/') + 1);

/** A tag name from a title, as the server derives it: lower case, other characters as '-'. */
export function nameFromTitle(title: string): string {
  return title
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/** Valid tag (node) name: letters, digits, `_`, `-`, `.`; not starting with `.` or `-`. */
export const isValidName = (name: string) => /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(name);

/** "German" for `de`, "Portuguese (Brazil)" for `pt_br`, the code when the browser does not know it. */
export function languageName(code: string, display = 'en'): string {
  const tag = code.replace('_', '-');
  try {
    const names = new Intl.DisplayNames([display], { type: 'language' });
    const name = names.of(tag);
    if (name && name.toLowerCase() !== tag.toLowerCase()) return name;
  } catch {
    /* unknown code */
  }
  return code;
}
