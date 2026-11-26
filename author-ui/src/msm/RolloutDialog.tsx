import { Button, ButtonGroup, Checkbox, Content, Divider, Heading, Text, ToastQueue, useDialogContainer } from '@adobe/react-spectrum';
import { useState } from 'react';
import { describeResult, stateLabel, type MsmClient } from './MsmClient';
import type { LiveCopyInfo, RelationshipInfo, RolloutResult } from './types';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  msm: MsmClient;
  /** the blueprint page */
  path: string;
  liveCopies: Array<RelationshipInfo & { liveCopy: LiveCopyInfo }>;
  onDone(result: RolloutResult): void;
  /** `path` is a component of a blueprint page: only it rolls out (no child pages). */
  component?: { title: string };
}

/**
 * Rollout of a blueprint page: to which live copies, and whether its child pages go too. Live copies whose rollout
 * configurations do not react to a manual rollout (e.g. push on modify only) are listed but left out.
 */
export function RolloutDialog({ msm, path, liveCopies, onDone, component }: Props) {
  const dialog = useDialogContainer();
  const manual = (c: { liveCopy: LiveCopyInfo }) => c.liveCopy.rolloutConfigs.some((r) => r.trigger === 'rollout');
  const [targets, setTargets] = useState<Set<string>>(() => new Set(liveCopies.filter(manual).map((c) => c.targetPath)));
  const [deep, setDeep] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      const result = await msm.rollout(path, { deep, targets: [...targets] });
      ToastQueue.positive(`Synced: ${describeResult(result)}`, { timeout: 6000 });
      if (result.conflicts.length) {
        ToastQueue.info(`Local pages with the same name were left as they are: ${result.conflicts.join(', ')}`, { timeout: 10000 });
      }
      onDone(result);
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setRunning(false);
    }
  };

  const toggle = (t: string, on: boolean) =>
    setTargets((s) => {
      const n = new Set(s);
      if (on) n.add(t);
      else n.delete(t);
      return n;
    });

  return (
    <StudioDialog size="M">
      <Heading>Sync changes</Heading>
      <Divider />
      <Content>
        <Text>
          {component ? (
            <>
              Synchronizes the <strong>{component.title}</strong> component to the linked copies you choose, with their sync rules.
            </>
          ) : (
            <>
              Synchronizes <strong>{path}</strong> to the linked copies you choose, with their sync rules.
            </>
          )}
        </Text>
        <ul className="studio-msm__targets">
          {liveCopies.map((c) => (
            <li key={c.targetPath}>
              <Checkbox isSelected={targets.has(c.targetPath)} isDisabled={!manual(c) || running} onChange={(on) => toggle(c.targetPath, on)}>
                {c.targetPath}
              </Checkbox>
              <span className={`studio-msm-state is-${c.status}`}>{stateLabel(c.status)}</span>
              {!manual(c) && <span className="studio-msm__muted">only {c.liveCopy.rolloutConfigs.map((r) => r.trigger).join(', ')}</span>}
            </li>
          ))}
        </ul>
        {!component && (
          <Checkbox isSelected={deep} onChange={setDeep} isDisabled={running}>
            Also the pages below it
          </Checkbox>
        )}
        {error && (
          <p className="studio-msm__error" role="alert">
            {error}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={running}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void run()} isDisabled={!targets.size} isPending={running}>
          Sync changes
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
