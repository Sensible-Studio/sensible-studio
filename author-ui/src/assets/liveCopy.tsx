import { Button, ButtonGroup, Checkbox, Content, Divider, Heading, Switch, TextField, ToastQueue } from '@adobe/react-spectrum';
import { useCallback, useEffect, useState } from 'react';
import type { DamClient, DamItem, LiveCopyInfo, LiveCopyStatus, RolloutResult } from './api';
import { FolderPicker, nodeName } from './dialogs';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { DialogContainer } from '@adobe/react-spectrum';

/** "Live copy", "Live copy · suspended": for lists and cards; null outside a live copy. */
export function liveCopyLabel(status: LiveCopyStatus | undefined | null): string | null {
  if (!status) return null;
  return status === 'suspended' ? 'Live copy · suspended' : 'Live copy';
}

/** One line about what a rollout did. */
export function rolloutSummary(r: RolloutResult): string {
  const parts: string[] = [];
  if (r.added) parts.push(`${r.added} added`);
  if (r.updated) parts.push(`${r.updated} updated`);
  if (r.removed) parts.push(`${r.removed} removed`);
  if (r.skipped.length) parts.push(`${r.skipped.length} left as they are`);
  return parts.length ? parts.join(', ') : 'Already up to date';
}

const STATUS_TEXT: Record<string, string> = {
  root: 'The live copy itself: it follows the folder below.',
  inherited: 'Follows its source: rollouts bring the source’s changes.',
  suspended: 'Suspended: kept as it is here until it is resumed.',
  local: 'Local to the live copy: rollouts leave it alone.',
};

/**
 * Live Copy in the inspector (AEM's MSM for assets): for an item in a live copy its source and the
 * actions on it; for a source the live copies that follow it, and Rollout.
 */
