import type { ReactNode } from 'react';
import { TOOLS, type Tool, type ToolCategory } from '../start/navigation';
import { ActionButton, Item, Menu, MenuTrigger, Section, Text, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import { PathCrumbs, type Crumb } from './PathCrumbs';
import { useNavigate } from './ShellContext';
import { VOCAB } from './vocabulary';
import { ScreenFrame } from './ScreenFrame';
import { WindowControls } from './CloseButton';

const SYSTEM = '/studio/start.html#tools';

/** The System group a tool belongs to, by the URL it is reached at. */
export function toolOf(url: string): { group: ToolCategory; tool: Tool } | null {
  const path = url.split(/[?#]/)[0];
  for (const group of TOOLS) {
    for (const tool of group.tools) {
      const t = tool.url.split(/[?#]/)[0];
      if (path === t || path.startsWith(`${t}/`) || path.startsWith(`${t}.`)) return { group, tool };
    }
  }
  return null;
}

/**
 * The breadcrumb of a System tool: Home › System › <group> › <tool>, the way the tool is reached from
 * Home, then whatever the console adds (`extra`: a configuration, a job, a page). Home, System and the
 * group open the Home screen at that place; the tool opens its console; the extra crumbs go to `onGo`.
 * The Tools menu after the path lists the group's other tools (Users › Groups, Permissions…): the shortcuts
 * between the settings of one family, without a row of tabs.
 */
export function ToolCrumbs({ url, title, extra = [], onGo, standaloneControls = true }: { url: string; title?: string; extra?: Crumb[]; onGo?(path: string): void; standaloneControls?: boolean }) {
  const navigate = useNavigate();
  const found = toolOf(url);
  const crumbs: Crumb[] = [
    { path: SYSTEM, title: VOCAB.system },
    ...(found ? [{ path: `${SYSTEM}/${found.group.id}`, title: found.group.title }] : []),
    { path: url, title: title ?? found?.tool.title ?? url },
    ...extra,
  ];
  const go = (path: string) => {
    if (path.startsWith(SYSTEM) || path === url) navigate(path);
    else onGo?.(path);
  };
  return (
    <div className="studio-toolcrumbs">
      <PathCrumbs crumbs={crumbs} onGo={go} />
      {found && found.group.tools.length > 1 && (
        <MenuTrigger align="start">
          <TooltipTrigger delay={600}>
            <ActionButton isQuiet aria-label="Tools" UNSAFE_className="studio-toolcrumbs__more">
              <Text>Tools</Text>
              <ChevronDown size="S" />
            </ActionButton>
            <Tooltip>Tools in {found.group.title}</Tooltip>
          </TooltipTrigger>
          <Menu aria-label={found.group.title} selectionMode="single" selectedKeys={[found.tool.url]} onAction={(k) => k !== found.tool.url && navigate(String(k))} UNSAFE_className="studio-toolcrumbs__menu">
            <Section title={found.group.title}>
              {found.group.tools.map((t) => (
                <Item key={t.url} textValue={t.title}>
                  <Text>{t.title}</Text>
                  <Text slot="description">{t.description}</Text>
                </Item>
              ))}
            </Section>
          </Menu>
        </MenuTrigger>
      )}
      {standaloneControls && <WindowControls onClose={() => navigate(SYSTEM)} closeLabel="Close settings" />}
    </div>
  );
}

/**
 * The bar of a System tool, in one piece: the breadcrumb (Home › System › group › tool › `extra`) with
 * the group's other tools behind its arrow, the tool's own view switch (`children`) in the centre, the
 * actions (`end`) at the right.
 * Every tool console uses this layout; the ones built before it spell the same classes out.
 */
export function ToolBar({ url, title, extra, onGo, children, end, primary, onClose, closeLabel, closable = true }: { url: string; title?: string; extra?: Crumb[]; onGo?(path: string): void; children?: ReactNode; end?: ReactNode; primary?: ReactNode; onClose?: () => void; closeLabel?: string; closable?: boolean }) {
  const navigate = useNavigate();
  return (
    <ScreenFrame
      identity={<ToolCrumbs url={url} title={title} extra={extra} onGo={onGo} standaloneControls={false} />}
      tools={children}
      actions={end}
      primary={primary}
      onClose={closable ? onClose ?? (() => navigate(SYSTEM)) : undefined}
      closeLabel={closeLabel ?? 'Close settings'}
    />
  );
}
