import { useEffect, useRef, useState } from 'react';
import type { GraniteComponentProps } from '../../registry';
import { useField, useFormStore } from '../../context';
import { scalarEntries } from '../../form/slingPost';
import { asString } from '../../node';
import { commonFieldProps, describedBy, FieldShell, requiredError } from '../FieldShell';
import { initialScalar } from './text';

type Command = 'bold' | 'italic' | 'underline' | 'insertUnorderedList' | 'insertOrderedList' | 'unlink' | 'removeFormat';

const TOOLS: Array<{ cmd: Command; label: string; glyph: string }> = [
  { cmd: 'bold', label: 'Bold', glyph: 'B' },
  { cmd: 'italic', label: 'Italic', glyph: 'I' },
  { cmd: 'underline', label: 'Underline', glyph: 'U' },
  { cmd: 'insertUnorderedList', label: 'Bulleted list', glyph: '•' },
  { cmd: 'insertOrderedList', label: 'Numbered list', glyph: '1.' },
];

/**
 * `cq/gui/components/authoring/dialog/richtext` — a compact rich text editor producing the same kind of
 * HTML the AEM RTE stores (`<p>`, `<b>`, `<i>`, `<u>`, lists, links). The RTE plugin configuration
 * (`rtePlugins`, `uiSettings`) is not interpreted yet; the common formatting set is always offered.
 */
export function RichText({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const editorRef = useRef<HTMLDivElement>(null);
  const [linkDraft, setLinkDraft] = useState<string | null>(null);
  const savedRange = useRef<Range | null>(null);

  const { id, value, setValue, error, disabled: ruleDisabled } = useField<string>(common.name, () => initialScalar(store, node), {
    label: common.label,
    entries: (v) => scalarEntries(common.name, normalizeHtml(asString(v))),
    values: (v) => [normalizeHtml(asString(v))],
    validate: (v) => requiredError(common.required, normalizeHtml(asString(v)) === ''),
  });

  // The editor is uncontrolled; seed it once.
  useEffect(() => {
    if (editorRef.current && editorRef.current.innerHTML !== (value ?? '')) {
      editorRef.current.innerHTML = value ?? '';
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sync = () => setValue(editorRef.current?.innerHTML ?? '');
  const exec = (cmd: Command) => {
    editorRef.current?.focus();
    document.execCommand(cmd);
    sync();
  };
  const startLink = () => {
    const sel = window.getSelection();
    savedRange.current = sel && sel.rangeCount ? sel.getRangeAt(0).cloneRange() : null;
    setLinkDraft('');
  };
  const applyLink = () => {
    const url = (linkDraft ?? '').trim();
    setLinkDraft(null);
    if (!url) return;
    editorRef.current?.focus();
    const sel = window.getSelection();
    if (savedRange.current && sel) {
      sel.removeAllRanges();
      sel.addRange(savedRange.current);
    }
    document.execCommand('createLink', false, url);
    sync();
  };

  const disabled = common.disabled || common.readOnly || ruleDisabled;
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <div className={`studio-g-rte${disabled ? ' is-disabled' : ''}`}>
        <div className="studio-g-rte__toolbar" role="toolbar" aria-label="Formatting">
          {TOOLS.map((t) => (
            <button
              key={t.cmd}
              type="button"
              title={t.label}
              aria-label={t.label}
              disabled={disabled}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => exec(t.cmd)}
              className={`studio-g-rte__tool studio-g-rte__tool--${t.cmd}`}
            >
              {t.glyph}
            </button>
          ))}
          <button type="button" title="Link" aria-label="Link" disabled={disabled} onMouseDown={(e) => e.preventDefault()} onClick={startLink} className="studio-g-rte__tool">
            🔗
          </button>
          <button type="button" title="Remove link" aria-label="Remove link" disabled={disabled} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('unlink')} className="studio-g-rte__tool">
            ⛓̸
          </button>
          <button type="button" title="Clear formatting" aria-label="Clear formatting" disabled={disabled} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('removeFormat')} className="studio-g-rte__tool">
            Tx
          </button>
          {linkDraft !== null && (
            <span className="studio-g-rte__link">
              <input
                autoFocus
                className="studio-g-input"
                placeholder="https://… or /content/…"
                value={linkDraft}
                onChange={(e) => setLinkDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    applyLink();
                  } else if (e.key === 'Escape') {
                    setLinkDraft(null);
                  }
                }}
              />
              <button type="button" className="studio-g-btn" onClick={applyLink}>
                Apply
              </button>
            </span>
          )}
        </div>
        <div
          id={id}
          ref={editorRef}
          className="studio-g-rte__editor"
          role="textbox"
          aria-multiline="true"
          aria-label={common.label ?? 'Text'}
          aria-describedby={describedBy(id, common.description, error)}
          contentEditable={!disabled}
          suppressContentEditableWarning
          data-placeholder={common.emptyText ?? ''}
          onInput={sync}
          onBlur={sync}
          onPaste={(e) => {
            e.preventDefault();
            document.execCommand('insertText', false, e.clipboardData.getData('text/plain'));
          }}
        />
      </div>
    </FieldShell>
  );
}

/** An editor left with only empty markup stores nothing. */
export function normalizeHtml(html: string): string {
  const trimmed = html.trim();
  if (trimmed === '' || /^(<p>)?(<br\s*\/?>)?(<\/p>)?$/i.test(trimmed)) return '';
  return trimmed;
}
