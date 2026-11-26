import { useEffect, useMemo, useState, type DragEvent } from 'react';
import type { GraniteComponentProps, RepositoryItem } from '../../registry';
import { useField, useFormStore, useGranite } from '../../context';
import { scalarEntries } from '../../form/slingPost';
import { asString, str, strList } from '../../node';
import { ChevronGlyph, FileGlyph, FolderGlyph, ImageGlyph } from '../glyphs';
import { commonFieldProps, describedBy, FieldShell, requiredError } from '../FieldShell';
import { initialScalar } from './text';

/**
 * `granite/ui/components/coral/foundation/form/pathfield` and the AEM variants that behave the same for
 * authors: `cq/gui/components/coral/common/form/pagefield`, `.../form/pathbrowser`, the XF and CF pickers.
 */
export function PathField({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const rootPath = str(node, 'rootPath', '/content') ?? '/content';
  const { id, value, setValue, error, disabled: ruleDisabled } = useField<string>(common.name, () => initialScalar(store, node), {
    label: common.label,
    entries: (v) => scalarEntries(common.name, asString(v)),
    values: (v) => [asString(v)],
    validate: (v) => requiredError(common.required, asString(v).trim() === ''),
  });
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <PathInput
        id={id}
        value={value ?? ''}
        onChange={setValue}
        rootPath={rootPath}
        placeholder={common.emptyText}
        disabled={common.disabled || common.readOnly || ruleDisabled}
        describedBy={describedBy(id, common.description, error)}
        title={common.label}
      />
    </FieldShell>
  );
}

interface PathInputProps {
  id: string;
  value: string;
  onChange(value: string): void;
  rootPath: string;
  placeholder?: string;
  disabled?: boolean;
  describedBy?: string;
  title?: string;
}

export function PathInput({ id, value, onChange, rootPath, placeholder, disabled, describedBy: aria, title }: PathInputProps) {
  const { env } = useGranite();
  const [picking, setPicking] = useState(false);
  return (
    <div className="studio-g-path">
      <input
        id={id}
        className="studio-g-input studio-g-path__input"
        type="text"
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        aria-describedby={aria}
        onChange={(e) => onChange(e.target.value)}
      />
      {env.listChildren && (
        <button
          type="button"
          className="studio-g-path__browse"
          disabled={disabled}
          onClick={() => setPicking(true)}
          aria-label={`Browse ${title ?? 'repository'}`}
          title="Browse"
        >
          <FolderGlyph />
        </button>
      )}
      {picking && (
        <RepositoryPicker
          rootPath={rootPath}
          initialPath={value && value.startsWith(rootPath) ? parentOf(value) : rootPath}
          title={title}
          onCancel={() => setPicking(false)}
          onSelect={(path) => {
            onChange(path);
            setPicking(false);
          }}
        />
      )}
    </div>
  );
}

function parentOf(path: string): string {
  const i = path.lastIndexOf('/');
  return i > 0 ? path.slice(0, i) : '/';
}

interface PickerProps {
  rootPath: string;
  initialPath: string;
  title?: string;
  onSelect(path: string): void;
  onCancel(): void;
}

