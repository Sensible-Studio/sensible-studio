import { ActionButton, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import BoxExport from '@spectrum-icons/workflow/BoxExport';
import Brush from '@spectrum-icons/workflow/Brush';
import Settings from '@spectrum-icons/workflow/Settings';
import Edit from '@spectrum-icons/workflow/Edit';
import Group from '@spectrum-icons/workflow/Group';
import Launch from '@spectrum-icons/workflow/Launch';
import Target from '@spectrum-icons/workflow/Target';
import RemoveCircle from '@spectrum-icons/workflow/RemoveCircle';
import Copy from '@spectrum-icons/workflow/Copy';
import Cut from '@spectrum-icons/workflow/Cut';
import Delete from '@spectrum-icons/workflow/Delete';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import LockOpen from '@spectrum-icons/workflow/LockOpen';
import Paste from '@spectrum-icons/workflow/Paste';
import Revert from '@spectrum-icons/workflow/Revert';
import SelectContainer from '@spectrum-icons/workflow/SelectContainer';
import ViewColumn from '@spectrum-icons/workflow/ViewColumn';
import Visibility from '@spectrum-icons/workflow/Visibility';
import VisibilityOff from '@spectrum-icons/workflow/VisibilityOff';
import Wrench from '@spectrum-icons/workflow/Wrench';
import MultipleCheck from '@spectrum-icons/workflow/MultipleCheck';
import TextWrap from '@spectrum-icons/workflow/ArrowDown';
import type { ReactElement, ReactNode } from 'react';

export type ToolbarAction =
  | 'edit'
  | 'editXf'
  | 'configure'
  | 'styles'
  | 'panelSelect'
  | 'copy'
  | 'cut'
  | 'delete'
  | 'paste'
  | 'insert'
  | 'group'
  | 'xfconvert'
  | 'rollout'
  | 'target'
  | 'untarget'
  | 'parent'
  | 'layout'
  | 'newline'
  | 'hide'
  | 'unhide'
  | 'reset'
  | 'cancelInheritance'
  | 'reenableInheritance'
  | 'policy'
  | 'lockStructure'
  | 'unlockStructure';

const BUTTONS: Record<ToolbarAction, { label: string; icon: ReactElement }> = {
  edit: { label: 'Edit', icon: <Edit /> },
  // the Experience Fragment's own action (Core's `editInNewTab`): opens the fragment's variation in the editor
  editXf: { label: 'Edit', icon: <Edit /> },
  configure: { label: 'Configure', icon: <Wrench /> },
  styles: { label: 'Styles', icon: <Brush /> },
  // Core's panel containers (Tabs, Accordion, Carousel): which panel the page shows, and their order
  panelSelect: { label: 'Select panel', icon: <MultipleCheck /> },
  copy: { label: 'Copy', icon: <Copy /> },
  cut: { label: 'Cut', icon: <Cut /> },
  delete: { label: 'Delete', icon: <Delete /> },
  paste: { label: 'Paste', icon: <Paste /> },
  insert: { label: 'Insert component', icon: <Add /> },
  group: { label: 'Group', icon: <Group /> },
  xfconvert: { label: 'Convert to experience fragment variant', icon: <BoxExport /> },
  rollout: { label: 'Sync changes', icon: <Launch /> },
  // Targeting mode, like AEM: the component gets an experience per audience
  target: { label: 'Personalize', icon: <Target /> },
  untarget: { label: 'Remove personalization', icon: <RemoveCircle /> },
  parent: { label: 'Parent', icon: <SelectContainer /> },
  layout: { label: 'Layout', icon: <ViewColumn /> },
  newline: { label: 'Float to new line', icon: <TextWrap /> },
  hide: { label: 'Hide component', icon: <VisibilityOff /> },
  unhide: { label: 'Show hidden components', icon: <Visibility /> },
  reset: { label: 'Revert breakpoint layout', icon: <Revert /> },
  // MSM, like AEM: the lock is closed while the component is inherited from the blueprint
  cancelInheritance: { label: 'Cancel inheritance', icon: <LockClosed /> },
  reenableInheritance: { label: 'Re-enable inheritance', icon: <LockOpen /> },
  // template editor, like AEM: the lock shows the component's state in the structure
  policy: { label: 'Configuration', icon: <Settings /> },
  lockStructure: { label: 'Lock structure component', icon: <LockOpen /> },
  unlockStructure: { label: 'Unlock structure component', icon: <LockClosed /> },
};

interface Props {
  actions: ToolbarAction[];
  onAction(action: ToolbarAction): void;
  /** Toggle buttons that are on (e.g. `newline`). */
  active?: ToolbarAction[];
  /** Actions rendered by their own component (e.g. `styles`: a popover). */
  render?: Partial<Record<ToolbarAction, () => ReactNode>>;
}

/** Keys the editor handles for the selected component (App.tsx), shown in the tooltips. */
const KEYS: Partial<Record<ToolbarAction, string>> = { copy: '⌘C', cut: '⌘X', paste: '⌘V', delete: '⌫', insert: 'i' };

/**
 * The component's actions, like AEM's: every action an icon of its own, all on the bar, its name (and key) in the
 * tooltip. Authors use these all day and know them by their icon; a word next to each, or a menu to open first,
 * only slows them down.
 */
export function EditToolbar({ actions, onAction, active = [], render = {} }: Props) {
  return (
    <div className="studio-toolbar studio-toolbar--icons" role="toolbar" aria-label="Component actions">
      {actions.map((a) => {
        const custom = render[a];
        if (custom) return <span key={a}>{custom()}</span>;
        const tip = KEYS[a] ? `${BUTTONS[a].label} (${KEYS[a]})` : BUTTONS[a].label;
        return (
          <TooltipTrigger key={a} delay={300}>
            <ActionButton
              isQuiet
              aria-label={BUTTONS[a].label}
              aria-pressed={active.includes(a) ? true : undefined}
              UNSAFE_className={active.includes(a) ? 'is-active' : undefined}
              onPress={() => onAction(a)}
            >
              {BUTTONS[a].icon}
            </ActionButton>
            <Tooltip>{tip}</Tooltip>
          </TooltipTrigger>
        );
      })}
    </div>
  );
}
