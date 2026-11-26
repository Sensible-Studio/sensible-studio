import { ActionButton, Content, DialogTrigger, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import DragHandle from '@spectrum-icons/workflow/DragHandle';
import MultipleCheck from '@spectrum-icons/workflow/MultipleCheck';
import { useState, type DragEvent, type KeyboardEvent } from 'react';
import { StudioDialog } from '../../shell/StudioDialog';

export interface PanelEntry {
  /** Node name of the panel's component in the container. */
  name: string;
  /** The component's title (`Teaser`). */
  title: string;
  /** Its panel title (`cq:panelTitle`), else its own title. */
  subtitle?: string;
}

interface Props {
  panels: PanelEntry[];
  /** Index of the panel showing in the page when the list opens. */
  active(): number;
  onNavigate(index: number): void;
  /** New order of the panels (node names); not offered when the container cannot be changed (inherited). */
  onReorder?(names: string[]): Promise<void>;
}

/**
 * The "Select panel" action of a panel container's toolbar, like Core's panel selector: the container's panels in
 * order (number, component, panel title); choosing one shows it in the page, dragging a handle reorders them.
 */
export function PanelSelectButton({ panels, active, onNavigate, onReorder }: Props) {
  const [open, setOpen] = useState(false);
  return (
    <DialogTrigger type="popover" placement="bottom start" isOpen={open} onOpenChange={setOpen}>
      <TooltipTrigger delay={400}>
        <ActionButton isQuiet aria-label="Select panel">
          <MultipleCheck />
        </ActionButton>
        <Tooltip>Select panel</Tooltip>
      </TooltipTrigger>
      <StudioDialog size="S" UNSAFE_className="studio-panelselect">
        <Content>
          <PanelList panels={panels} initial={active()} onNavigate={onNavigate} onReorder={onReorder} />
        </Content>
      </StudioDialog>
    </DialogTrigger>
  );
}

function PanelList({ panels, initial, onNavigate, onReorder }: Omit<Props, 'active'> & { initial: number }) {
  const [order, setOrder] = useState(panels);
  const [selected, setSelected] = useState(order[initial]?.name ?? order[0]?.name);
  const [dragging, setDragging] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const choose = (name: string, list = order) => {
    setSelected(name);
    onNavigate(list.findIndex((p) => p.name === name));
  };

  const moveTo = (name: string, target: number) => {
    const from = order.findIndex((p) => p.name === name);
    if (from < 0 || from === target) return order;
    const next = [...order];
    const [it] = next.splice(from, 1);
    next.splice(target, 0, it);
    setOrder(next);
    return next;
  };

  const commit = async (next: PanelEntry[]) => {
    if (!onReorder || next.map((p) => p.name).join() === panels.map((p) => p.name).join()) return;
    setBusy(true);
    try {
      await onReorder(next.map((p) => p.name));
    } finally {
      setBusy(false);
    }
  };

  const onKey = (e: KeyboardEvent<HTMLLIElement>, i: number) => {
    const go = (j: number) => {
      e.preventDefault();
      const target = order[Math.max(0, Math.min(order.length - 1, j))];
      if (!target) return;
      choose(target.name);
      (e.currentTarget.parentElement?.children[order.indexOf(target)] as HTMLElement | undefined)?.focus();
    };
    if (e.key === 'ArrowDown') go(i + 1);
    else if (e.key === 'ArrowUp') go(i - 1);
    else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      choose(order[i].name);
    }
  };

  return (
    <ol className={`studio-panelselect__list${busy ? ' is-busy' : ''}`} role="listbox" aria-label="Panels">
      {order.map((p, i) => (
        <li
          key={p.name}
          role="option"
          aria-selected={p.name === selected}
          tabIndex={p.name === selected ? 0 : -1}
          className={`studio-panelselect__row${p.name === selected ? ' is-selected' : ''}${p.name === dragging ? ' is-dragging' : ''}`}
          onClick={() => choose(p.name)}
          onKeyDown={(e) => onKey(e, i)}
          onDragOver={(e: DragEvent) => {
            if (!dragging) return;
            e.preventDefault();
            moveTo(dragging, i);
          }}
          onDrop={(e) => e.preventDefault()}
        >
          <span className="studio-panelselect__index">{i + 1}</span>
          <span className="studio-panelselect__title">
            {p.title}
            {p.subtitle && (
              <>
                : <span className="studio-panelselect__subtitle">{p.subtitle}</span>
              </>
            )}
          </span>
          {onReorder && (
            <span
              className="studio-panelselect__handle"
              draggable={!busy}
              aria-label={`Reorder ${p.subtitle || p.title}`}
              title="Drag to reorder"
              onClick={(e) => e.stopPropagation()}
              onDragStart={(e) => {
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', p.name);
                setDragging(p.name);
              }}
              onDragEnd={() => {
                if (dragging) void commit(order);
                setDragging(null);
              }}
            >
              <DragHandle size="S" />
            </span>
          )}
        </li>
      ))}
    </ol>
  );
}
