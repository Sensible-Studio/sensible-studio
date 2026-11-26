import { ActionButton, Button, Text, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import ChevronLeft from '@spectrum-icons/workflow/ChevronLeft';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Alert from '@spectrum-icons/workflow/Alert';
import Info from '@spectrum-icons/workflow/Info';
import { useEffect, useState } from 'react';

export interface PageStatus {
  id: string;
  variant: 'info' | 'warning';
  title: string;
  text?: string;
  actions?: Array<{ label: string; onPress(): void }>;
}

interface Props {
  statuses: PageStatus[];
}

/**
 * The strip under the top bar AEM shows while a page has a status worth knowing before editing:
 * in a workflow, locked by someone else, a live copy. One status at a time, with previous/next
 * and a counter when there are several; nothing at all when there are none.
 */
export function StatusBar({ statuses }: Props) {
  const [index, setIndex] = useState(0);
  useEffect(() => {
    if (index >= statuses.length) setIndex(0);
  }, [statuses, index]);
  if (statuses.length === 0) return null;
  const s = statuses[Math.min(index, statuses.length - 1)];
  return (
    <div className={`studio-statusbar studio-statusbar--${s.variant}`} role="status">
      <span className="studio-statusbar__icon">{s.variant === 'warning' ? <Alert size="S" /> : <Info size="S" />}</span>
      <span className="studio-statusbar__text">
        <strong>{s.title}</strong>
        {s.text && <span> {s.text}</span>}
      </span>
      {s.actions?.map((a) => (
        <Button key={a.label} variant="secondary" isQuiet onPress={a.onPress}>
          <Text>{a.label}</Text>
        </Button>
      ))}
      {statuses.length > 1 && (
        <span className="studio-statusbar__nav">
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Show previous" onPress={() => setIndex((i) => (i - 1 + statuses.length) % statuses.length)}>
              <ChevronLeft />
            </ActionButton>
            <Tooltip>Previous</Tooltip>
          </TooltipTrigger>
          <span className="studio-statusbar__badge">
            {index + 1}/{statuses.length}
          </span>
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Show next" onPress={() => setIndex((i) => (i + 1) % statuses.length)}>
              <ChevronRight />
            </ActionButton>
            <Tooltip>Next</Tooltip>
          </TooltipTrigger>
        </span>
      )}
    </div>
  );
}
