import {
  Breadcrumbs,
  Button,
  ButtonGroup,
  Content,
  Divider,
  Heading,
  IllustratedMessage,
  Image,
  Item,
  ListView,
  ProgressCircle,
  SearchField,
  Text,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Document from '@spectrum-icons/workflow/Document';
import Folder from '@spectrum-icons/workflow/Folder';
import { useEffect, useMemo, useState, type Key } from 'react';
import type { CfClient, CfSummary, DamItem } from './api';
import { RecordIcon } from '../shell/recordIcons';
import { StudioPickerDialog } from '../shell/StudioDialog';

function trail(root: string, path: string, titles: Record<string, string> = {}): Array<{ path: string; title: string }> {
  const out = [{ path: root, title: titles[root] ?? (root === '/content/dam' ? 'Media' : root.split('/').pop() || root) }];
  if (!path.startsWith(root) || path === root) return out;
  let current = root;
  for (const segment of path.slice(root.length).split('/').filter(Boolean)) {
    current = `${current}/${segment}`;
    out.push({ path: current, title: titles[current] ?? segment });
  }
  return out;
}

const IMAGE = /\.(png|jpe?g|gif|webp|svg|avif|tiff?)$/i;

/** Browse the DAM below `root` and pick an asset (content reference). */
export function AssetPickerDialog({ client, root, initial, onSelect }: { client: CfClient; root: string; initial?: string; onSelect(path: string): void }) {
  const dialog = useDialogContainer();
  const start = initial && initial.startsWith(root) ? initial.slice(0, initial.lastIndexOf('/')) : root;
  const [path, setPath] = useState(start);
  const [items, setItems] = useState<DamItem[] | null>(null);
  const [selected, setSelected] = useState<string | null>(initial ?? null);
  const [error, setError] = useState<string | null>(null);
  // folder titles seen so far, so the trail reads like the list does
  const [titles, setTitles] = useState<Record<string, string>>({});
  const remember = (list: DamItem[]) => setTitles((t) => ({ ...t, ...Object.fromEntries(list.map((i) => [i.path, i.title])) }));

  useEffect(() => {
    const parent = root.slice(0, root.lastIndexOf('/'));
    if (parent.startsWith('/content/dam')) client.listDam(parent).then(remember, () => undefined);
  }, [client, root]);

  useEffect(() => {
    let cancelled = false;
    setItems(null);
    client
      .listDam(path)
      .then((list) => {
        if (cancelled) return;
        remember(list);
        setItems(list.filter((i) => i.kind !== 'fragment'));
      })
      .catch((e: unknown) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [client, path]);

  const choose = () => {
    if (selected) onSelect(selected);
    dialog.dismiss();
  };

  return (
    <StudioPickerDialog size="L">
      <Heading>Select file</Heading>
      <Divider />
      <Content>
        <Breadcrumbs onAction={(k: Key) => setPath(String(k))} size="M">
          {trail(root, path, titles).map((t) => (
            <Item key={t.path}>{t.title}</Item>
          ))}
        </Breadcrumbs>
        {error && <Text>{error}</Text>}
        {items === null && !error ? (
          <ProgressCircle aria-label="Loading" isIndeterminate marginTop="size-300" />
        ) : (
          <ListView
            aria-label="Media"
            selectionMode="single"
            selectionStyle="highlight"
            height="size-4600"
            marginTop="size-150"
            selectedKeys={selected ? [selected] : []}
            onSelectionChange={(keys) => {
              const k = [...(keys as Set<Key>)][0];
              const item = items?.find((i) => i.path === k);
              if (item?.kind === 'asset') setSelected(String(k));
            }}
            onAction={(k: Key) => {
              const item = items?.find((i) => i.path === k);
              if (item?.kind === 'folder') setPath(item.path);
              else if (item) {
                onSelect(item.path);
                dialog.dismiss();
              }
            }}
            items={items ?? []}
            renderEmptyState={() => (
              <IllustratedMessage>
                <Heading>Empty folder</Heading>
              </IllustratedMessage>
            )}
          >
            {(item) => (
              <Item key={item.path} textValue={item.title} hasChildItems={item.kind === 'folder'}>
                {item.kind === 'folder' ? (
                  <Folder marginEnd="size-100" />
                ) : IMAGE.test(item.name) ? (
                  <Image src={client.thumbnailUrl(item.path)} alt="" objectFit="cover" />
                ) : (
                  <Document marginEnd="size-100" />
                )}
                <Text>{item.title}</Text>
                <Text slot="description">{item.name}</Text>
              </Item>
            )}
          </ListView>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={choose} isDisabled={!selected}>
          Select
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}

/** Pick a content fragment (fragment reference), limited to the models the element allows. */
export function FragmentPickerDialog({
  client,
  root,
  allowedModels,
  initial,
  onSelect,
}: {
  client: CfClient;
  root: string;
  allowedModels?: string[];
  initial?: string;
  onSelect(path: string): void;
}) {
  const dialog = useDialogContainer();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<CfSummary[] | null>(null);
  const [selected, setSelected] = useState<string | null>(initial ?? null);
  const models = useMemo(() => (allowedModels?.length ? allowedModels : [null]), [allowedModels]);

  useEffect(() => {
    let cancelled = false;
    const t = window.setTimeout(() => {
      Promise.all(models.map((m) => client.search(root, query, m, 0, 200)))
        .then((parts) => {
          if (cancelled) return;
          const seen = new Set<string>();
          setResults(parts.flatMap((p) => p.fragments).filter((f) => !seen.has(f.path) && !!seen.add(f.path)));
        })
        .catch(() => !cancelled && setResults([]));
    }, 200);
    return () => {
      cancelled = true;
      window.clearTimeout(t);
    };
  }, [client, root, query, models]);

  return (
    <StudioPickerDialog size="L">
      <Heading>Select content fragment</Heading>
      <Divider />
      <Content>
        <SearchField aria-label="Search content fragments" placeholder="Search" value={query} onChange={setQuery} width="100%" autoFocus />
        {results === null ? (
          <ProgressCircle aria-label="Loading" isIndeterminate marginTop="size-300" />
        ) : (
          <ListView
            aria-label="Content Fragments"
            selectionMode="single"
            selectionStyle="highlight"
            height="size-4600"
            marginTop="size-150"
            selectedKeys={selected ? [selected] : []}
            onSelectionChange={(keys) => setSelected(String([...(keys as Set<Key>)][0] ?? '') || null)}
            onAction={(k: Key) => {
              onSelect(String(k));
              dialog.dismiss();
            }}
            items={results}
            renderEmptyState={() => (
              <IllustratedMessage>
                <Heading>No content fragments found</Heading>
              </IllustratedMessage>
            )}
          >
            {(f) => (
              <Item key={f.path} textValue={f.title}>
                <RecordIcon marginEnd="size-100" />
                <Text>{f.title}</Text>
                <Text slot="description">
                  {f.modelTitle ?? 'No model'} · {f.path}
                </Text>
              </Item>
            )}
          </ListView>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={!selected}
          onPress={() => {
            if (selected) onSelect(selected);
            dialog.dismiss();
          }}
        >
          Select
        </Button>
      </ButtonGroup>
    </StudioPickerDialog>
  );
}
