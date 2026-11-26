import { ActionButton, AlertDialog, Button, ButtonGroup, Content, Dialog, Divider, Heading } from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import type { ComponentProps, ReactNode } from 'react';

type StandardDialogProps = {
  title: string; subject?: string; size?: 'S' | 'M' | 'L'; children: ReactNode; extras?: ReactNode; cancelLabel?: string; primaryLabel?: string;
  onCancel(): void; onPrimary?(): void; primaryDisabled?: boolean; busy?: boolean; destructive?: boolean;
};
type SlotDialogProps = ComponentProps<typeof Dialog>;

export function StudioDialog(props: StandardDialogProps): JSX.Element;
export function StudioDialog(props: SlotDialogProps): JSX.Element;
export function StudioDialog(props: StandardDialogProps | SlotDialogProps) {
  if (!('title' in props)) {
    const { UNSAFE_className, size, isDismissable = true, ...rest } = props;
    const token = typeof size === 'string' ? size.toLowerCase() : 'm';
    return <Dialog {...rest} size={size} isDismissable={isDismissable} UNSAFE_className={`studio-dialog-standard studio-dialog-standard--${token}${UNSAFE_className ? ` ${UNSAFE_className}` : ''}`} />;
  }
  const { title, subject, size = 'M', children, extras, cancelLabel = 'Cancel', primaryLabel, onCancel, onPrimary, primaryDisabled, busy, destructive } = props;
  return (
    <Dialog size={size} UNSAFE_className={`studio-dialog-standard studio-dialog-standard--${size.toLowerCase()}`}>
      <Heading>{title}</Heading>{subject && <p className="studio-dialog-standard__subject">{subject}</p>}<ActionButton isQuiet aria-label="Close dialog" onPress={onCancel} isDisabled={busy} UNSAFE_className="studio-dialog-standard__close"><Close /></ActionButton><Divider />
      <Content>{children}</Content>
      <ButtonGroup UNSAFE_className="studio-dialog-standard__footer">
        {extras}<span className="studio-dialog-standard__spacer" />
        <Button variant="secondary" onPress={onCancel} isDisabled={busy}>{cancelLabel}</Button>
        {primaryLabel && <Button variant={destructive ? 'negative' : 'accent'} onPress={onPrimary} isDisabled={primaryDisabled || busy} isPending={busy}>{primaryLabel}</Button>}
      </ButtonGroup>
    </Dialog>
  );
}

type StandardConfirmProps = Omit<StandardDialogProps, 'children'> & { children: ReactNode };
type AlertProps = ComponentProps<typeof AlertDialog>;

export function StudioConfirmDialog(props: StandardConfirmProps): JSX.Element;
export function StudioConfirmDialog(props: AlertProps): JSX.Element;
export function StudioConfirmDialog(props: StandardConfirmProps | AlertProps) {
  if ('onPrimaryAction' in props) return <AlertDialog {...props} />;
  return <StudioDialog {...props} size="S">{props.children}</StudioDialog>;
}

type StandardPickerProps = StandardDialogProps & { summary?: ReactNode };
type SlotPickerProps = SlotDialogProps;

export function StudioPickerDialog(props: StandardPickerProps): JSX.Element;
export function StudioPickerDialog(props: SlotPickerProps): JSX.Element;
export function StudioPickerDialog(props: StandardPickerProps | SlotPickerProps) {
  if (!('title' in props)) return <StudioDialog {...props} size={props.size ?? 'L'} UNSAFE_className={`studio-picker-dialog-shell${props.UNSAFE_className ? ` ${props.UNSAFE_className}` : ''}`} />;
  const { summary, ...standard } = props;
  return <StudioDialog {...standard} size={standard.size ?? 'L'}><div className="studio-picker-dialog">{standard.children}{summary && <aside aria-label="Selection summary">{summary}</aside>}</div></StudioDialog>;
}