/** Column-less, keyboard friendly repository browser backed by `env.listChildren`. */
export function RepositoryPicker({ rootPath, initialPath, title, onSelect, onCancel }: PickerProps) {
  const { env } = useGranite();
  const [path, setPath] = useState(initialPath);
  const [entries, setEntries] = useState<RepositoryItem[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setEntries(null);
    setFailure(null);
    env
      .listChildren?.(path)
      .then((list) => !cancelled && setEntries(list))
      .catch((e: unknown) => !cancelled && setFailure(String(e)));
    return () => {
      cancelled = true;
    };
  }, [env, path]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onCancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  const crumbs = breadcrumbs(rootPath, path);

  return (
    <div className="studio-g-picker" role="dialog" aria-modal="true" aria-label={`Select ${title ?? 'path'}`}>
      <div className="studio-g-picker__backdrop" onClick={onCancel} />
      <div className="studio-g-picker__window">
        <header className="studio-g-picker__header">
          <nav className="studio-g-picker__crumbs" aria-label="Location">
            {crumbs.map((c, i) => (
              <span key={c.path}>
                {i > 0 && <span className="studio-g-picker__sep">/</span>}
                <button type="button" onClick={() => setPath(c.path)} disabled={c.path === path}>
                  {c.label}
                </button>
              </span>
            ))}
          </nav>
        </header>
        <ul className="studio-g-picker__list" role="listbox" aria-label="Items">
          {failure && <li className="studio-g-picker__empty">Could not list {path}: {failure}</li>}
          {!failure && entries === null && <li className="studio-g-picker__empty">Loading…</li>}
          {entries?.length === 0 && <li className="studio-g-picker__empty">Empty</li>}
          {entries?.map((item) => (
            <li key={item.path} role="option" aria-selected={selected === item.path}>
              <button
                type="button"
                className={`studio-g-picker__item${selected === item.path ? ' is-selected' : ''}`}
                onClick={() => setSelected(item.path)}
                onDoubleClick={() => (item.hasChildren ? setPath(item.path) : onSelect(item.path))}
              >
                <span className="studio-g-picker__icon" aria-hidden="true">
                  {iconFor(item.type)}
                </span>
                <span className="studio-g-picker__name">{item.title || item.name}</span>
                <span className="studio-g-picker__type">{item.type}</span>
              </button>
              {item.hasChildren && (
                <button
                  type="button"
                  className="studio-g-picker__open"
                  onClick={() => setPath(item.path)}
                  aria-label={`Open ${item.title || item.name}`}
                >
                  ›
                </button>
              )}
            </li>
          ))}
        </ul>
        <footer className="studio-g-picker__footer">
          <code className="studio-g-picker__selection">{selected ?? path}</code>
          <button type="button" className="studio-g-btn" onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className="studio-g-btn studio-g-btn--primary" onClick={() => onSelect(selected ?? path)}>
            Select
          </button>
        </footer>
      </div>
    </div>
  );
}

function iconFor(type?: string): string {
  if (type === 'cq:Page') return '▤';
  if (type === 'dam:Asset' || type === 'nt:file') return '▣';
  return '▸';
}

function breadcrumbs(rootPath: string, path: string): Array<{ path: string; label: string }> {
  const root = rootPath.replace(/\/$/, '') || '/';
  const out = [{ path: root, label: root }];
  if (!path.startsWith(root) || path === root) return out;
  let current = root;
  for (const segment of path.slice(root.length).split('/').filter(Boolean)) {
    current = `${current}/${segment}`;
    out.push({ path: current, label: segment });
  }
  return out;
}

/** The MIME type DAM would give a file name, for the `mimeTypes` check of drops. */
function mimeOf(path: string): string | undefined {
  const ext = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  const map: Record<string, string> = {
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    png: 'image/png',
    gif: 'image/gif',
    tif: 'image/tiff',
    tiff: 'image/tiff',
    svg: 'image/svg+xml',
    webp: 'image/webp',
    pdf: 'application/pdf',
    mp4: 'video/mp4',
  };
  return map[ext];
}

function accepts(mimeTypes: string[], path: string): boolean {
  if (!mimeTypes.length) return true;
  const mime = mimeOf(path);
  if (!mime) return true; // unknown extension: let the server decide
  return mimeTypes.some((m) => m === mime || (m.endsWith('/*') && mime.startsWith(m.slice(0, -1))));
}

/** The DAM path carried by a drag from the editor's Assets panel (or any text drag of a DAM path). */
export const ASSET_DRAG_TYPE = 'application/x-studio-asset';

function draggedAsset(e: DragEvent): string | null {
  const path = e.dataTransfer.getData(ASSET_DRAG_TYPE) || e.dataTransfer.getData('text/plain');
  return path && path.startsWith('/content/dam/') ? path.trim() : null;
}

function canDrop(e: DragEvent): boolean {
  return e.dataTransfer.types.includes(ASSET_DRAG_TYPE);
}

