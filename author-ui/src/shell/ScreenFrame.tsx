import type { ReactNode } from 'react';
import { WindowControls } from './CloseButton';

interface Props {
  /** Left-side navigation, insertion, filter, or structure control. */
  navigation?: ReactNode;
  /** Location and subject identity. */
  identity: ReactNode;
  /** Screen-specific task and view controls. */
  tools?: ReactNode;
  /** Secondary actions, status, and feedback. */
  actions?: ReactNode;
  /** The screen's single primary action. */
  primary?: ReactNode;
  /** Document screens close back to their origin; consoles omit this. */
  onClose?: () => void;
  closeLabel?: string;
  windowControlsDisabled?: boolean;
  className?: string;
}

/**
 * Stable top frame shared by consoles and document screens. It establishes placement only; each
 * screen keeps ownership of its actions, data, shortcuts, and navigation.
 */
export function ScreenFrame({ navigation, identity, tools, actions, primary, onClose, closeLabel, windowControlsDisabled, className }: Props) {
  return (
    <header className={`studio-screenframe${className ? ` ${className}` : ''}`}>
      {navigation && <div className="studio-screenframe__navigation">{navigation}</div>}
      <div className="studio-screenframe__identity">{identity}</div>
      {tools && <div className="studio-screenframe__tools">{tools}</div>}
      {actions && <div className="studio-screenframe__actions">{actions}</div>}
      {primary && <div className="studio-screenframe__primary">{primary}</div>}
      <div className="studio-screenframe__window">
        <WindowControls onClose={onClose} closeLabel={closeLabel} isDisabled={windowControlsDisabled} />
      </div>
    </header>
  );
}
