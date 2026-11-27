import { Button, ButtonGroup, Checkbox, Content, Divider, Heading, ProgressCircle, Radio, RadioGroup, Text, ToastQueue, useDialogContainer } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { Launch, LaunchPage, LaunchesClient } from './api';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  client: LaunchesClient;
  launch: Launch;
  /** The launch page the dialog was opened from (the editor); the console passes none. */
  page?: string;
  onDone(deleted: boolean): void;
}

/**
 * AEM's Promote Launch: the whole launch, the current page, or the page and its subpages; only the pages
 * modified in the launch; delete the launch afterwards. Shows what will be promoted before it happens.
 */
export function PromoteLaunchDialog({ client, launch, page, onDone }: Props) {
  const dialog = useDialogContainer();
  const [pages, setPages] = useState<LaunchPage[] | null>(null);
  const [scope, setScope] = useState<'all' | 'page' | 'pageAndSubpages'>(page ? 'pageAndSubpages' : 'all');
  const [modifiedOnly, setModifiedOnly] = useState(true);
  const [remove, setRemove] = useState(true);
  const [publish, setPublish] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    client.info(launch.path).then((i) => setPages(i.pages ?? []), () => setPages([]));
  }, [client, launch.path]);

  const affected = (pages ?? []).filter((p) => {
    const inScope = scope === 'all' ? true : scope === 'page' ? p.path === page : p.path === page || (!!page && p.path.startsWith(page + '/'));
    return inScope && (!modifiedOnly || p.modified || !p.sourceExists);
  });

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await client.promote(launch.path, { scope, page, modifiedOnly, delete: remove, publish });
      const n = r.result.promoted.length + r.result.created.length;
      ToastQueue.positive(`${n} page${n === 1 ? '' : 's'} live from "${launch.title}"${r.result.created.length ? ` (${r.result.created.length} new)` : ''}${r.published ? `; ${r.published.length} sent to publish` : ''}${r.deleted ? '; the release was deleted' : ''}`, { timeout: 6000 });
      if (r.publishError) ToastQueue.negative(`The pages are live on the author but were not published: ${r.publishError}`, { timeout: 10000 });
      onDone(r.deleted);
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>Go live: {launch.title}</Heading>
      <Divider />
      <Content>
        <Text>Going live copies the release's pages over their source pages (each source keeps a version from before).</Text>
        {page && (
          <RadioGroup label="Go live" value={scope} onChange={(v) => setScope(v as typeof scope)} marginTop="size-150" isDisabled={busy}>
            <Radio value="all">The whole release</Radio>
            <Radio value="page">This page only</Radio>
            <Radio value="pageAndSubpages">This page and the pages below it</Radio>
          </RadioGroup>
        )}
        {/* one under the other: side by side they wrapped as they pleased */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', marginTop: 8 }}>
        <Checkbox isSelected={modifiedOnly} onChange={setModifiedOnly} isDisabled={busy}>
          Only the pages changed in the release
        </Checkbox>
        <Checkbox isSelected={publish} onChange={setPublish} isDisabled={busy}>
          Publish the pages afterwards
        </Checkbox>
        <Checkbox isSelected={remove} onChange={setRemove} isDisabled={busy}>
          Delete the release afterwards
        </Checkbox>
        </div>
        <div className="studio-launch__pages">
          {pages === null ? (
            <ProgressCircle size="S" isIndeterminate aria-label="Loading pages" />
          ) : affected.length === 0 ? (
            <Text UNSAFE_className="studio-publish__hint">Nothing goes live with these options: no page was changed in the release.</Text>
          ) : (
            <>
              <Text UNSAFE_className="studio-publish__hint">
                {affected.length} page{affected.length === 1 ? ' goes' : 's go'} live:
              </Text>
              <ul>
                {affected.map((p) => (
                  <li key={p.path}>
                    <span className="studio-publish__title">{p.title}</span>
                    <span className="studio-publish__meta">{!p.sourceExists ? 'New page' : p.modified ? 'Modified in the release' : 'Unchanged'}</span>
                    <code className="studio-publish__path">{p.sourcePath}</code>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
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
        <Button variant="accent" onPress={() => void submit()} isPending={busy} isDisabled={pages === null || affected.length === 0}>
          Go live
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