/**
 * `cq/gui/components/authoring/dialog/fileupload` as used by image dialogs: a drop zone showing the referenced
 * DAM asset (`fileReferenceParameter`), with Clear and Pick. Assets are dropped from the Assets side panel or
 * picked from the DAM. Uploading binaries into the component is not supported; choosing a reference removes a
 * previously uploaded binary, like AEM does.
 */
export function FileUpload({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const { env } = useGranite();
  const common = commonFieldProps(node);
  const refName = str(node, 'fileReferenceParameter') ?? './fileReference';
  const binaryName = str(node, 'name');
  const mimeTypes = strList(node, 'mimeTypes');
  const label = common.label;
  const { id, value, setValue, error, disabled: ruleDisabled } = useField<string>(refName, () => asString(store.contentValue(refName)), {
    label: label ?? 'Asset',
    entries: (v) => {
      const ref = asString(v);
      const out = scalarEntries(refName, ref);
      if (ref && binaryName) out.push({ name: `${binaryName}@Delete`, value: '' });
      return out;
    },
    values: (v) => [asString(v)],
    validate: (v) => requiredError(common.required, asString(v) === ''),
  });
  const disabled = common.disabled || ruleDisabled;
  const [over, setOver] = useState(false);
  const [rejected, setRejected] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const path = value ?? '';

  const choose = (p: string) => {
    if (!accepts(mimeTypes, p)) {
      setRejected(`${p.slice(p.lastIndexOf('/') + 1)} is not an accepted file type.`);
      return;
    }
    setRejected(null);
    setValue(p);
  };

  return (
    <FieldShell id={id} label={label} required={common.required} description={common.description} error={error ?? rejected}>
      <div
        id={id}
        className={`studio-g-upload${path ? '' : ' is-empty'}${over ? ' is-over' : ''}${disabled ? ' is-disabled' : ''}`}
        onDragOver={(e) => {
          if (disabled || !canDrop(e)) return;
          e.preventDefault();
          e.dataTransfer.dropEffect = 'copy';
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          setOver(false);
          if (disabled) return;
          const dropped = draggedAsset(e);
          if (!dropped) return;
          e.preventDefault();
          choose(dropped);
        }}
      >
        {path ? (
          <>
            <AssetPreview path={path} />
            <div className="studio-g-upload__name" title={path}>
              {path.slice(path.lastIndexOf('/') + 1)}
            </div>
          </>
        ) : (
          <div className="studio-g-upload__empty">
            <ImageGlyph size={32} />
          </div>
        )}
        <div className="studio-g-upload__actions">
          {path && (
            <button type="button" className="studio-g-linkbtn" disabled={disabled} onClick={() => setValue('')}>
              Clear
            </button>
          )}
          {env.listChildren && (
            <button type="button" className="studio-g-linkbtn" disabled={disabled} onClick={() => setPicking(true)}>
              Pick
            </button>
          )}
        </div>
        <div className="studio-g-upload__hint">{env.listChildren ? 'Pick a file from Media, or drop one here.' : 'Drop a file here from the Media panel.'}</div>
      </div>
      {picking && (
        <AssetPicker
          initialPath={path ? parentOf(path) : '/content/dam'}
          mimeTypes={mimeTypes}
          onCancel={() => setPicking(false)}
          onSelect={(p) => {
            setPicking(false);
            choose(p);
          }}
        />
      )}
    </FieldShell>
  );
}

/** Thumbnail of a DAM asset: the small rendition, then the original, then a file icon. */
export function AssetPreview({ path, className = 'studio-g-upload__preview' }: { path: string; className?: string }) {
  const { env } = useGranite();
  const sources = useMemo(
    () => [env.assetThumbnail?.(path), env.assetUrl?.(path)].filter((u): u is string => !!u),
    [env, path],
  );
  const [attempt, setAttempt] = useState(0);
  useEffect(() => setAttempt(0), [sources]);
  const src = sources[attempt];
  const isImage = (mimeOf(path) ?? 'image/').startsWith('image/');
  if (!src || !isImage) {
    return (
      <div className={`${className} is-icon`}>
        <FileGlyph size={36} />
      </div>
    );
  }
  return (
    <div className={className}>
      <img src={src} alt="" onError={() => setAttempt((a) => a + 1)} />
    </div>
  );
}

interface AssetPickerProps {
  initialPath: string;
  mimeTypes: string[];
  onSelect(path: string): void;
  onCancel(): void;
}

const FOLDER_TYPES = new Set(['sling:Folder', 'sling:OrderedFolder', 'nt:folder']);

/** Browses `/content/dam` as cards: folders first, then the assets of the current folder, filterable by name. */
export function AssetPicker({ initialPath, mimeTypes, onSelect, onCancel }: AssetPickerProps) {
  const { env } = useGranite();
  const [path, setPath] = useState(initialPath.startsWith('/content/dam') ? initialPath : '/content/dam');
  const [entries, setEntries] = useState<RepositoryItem[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    let live = true;
    setEntries(null);
    setFailure(null);
    setSelected(null);
    env
      .listChildren?.(path)
      .then((list) => live && setEntries(list))
      .catch((e: unknown) => live && setFailure(String(e)));
    return () => {
      live = false;
    };
  }, [env, path]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onCancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  const q = filter.trim().toLowerCase();
  const matches = (i: RepositoryItem) => !q || `${i.title ?? ''} ${i.name}`.toLowerCase().includes(q);
  const folders = (entries ?? []).filter((i) => FOLDER_TYPES.has(i.type ?? '') && matches(i));
  const assets = (entries ?? []).filter((i) => i.type === 'dam:Asset' && accepts(mimeTypes, i.path) && matches(i));
  const crumbs = breadcrumbs('/content/dam', path);

  return (
    <div className="studio-g-picker" role="dialog" aria-modal="true" aria-label="Select a file">
      <div className="studio-g-picker__backdrop" onClick={onCancel} />
      <div className="studio-g-picker__window studio-g-picker__window--assets">
        <header className="studio-g-picker__header">
          <div className="studio-g-picker__title">Select Media</div>
          <nav className="studio-g-picker__crumbs" aria-label="Location">
            {crumbs.map((c, i) => (
              <span key={c.path}>
                {i > 0 && (
                  <span className="studio-g-picker__sep">
                    <ChevronGlyph />
                  </span>
                )}
                <button type="button" onClick={() => setPath(c.path)} disabled={c.path === path}>
                  {i === 0 ? 'Media' : c.label}
                </button>
              </span>
            ))}
          </nav>
          <input
            className="studio-g-input studio-g-picker__filter"
            type="search"
            placeholder="Filter this folder"
            aria-label="Filter this folder"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </header>
        <div className="studio-g-picker__grid" role="listbox" aria-label="Folders and files">
          {failure && <div className="studio-g-picker__empty">Could not list {path}: {failure}</div>}
          {!failure && entries === null && <div className="studio-g-picker__empty">Loading…</div>}
          {entries && !folders.length && !assets.length && <div className="studio-g-picker__empty">No files here.</div>}
          {folders.map((f) => (
            <button
              key={f.path}
              type="button"
              role="option"
              aria-selected={false}
              className="studio-g-card studio-g-card--folder"
              onClick={() => setPath(f.path)}
            >
              <span className="studio-g-card__thumb">
                <FolderGlyph />
              </span>
              <span className="studio-g-card__name">{f.title || f.name}</span>
            </button>
          ))}
          {assets.map((a) => (
            <button
              key={a.path}
              type="button"
              role="option"
              aria-selected={selected === a.path}
              className={`studio-g-card${selected === a.path ? ' is-selected' : ''}`}
              onClick={() => setSelected(a.path)}
              onDoubleClick={() => onSelect(a.path)}
            >
              <AssetPreview path={a.path} className="studio-g-card__thumb" />
              <span className="studio-g-card__name">{a.title || a.name}</span>
            </button>
          ))}
        </div>
        <footer className="studio-g-picker__footer">
          <code className="studio-g-picker__selection">{selected ?? ''}</code>
          <button type="button" className="studio-g-btn" onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className="studio-g-btn studio-g-btn--primary" disabled={!selected} onClick={() => selected && onSelect(selected)}>
            Select
          </button>
        </footer>
      </div>
    </div>
  );
}
