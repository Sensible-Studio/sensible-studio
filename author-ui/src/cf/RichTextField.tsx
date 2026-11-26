import { ActionButton, ActionGroup, Button, Flex, Item, Picker, Text, TextField, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import TextBold from '@spectrum-icons/workflow/TextBold';
import TextBulleted from '@spectrum-icons/workflow/TextBulleted';
import TextItalic from '@spectrum-icons/workflow/TextItalic';
import TextNumbered from '@spectrum-icons/workflow/TextNumbered';
import TextUnderline from '@spectrum-icons/workflow/TextUnderline';
import Link from '@spectrum-icons/workflow/Link';
import Unlink from '@spectrum-icons/workflow/Unlink';
import { useEffect, useId, useRef, useState, type Key } from 'react';

export const CONTENT_TYPES = [
  { key: 'text/html', label: 'Rich text' },
  { key: 'text/x-markdown', label: 'Markdown' },
  { key: 'text/plain', label: 'Plain text' },
];

interface Props {
  label: string;
  description?: string;
  value: string;
  onChange(value: string): void;
  contentType: string;
  onContentTypeChange(type: string): void;
  isRequired?: boolean;
  errorMessage?: string | null;
  isDisabled?: boolean;
}

/**
 * Multi-line text element. Rich text is edited in place (bold, italic, underline, lists, links) and stored as
 * HTML; Markdown and plain text use a monospace area. Authors can switch the format like in AEM; the choice
 * is stored as `<element>@ContentType`.
 */
export function RichTextField({ label, description, value, onChange, contentType, onContentTypeChange, isRequired, errorMessage, isDisabled }: Props) {
  const id = useId();
  const editor = useRef<HTMLDivElement>(null);
  const savedRange = useRef<Range | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const isHtml = contentType === 'text/html';

  // The editor is uncontrolled while focused; sync it when the value changes from outside.
  useEffect(() => {
    const el = editor.current;
    if (el && document.activeElement !== el && el.innerHTML !== value) el.innerHTML = value;
  }, [value, isHtml]);

  const sync = () => onChange(normalizeHtml(editor.current?.innerHTML ?? ''));
  const exec = (cmd: string, arg?: string) => {
    editor.current?.focus();
    document.execCommand(cmd, false, arg);
    sync();
  };
  const onTool = (key: Key) => {
    if (key === 'link') {
      const sel = window.getSelection();
      savedRange.current = sel && sel.rangeCount ? sel.getRangeAt(0).cloneRange() : null;
      setLink('');
      return;
    }
    exec(String(key));
  };
  const applyLink = () => {
    const url = (link ?? '').trim();
    setLink(null);
    if (!url) return;
    editor.current?.focus();
    const sel = window.getSelection();
    if (savedRange.current && sel) {
      sel.removeAllRanges();
      sel.addRange(savedRange.current);
    }
    exec('createLink', url);
  };

  return (
    <div className={`studio-cf-rte${errorMessage ? ' is-invalid' : ''}`}>
      <Flex alignItems="end" justifyContent="space-between" gap="size-200" wrap>
        <label className="studio-cf-label" htmlFor={id}>
          {label}
          {isRequired && <span className="studio-cf-required"> *</span>}
        </label>
        <Picker
          aria-label={`${label} format`}
          isQuiet
          width="size-1700"
          selectedKey={contentType}
          isDisabled={isDisabled}
          onSelectionChange={(k) => onContentTypeChange(String(k))}
          items={CONTENT_TYPES}
        >
          {(item) => <Item key={item.key}>{item.label}</Item>}
        </Picker>
      </Flex>
      {isHtml ? (
        <div className="studio-cf-rte__box">
          <div className="studio-cf-rte__toolbar">
            <ActionGroup
              aria-label="Formatting"
              isQuiet
              density="compact"
              buttonLabelBehavior="hide"
              isDisabled={isDisabled}
              onAction={onTool}
              overflowMode="collapse"
            >
              <Item key="bold" aria-label="Bold">
                <TextBold />
                <Text>Bold</Text>
              </Item>
              <Item key="italic" aria-label="Italic">
                <TextItalic />
                <Text>Italic</Text>
              </Item>
              <Item key="underline" aria-label="Underline">
                <TextUnderline />
                <Text>Underline</Text>
              </Item>
              <Item key="insertUnorderedList" aria-label="Bulleted list">
                <TextBulleted />
                <Text>Bulleted list</Text>
              </Item>
              <Item key="insertOrderedList" aria-label="Numbered list">
                <TextNumbered />
                <Text>Numbered list</Text>
              </Item>
              <Item key="link" aria-label="Link">
                <Link />
                <Text>Link</Text>
              </Item>
              <Item key="unlink" aria-label="Remove link">
                <Unlink />
                <Text>Remove link</Text>
              </Item>
            </ActionGroup>
            {link !== null && (
              <Flex gap="size-100" alignItems="center" marginStart="size-100">
                <TextField aria-label="Link URL" autoFocus value={link} onChange={setLink} placeholder="https://… or /content/…" width="size-3600" onKeyDown={(e) => e.key === 'Enter' && applyLink()} />
                <Button variant="primary" onPress={applyLink}>
                  Apply
                </Button>
                <TooltipTrigger>
                  <ActionButton isQuiet onPress={() => setLink(null)} aria-label="Cancel link">
                    ✕
                  </ActionButton>
                  <Tooltip>Cancel</Tooltip>
                </TooltipTrigger>
              </Flex>
            )}
          </div>
          <div
            id={id}
            ref={editor}
            className="studio-cf-rte__editor"
            role="textbox"
            aria-multiline="true"
            aria-label={label}
            aria-invalid={errorMessage ? true : undefined}
            contentEditable={!isDisabled}
            suppressContentEditableWarning
            onInput={sync}
            onBlur={sync}
            onPaste={(e) => {
              e.preventDefault();
              document.execCommand('insertText', false, e.clipboardData.getData('text/plain'));
            }}
          />
        </div>
      ) : (
        <textarea
          id={id}
          className="studio-cf-rte__plain"
          value={value}
          rows={10}
          disabled={isDisabled}
          aria-invalid={errorMessage ? true : undefined}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
      {errorMessage ? <div className="studio-cf-error">{errorMessage}</div> : description ? <div className="studio-cf-help">{description}</div> : null}
    </div>
  );
}

/** An editor left with only empty markup stores nothing. */
export function normalizeHtml(html: string): string {
  const trimmed = html.trim();
  if (trimmed === '' || /^(<p>)?(<br\s*\/?>)?(<\/p>)?$/i.test(trimmed)) return '';
  return trimmed;
}
