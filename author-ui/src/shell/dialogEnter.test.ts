import { describe, expect, it } from 'vitest';
import { wantsSubmit } from './dialogEnter';

function dialog(html: string): HTMLElement {
  const d = document.createElement('div');
  d.innerHTML = `<section role="dialog">${html}</section>`;
  document.body.append(d);
  return d;
}
const enter = (el: Element, init: KeyboardEventInit = {}) => {
  const e = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...init });
  Object.defineProperty(e, 'target', { value: el });
  return e;
};
const FOOTER = '<div class="studio-dialog-standard__footer"><button data-variant="secondary">Cancel</button><button data-variant="accent">Create</button></div>';

describe('Enter in a dialog', () => {
  it('presses the main button from a one-line field', () => {
    const d = dialog(`<input type="text">${FOOTER}`);
    expect(wantsSubmit(enter(d.querySelector('input')!))?.textContent).toBe('Create');
  });
  it('does nothing while the main button is disabled', () => {
    const d = dialog('<input type="text"><div class="studio-dialog-standard__footer"><button data-variant="accent" disabled>Create</button></div>');
    expect(wantsSubmit(enter(d.querySelector('input')!))).toBeNull();
  });
  it('leaves text areas, pickers and destructive dialogs alone', () => {
    const d = dialog(`<textarea></textarea><input type="text" role="combobox"><input type="checkbox">${FOOTER}`);
    expect(wantsSubmit(enter(d.querySelector('textarea')!))).toBeNull();
    expect(wantsSubmit(enter(d.querySelector('[role=combobox]')!))).toBeNull();
    expect(wantsSubmit(enter(d.querySelector('[type=checkbox]')!))).toBeNull();
    const del = dialog('<input type="text"><div class="studio-dialog-standard__footer"><button data-variant="negative">Delete</button></div>');
    expect(wantsSubmit(enter(del.querySelector('input')!))).toBeNull();
  });
  it('leaves a key another handler took, and Shift+Enter', () => {
    const d = dialog(`<input type="text">${FOOTER}`);
    const taken = enter(d.querySelector('input')!);
    taken.preventDefault();
    expect(wantsSubmit(taken)).toBeNull();
    expect(wantsSubmit(enter(d.querySelector('input')!, { shiftKey: true }))).toBeNull();
  });
  it('is not for the editor\'s floating component dialog, nor outside a dialog', () => {
    const f = document.createElement('div');
    f.className = 'studio-dialog--floating';
    f.innerHTML = `<section role="dialog"><input type="text">${FOOTER}</section>`;
    document.body.append(f);
    expect(wantsSubmit(enter(f.querySelector('input')!))).toBeNull();
    const loose = document.createElement('input');
    document.body.append(loose);
    expect(wantsSubmit(enter(loose))).toBeNull();
  });
});
