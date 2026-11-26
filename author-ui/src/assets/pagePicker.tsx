import { ActionButton, Button, ButtonGroup, Content, DialogTrigger, Divider, Heading, ProgressCircle } from '@adobe/react-spectrum';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import FolderOpen from '@spectrum-icons/workflow/FolderOpen';
import { useEffect, useState } from 'react';
import { StudioPickerDialog } from '../shell/StudioDialog';

export interface PageItem {
  name: string;
  path: string;
  title: string;
  /** a page can be picked; a folder can only be opened */
  page: boolean;
  hasChildren: boolean;
}

export type PageLister = (path: string) => Promise<PageItem[]>;

/** What the picker browses: the pages of the site, or the records (content fragments) in Media. */
export type PickKind = 'pages' | 'records';
export const PICK_ROOT: Record<PickKind, string> = { pages: '/content', records: '/content/dam' };

const FOLDERS = new Set(['sling:Folder', 'sling:OrderedFolder', 'nt:folder']);
const isNode = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/** The pages and folders below `path`, from the repository's JSON view (two levels: titles and "has pages below"). */
export function pagesOf(tree: Record<string, unknown>, path: string): PageItem[] {
  const out: PageItem[] = [];
  for (const [name, node] of Object.entries(tree)) {
    if (!isNode(node) || name.includes(':')) continue;
    const type = String(node['jcr:primaryType'] ?? '');
    const page = type === 'cq:Page';
    if (!page && !FOLDERS.has(type)) continue;
    const content = isNode(node['jcr:content']) ? node['jcr:content'] : {};
    const hasChildren = Object.entries(node).some(([k, v]) => !k.includes(':') && isNode(v) && (v['jcr:primaryType'] === 'cq:Page' || FOLDERS.has(String(v['jcr:primaryType'] ?? ''))));
    out.push({ name, path: `${path}/${name}`, title: String(content['jcr:title'] ?? node['jcr:title'] ?? name), page, hasChildren });
  }
  return out;
}

/** The records and folders below a Media folder (same JSON view; a record is an asset marked `contentFragment`). */
export function recordsOf(tree: Record<string, unknown>, path: string): PageItem[] {
  const out: PageItem[] = [];
  for (const [name, node] of Object.entries(tree)) {
    if (!isNode(node) || name.includes(':')) continue;
    const type = String(node['jcr:primaryType'] ?? '');
    const content = isNode(node['jcr:content']) ? node['jcr:content'] : {};
    if (type === 'dam:Asset') {
      if (content.contentFragment !== true && content.contentFragment !== 'true') continue;
      out.push({ name, path: `${path}/${name}`, title: String(content['jcr:title'] ?? name), page: true, hasChildren: false });
    } else if (FOLDERS.has(type)) {
      out.push({ name, path: `${path}/${name}`, title: String(content['jcr:title'] ?? node['jcr:title'] ?? name), page: false, hasChildren: true });
    }
  }
  return out;
}

export const httpRecordLister: PageLister = async (path) => {
  const res = await fetch(`${path}.2.json`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`${res.status}`);
  return recordsOf((await res.json()) as Record<string, unknown>, path);
};

export const httpPageLister: PageLister = async (path) => {
  const res = await fetch(`${path}.2.json`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`${res.status}`);
  return pagesOf((await res.json()) as Record<string, unknown>, path);
};

/** Where browsing starts for a value: the folder of the page it names, or the site root. */
export function startFolder(value: string, root: string): string {
  const v = value.trim().replace(/\.html?$/, '');
  if (!v.startsWith(`${root}/`)) return root;
  const parent = v.slice(0, v.lastIndexOf('/'));
  return parent.length >= root.length ? parent : root;
}

/**
 * The folder button next to a path field: browses the pages under `root` and hands back the path of the one chosen.
 * The field itself stays free text (external links are typed).
 */
