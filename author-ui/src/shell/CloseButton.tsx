import Close from '@spectrum-icons/workflow/Close';
import FullScreen from '@spectrum-icons/workflow/FullScreen';
import FullScreenExit from '@spectrum-icons/workflow/FullScreenExit';
import { useShell } from './ShellContext';

/**
 * The controls every document screen ends its top bar with: in the shell, a full screen toggle (the
 * screen alone / the bar with the tabs back, the screen staying open), then the × that closes the
 * screen — what it does is the screen's `leave` (back where the author came from, or its console).
 * Every screen opened from a list (the page editor, a fragment, a model, an asset, a properties form,
 * a wizard…) ends with this, so they all open, grow and close the same way.
 */
/** The full screen toggle alone: at the end of a console's bar (a console has no ×, it is a place to stay). */
export function FullScreenButton() {
  const shell = useShell();
  if (!shell) return null;
  const label = shell.full ? 'Exit full screen' : 'Full screen';
  return (
    <button
      type="button"
      className="studio-window-control"
      aria-label={label}
      title={shell.full ? 'Exit full screen: the tabs come back, this stays open' : 'Full screen: this alone, without the tabs'}
      onClick={shell.full ? shell.exitFull : shell.enterFull}
    >
      {shell.full ? <FullScreenExit /> : <FullScreen />}
    </button>
  );
}

export function CloseButton({ onPress, label = 'Close', isDisabled }: { onPress(): void; label?: string; isDisabled?: boolean }) {
  return (
    <button type="button" className="studio-window-control" aria-label={label} title={label} onClick={onPress} disabled={isDisabled}>
      <Close />
    </button>
  );
}

/** The explicit window-control cluster used by every screen frame. */
export function WindowControls({ onClose, closeLabel = 'Close', isDisabled }: { onClose?: () => void; closeLabel?: string; isDisabled?: boolean }) {
  return <span className="studio-closebtn"><FullScreenButton />{onClose && <CloseButton onPress={onClose} label={closeLabel} isDisabled={isDisabled} />}</span>;
}
