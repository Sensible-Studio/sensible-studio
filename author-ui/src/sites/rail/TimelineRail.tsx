import {
  ActionButton,
  Button,
  ButtonGroup,
  Content,
  DialogContainer,
  Divider,
  Form,
  Heading,
  Item,
  Menu,
  MenuTrigger,
  Picker,
  ProgressCircle,
  TextArea,
  TextField,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Annotate from '@spectrum-icons/workflow/Annotate';
import Clock from '@spectrum-icons/workflow/Clock';
import Close from '@spectrum-icons/workflow/Close';
import Comment from '@spectrum-icons/workflow/Comment';
import History from '@spectrum-icons/workflow/History';
import LaunchIcon from '@spectrum-icons/workflow/Launch';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import More from '@spectrum-icons/workflow/More';
import WorkflowIcon from '@spectrum-icons/workflow/Workflow';
import { useCallback, useEffect, useState, type Key, type KeyboardEvent as ReactKeyboardEvent, type ReactElement } from 'react';
import { StartWorkflowDialog } from '../../workflow/StartWorkflowDialog';
import type { WorkflowClient } from '../../workflow/api';
import { ago, versionPreviewUrl, type SiteRailClient, type Timeline, type TimelineEvent, type TimelineKind } from './api';
import { CompareDialog } from './CompareDialog';
import { RestoreTreeDialog } from './RestoreTreeDialog';
import { RestoreVersionDialog } from './RestoreVersionDialog';
import { StudioDialog } from '../../shell/StudioDialog';

type DialogKind = 'workflow' | 'version' | 'restoreVersion' | 'restoreTree' | 'compare';

const SHOW = [
  { id: 'all', label: 'Show All' },
  { id: 'comment', label: 'Comments' },
  { id: 'annotation', label: 'Annotations' },
  { id: 'activity', label: 'Reports' },
  { id: 'launch', label: 'Releases' },
  { id: 'version', label: 'Versions' },
  { id: 'workflow', label: 'Flows' },
];

const ICONS: Record<TimelineKind, ReactElement> = {
  activity: <Clock size="S" />,
  version: <History size="S" />,
  workflow: <WorkflowIcon size="S" />,
  comment: <Comment size="S" />,
  annotation: <Annotate size="S" />,
  launch: <LaunchIcon size="S" />,
};

interface Props {
  rail: SiteRailClient;
  /** the selected page (one page selected), else null */
  page: { path: string; title: string } | null;
  onChanged?(): void;
  /** for tests: the workflow client of Start Workflow */
  workflow?: WorkflowClient;
  /** Opens one of the rail's dialogs from outside (the console's Restore ▾); a new nonce opens it again. */
  request?: { dialog: 'version' | 'restoreVersion' | 'restoreTree'; nonce: number } | null;
}

/**
 * The Timeline rail of a selected page: what happened to it (created, modified, published, rolled out),
 * its versions, workflows, launches, comments and annotations, newest first, with a filter; at the bottom a comment field and
 * "Start Flow" / "Save as Version".
 */
export function TimelineRail({ rail, page, onChanged, workflow, request }: Props) {
  const [timeline, setTimeline] = useState<Timeline | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [show, setShow] = useState('all');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [dialog, setDialog] = useState<DialogKind | null>(null);
  const [openVersion, setOpenVersion] = useState<string | null>(null);
  const [reverting, setReverting] = useState(false);
  const [comparing, setComparing] = useState<string | null>(null);
  const path = page?.path ?? null;

  const reload = useCallback(() => {
    if (!path) return;
    rail.timeline(path).then(
      (t) => {
        setTimeline(t);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [rail, path]);

  useEffect(() => {
    setTimeline(null);
    setError(null);
    reload();
  }, [reload]);
  useEffect(() => {
    if (request && page) setDialog(request.dialog);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- a request opens once, by its nonce
  }, [request?.nonce]);

  if (!page) return <p className="studio-rail__empty">Select a page to display its timeline.</p>;

  const send = async () => {
    if (!comment.trim()) return;
    setBusy(true);
    try {
      await rail.comment(page.path, comment);
      setComment('');
      reload();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
    setBusy(false);
  };

  const remove = async (e: TimelineEvent) => {
    try {
      await rail.deleteComment(e.id);
      reload();
    } catch (err) {
      ToastQueue.negative(err instanceof Error ? err.message : String(err), { timeout: 6000 });
    }
  };

  const revert = async (e: TimelineEvent) => {
    setReverting(true);
    try {
      await rail.restore(page.path, e.id);
      ToastQueue.positive(`${page.title} reverted to ${e.title}`, { timeout: 4000 });
      setOpenVersion(null);
      reload();
      onChanged?.();
    } catch (err) {
      ToastQueue.negative(err instanceof Error ? err.message : String(err), { timeout: 8000 });
    }
    setReverting(false);
  };

  const events = (timeline?.events ?? []).filter((e) => show === 'all' || e.kind === show);

  return (
    <div className="studio-timeline">
      <div className="studio-timeline__head">
        <Picker aria-label="Show" isQuiet selectedKey={show} onSelectionChange={(k) => k !== null && setShow(String(k))} items={SHOW} width="size-2000">
          {(o) => <Item key={o.id}>{o.label}</Item>}
        </Picker>
      </div>
      {timeline?.lockOwner && (
        <p className="studio-timeline__lock">
          <LockClosed size="XS" /> Locked by {timeline.lockOwner}
        </p>
      )}
      <ol className="studio-timeline__events" aria-label={`Timeline of ${page.title}`}>
        {error ? (
          <li className="studio-rail__empty">{error}</li>
        ) : !timeline ? (
          <li>
            <ProgressCircle size="S" isIndeterminate aria-label="Loading" />
          </li>
        ) : events.length === 0 ? (
          <li className="studio-rail__empty">Nothing to show.</li>
        ) : (
          events.map((e) => (
            <li key={`${e.kind}:${e.id}`} className={`studio-timeline__event studio-timeline__event--${e.kind}`}>
              <span className="studio-timeline__icon" aria-hidden>
                {ICONS[e.kind]}
              </span>
              <div
                className={`studio-timeline__body${e.kind === 'version' ? ' is-expandable' : ''}`}
                {...(e.kind === 'version'
                  ? {
                      role: 'button',
                      tabIndex: 0,
                      'aria-expanded': openVersion === e.id,
                      onClick: () => setOpenVersion((o) => (o === e.id ? null : e.id)),
                      onKeyDown: (ev: ReactKeyboardEvent) => {
                        if (ev.key === 'Enter' || ev.key === ' ') {
                          ev.preventDefault();
                          setOpenVersion((o) => (o === e.id ? null : e.id));
                        }
                      },
                    }
                  : {})}
              >
                <div className="studio-timeline__title">
                  {e.title}
                  {e.kind === 'workflow' && e.instance && e.status && e.status !== 'COMPLETED' && <span className="studio-timeline__status">{e.status.toLowerCase()}</span>}
                </div>
                {e.kind === 'annotation' ? (
                  <>
                    <div className="studio-timeline__note" style={e.color ? { borderLeftColor: e.color } : undefined}>
                      {e.text || <span className="studio-timeline__empty">No text</span>}
                    </div>
                    <div className="studio-timeline__text">
                      {e.component && <span title={e.component}>On {e.component.slice(e.component.lastIndexOf('/') + 1)} · </span>}
                      <a href={`/editor.html${page.path}.html`} target="_blank" rel="noreferrer">
                        Open in editor
                      </a>
                    </div>
                  </>
                ) : (
                  e.text && <div className="studio-timeline__text">{e.text}</div>
                )}
                {e.kind === 'launch' && (
                  <div className="studio-timeline__text">
                    {e.liveDate && <>Live {new Date(e.liveDate).toLocaleDateString()} · </>}
                    {e.pageInLaunch && (
                      <a href={`/editor.html${e.pageInLaunch}.html`} target="_blank" rel="noreferrer">
                        {e.promoted ? 'Open the release copy' : 'Open in release'}
                      </a>
                    )}
                  </div>
                )}
                <div className="studio-timeline__meta" title={new Date(e.time).toLocaleString()}>
                  {ago(e.time)}
                  {e.user ? ` by ${e.user}` : ''}
                </div>
                {e.kind === 'version' && openVersion === e.id && (
                  <div className="studio-timeline__actions" onClick={(ev) => ev.stopPropagation()}>
                    <Button variant="secondary" onPress={() => void revert(e)} isDisabled={reverting}>
                      Revert to this Version
                    </Button>
                    <Button variant="secondary" onPress={() => window.open(versionPreviewUrl(page.path, e.id), '_blank', 'noopener')}>
                      Preview Version
                    </Button>
                    <Button
                      variant="secondary"
                      onPress={() => {
                        setComparing(e.id);
                        setDialog('compare');
                      }}
                    >
                      Compare to Current
                    </Button>
                  </div>
                )}
              </div>
              {e.kind === 'comment' && e.mine && (
                <TooltipTrigger>
                  <ActionButton isQuiet aria-label="Delete comment" onPress={() => void remove(e)} UNSAFE_className="studio-timeline__delete">
                    <Close size="S" />
                  </ActionButton>
                  <Tooltip>Delete comment</Tooltip>
                </TooltipTrigger>
              )}
            </li>
          ))
        )}
      </ol>
      <div className="studio-timeline__compose">
        <TextField
          aria-label="Comment"
          placeholder="Comment"
          value={comment}
          onChange={setComment}
          onKeyDown={(ev) => (ev.key === 'Enter' ? void send() : ev.continuePropagation())}
          isDisabled={busy}
          width="100%"
        />
        <MenuTrigger align="end" direction="top">
          <ActionButton isQuiet aria-label="More actions">
            <More />
          </ActionButton>
          <Menu onAction={(k: Key) => setDialog(k as DialogKind)}>
            <Item key="version">Save as Version</Item>
            <Item key="restoreVersion">Restore Version</Item>
            <Item key="restoreTree">Restore Tree</Item>
            <Item key="workflow">Start Flow</Item>
          </Menu>
        </MenuTrigger>
      </div>
      {dialog === 'workflow' && (
        <StartWorkflowDialog payloads={[page.path]} client={workflow} onClose={() => setDialog(null)} onStarted={() => window.setTimeout(reload, 800)} />
      )}
      <DialogContainer onDismiss={() => setDialog(null)}>
        {dialog === 'version' && (
          <SaveVersionDialog
            page={page}
            onClose={() => setDialog(null)}
            onSave={async (label, text) => {
              const v = await rail.saveVersion(page.path, label, text);
              ToastQueue.positive(`Version ${v.version} of ${page.title} saved`, { timeout: 4000 });
              setDialog(null);
              reload();
              onChanged?.();
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer type="fullscreenTakeover" onDismiss={() => setDialog(null)}>
        {dialog === 'restoreVersion' && (
          <RestoreVersionDialog
            rail={rail}
            page={page}
            onRestored={() => {
              reload();
              onChanged?.();
            }}
          />
        )}
        {dialog === 'compare' && comparing && (
          <CompareDialog
            rail={rail}
            page={page}
            version={comparing}
            versions={(timeline?.events ?? [])
              .filter((x) => x.kind === 'version')
              .map((x) => ({ id: x.id, label: x.version && x.title !== `Version ${x.version}` ? `${x.version} · ${x.title}` : x.version ?? x.title, time: x.time }))}
          />
        )}
        {dialog === 'restoreTree' && (
          <RestoreTreeDialog
            rail={rail}
            page={page}
            onRestored={() => {
              reload();
              onChanged?.();
            }}
          />
        )}
      </DialogContainer>
    </div>
  );
}

function SaveVersionDialog({ page, onClose, onSave }: { page: { title: string }; onClose(): void; onSave(label: string, comment: string): Promise<void> }) {
  const [label, setLabel] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    setBusy(true);
    try {
      await onSave(label.trim(), comment.trim());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>Save as Version</Heading>
      <Divider />
      <Content>
        <p className="studio-rail__hint">A version of {page.title} as it is now. Restore it later from the page&apos;s versions.</p>
        <Form>
          <TextField label="Label" value={label} onChange={setLabel} autoFocus />
          <TextArea label="Comment" value={comment} onChange={setComment} />
        </Form>
        {error && (
          <p className="studio-rail__error" role="alert">
            {error}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void save()} isDisabled={busy}>
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