export function LiveCopyPane({
  client,
  item,
  onChanged,
  onOpen,
}: {
  client: DamClient;
  item: DamItem;
  onChanged(): void;
  onOpen?(path: string): void;
}) {
  const [info, setInfo] = useState<LiveCopyInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detaching, setDetaching] = useState(false);
  const [copying, setCopying] = useState(false);
  const load = useCallback(async () => {
    try {
      setInfo(await client.liveCopy(item.path));
      setError(null);
    } catch (e) {
      setInfo({ liveCopies: [] });
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client, item.path]);
  useEffect(() => {
    setInfo(null);
    void load();
  }, [load]);

  const run = async (what: string, f: () => Promise<RolloutResult | void>) => {
    setBusy(true);
    setError(null);
    try {
      const r = await f();
      ToastQueue.positive(r ? `${what}: ${rolloutSummary(r)}` : what, { timeout: 4000 });
      await load();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  if (!info) return null;
  const inCopy = !!info.root;
  const status = info.status ?? 'local';
  return (
    <section className="studio-assets__livecopy" aria-label="Live Copy">
      <h3 className="studio-rail__heading">Live Copy</h3>
      {inCopy && (
        <div className="studio-assets__livecopy-block">
          <p className="studio-assets__muted">{STATUS_TEXT[status]}</p>
          <dl className="studio-preview__meta">
            <div>
              <dt>Source</dt>
              <dd>
                {onOpen && info.sourceExists ? (
                  <button type="button" className="studio-assets__livecopy-link" onClick={() => onOpen(info.source!)}>
                    {info.source}
                  </button>
                ) : (
                  <>
                    {info.source}
                    {info.sourceExists === false ? ' (gone)' : ''}
                  </>
                )}
              </dd>
            </div>
            {info.lastRolledOut && (
              <div>
                <dt>Last rollout</dt>
                <dd>
                  {new Date(info.lastRolledOut).toLocaleString()}
                  {info.lastRolledOutBy ? ` · ${info.lastRolledOutBy}` : ''}
                </dd>
              </div>
            )}
          </dl>
          {status === 'root' && (
            <Switch
              isSelected={!!info.pushOnModify}
              isDisabled={busy}
              onChange={(on) =>
                void run(on ? 'The source’s changes now come at once' : 'Changes now come with Rollout or Synchronize', () => client.liveCopyPush(item.path, on))
              }
            >
              Take the source’s changes at once
            </Switch>
          )}
          {!!info.cancelledProperties?.length && (
            <div className="studio-assets__livecopy-fields">
              <strong>Changed here</strong>
              <ul>
                {info.cancelledProperties.map((f) => (
                  <li key={f}>
                    <code>{f}</code>
                    <button
                      type="button"
                      className="studio-assets__livecopy-link"
                      disabled={busy}
                      onClick={() => void run(`${f} follows the source again`, () => client.liveCopyInherit(item.path, f))}
                    >
                      Follow the source
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="studio-assets__livecopy-actions">
            {status !== 'local' && status !== 'suspended' && (
              <Button variant="secondary" isDisabled={busy || !info.sourceExists} onPress={() => void run('Synchronized', () => client.liveCopySync(item.path))}>
                Synchronize
              </Button>
            )}
            {status === 'inherited' && (
              <Button variant="secondary" isDisabled={busy} onPress={() => void run('Suspended', () => client.liveCopySuspend(item.path, true))}>
                Suspend
              </Button>
            )}
            {status === 'suspended' && (
              <Button variant="secondary" isDisabled={busy} onPress={() => void run('Resumed', () => client.liveCopySuspend(item.path, false))}>
                Resume
              </Button>
            )}
            {status !== 'local' && (
              <Button variant="secondary" isDisabled={busy || !info.sourceExists} onPress={() => void run('Reset', () => client.liveCopySync(item.path, true))}>
                Reset
              </Button>
            )}
            {status === 'root' && (
              <Button variant="negative" isDisabled={busy} onPress={() => setDetaching(true)}>
                Detach
              </Button>
            )}
          </div>
        </div>
      )}
      {info.liveCopies.length > 0 && (
        <div className="studio-assets__livecopy-block">
          <strong>Live copies of this {item.type === 'folder' ? 'folder' : 'file'}</strong>
          <ul className="studio-assets__livecopy-list">
            {info.liveCopies.map((c) => (
              <li key={c.path}>
                {onOpen && c.status !== 'missing' ? (
                  <button type="button" className="studio-assets__livecopy-link" onClick={() => onOpen(c.path)}>
                    {c.path}
                  </button>
                ) : (
                  <span>{c.path}</span>
                )}
                <small className="studio-assets__muted">
                  {c.title} · {c.status === 'missing' ? 'not there yet' : c.status}
                </small>
              </li>
            ))}
          </ul>
          <Button variant="accent" isDisabled={busy} onPress={() => void run('Rolled out', () => client.rollout(item.path))}>
            Rollout
          </Button>
        </div>
      )}
      {!inCopy && (
        <div className="studio-assets__livecopy-block">
          <Button variant="secondary" isDisabled={busy} onPress={() => setCopying(true)}>
            Create live copy…
          </Button>
        </div>
      )}
      {error && <p className="studio-assets__dialog-error">{error}</p>}
      <DialogContainer onDismiss={() => setCopying(false)}>
        {copying && (
          <CreateLiveCopyDialog
            client={client}
            source={item.path}
            sourceTitle={item.title}
            file={item.type === 'asset'}
            onDone={() => {
              void load();
              onChanged();
            }}
            close={() => setCopying(false)}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDetaching(false)}>
        {detaching && (
          <StudioConfirmDialog
            title="Detach live copy"
            variant="destructive"
            primaryActionLabel="Detach"
            cancelLabel="Cancel"
            onPrimaryAction={() => void run('Detached', () => client.liveCopyDetach(item.path))}
            onCancel={() => setDetaching(false)}
          >
            <strong>{item.title}</strong> and everything in it become ordinary folders and files, kept as they are. Rollouts no longer reach them; this
            cannot be undone.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </section>
  );
}

/**
 * Create › Live Copy: a folder here that follows a folder chosen elsewhere in Media; or, from a
 * file or folder's Live Copy pane, a copy of it in a folder chosen here.
 */
export function CreateLiveCopyDialog({
  client,
  parent,
  source: fixedSource,
  sourceTitle,
  file = false,
  onDone,
  close,
}: {
  client: DamClient;
  /** where the live copy goes (Create menu); absent: chosen in the dialog */
  parent?: string;
  /** what it follows (Live Copy pane); absent: chosen in the dialog */
  source?: string;
  sourceTitle?: string;
  file?: boolean;
  onDone(path: string): void;
  close(): void;
}) {
  const [source, setSource] = useState(fixedSource ?? '/content/dam');
  const [target, setTarget] = useState(parent ?? '/content/dam');
  const [title, setTitle] = useState('');
  const [push, setPush] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sourceName = source.substring(source.lastIndexOf('/') + 1);
  // a file keeps its name; a folder's name comes from the title
  const name = file ? sourceName : source === '/content/dam' && !title ? '' : nodeName(title || sourceName);
  const invalid = source === '/content/dam' || target === source || target.startsWith(`${source}/`);
  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await client.createLiveCopy(source, target, file ? '' : title.trim() || sourceName, name, push);
      ToastQueue.positive(`Live copy made: ${rolloutSummary(r)}`, { timeout: 4000 });
      onDone(r.path);
      close();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="M">
      <Heading>Create Live Copy</Heading>
      <Divider />
      <Content>
        <p className="studio-assets__muted">
          {file ? 'A copy of this file' : 'A folder'} that follows {fixedSource ? `“${sourceTitle ?? sourceName}”` : 'a folder elsewhere in Media'}: it is
          copied now, and later changes come with Rollout or Synchronize, or at once. Fields changed in the live copy stay as they are changed.
        </p>
        {fixedSource ? (
          <>
            <strong>Put the live copy in</strong>
            <FolderPicker client={client} value={target} onChange={setTarget} exclude={[source]} />
          </>
        ) : (
          <>
            <strong>Source folder</strong>
            <FolderPicker client={client} value={source} onChange={setSource} exclude={[target]} />
          </>
        )}
        {!file && <TextField label="Title" value={title} onChange={setTitle} width="100%" />}
        <p className="studio-assets__muted">
          Name: {name || '—'} · in {target}
        </p>
        <Checkbox isSelected={push} onChange={setPush}>
          Take the source’s changes at once
        </Checkbox>
        {invalid && source !== '/content/dam' && <p className="studio-assets__dialog-error">A live copy cannot be inside its source.</p>}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void create()} isDisabled={busy || invalid || !name}>
          Create
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
