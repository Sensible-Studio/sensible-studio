import { Button, ButtonGroup, Checkbox, Content, Divider, Form, Heading, Text, TextField, ToastQueue, useDialogContainer } from '@adobe/react-spectrum';
import { useState } from 'react';
import type { MsmClient } from '../msm/MsmClient';
import type { Launch, LaunchesClient } from './api';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  client: LaunchesClient;
  /** MSM: creates the launch's pages as a live copy of the source when "inherit" is on. */
  msm: MsmClient;
  source: string;
  sourceTitle: string;
  /** further source pages of the same release (several pages selected) */
  more?: string[];
  onCreated(launch: Launch): void;
}

function defaultLiveDate(): string {
  const d = new Date(Date.now() + 7 * 86400_000);
  d.setHours(9, 0, 0, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * AEM's Create Launch: a title, the live date, whether the subpages come along and whether the launch
 * keeps receiving the source's changes until it is promoted (an MSM live copy of the source).
 */
export function CreateLaunchDialog({ client, msm, source, sourceTitle, more = [], onCreated }: Props) {
  const dialog = useDialogContainer();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [liveDate, setLiveDate] = useState(defaultLiveDate);
  const [deep, setDeep] = useState(true);
  const [inherit, setInherit] = useState(true);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const at = liveDate ? new Date(liveDate) : null;
      // parents first; with the subpages along, a page below another selected one is already in
      const all = [source, ...more].sort((a, b) => a.split('/').length - b.split('/').length);
      const pages = deep ? all.filter((p) => !all.some((o) => o !== p && p.startsWith(`${o}/`))) : all;
      const [source0, ...rest] = pages;
      const r = await client.create({ source: source0, title: title.trim(), description: description || undefined, liveDate: at && !Number.isNaN(at.getTime()) ? at.toISOString() : undefined, deep, liveCopy: inherit, productionReady: ready && !!at && !Number.isNaN(at.getTime()) });
      let launch = r.launch;
      try {
        if (inherit && r.liveCopyParent && r.liveCopyName) await msm.createLiveCopy({ source: source0, parent: r.liveCopyParent, name: r.liveCopyName, deep, rolloutConfigs: [] });
        for (const page of rest) {
          const a = await client.addSource(launch.path, { source: page, deep, liveCopy: inherit });
          if (inherit && a.liveCopyParent && a.liveCopyName) await msm.createLiveCopy({ source: page, parent: a.liveCopyParent, name: a.liveCopyName, deep, rolloutConfigs: [] });
          launch = a.launch;
        }
      } catch (e) {
        // a release with only some of its pages would mislead: none is left
        await client.remove(launch.path).catch(() => undefined);
        throw e;
      }
      ToastQueue.positive(`Release "${launch.title}" created`, { timeout: 4000 });
      onCreated(launch);
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>Create Release</Heading>
      <Divider />
      <Content>
        <Text>
          A release is a copy of <strong>{more.length ? `${more.length + 1} pages` : sourceTitle}</strong> you work on apart from the live site and promote back when it is ready.
        </Text>
        <Form
          marginTop="size-150"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <TextField label="Release title" value={title} onChange={setTitle} isRequired autoFocus />
          <TextField label="Description" value={description} onChange={setDescription} />
          <TextField type="datetime-local" label="Live date" value={liveDate} onChange={setLiveDate} description={ready && liveDate ? 'When the release goes live by itself' : 'When the release is meant to go live (for information; promotion is manual)'} />
          <Checkbox isSelected={ready && !!liveDate} onChange={setReady} isDisabled={!liveDate}>
            Production ready: go live and publish by itself at the live date
          </Checkbox>
          <Checkbox isSelected={deep} onChange={setDeep}>
            Include subpages
          </Checkbox>
          <Checkbox isSelected={inherit} onChange={setInherit}>
            Inherit source page live data
          </Checkbox>
          <Text UNSAFE_className="studio-publish__hint">
            {inherit ? 'The release pages are a linked copy of the source: changes made on the site reach the release until a page is edited there.' : 'The release pages are a plain copy: changes made on the site afterwards do not reach the release.'}
          </Text>
        </Form>
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
        <Button variant="accent" onPress={() => void submit()} isPending={busy} isDisabled={!title.trim()}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
