import { useEffect, useRef } from 'react';
import { cleanHtml, cleanText, type InplaceEditing } from './inplace';

export interface InlineSession {
  /** The element in the content frame being edited. */
  el: HTMLElement;
  editing: InplaceEditing;
}

export type InlineEnd = { reason: 'save'; value: string; changed: boolean } | { reason: 'cancel' };

/** Dispatched on the content frame's window with detail `save` or `cancel` to end the session. */
export const END_EVENT = 'studio-inline-end';

interface Options {
  session: InlineSession | null;
  onEnd(end: InlineEnd): void;
}

/**
 * Runs an in-place editing session on an element of the content frame: makes it editable, focuses
 * it, and ends the session on Enter (titles), Escape, or a click elsewhere in the page. The value is
 * read when the session ends; the element keeps the edited content until the page re-renders.
 */
export function useInlineEdit({ session, onEnd }: Options) {
  const endRef = useRef(onEnd);
  endRef.current = onEnd;

  useEffect(() => {
    if (!session) return;
    const { el, editing } = session;
    const doc = el.ownerDocument;
    const win = doc.defaultView;
    if (!win) return;
    const original = editing.kind === 'title' ? cleanText(el.textContent ?? '') : cleanHtml(el.innerHTML);
    let ended = false;
    const end = (reason: 'save' | 'cancel') => {
      if (ended) return;
      ended = true;
      if (reason === 'cancel') {
        if (editing.kind === 'title') el.textContent = original;
        else el.innerHTML = original;
        endRef.current({ reason: 'cancel' });
      } else {
        const value = editing.kind === 'title' ? cleanText(el.textContent ?? '') : cleanHtml(el.innerHTML);
        endRef.current({ reason: 'save', value, changed: value !== original });
      }
    };

    if (!doc.getElementById('studio-inline-style')) {
      const style = doc.createElement('style');
      style.id = 'studio-inline-style';
      style.textContent = '.studio-inline-editing{outline:none;cursor:text;min-height:1em}';
      doc.head.appendChild(style);
    }
    el.setAttribute('contenteditable', editing.kind === 'title' ? 'plaintext-only' : 'true');
    if (el.getAttribute('contenteditable') !== 'plaintext-only' && editing.kind === 'title') el.setAttribute('contenteditable', 'true');
    el.setAttribute('spellcheck', 'true');
    el.classList.add('studio-inline-editing');
    el.focus({ preventScroll: true });
    const sel = win.getSelection();
    if (sel) {
      const range = doc.createRange();
      range.selectNodeContents(el);
      if (editing.kind === 'text') range.collapse(false);
      sel.removeAllRanges();
      sel.addRange(range);
    }

    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        ev.preventDefault();
        end('cancel');
      } else if (ev.key === 'Enter' && editing.kind === 'title') {
        ev.preventDefault();
        end('save');
      } else if (editing.kind === 'title' && (ev.key === 'Enter' || ev.key === 'Tab')) {
        ev.preventDefault();
      }
    };
    const onPaste = (ev: ClipboardEvent) => {
      if (editing.kind !== 'title') return;
      ev.preventDefault();
      const text = ev.clipboardData?.getData('text/plain').replace(/\s+/g, ' ') ?? '';
      doc.execCommand('insertText', false, text);
    };
    const onPointerDown = (ev: PointerEvent) => {
      if (!el.contains(ev.target as Node)) end('save');
    };
    // the toolbar's Save and Close buttons (in the editor document) end the session through this event
    const onEndEvent = (ev: Event) => end((ev as CustomEvent<'save' | 'cancel'>).detail === 'cancel' ? 'cancel' : 'save');
    el.addEventListener('keydown', onKey);
    el.addEventListener('paste', onPaste);
    doc.addEventListener('pointerdown', onPointerDown, true);
    win.addEventListener(END_EVENT, onEndEvent);
    return () => {
      el.removeEventListener('keydown', onKey);
      el.removeEventListener('paste', onPaste);
      doc.removeEventListener('pointerdown', onPointerDown, true);
      win.removeEventListener(END_EVENT, onEndEvent);
      el.removeAttribute('contenteditable');
      el.removeAttribute('spellcheck');
      el.classList.remove('studio-inline-editing');
      if (!ended) end('save');
    };
  }, [session]);
}

/** Runs a rich text command in the frame of the edited element, keeping the selection in it. */
export function execute(el: HTMLElement, command: string, value?: string) {
  const doc = el.ownerDocument;
  el.focus({ preventScroll: true });
  doc.execCommand(command, false, value);
}

/** The formatting at the caret: block tag, bold/italic/underline, list, link. */
export function formattingAt(el: HTMLElement): {
  block: string;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  link: string | null;
  ul: boolean;
  ol: boolean;
} {
  const doc = el.ownerDocument;
  const sel = doc.defaultView?.getSelection();
  let n: Node | null = sel && sel.rangeCount ? sel.getRangeAt(0).startContainer : null;
  let block = '';
  let link: string | null = null;
  let ul = false;
  let ol = false;
  while (n && n !== el) {
    if (n.nodeType === 1) {
      const tag = (n as Element).tagName.toLowerCase();
      if (!block && /^(p|h[1-6]|blockquote|pre|div)$/.test(tag)) block = tag;
      if (tag === 'a' && !link) link = (n as HTMLAnchorElement).getAttribute('href');
      if (tag === 'ul') ul = true;
      if (tag === 'ol') ol = true;
    }
    n = n.parentNode;
  }
  const q = (c: string) => {
    try {
      return doc.queryCommandState(c);
    } catch {
      return false;
    }
  };
  return {
    block,
    bold: q('bold'),
    italic: q('italic'),
    underline: q('underline'),
    link,
    ul,
    ol,
  };
}
