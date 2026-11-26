import { ActionButton, DialogContainer, Item, TagGroup, Text } from '@adobe/react-spectrum';
import TagIcon from '@spectrum-icons/workflow/Label';
import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { HttpTagsClient, TAG_ROOT, type TagsClient } from '../tags/api';
import { TagPicker } from '../tags/TagPicker';
import { tagIdOf, tagPathOf } from './api';

let shared: TagsClient | null = null;

/** The tag tree the Tags field browses; tests provide their own. */
export const TagsClientContext = createContext<TagsClient | null>(null);

function useTagsClient(): TagsClient {
  const c = useContext(TagsClientContext);
  if (c) return c;
  shared ??= new HttpTagsClient();
  return shared;
}

/** Titles of tag IDs, looked up once per ID (the ID itself until then, or if the tag is gone). */
const titles = new Map<string, string>();

function useTagTitles(client: TagsClient, ids: string[]): { titles: Map<string, string>; version: number } {
  const [version, rerender] = useState(0);
  const key = ids.join('|');
  useEffect(() => {
    let live = true;
    const missing = ids.filter((id) => !titles.has(id));
    Promise.all(
      missing.map((id) =>
        client.tag(tagPathOf(id)).then(
          (t) => titles.set(id, t.title || id),
          () => titles.set(id, id),
        ),
      ),
    ).then(() => live && missing.length > 0 && rerender((n) => n + 1));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, key]);
  return { titles, version };
}

/** The Tags field of the asset properties: chips of `cq:tags`, an "Add tag" picker over the tag tree. */
export function AssetTagsField({
  values,
  onChange,
  label = 'Tags',
  description,
  root,
}: {
  values: string[];
  onChange(v: string[]): void;
  label?: string;
  description?: string;
  /** the tag the picker opens at, such as wknd: (the whole tree otherwise) */
  root?: string;
}) {
  const client = useTagsClient();
  const [picking, setPicking] = useState(false);
  const { titles: names, version } = useTagTitles(client, values);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const items = useMemo(() => values.map((id) => ({ id, title: names.get(id) ?? id })), [values, names, version]);
  return (
    <div className="studio-assets__tags studio-assets__cqtags">
      <div className="studio-assets__cqtags-head">
        <Text UNSAFE_className="studio-assets__cqtags-label">{label}</Text>
        <ActionButton isQuiet onPress={() => setPicking(true)} aria-label={`Add ${label.toLowerCase()}`}>
          <TagIcon />
          <Text>Add tag</Text>
        </ActionButton>
      </div>
      {description && <Text UNSAFE_className="studio-assets__muted">{description}</Text>}
      {items.length > 0 ? (
        <TagGroup aria-label={label} items={items} onRemove={(keys) => onChange(values.filter((v) => !keys.has(v)))}>
          {(t) => (
            <Item key={t.id} textValue={t.title}>
              <span title={t.id}>{t.title}</span>
            </Item>
          )}
        </TagGroup>
      ) : (
        <Text UNSAFE_className="studio-assets__muted">No tags.</Text>
      )}
      <DialogContainer onDismiss={() => setPicking(false)}>
        {picking && (
          <TagPicker
            client={client}
            title="Select tag"
            start={root ? tagPathOf(root) : TAG_ROOT}
            onPick={(path) => {
              const id = tagIdOf(path);
              if (id && !values.includes(id)) onChange([...values, id]);
            }}
          />
        )}
      </DialogContainer>
    </div>
  );
}
