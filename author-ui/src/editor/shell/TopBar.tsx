import { ActionButton, Button, Item, Menu, MenuTrigger, Section, Text, ToggleButton, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import Devices from '@spectrum-icons/workflow/Devices';
import UserActivity from '@spectrum-icons/workflow/UserActivity';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import Properties from '@spectrum-icons/workflow/Properties';
import RailLeft from '@spectrum-icons/workflow/RailLeft';
import NoteAdd from '@spectrum-icons/workflow/NoteAdd';
import Redo from '@spectrum-icons/workflow/Redo';
import Undo from '@spectrum-icons/workflow/Undo';
import type { Key } from 'react';
import type { PageInfo } from '../api/StudioClient';
import { WindowControls } from '../../shell/CloseButton';

export type EditorMode = 'edit' | 'layout' | 'developer' | 'targeting' | 'timewarp' | 'preview';

export type PageMenuAction =
  | 'properties'
  | 'editTemplate'
  | 'lock'
  | 'unlock'
  | 'published'
  | 'reload'
  | 'start'
  | 'msm'
  | 'sync'
  | 'workflow'
  | 'publish'
  | 'unpublish'
  | 'createLaunch'
  | 'promoteLaunch'
  | 'openLaunch'
  | 'openLaunchSource'
  | 'initialProperties'
  | 'pagePolicy'
  | 'enableTemplate'
  | 'disableTemplate';

/** The parts of a template the template editor switches between (Layout works on the structure). */
export type TemplateMode = 'structure' | 'initial' | 'layout';

interface Props {
  page: PageInfo | null;
  pagePath: string;
  mode: EditorMode;
  onMode(mode: EditorMode): void;
  sidePanelOpen: boolean;
  onToggleSidePanel(): void;
  /** Close the editor: back where the page was opened from, or close its tab. */
  onClose?(): void;
  emulatorOpen: boolean;
  onToggleEmulator(): void;
  /** Launches: the launch this page is in (then Promote Launch), or the launches made from it (Open). */
  launch?: { title: string; source?: string } | null;
  launchesOfPage?: Array<{ path: string; title: string }>;
  /** A Publish Later / Unpublish Later waiting for its time. */
  scheduledPublication?: { action: string; when: string } | null;
  /** Preview: the ContextHub bar (personas, stores) that drives targeted components. */
  contextHubOpen?: boolean;
  onToggleContextHub?(): void;
  onPageAction(action: PageMenuAction, argument?: string): void;
  /** Number of annotations on the page; the Annotations button shows it. */
  annotations?: number;
  /** The Annotations button: adds a note. */
  onAnnotate?(): void;
  /** Edit mode shows the notes over the page; the crossed-out note next to Annotations hides and shows them. */
  annotationsShown?: boolean;
  onToggleAnnotations?(): void;
  canUndo?: boolean;
  canRedo?: boolean;
  onUndo?(): void;
  onRedo?(): void;
  isBusy?: boolean;
  /** MSM entries of the page menu: the page is a live copy (`sync`: it can be synchronized) or a blueprint */
  msm?: { label: string; sync?: boolean };
  /** The template editor: the template, the part shown, and its own modes and page menu, like AEM's. */
  template?: { title: string; part: 'structure' | 'initial'; status: string; lastModified?: string; lastModifiedBy?: string };
  onTemplateMode?(mode: TemplateMode): void;
}

const MODE_LABEL: Record<Exclude<EditorMode, 'preview'>, string> = { edit: 'Edit', layout: 'Layout', developer: 'Developer', targeting: 'Personalize', timewarp: 'History' };
const TEMPLATE_MODE_LABEL: Record<TemplateMode, string> = { structure: 'Structure', initial: 'Initial Content', layout: 'Layout' };

/** Canvas-first command bar: page identity, task controls, view controls and one leading action. */
export function TopBar(props: Props) {
  const { page, pagePath, mode, onMode, sidePanelOpen, onToggleSidePanel, onClose, emulatorOpen, onToggleEmulator, launch, launchesOfPage, scheduledPublication, contextHubOpen, onToggleContextHub, onPageAction, msm, canUndo, canRedo, onUndo, onRedo, isBusy, template, onTemplateMode, annotations, onAnnotate, annotationsShown, onToggleAnnotations } =
    props;
  const authoringMode = mode === 'preview' ? 'edit' : mode;
  const title = template ? template.title : (page?.title ?? pagePath.split('/').pop());
  const templateMode: TemplateMode = mode === 'layout' ? 'layout' : (template?.part ?? 'structure');
  const modeLabel = template ? TEMPLATE_MODE_LABEL[templateMode] : MODE_LABEL[authoringMode];
  const publication = page?.published ? 'Published' : page?.lastReplicationAction === 'Activate' ? 'Modified' : 'Draft';

  return (
    <header className="studio-topbar">
      <div className="studio-topbar__navigation" aria-label="Library">
        <ToggleButton isQuiet isSelected={sidePanelOpen} onChange={onToggleSidePanel} aria-label="Content library">
          <RailLeft />
          <Text>Library</Text>
        </ToggleButton>
      </div>

      <div className="studio-topbar__identity">
        <MenuTrigger>
          <ActionButton isQuiet aria-label="Page menu">
            <Properties />
            <Text>Page</Text>
          </ActionButton>
          <Menu onAction={(k: Key) => (String(k).startsWith('openLaunch:') ? onPageAction('openLaunch', String(k).slice('openLaunch:'.length)) : onPageAction(k as PageMenuAction))} disabledKeys={page?.locked && !page.canUnlock ? ['unlock'] : []}>
            {template ? [
              <Section key="template" title={modifiedLine(template)}>
                <Item key="initialProperties">Initial Page Properties</Item>
                <Item key="pagePolicy">Page Configuration</Item>
                {template.status === 'enabled' ? (
                  <Item key="disableTemplate">Disable Template</Item>
                ) : (
                  <Item key="enableTemplate">Enable Template</Item>
                )}
                <Item key="reload">Reload Page</Item>
              </Section>,
            ] : [
              <Section key="page" title={modifiedLine(page)}>
                {[
                  <Item key="properties">Open Properties</Item>,
                  page?.locked ? (
                    <Item key="unlock" textValue="Unlock Page">
                      <Text>Unlock Page</Text>
                      <Text slot="description">Locked by {page.lockOwner}</Text>
                    </Item>
                  ) : (
                    <Item key="lock">Lock Page</Item>
                  ),
                  ...(launch ? [] : [<Item key="publish" textValue="Publish Page">
                    <Text>Publish Page</Text>
                    <Text slot="description">
                      {scheduledPublication
                        ? `${scheduledPublication.action === 'Deactivate' ? 'Unpublish' : 'Publish'} scheduled for ${new Date(scheduledPublication.when).toLocaleString()}`
                        : page?.published
                          ? `Published ${page.lastReplicated ? relativeTime(new Date(page.lastReplicated)) : ''}`
                          : page?.lastReplicationAction === 'Activate'
                            ? 'Modified since it was published'
                            : 'Not published'}
                    </Text>
                  </Item>]),
                  ...(page?.lastReplicationAction === 'Activate' && !launch ? [<Item key="unpublish">Unpublish Page</Item>] : []),
                  // AEM: the page's editable template opens in the template editor (after Unpublish, before View as Published)
                  ...(page?.editableTemplate && page.template ? [<Item key="editTemplate">Edit Template</Item>] : []),
                  <Item key="published">View as Published</Item>,
                  <Item key="reload">Reload Page</Item>,
                  <Item key="workflow">Start Flow</Item>,
                ]}
              </Section>,
              <Section key="launches" title={launch ? `Release: ${launch.title}` : 'Releases'}>
                {launch
                  ? [<Item key="promoteLaunch">Go live</Item>, ...(launch.source ? [<Item key="openLaunchSource">Open Source Page</Item>] : [])]
                  : [
                      <Item key="createLaunch">Create Release</Item>,
                      ...(launchesOfPage ?? []).map((l) => (
                        <Item key={`openLaunch:${l.path}`} textValue={`Open in release ${l.title}`}>
                          Open in launch “{l.title}”
                        </Item>
                      )),
                    ]}
              </Section>,
              ...(msm
                ? [
                    <Section key="msm" title="Sync">
                      {[<Item key="msm">{msm.label}</Item>, ...(msm.sync ? [<Item key="sync">Synchronize Page</Item>] : [])]}
                    </Section>,
                  ]
                : []),
              <Section key="studio" title="Sensible Studio">
                <Item key="start">Show in Pages</Item>
              </Section>,
            ]}
          </Menu>
        </MenuTrigger>
        <div className="studio-topbar__page">
          <h1 className="studio-topbar__title" title={pagePath}>{title}</h1>
          <div className="studio-topbar__state" aria-label="Page status">
            <span className={`studio-state studio-state--${isBusy ? 'saving' : 'saved'}`} aria-live="polite">{isBusy ? 'Saving…' : 'Saved'}</span>
            {!template && <span className={`studio-state studio-state--${publication.toLowerCase()}`}>{publication === 'Modified' ? 'Unpublished changes' : publication}</span>}
            {page?.locked && !template && (
              <button type="button" className="studio-state studio-state--locked" onClick={() => onPageAction('unlock')} disabled={!page.canUnlock}>
                <LockClosed size="S" /> Locked{page.lockOwner ? ` by ${page.lockOwner}` : ''}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="studio-topbar__history" aria-label="History">
        {mode !== 'preview' && (
          <>
            <TooltipTrigger delay={500}>
              <ActionButton isQuiet isDisabled={!canUndo} onPress={onUndo} aria-label="Undo">
                <Undo />
              </ActionButton>
              <Tooltip>Undo</Tooltip>
            </TooltipTrigger>
            <TooltipTrigger delay={500}>
              <ActionButton isQuiet isDisabled={!canRedo} onPress={onRedo} aria-label="Redo">
                <Redo />
              </ActionButton>
              <Tooltip>Redo</Tooltip>
            </TooltipTrigger>
          </>
        )}
      </div>

      <div className="studio-topbar__view" aria-label="View and mode">
        <ToggleButton isQuiet isSelected={emulatorOpen} onChange={onToggleEmulator} aria-label="Viewport controls">
          <Devices />
          <Text>Viewport</Text>
        </ToggleButton>
        {mode === 'preview' && onToggleContextHub && !template && (
          <ToggleButton isQuiet isSelected={!!contextHubOpen} onChange={onToggleContextHub} aria-label="Visitor simulator">
            <UserActivity />
            <Text>Visitor</Text>
          </ToggleButton>
        )}
        <div className="studio-mode" aria-label="Editor mode">
          <ActionButton
            isQuiet
            onPress={() => onMode(authoringMode)}
            aria-label={`${modeLabel} mode`}
            UNSAFE_className={`studio-mode__current${mode !== 'preview' ? ' is-current' : ''}`}
          >
            <Text>{modeLabel}</Text>
          </ActionButton>
          <MenuTrigger align="end">
            <ActionButton isQuiet aria-label="Select another mode">
              <ChevronDown />
            </ActionButton>
            {template ? (
              <Menu selectionMode="single" selectedKeys={[templateMode]} onAction={(k: Key) => onTemplateMode?.(k as TemplateMode)}>
                <Item key="structure">Structure</Item>
                <Item key="initial">Initial Content</Item>
                <Item key="layout">Layout</Item>
              </Menu>
            ) : (
              <Menu selectionMode="single" selectedKeys={[authoringMode]} onAction={(k: Key) => onMode(k as EditorMode)}>
                <Item key="edit">Edit</Item>
                <Item key="layout">Layout</Item>
                <Item key="developer">Developer</Item>
                <Item key="targeting">Personalize</Item>
                <Item key="timewarp">History</Item>
              </Menu>
            )}
          </MenuTrigger>
          <ActionButton isQuiet onPress={() => onMode(mode === 'preview' ? 'edit' : 'preview')} UNSAFE_className={mode === 'preview' ? 'is-current' : undefined}>
            <Text>Preview</Text>
          </ActionButton>
        </div>
      </div>

      <div className="studio-topbar__actions">
        {onToggleAnnotations && !template && !!annotations && mode === 'edit' && (
          <TooltipTrigger delay={500}>
            <ActionButton
              isQuiet
              aria-label={annotationsShown ? 'Hide annotations' : 'Show annotations'}
              aria-pressed={!annotationsShown}
              onPress={onToggleAnnotations}
              UNSAFE_className={`studio-topbar__icon-button studio-frame-toggle${annotationsShown ? '' : ' is-pressed'}`}
            >
              <span className="studio-icon-crossed" aria-hidden="true">
                <NoteAdd />
              </span>
            </ActionButton>
            <Tooltip>{annotationsShown ? 'Hide annotations' : 'Show annotations'}</Tooltip>
          </TooltipTrigger>
        )}
        {onAnnotate && !template && mode === 'edit' && (
          <TooltipTrigger delay={500}>
            <ActionButton isQuiet aria-label="Add annotation" onPress={onAnnotate} UNSAFE_className="studio-topbar__annotate">
              <NoteAdd />
              {annotations ? <span className="studio-topbar__badge">{annotations}</span> : null}
            </ActionButton>
            <Tooltip>Add annotation (to the selected component)</Tooltip>
          </TooltipTrigger>
        )}
        {!template && !launch && (
          <Button variant="accent" isDisabled={page?.canPublish === false} onPress={() => onPageAction('publish')}>
            Publish
          </Button>
        )}
        {/* full screen always, then close when the editor was opened from somewhere */}
        <WindowControls onClose={onClose} closeLabel="Close editor" />
      </div>
    </header>
  );
}

function modifiedLine(page: Pick<PageInfo, 'lastModified' | 'lastModifiedBy'> | null): string {
  if (!page) return 'Page';
  const when = page.lastModified ? relativeTime(new Date(page.lastModified)) : null;
  return when ? `Modified ${when}${page.lastModifiedBy ? ` by ${page.lastModifiedBy}` : ''}` : 'Not modified yet';
}

export function relativeTime(date: Date, now = new Date()): string {
  const s = Math.round((now.getTime() - date.getTime()) / 1000);
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 31536000],
    ['month', 2592000],
    ['day', 86400],
    ['hour', 3600],
    ['minute', 60],
  ];
  const fmt = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  for (const [unit, secs] of units) if (Math.abs(s) >= secs) return fmt.format(-Math.floor(s / secs), unit);
  return 'just now';
}
