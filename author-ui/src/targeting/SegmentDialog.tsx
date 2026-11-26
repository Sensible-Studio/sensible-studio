import { Button, ButtonGroup, Content, Divider, Form, Heading, NumberField, TextField, useDialogContainer } from '@adobe/react-spectrum';
import { useState } from 'react';
import { segmentName, type Expression, type Logic, type Segment } from './api';
import { ExpressionEditor, emptyComparison } from './ExpressionEditor';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  conf: string;
  /** store/property names of the configuration's ContextHub, offered with the usual ones */
  properties?: string[];
  segment: Segment | null;
  onSave(s: { conf: string; name: string; title: string; description?: string; boost?: number; expression: Expression | null }): Promise<void>;
}

function asLogic(e: Expression | null | undefined): Logic {
  if (!e) return { type: 'and', items: [emptyComparison()] };
  return e.type === 'comparison' ? { type: 'and', items: [e] } : e;
}

/** Create or edit an audience: title, description, boost and its conditions. */
export function SegmentDialog({ conf, segment, onSave, properties }: Props) {
  const dialog = useDialogContainer();
  const [title, setTitle] = useState(segment?.title ?? '');
  const [name, setName] = useState(segment?.name ?? '');
  const [nameTouched, setNameTouched] = useState(!!segment);
  const [description, setDescription] = useState(segment?.description ?? '');
  const [boost, setBoost] = useState(segment?.boost ?? 0);
  const [expression, setExpression] = useState<Logic>(asLogic(segment?.expression));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await onSave({ conf, name: name.trim(), title: title.trim() || name.trim(), description, boost, expression: expression.items.length ? expression : null });
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="L">
      <Heading>{segment ? `Audience: ${segment.title}` : 'Create Audience'}</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <TextField
            label="Title"
            value={title}
            onChange={(t) => {
              setTitle(t);
              if (!nameTouched) setName(segmentName(t));
            }}
            isRequired
            autoFocus
          />
          <TextField
            label="Name"
            value={name}
            onChange={(n) => {
              setName(n);
              setNameTouched(true);
            }}
            isDisabled={!!segment}
            isRequired
            description={`${conf}/settings/wcm/segments/${name || '…'}`}
          />
          <TextField label="Description" value={description} onChange={setDescription} />
          <NumberField label="Boost" value={boost} onChange={setBoost} minValue={0} maxValue={1000} description="When several audiences match, the one with the highest boost decides the experience" />
        </Form>
        <ExpressionEditor value={expression} onChange={setExpression} properties={properties} />
        {error && (
          <p className="studio-publish__error" role="alert">
            {error}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isPending={busy} isDisabled={!name.trim() || !/^[A-Za-z0-9_-]+$/.test(name.trim())}>
          {segment ? 'Save' : 'Create'}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
