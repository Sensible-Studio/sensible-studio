import { ActionButton, Content, DialogTrigger, Heading, Item, Picker, SearchField } from '@adobe/react-spectrum';
import InfoOutline from '@spectrum-icons/workflow/InfoOutline';
import { useMemo, useState, type Key } from 'react';
import type { ComponentInfo } from '../api/StudioClient';
import { endDrag, startDrag } from '../overlay/dragPayload';
import { ComponentIcon } from './ComponentIcon';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  components: ComponentInfo[];
}

const ALL = '__all';

/** Components allowed somewhere on the page; drag one onto the page to add it. */
export function ComponentsPanel({ components: all }: Props) {
  // as AEM's component browser: groups starting with `.` (`.hidden`) are not offered
  const components = useMemo(() => all.filter((c) => !c.group?.startsWith('.')), [all]);
  const [filter, setFilter] = useState('');
  const [group, setGroup] = useState<string>(ALL);
  const groups = useMemo(() => [...new Set(components.map((c) => c.group))].sort(), [components]);
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return components
      .filter((c) => group === ALL || c.group === group)
      .filter((c) => !q || c.title.toLowerCase().includes(q) || c.resourceType.toLowerCase().includes(q))
      .sort((a, b) => a.title.localeCompare(b.title));
  }, [components, filter, group]);

  return (
    <div className="studio-panel">
      <div className="studio-panel__controls">
        <SearchField aria-label="Filter" placeholder="Filter" width="100%" value={filter} onChange={setFilter} />
        <Picker aria-label="Group" placeholder="Group" width="100%" selectedKey={group} onSelectionChange={(k: Key | null) => setGroup(String(k ?? ALL))}>
          {[{ id: ALL, name: 'All groups' }, ...groups.map((g) => ({ id: g, name: g }))].map((g) => (
            <Item key={g.id}>{g.name}</Item>
          ))}
        </Picker>
      </div>
      {shown.length === 0 ? (
        <p className="studio-panel__empty">{components.length ? 'No components match.' : 'No component is allowed on this page.'}</p>
      ) : (
        <ul className="studio-complist" aria-label="Components">
          {shown.map((c) => (
            <li
              key={c.resourceType}
              className="studio-complist__item"
              draggable
              onDragStart={(e) => startDrag(e, { kind: 'component', component: c }, c.title)}
              onDragEnd={endDrag}
            >
              <ComponentIcon component={c} abbreviation={abbreviation(c)} />
              <span className="studio-complist__text">
                <span className="studio-complist__title">{c.title}</span>
                <span className="studio-complist__group">{c.group}</span>
              </span>
              <ComponentInfoButton component={c} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The (i) of AEM's component browser: the component's description, group and type. */
function ComponentInfoButton({ component: c }: { component: ComponentInfo }) {
  return (
    <span className="studio-complist__info" onDragStart={(e) => e.preventDefault()}>
      <DialogTrigger type="popover" placement="end top">
        <ActionButton isQuiet aria-label={`Show description for ${c.title}`} UNSAFE_className="studio-complist__info-button">
          <InfoOutline size="XS" />
        </ActionButton>
        <StudioDialog size="S">
          <Heading>{c.title}</Heading>
          <Content>
            <div className="studio-compinfo">
              <p className="studio-compinfo__description">{c.description?.trim() || 'No description.'}</p>
              <dl>
                <dt>Group</dt>
                <dd>{c.group}</dd>
                <dt>Resource type</dt>
                <dd>
                  <code>{c.resourceType}</code>
                </dd>
                {c.path && (
                  <>
                    <dt>Path</dt>
                    <dd>
                      <code>{c.path}</code>
                    </dd>
                  </>
                )}
              </dl>
            </div>
          </Content>
        </StudioDialog>
      </DialogTrigger>
    </span>
  );
}

export function abbreviation(c: Pick<ComponentInfo, 'abbreviation' | 'title'>): string {
  if (c.abbreviation) return c.abbreviation.slice(0, 3);
  const words = c.title.split(/[\s\-_]+/).filter(Boolean);
  return words.length > 1 ? (words[0][0] + words[1][0]).toUpperCase() : c.title.slice(0, 2).replace(/^./, (ch) => ch.toUpperCase());
}
