import './components.scss';
import {
  ActionButton,
  Cell,
  Column,
  Item,
  Picker,
  Row,
  SearchField,
  TableBody,
  TableHeader,
  TableView,
  TabList,
  TabPanels,
  Tabs,
  Text,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import type { SortDescriptor } from '@react-types/shared';
import Data from '@spectrum-icons/workflow/Data';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useMemo, useState, type Key } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { KeyValueList, KeyValueRow } from '../shell/Settings';
import { Link, useNavigate, useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { VOCAB } from '../shell/vocabulary';
import { templateEditorUrl } from '../templates/api';
import {
  COMPONENTS_URL,
  componentUrl,
  filterComponents,
  groupOf,
  groupsOf,
  isHidden,
  locationOf,
  type ComponentDetail,
  type ComponentPolicy,
  type ComponentsClient,
  type ComponentSummary,
  type ComponentUsage,
} from './api';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
const when = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '');
const by = (iso?: string, user?: string) => [when(iso), user].filter(Boolean).join(' · ');

/** The component's picture: its icon file, or two letters on a tile as AEM draws them. */
function ComponentIcon({ c, large }: { c: ComponentSummary; large?: boolean }) {
  const cls = `studio-comp__icon${large ? ' is-large' : ''}${isHidden(c) ? ' is-hidden' : ''}`;
  return c.iconImage ? <img className={cls} src={c.iconImage} alt="" /> : <span className={cls} aria-hidden>{c.abbreviation}</span>;
}

// ------------------------------------------------------------------------------------------------ list

/**
 * AEM's Tools › General › Components: every component of `/apps` and `/libs` (hidden groups too) with its group
 * and resource type; a row opens the component's properties, policies and live usage.
 */