export function PagePickerButton({
  value,
  onPick,
  root = '/content',
  list = httpPageLister,
  records,
}: {
  value: string;
  onPick(path: string): void;
  root?: string;
  list?: PageLister;
  /** also offer records: `true` for the repository's, or a lister (tests) */
  records?: boolean | PageLister;
}) {
  const recordList = records === true ? httpRecordLister : records || null;
  return (
    <DialogTrigger>
      <ActionButton isQuiet aria-label={recordList ? 'Browse pages and content fragments' : 'Browse pages'} UNSAFE_className="studio-pagepicker__button">
        <FolderOpen />
      </ActionButton>
      {(close) => <PagePickerDialog value={value} root={root} list={list} recordList={recordList} onPick={(p) => (onPick(p), close())} close={close} />}
    </DialogTrigger>
  );
}

function PagePickerDialog({ value, root, list: pageList, recordList, onPick, close }: { value: string; root: string; list: PageLister; recordList: PageLister | null; onPick(path: string): void; close(): void }) {
  const [kind, setKind] = useState<PickKind>(() => (recordList && value.trim().startsWith(`${PICK_ROOT.records}/`) ? 'records' : 'pages'));
  const list = kind === 'records' && recordList ? recordList : pageList;
  const base = kind === 'records' ? PICK_ROOT.records : root;
  const [folder, setFolder] = useState(() => startFolder(value, base));
  const [items, setItems] = useState<PageItem[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setItems(null);
    list(folder).then(
      (l) => live && setItems(kind === 'records' ? l : l.filter((i) => !i.path.startsWith('/content/dam') && !i.path.startsWith('/content/launches'))),
      () => live && setItems([]),
    );
    return () => {
      live = false;
    };
  }, [list, folder]);

  const crumbs = folder.split('/').filter(Boolean);
  return (
    <StudioPickerDialog size="M">
      <Heading>{kind === 'records' ? 'Choose a content fragment' : 'Choose a page'}</Heading>
      <Divider />
      <Content>
        {recordList && (
          <div className="studio-pagepicker__kinds" role="tablist" aria-label="What to link">
            {(['pages', 'records'] as const).map((k) => (
              <button
                key={k}
                type="button"
                role="tab"
                aria-selected={kind === k}
                className={`studio-pagepicker__kind${kind === k ? ' is-selected' : ''}`}
                onClick={() => {
                  if (k === kind) return;
                  setKind(k);
                  setSelected(null);
                  setFolder(k === 'records' ? PICK_ROOT.records : root);
                }}
              >
                {k === 'pages' ? 'Pages' : 'Content Fragments'}
              </button>
            ))}
            <span className="studio-pagepicker__kindhint">{kind === 'records' ? 'A content fragment opens as a card over the image.' : 'A page is a link.'}</span>
          </div>
        )}
        <nav className="studio-pagepicker__crumbs" aria-label="Folder">
          {crumbs.map((c, i) => (
            <span key={i}>
              {i > 0 && ' / '}
              <button type="button" className="studio-pagepicker__crumb" onClick={() => (setSelected(null), setFolder('/' + crumbs.slice(0, i + 1).join('/')))}>
                {c}
              </button>
            </span>
          ))}
        </nav>
        {items === null ? (
          <ProgressCircle size="S" isIndeterminate aria-label="Loading" />
        ) : items.length === 0 ? (
          <p className="studio-assets__muted">{kind === 'records' ? 'No content fragments here.' : 'No pages here.'}</p>
        ) : (
          <ul className="studio-pagepicker__list" role="listbox" aria-label="Pages">
            {items.map((i) => (
              <li key={i.path} role="option" aria-selected={selected === i.path} className={selected === i.path ? 'is-selected' : ''}>
                <button
                  type="button"
                  className="studio-pagepicker__item"
                  onClick={() => (i.page ? setSelected(i.path) : (setSelected(null), setFolder(i.path)))}
                  onDoubleClick={() => i.page && onPick(i.path)}
                >
                  <span className="studio-pagepicker__title">{i.title}</span>
                  <span className="studio-pagepicker__name">{i.page ? i.name : 'Folder'}</span>
                </button>
                {i.hasChildren && (
                  <button type="button" className="studio-pagepicker__open" aria-label={`Open ${i.title}`} onClick={() => (setSelected(null), setFolder(i.path))}>
                    <ChevronRight size="S" />
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close}>
          Cancel
        </Button>
        <Button variant="accent" isDisabled={!selected} onPress={() => selected && onPick(selected)}>
          Choose
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}
