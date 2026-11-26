import { ActionButton, Item, Picker, ProgressCircle, SearchField, TextField } from '@adobe/react-spectrum';
import Document from '@spectrum-icons/workflow/Document';
import { useEffect, useRef, useState, type Key } from 'react';
import type { AssetInfo, AssetType, StudioClient } from '../api/StudioClient';
import { endDrag, startDrag } from '../overlay/dragPayload';

interface Props {
  client: StudioClient;
}

const PAGE = 30;

/** DAM assets: filter by type, text and folder; drag one onto an image component or a container. */
export function AssetsPanel({ client }: Props) {
  const [type, setType] = useState<AssetType>('images');
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [path, setPath] = useState('/content/dam');
  const [root, setRoot] = useState('/content/dam');
  const [assets, setAssets] = useState<AssetInfo[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const request = useRef(0);

  // debounce the text filter
  useEffect(() => {
    const t = setTimeout(() => setQuery(q), 300);
    return () => clearTimeout(t);
  }, [q]);

  const load = async (offset: number) => {
    const id = ++request.current;
    setLoading(true);
    setError(null);
    try {
      const page = await client.searchAssets({ path: root, type, q: query, offset, limit: PAGE });
      if (id !== request.current) return;
      setTotal(page.total);
      setAssets((prev) => (offset === 0 ? page.assets : [...prev, ...page.assets]));
    } catch (e) {
      if (id === request.current) setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (id === request.current) setLoading(false);
    }
  };

  useEffect(() => {
    void load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, type, query, root]);

  return (
    <div className="studio-panel">
      <div className="studio-panel__controls">
        <Picker aria-label="Media type" width="100%" selectedKey={type} onSelectionChange={(k: Key | null) => setType((k as AssetType) ?? 'images')}>
          <Item key="images">Images</Item>
          <Item key="videos">Videos</Item>
          <Item key="documents">Documents</Item>
          <Item key="all">All files</Item>
        </Picker>
        <SearchField aria-label="Filter" placeholder="Filter" width="100%" value={q} onChange={setQ} />
        <TextField
          label="Path"
          width="100%"
          value={path}
          onChange={setPath}
          onBlur={() => setRoot(normalizeRoot(path))}
          onKeyDown={(e) => {
            if (e.key === 'Enter') setRoot(normalizeRoot(path));
          }}
        />
      </div>

      {error && <p className="studio-panel__empty">{error}</p>}
      {!error && !loading && assets.length === 0 && <p className="studio-panel__empty">No files found.</p>}

      <ul className="studio-assetgrid" aria-label="Media">
        {assets.map((a) => (
          <li
            key={a.path}
            className="studio-asset"
            draggable
            onDragStart={(e) => startDrag(e, { kind: 'asset', asset: a }, a.path)}
            onDragEnd={endDrag}
            title={a.path}
          >
            <div className="studio-asset__thumb">
              {a.thumbnail ? (
                <img src={client.assetUrl(a.thumbnail)} alt="" loading="lazy" draggable={false} />
              ) : (
                <Document size="L" />
              )}
            </div>
            <div className="studio-asset__meta">
              <span className="studio-asset__name">{a.name}</span>
              <span className="studio-asset__info">
                {[a.width && a.height ? `${a.width} × ${a.height}` : null, a.size ? formatSize(a.size) : null].filter(Boolean).join(' · ')}
              </span>
            </div>
          </li>
        ))}
      </ul>

      <div className="studio-panel__more">
        {loading ? (
          <ProgressCircle size="S" isIndeterminate aria-label="Loading files" />
        ) : (
          assets.length < total && (
            <ActionButton isQuiet onPress={() => void load(assets.length)}>
              Load more ({total - assets.length})
            </ActionButton>
          )
        )}
      </div>
    </div>
  );
}

function normalizeRoot(p: string): string {
  const v = p.trim().replace(/\/+$/, '');
  return v.startsWith('/content/dam') ? v : '/content/dam';
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