export function ComponentsConsole({ client }: { client: ComponentsClient }) {
  const navigate = useNavigate();
  const [list, setList] = useState<ComponentSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [group, setGroup] = useState('');
  const [location, setLocation] = useState('');
  const [sort, setSort] = useState<SortDescriptor>({ column: 'title', direction: 'ascending' });

  useScreenTitle('Components', `Components | ${VOCAB.product}`);

  const load = useCallback(() => {
    setError(null);
    client.list().then(setList, (e: unknown) => setError(message(e)));
  }, [client]);
  useEffect(load, [load]);

  const groups = useMemo(() => groupsOf(list ?? []), [list]);
  const shown = useMemo(() => {
    const rows = filterComponents(list ?? [], { text, group, location });
    const key = (c: ComponentSummary) => (sort.column === 'group' ? groupOf(c) : sort.column === 'type' ? c.path : c.title);
    const dir = sort.direction === 'descending' ? -1 : 1;
    return [...rows].sort((a, b) => dir * key(a).localeCompare(key(b), undefined, { sensitivity: 'base', numeric: true }));
  }, [list, text, group, location, sort]);

  return (
    <div className="studio-sites studio-comp">
      <ToolBar
        url={COMPONENTS_URL}
        end={
          <>
            {list && <span className="studio-comp__count">{shown.length === list.length ? `${list.length} components` : `${shown.length} of ${list.length}`}</span>}
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet aria-label="Refresh" onPress={load}>
                <Refresh />
              </ActionButton>
              <Tooltip>Read the components again</Tooltip>
            </TooltipTrigger>
          </>
        }
      >
        <SearchField aria-label="Filter components" placeholder="Title, resource type, group" value={text} onChange={setText} width="size-3400" />
        <Picker aria-label="Group" selectedKey={group || 'all'} onSelectionChange={(k) => setGroup(k === 'all' ? '' : String(k))} width="size-2400" isDisabled={!list}>
          {[{ id: 'all', name: 'All groups' }, ...groups.map((g) => ({ id: g, name: g }))].map((g) => (
            <Item key={g.id}>{g.name}</Item>
          ))}
        </Picker>
        <Picker aria-label="Location" selectedKey={location || 'all'} onSelectionChange={(k) => setLocation(k === 'all' ? '' : String(k))} width="size-2000">
          <Item key="all">/apps and /libs</Item>
          <Item key="apps">/apps</Item>
          <Item key="libs">/libs</Item>
        </Picker>
      </ToolBar>
      <main className="studio-cfc__content studio-comp__list">
        {error ? (
          <ErrorState title="Could not read the components">{error}</ErrorState>
        ) : !list ? (
          <LoadingState />
        ) : (
          <TableView
            aria-label="Components"
            density="compact"
            overflowMode="truncate"
            height="100%"
            sortDescriptor={sort}
            onSortChange={setSort}
            onAction={(k: Key) => navigate(componentUrl(String(k)))}
            renderEmptyState={() => <EmptyState title="No component matches">Change the words or the group.</EmptyState>}
          >
            <TableHeader>
              <Column key="icon" width={56} hideHeader>
                Icon
              </Column>
              <Column key="title" isRowHeader allowsSorting width="2fr">
                Title
              </Column>
              <Column key="group" allowsSorting width="1.3fr">
                Group
              </Column>
              <Column key="type" allowsSorting width="3fr">
                Resource type
              </Column>
            </TableHeader>
            <TableBody items={shown}>
              {(c) => (
                <Row key={c.path}>
                  <Cell textValue={c.abbreviation}>
                    <ComponentIcon c={c} />
                  </Cell>
                  <Cell textValue={c.title}>
                    <span className="studio-comp__title">
                      <span>{c.title}</span>
                      {c.container && <span className="studio-comp__tag">Container</span>}
                    </span>
                  </Cell>
                  <Cell textValue={groupOf(c)}>
                    <span className={isHidden(c) ? 'studio-comp__muted' : undefined}>{groupOf(c)}</span>
                  </Cell>
                  <Cell textValue={c.path}>
                    <code className="studio-comp__path">{c.path}</code>
                  </Cell>
                </Row>
              )}
            </TableBody>
          </TableView>
        )}
      </main>
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ one component

interface TabDef {
  id: string;
  label: string;
}

/** A component's details, as AEM's Component Properties: Properties, Policies, Live Usage (and its README). */
export function ComponentDetails({ client, path }: { client: ComponentsClient; path: string }) {
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ComponentDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [policies, setPolicies] = useState<ComponentPolicy[] | null>(null);
  const [usage, setUsage] = useState<ComponentUsage | null>(null);
  const [tab, setTab] = useState<string>('properties');

  useScreenTitle(detail?.title ?? path.split('/').pop() ?? 'Component', `${detail?.title ?? path} | Components | ${VOCAB.product}`);

  useEffect(() => {
    client.component(path).then(setDetail, (e: unknown) => setError(message(e)));
    client.policies(path).then(setPolicies, () => setPolicies([]));
    client.usage(path).then(setUsage, () => setUsage({ pages: [], uses: 0, truncated: false }));
  }, [client, path]);

  const close = () => navigate(COMPONENTS_URL);
  const tabs: TabDef[] = [
    { id: 'properties', label: 'Properties' },
    { id: 'policies', label: policies ? `Policies (${policies.length})` : 'Policies' },
    { id: 'usage', label: usage ? `Live Usage (${usage.pages.length}${usage.truncated ? '+' : ''})` : 'Live Usage' },
    ...(detail?.readme ? [{ id: 'docs', label: 'Documentation' }] : []),
  ];

  return (
    <div className="studio-sites studio-comp">
      <ToolBar
        url={COMPONENTS_URL}
        extra={[{ path: `component:${path}`, title: detail?.title ?? path.split('/').pop() ?? path }]}
        onGo={() => {}}
        onClose={close}
        closeLabel="Close component"
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet onPress={() => navigate(`/bin/browser.html${path}`)}>
              <Data />
              <Text>Repository Browser</Text>
            </ActionButton>
            <Tooltip>The component's nodes: dialog, scripts, edit config</Tooltip>
          </TooltipTrigger>
        }
      />
      <main className="studio-sites__content studio-comp__details">
        {error ? (
          <ErrorState title={`Could not open ${path}`} action={{ label: 'All components', onPress: close }}>
            {error}
          </ErrorState>
        ) : !detail ? (
          <LoadingState />
        ) : (
          <>
            <header className="studio-comp__head">
              <ComponentIcon c={detail} large />
              <div>
                <h1>{detail.title}</h1>
                <code>{detail.path}</code>
              </div>
            </header>
            <Tabs aria-label="Component" items={tabs} selectedKey={tab} onSelectionChange={(k) => setTab(String(k))} UNSAFE_className="studio-comp__tabs">
              <TabList>{(t: TabDef) => <Item key={t.id}>{t.label}</Item>}</TabList>
              <TabPanels>
                {(t: TabDef) => (
                  <Item key={t.id}>
                    {t.id === 'properties' ? (
                      <Properties detail={detail} />
                    ) : t.id === 'policies' ? (
                      <Policies policies={policies} />
                    ) : t.id === 'usage' ? (
                      <Usage usage={usage} />
                    ) : (
                      <pre className="studio-comp__readme">{detail.readme}</pre>
                    )}
                  </Item>
                )}
              </TabPanels>
            </Tabs>
          </>
        )}
      </main>
    </div>
  );
}

function NodeRow({ label, path, none = 'None' }: { label: string; path?: string; none?: string }) {
  return (
    <KeyValueRow label={label}>
      {path ? (
        <Link to={`/bin/browser.html${path}`} className="studio-comp__link">
          {path.split('/').pop()}
        </Link>
      ) : (
        <span className="studio-comp__muted">{none}</span>
      )}
    </KeyValueRow>
  );
}

function Properties({ detail: d }: { detail: ComponentDetail }) {
  return (
    <div className="studio-comp__panel">
      <KeyValueList>
        <KeyValueRow label="Title">{d.title}</KeyValueRow>
        <KeyValueRow label="Icon">
          <span className="studio-comp__iconrow">
            <ComponentIcon c={d} />
            <span className="studio-comp__muted">{d.iconImage ? d.iconImage.split('/').pop() : d.icon ? `cq:icon ${d.icon}` : 'Letters of the title'}</span>
          </span>
        </KeyValueRow>
        <KeyValueRow label="Group">
          {groupOf(d)}
          {isHidden(d) && <span className="studio-comp__muted"> · authors cannot add it</span>}
        </KeyValueRow>
        <KeyValueRow label="Description">{d.description || <span className="studio-comp__muted">None</span>}</KeyValueRow>
        <KeyValueRow label="Resource type">
          <code>{d.resourceType}</code>
          <span className="studio-comp__muted">{` · in /${locationOf(d.path)}`}</span>
        </KeyValueRow>
        <KeyValueRow label="Resource super type">
          {d.superType ? (
            <span className="studio-comp__chain">
              {(d.superTypes ?? []).length > 0
                ? d.superTypes!.map((s, i) => (
                    <span key={s.path}>
                      {i > 0 && <span className="studio-comp__muted"> › </span>}
                      <Link to={componentUrl(s.path)} className="studio-comp__link" title={s.path}>
                        {s.title}
                      </Link>
                    </span>
                  ))
                : <code>{d.superType}</code>}
            </span>
          ) : (
            <span className="studio-comp__muted">None</span>
          )}
        </KeyValueRow>
        <KeyValueRow label="Container">{d.container ? 'Yes: authors put other components in it' : 'No'}</KeyValueRow>
        <NodeRow label="Dialog" path={d.dialog} none={d.superType ? 'Its super type’s' : 'None'} />
        <NodeRow label="Design dialog" path={d.designDialog} />
        <NodeRow label="Edit config" path={d.editConfig} />
        {d.childEditConfig && <NodeRow label="Child edit config" path={d.childEditConfig} />}
        <NodeRow label="Default content" path={d.template} />
        <KeyValueRow label="Scripts">
          {d.scripts && d.scripts.length > 0 ? <code>{d.scripts.join(', ')}</code> : <span className="studio-comp__muted">{d.superType ? 'Its super type’s' : 'None'}</span>}
        </KeyValueRow>
        <KeyValueRow label="Created">{by(d.created, d.createdBy) || <span className="studio-comp__muted">Unknown</span>}</KeyValueRow>
        {d.lastModified && <KeyValueRow label="Modified">{by(d.lastModified, d.lastModifiedBy)}</KeyValueRow>}
      </KeyValueList>
    </div>
  );
}

function Policies({ policies }: { policies: ComponentPolicy[] | null }) {
  if (!policies) return <LoadingState />;
  if (policies.length === 0)
    return (
      <EmptyState title="No policy">
        No configuration has a policy for this component. A template author creates one in the template editor, on the component in the template's structure.
      </EmptyState>
    );
  return (
    <div className="studio-comp__panel">
      <table className="studio-comp__table">
        <thead>
          <tr>
            <th>Policy</th>
            <th>Configuration</th>
            <th>Last modified</th>
            <th>Templates</th>
          </tr>
        </thead>
        <tbody>
          {policies.map((p) => (
            <tr key={p.path}>
              <td>
                <Link to={`/bin/browser.html${p.path}`} className="studio-comp__link" title={p.path}>
                  {p.title}
                </Link>
                {p.description && <div className="studio-comp__muted">{p.description}</div>}
              </td>
              <td>{p.confTitle ?? p.conf}</td>
              <td>{by(p.lastModified, p.lastModifiedBy) || <span className="studio-comp__muted">Unknown</span>}</td>
              <td>
                {p.templates.length === 0 ? (
                  <span className="studio-comp__muted">No template uses it</span>
                ) : (
                  <ul className="studio-comp__list-plain">
                    {p.templates.map((t) => (
                      <li key={t.path}>
                        <Link to={templateEditorUrl(t.path)} className="studio-comp__link" title={t.path}>
                          {t.title}
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Usage({ usage }: { usage: ComponentUsage | null }) {
  if (!usage) return <LoadingState />;
  if (usage.pages.length === 0) return <EmptyState title="Not used">No page under /content holds this component.</EmptyState>;
  return (
    <div className="studio-comp__panel">
      <p className="studio-comp__muted">
        {`${usage.uses} instance${usage.uses === 1 ? '' : 's'} on ${usage.pages.length} page${usage.pages.length === 1 ? '' : 's'}`}
        {usage.truncated ? '; the search stopped there, there are more' : ''}
      </p>
      <table className="studio-comp__table">
        <thead>
          <tr>
            <th>Title</th>
            <th>Path</th>
            <th className="is-num">Instances</th>
          </tr>
        </thead>
        <tbody>
          {usage.pages.map((p) => (
            <tr key={p.path}>
              <td>
                <Link to={`/editor.html${p.path}.html`} className="studio-comp__link">
                  {p.title}
                </Link>
              </td>
              <td>
                <Link to={`/sites.html${p.path.slice(0, p.path.lastIndexOf('/')) || '/content'}`} className="studio-comp__path" title="Show in Pages">
                  {p.path}
                </Link>
              </td>
              <td className="is-num">{p.count}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
