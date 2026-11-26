import { Button, ButtonGroup, Content, DialogContainer, Divider, Form, Heading, Item, Picker, TextArea, TextField, ToastQueue } from '@adobe/react-spectrum';
import { useEffect, useMemo, useState } from 'react';
import { HttpWorkflowClient, workflowUrl, type ModelSummary, type WorkflowClient } from './api';
import { inboxChanged } from './InboxBell';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  /** Pages or assets to run the workflow on: one workflow each. */
  payloads: string[];
  onClose(): void;
  /** Called after the workflows started (e.g. to refresh a console). */
  onStarted?(ids: string[]): void;
  client?: WorkflowClient;
}

/**
 * "Start Flow" for a selection in the Sites or Assets console: pick a synced model, optionally a title and a
 * comment, and one workflow starts per selected item.
 */
export function StartWorkflowDialog({ payloads, onClose, onStarted, client }: Props) {
  const wf = useMemo(() => client ?? new HttpWorkflowClient(), [client]);
  const [models, setModels] = useState<ModelSummary[] | null>(null);
  const [model, setModel] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    wf.models().then(
      (list) => setModels(list.filter((m) => m.version)),
      (e: unknown) => setFailure(e instanceof Error ? e.message : String(e)),
    );
  }, [wf]);

  const start = async () => {
    if (!model) return;
    setBusy(true);
    setFailure(null);
    const ids: string[] = [];
    try {
      for (const p of payloads) ids.push((await wf.start(model, p, title.trim() || undefined, comment.trim() || undefined)).id);
      const name = models?.find((m) => m.id === model)?.title ?? model;
      ToastQueue.positive(payloads.length === 1 ? `${name} started on ${payloads[0]}` : `${name} started on ${payloads.length} items`, {
        timeout: 5000,
        actionLabel: 'Instances',
        onAction: () => (window.location.href = workflowUrl.instances()),
        shouldCloseOnAction: true,
      });
      inboxChanged();
      onStarted?.(ids);
      onClose();
    } catch (e) {
      setFailure(`${ids.length ? `${ids.length} started, then: ` : ''}${e instanceof Error ? e.message : String(e)}`);
      setBusy(false);
    }
  };

  return (
    <DialogContainer onDismiss={onClose}>
      <StudioDialog size="M">
        <Heading>Start Flow</Heading>
        <Divider />
        <Content>
          <p className="studio-wf__runmodel">
            {payloads.length === 1 ? payloads[0] : `${payloads.length} items: one flow each`}
          </p>
          <Form>
            <Picker
              label="Flow Model"
              isRequired
              items={models ?? []}
              isLoading={!models}
              selectedKey={model}
              onSelectionChange={(k) => setModel(k === null ? null : String(k))}
              width="100%"
              autoFocus
            >
              {(m) => <Item key={m.id}>{m.title}</Item>}
            </Picker>
            <TextField label="Title" value={title} onChange={setTitle} />
            <TextArea label="Comment" value={comment} onChange={setComment} />
          </Form>
          {models && models.length === 0 && <p className="studio-wf__hint">No flow model is synced yet.</p>}
          {failure && (
            <p className="studio-wf__failure" role="alert">
              {failure}
            </p>
          )}
        </Content>
        <ButtonGroup>
          <Button variant="secondary" onPress={onClose} isDisabled={busy}>
            Cancel
          </Button>
          <Button variant="accent" onPress={() => void start()} isDisabled={busy || !model}>
            Start
          </Button>
        </ButtonGroup>
      </StudioDialog>
    </DialogContainer>
  );
}
