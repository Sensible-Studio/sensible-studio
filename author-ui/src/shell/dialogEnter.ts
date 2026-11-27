/**
 * Enter in a one-line field of a dialog does what its main button does (Create, Save, Start…), as forms do everywhere.
 * A field that uses Enter itself (a tag field, a search) keeps it: such handlers stop the key before it gets here.
 * Destructive dialogs are left alone: deleting takes a deliberate click.
 */
export function primaryOf(dialog: Element): HTMLButtonElement | null {
  const buttons = [...dialog.querySelectorAll<HTMLButtonElement>('button[data-variant="accent"], button[data-variant="cta"]')];
  // the dialog's own footer, not an accent button somewhere in its content
  const inFooter = buttons.filter((b) => b.closest('[class*="spectrum-Dialog-buttonGroup"], [class*="spectrum-ButtonGroup"], .studio-dialog-standard__footer'));
  const b = (inFooter.length ? inFooter : buttons).pop() ?? null;
  return b && !b.disabled && b.getAttribute('aria-disabled') !== 'true' ? b : null;
}

export function wantsSubmit(e: KeyboardEvent): HTMLButtonElement | null {
  if (e.key !== 'Enter' || e.defaultPrevented || e.isComposing || e.shiftKey || e.metaKey || e.ctrlKey || e.altKey) return null;
  const t = e.target;
  if (!(t instanceof HTMLInputElement)) return null;
  if (!['text', 'email', 'url', 'tel', 'password', 'number', ''].includes(t.type)) return null;
  if (t.getAttribute('role') === 'combobox' || t.getAttribute('aria-haspopup') || t.getAttribute('aria-autocomplete') || t.readOnly) return null;
  const dialog = t.closest('[role="dialog"]');
  if (!dialog || dialog.closest('.studio-dialog--floating') || dialog.matches('.studio-dialog--floating')) return null;
  return primaryOf(dialog);
}

let installed = false;

export function installDialogEnter(doc: Document = document): void {
  if (installed) return;
  installed = true;
  doc.addEventListener('keydown', (e) => {
    const button = wantsSubmit(e);
    if (!button) return;
    e.preventDefault();
    // the field's value is committed on blur by some fields (numbers): let it settle first
    (e.target as HTMLElement).blur();
    button.focus();
    button.click();
  });
}
