import { ActionButton, Content, DialogTrigger, ProgressCircle, Text, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Brush from '@spectrum-icons/workflow/Brush';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import { useEffect, useState } from 'react';
import type { PostEntry } from '../../granite';
import type { StudioClient, StylesInfo } from '../api/StudioClient';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  client: StudioClient;
  path: string;
  /** Saves the new `cq:styleIds` (the entries of a Sling POST to the component). */
  onSave(entries: PostEntry[]): Promise<void>;
}

/**
 * The Styles action of the component toolbar: the Style System groups of the component's policy
 * (single or multiple choice per group); a click toggles a style and saves `cq:styleIds`, like AEM.
 */
export function StylesButton({ client, path, onSave }: Props) {
  const [open, setOpen] = useState(false);
  return (
    <DialogTrigger type="popover" placement="bottom end" isOpen={open} onOpenChange={setOpen}>
      <TooltipTrigger delay={400}>
        <ActionButton isQuiet aria-label="Styles">
          <Brush />
        </ActionButton>
        <Tooltip>Styles</Tooltip>
      </TooltipTrigger>
      <StudioDialog size="S" UNSAFE_className="studio-styles">
        <Content>
          <StylesList client={client} path={path} onSave={onSave} />
        </Content>
      </StudioDialog>
    </DialogTrigger>
  );
}

function StylesList({ client, path, onSave }: Props) {
  const [info, setInfo] = useState<StylesInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [applied, setApplied] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let live = true;
    client
      .getStyles(path)
      .then((i) => {
        if (!live) return;
        setInfo(i);
        setApplied(i.applied);
      })
      .catch((e: unknown) => live && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      live = false;
    };
  }, [client, path]);

  if (error) return <Text>{error}</Text>;
  if (!info) return <ProgressCircle aria-label="Loading styles" isIndeterminate size="S" />;
  if (info.groups.length === 0) return <Text>This component has no styles.</Text>;

  const toggle = async (group: StylesInfo['groups'][number], id: string) => {
    const groupIds = group.styles.map((s) => s.id);
    let next: string[];
    if (applied.includes(id)) next = applied.filter((x) => x !== id);
    else if (group.multiple) next = [...applied, id];
    else next = [...applied.filter((x) => !groupIds.includes(x)), id];
    // policy order, like AEM writes them
    const order = info.groups.flatMap((g) => g.styles.map((s) => s.id));
    next = order.filter((x) => next.includes(x));
    setApplied(next);
    setBusy(true);
    try {
      await onSave(
        next.length
          ? [...next.map((v) => ({ name: './cq:styleIds', value: v })), { name: './cq:styleIds@TypeHint', value: 'String[]' }]
          : [{ name: './cq:styleIds@Delete', value: 'true' }],
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={`studio-styles__groups${busy ? ' is-busy' : ''}`}>
      {info.groups.map((g) => (
        <section key={g.name} className="studio-styles__group" aria-label={g.label}>
          <h3>{g.label}</h3>
          <ul role="listbox" aria-multiselectable={g.multiple}>
            {g.styles.map((s) => {
              const on = applied.includes(s.id);
              return (
                <li key={s.id} role="option" aria-selected={on}>
                  <button type="button" className={`studio-styles__style${on ? ' is-selected' : ''}`} onClick={() => void toggle(g, s.id)}>
                    <span className="studio-styles__check">{on && <Checkmark size="S" />}</span>
                    <span>{s.label}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
