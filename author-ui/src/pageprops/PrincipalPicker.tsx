import { Item, ComboBox, Text } from '@adobe/react-spectrum';
import UserGroup from '@spectrum-icons/workflow/UserGroup';
import User from '@spectrum-icons/workflow/User';
import { useEffect, useState } from 'react';
import type { PagePermissionsClient, PrincipalChoice } from './api';

/** Searches users and groups as you type (by ID or name). */
export function PrincipalPicker({
  client,
  label = 'Select user or group',
  exclude = [],
  onSelect,
  autoFocus,
}: {
  client: PagePermissionsClient;
  label?: string;
  exclude?: string[];
  onSelect(choice: PrincipalChoice): void;
  autoFocus?: boolean;
}) {
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<PrincipalChoice[]>([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    let live = true;
    setLoading(true);
    const t = window.setTimeout(() => {
      client.findPrincipals(query).then(
        (r) => {
          if (!live) return;
          setItems(r.filter((p) => !exclude.includes(p.id)));
          setLoading(false);
        },
        () => live && setLoading(false),
      );
    }, 200);
    return () => {
      live = false;
      window.clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, query, exclude.join('|')]);
  return (
    <ComboBox
      label={label}
      width="100%"
      items={items}
      inputValue={query}
      onInputChange={setQuery}
      loadingState={loading ? 'loading' : 'idle'}
      autoFocus={autoFocus}
      menuTrigger="focus"
      onSelectionChange={(key) => {
        const p = items.find((i) => i.id === key);
        if (p) {
          onSelect(p);
          setQuery('');
        }
      }}
    >
      {(p) => (
        <Item key={p.id} textValue={p.title}>
          {p.group ? <UserGroup size="S" /> : <User size="S" />}
          <Text>{p.title}</Text>
          {p.title !== p.id && <Text slot="description">{p.id}</Text>}
        </Item>
      )}
    </ComboBox>
  );
}
