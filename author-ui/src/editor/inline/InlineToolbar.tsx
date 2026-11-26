import {
  ActionButton,
  Button,
  ButtonGroup,
  Content,
  DialogTrigger,
  Divider,
  Form,
  Heading,
  Item,
  Menu,
  MenuTrigger,
  Text,
  TextField,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import Close from '@spectrum-icons/workflow/Close';
import Link from '@spectrum-icons/workflow/Link';
import TextAlignCenter from '@spectrum-icons/workflow/TextAlignCenter';
import TextAlignJustify from '@spectrum-icons/workflow/TextAlignJustify';
import TextAlignLeft from '@spectrum-icons/workflow/TextAlignLeft';
import TextAlignRight from '@spectrum-icons/workflow/TextAlignRight';
import TextBold from '@spectrum-icons/workflow/TextBold';
import TextBulleted from '@spectrum-icons/workflow/TextBulleted';
import TextIndentDecrease from '@spectrum-icons/workflow/TextIndentDecrease';
import TextIndentIncrease from '@spectrum-icons/workflow/TextIndentIncrease';
import TextItalic from '@spectrum-icons/workflow/TextItalic';
import TextNumbered from '@spectrum-icons/workflow/TextNumbered';
import TextParagraph from '@spectrum-icons/workflow/TextParagraph';
import TextUnderline from '@spectrum-icons/workflow/TextUnderline';
import Unlink from '@spectrum-icons/workflow/Unlink';
import { useEffect, useState, type Key, type ReactElement } from 'react';
import type { InplaceEditing } from './inplace';
import { execute, formattingAt } from './useInlineEdit';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  el: HTMLElement;
  editing: InplaceEditing;
  onSave(): void;
  onCancel(): void;
}

/** The floating rich text toolbar of an in-place text editor: format, lists, links, paragraph format, close and save. */
export function InlineToolbar({ el, editing, onSave, onCancel }: Props) {
  const [state, setState] = useState(() => formattingAt(el));
  const f = editing.features;

  useEffect(() => {
    const doc = el.ownerDocument;
    const update = () => setState(formattingAt(el));
    doc.addEventListener('selectionchange', update);
    el.addEventListener('keyup', update);
    el.addEventListener('mouseup', update);
    return () => {
      doc.removeEventListener('selectionchange', update);
      el.removeEventListener('keyup', update);
      el.removeEventListener('mouseup', update);
    };
  }, [el]);

  const run = (command: string, value?: string) => {
    execute(el, command, value);
    setState(formattingAt(el));
  };

  const button = (label: string, icon: ReactElement, onPress: () => void, active = false) => (
    <TooltipTrigger key={label} delay={400}>
      <ActionButton isQuiet aria-label={label} aria-pressed={active} UNSAFE_className={active ? 'is-active' : undefined} onPress={onPress}>
        {icon}
      </ActionButton>
      <Tooltip>{label}</Tooltip>
    </TooltipTrigger>
  );

  const blockLabel = editing.formats.find((x) => x.tag === state.block)?.label ?? 'Paragraph';

  return (
    <div
      className="studio-toolbar studio-toolbar--inline"
      role="toolbar"
      aria-label="Text formatting"
      // keep the caret in the frame: buttons must not take the focus
      onPointerDown={(e) => e.preventDefault()}
      onMouseDown={(e) => e.preventDefault()}
    >
      {editing.kind === 'text' && (
        <>
          {f.bold && button('Bold', <TextBold />, () => run('bold'), state.bold)}
          {f.italic && button('Italic', <TextItalic />, () => run('italic'), state.italic)}
          {f.underline && button('Underline', <TextUnderline />, () => run('underline'), state.underline)}
          {f.justify && (
            <>
              {button('Align left', <TextAlignLeft />, () => run('justifyLeft'))}
              {button('Align center', <TextAlignCenter />, () => run('justifyCenter'))}
              {button('Align right', <TextAlignRight />, () => run('justifyRight'))}
              {button('Justify', <TextAlignJustify />, () => run('justifyFull'))}
            </>
          )}
          {f.lists && (
            <>
              {button('Bullet list', <TextBulleted />, () => run('insertUnorderedList'), state.ul)}
              {button('Numbered list', <TextNumbered />, () => run('insertOrderedList'), state.ol)}
              {button('Outdent', <TextIndentDecrease />, () => run('outdent'))}
              {button('Indent', <TextIndentIncrease />, () => run('indent'))}
            </>
          )}
          {f.links && (
            <>
              <LinkButton el={el} current={state.link} onApply={(href) => run('createLink', href)} />
              {button('Unlink', <Unlink />, () => run('unlink'), false)}
            </>
          )}
          {f.paraformat && (
            <MenuTrigger>
              <ActionButton isQuiet aria-label={`Paragraph format: ${blockLabel}`} UNSAFE_className="studio-toolbar__paraformat">
                <TextParagraph />
                <Text>{blockLabel}</Text>
              </ActionButton>
              <Menu
                selectionMode="single"
                selectedKeys={state.block ? [state.block] : []}
                onAction={(k: Key) => run('formatBlock', `<${String(k)}>`)}
              >
                {editing.formats.map((x) => (
                  <Item key={x.tag}>{x.label}</Item>
                ))}
              </Menu>
            </MenuTrigger>
          )}
          <span className="studio-toolbar__gap" />
        </>
      )}
      {button('Close', <Close />, onCancel)}
      {button('Save', <Checkmark />, onSave)}
    </div>
  );
}

function LinkButton({ el, current, onApply }: { el: HTMLElement; current: string | null; onApply(href: string): void }) {
  const [href, setHref] = useState(current ?? '');
  const [saved, setSaved] = useState<Range | null>(null);
  return (
    <DialogTrigger
      type="popover"
      onOpenChange={(open) => {
        if (open) {
          // the popover takes the focus: remember where the link goes
          const sel = el.ownerDocument.defaultView?.getSelection();
          setSaved(sel && sel.rangeCount ? sel.getRangeAt(0).cloneRange() : null);
          setHref(current ?? '');
        }
      }}
    >
      <TooltipTrigger delay={400}>
        <ActionButton isQuiet aria-label="Link" aria-pressed={!!current} UNSAFE_className={current ? 'is-active' : undefined}>
          <Link />
        </ActionButton>
        <Tooltip>Link</Tooltip>
      </TooltipTrigger>
      {(close) => (
        <StudioDialog size="S" UNSAFE_className="studio-inline-link">
          <Heading>Link</Heading>
          <Divider />
          <Content>
            <Form
              onSubmit={(e) => {
                e.preventDefault();
                apply();
                close();
              }}
            >
              <TextField label="Path or URL" value={href} onChange={setHref} autoFocus width="100%" />
            </Form>
          </Content>
          <ButtonGroup>
            <Button variant="secondary" onPress={close}>
              Cancel
            </Button>
            <Button
              variant="accent"
              onPress={() => {
                apply();
                close();
              }}
              isDisabled={!href.trim()}
            >
              Apply
            </Button>
          </ButtonGroup>
        </StudioDialog>
      )}
    </DialogTrigger>
  );

  function apply() {
    const sel = el.ownerDocument.defaultView?.getSelection();
    if (saved && sel) {
      sel.removeAllRanges();
      sel.addRange(saved);
    }
    onApply(href.trim());
  }
}
