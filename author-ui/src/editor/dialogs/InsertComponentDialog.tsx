import { Content, Divider, Heading, Item, ListBox, SearchField, Section, Text, useDialogContainer } from '@adobe/react-spectrum';
import { useMemo, useState } from 'react';
import type { ComponentInfo } from '../api/StudioClient';
import { ComponentIcon } from '../panels/ComponentIcon';
import { abbreviation } from '../panels/ComponentsPanel';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  components: ComponentInfo[];
  onPick(component: ComponentInfo): void;
}

/** "Insert New Component": the components the target container allows, grouped, with a filter. */
export function InsertComponentDialog({ components, onPick }: Props) {
  const container = useDialogContainer();
  const [filter, setFilter] = useState('');
  const sections = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const byGroup = new Map<string, ComponentInfo[]>();
    for (const c of components) {
      if (q && !c.title.toLowerCase().includes(q) && !c.group.toLowerCase().includes(q)) continue;
      byGroup.set(c.group, [...(byGroup.get(c.group) ?? []), c]);
    }
    return [...byGroup.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([group, items]) => ({ group, items: items.sort((a, b) => a.title.localeCompare(b.title)) }));
  }, [components, filter]);

  return (
    <StudioDialog size="M" isDismissable>
      <Heading>Insert New Component</Heading>
      <Divider />
      <Content>
        <SearchField aria-label="Filter components" placeholder="Enter Keyword" width="100%" value={filter} onChange={setFilter} autoFocus marginBottom="size-150" />
        {sections.length === 0 ? (
          <Text>{components.length ? 'No component matches.' : 'This container does not allow any component.'}</Text>
        ) : (
          <ListBox
            aria-label="Components"
            items={sections}
            selectionMode="single"
            maxHeight="size-6000"
            onSelectionChange={(keys) => {
              const key = keys === 'all' ? undefined : [...keys][0];
              const c = components.find((x) => x.resourceType === key);
              if (c) {
                container.dismiss();
                onPick(c);
              }
            }}
          >
            {(s) => (
              <Section key={s.group} title={s.group} items={s.items}>
                {(c) => (
                  <Item key={c.resourceType} textValue={c.title}>
                    <Text>
                      <ComponentIcon component={c} abbreviation={abbreviation(c)} /> {c.title}
                    </Text>
                    {c.description ? <Text slot="description">{c.description}</Text> : null}
                  </Item>
                )}
              </Section>
            )}
          </ListBox>
        )}
      </Content>
    </StudioDialog>
  );
}
