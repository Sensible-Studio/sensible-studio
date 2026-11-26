import { ActionButton, Button, DialogContainer, Item, Picker, Text, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import OpenIn from '@spectrum-icons/workflow/OpenIn';
import { useCallback, useEffect, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { ProductBar } from '../shell/ProductBar';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { describe, type Segment, type TargetingClient } from './api';
import { SegmentDialog } from './SegmentDialog';
import { useNavigate, useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  client: TargetingClient;
  studio: StudioClient;
  initialConf?: string;
}

/**
 * The Audiences console (`/audiences.html[/conf/<site>]`), AEM's Personalization → Audiences: the
 * ContextHub segments of a configuration, with their conditions; create, edit, remove.
 */
export function AudiencesConsole({ client, studio, initialConf }: Props) {
  const go = useNavigate();
  const tab = useTab();
  const [confs, setConfs] = useState<string[]>([]);
  const [conf, setConf] = useState(initialConf ?? '/conf/global');
  const [segments, setSegments] = useState<Segment[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Segment | 'new' | null>(null);
  const [removing, setRemoving] = useState<Segment | null>(null);
  const [user, setUser] = useState<string | null>(null);
  // the store properties the configuration's ContextHub knows, for the conditions
  const [properties, setProperties] = useState<string[]>([]);
  useEffect(() => {
    client.contexthub(conf).then(
      (c) => setProperties(c.properties),
      () => setProperties([]),
    );
  }, [client, conf]);

  useEffect(() => {
    studio.currentUser().then(setUser, () => setUser(null));
    fetch('/conf.1.json', { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : {}))
      .then((j: Record<string, unknown>) => {
        const names = Object.entries(j)
          .filter(([k, v]) => !k.includes(':') && v && typeof v === 'object')
          .map(([k]) => `/conf/${k}`);
        setConfs(names.includes('/conf/global') ? names : ['/conf/global', ...names]);
      })
      .catch(() => setConfs(['/conf/global']));
  }, [studio]);

  const load = useCallback(() => {
    setSegments(null);
    client.segments(conf).then(
      (r) => {
        setSegments(r.segments);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client, conf]);
  useEffect(load, [load]);
  const tabConf = tab?.place.kind === 'audiences' ? tab.place.conf : undefined;
  useEffect(() => {
    if (tabConf && tabConf !== conf) setConf(tabConf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabConf]);
  useEffect(() => {
    if (tab) {
      if (tabConf !== conf) tab.setPlace({ kind: 'audiences', conf }, { replace: true });
    } else window.history.replaceState(null, '', `/audiences.html${conf}`);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conf]);

  return (
    <div className="studio-sites studio-audiences">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/audiences.html"
        closable={false}
        end={
          <Picker aria-label="Configuration" selectedKey={conf} onSelectionChange={(k) => setConf(String(k))} isQuiet width="size-2400">
            {confs.map((c) => (
              <Item key={c}>{c.replace('/conf/', '')}</Item>
            ))}
          </Picker>
        }
        primary={<Button variant="accent" onPress={() => setEditing('new')}><Add /><Text>Create audience</Text></Button>}
      />
      <main className="studio-sites__content">
        {error && <ErrorState title="Could not load audiences">{error}</ErrorState>}
        {!segments && !error && <LoadingState label="Loading audiences" />}
        {segments && segments.length === 0 && <EmptyState title="No audiences">Create one under {conf}/settings/wcm/segments with a title and conditions on the visitor's profile, location, or device.</EmptyState>}
        {segments && segments.length > 0 && (
          <table className="studio-audiences__table studio-table">
            <thead>
              <tr>
                <th>Title</th>
                <th>Conditions</th>
                <th>Boost</th>
                <th>Name</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {segments.map((s) => (
                <tr key={s.path}>
                  <td>
                    <strong>{s.title}</strong>
                    {s.description && <div className="studio-replication__muted">{s.description}</div>}
                  </td>
                  <td className="studio-audiences__conditions">{describe(s.expression)}</td>
                  <td>{s.boost}</td>
                  <td>
                    <code>{s.name}</code>
                  </td>
                  <td className="studio-audiences__actions">
                    <TooltipTrigger delay={400}>
                      <ActionButton isQuiet onPress={() => setEditing(s)}>
                        <Edit />
                        <Text>Edit</Text>
                      </ActionButton>
                      <Tooltip>Edit the title and the conditions</Tooltip>
                    </TooltipTrigger>
                    <TooltipTrigger delay={400}>
                      <ActionButton isQuiet onPress={() => go(`${s.path}.html`, { newTab: true })}>
                        <OpenIn />
                        <Text>Open</Text>
                      </ActionButton>
                      <Tooltip>Open the segment page</Tooltip>
                    </TooltipTrigger>
                    <TooltipTrigger delay={400}>
                      <ActionButton isQuiet onPress={() => setRemoving(s)}>
                        <Delete />
                        <Text>Remove</Text>
                      </ActionButton>
                      <Tooltip>Remove the audience</Tooltip>
                    </TooltipTrigger>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </main>
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title="Remove audience"
            variant="destructive"
            primaryActionLabel="Remove"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              client.removeSegment(removing.path).then(
                () => {
                  load();
                  ToastQueue.positive(`Audience ${removing.title} removed`, { timeout: 3000 });
                },
                (e: unknown) => ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 }),
              )
            }
          >
            {`You are going to remove ${removing.title}. Content shown only to this audience stops being targeted to it.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && (
          <SegmentDialog
            conf={conf}
            properties={properties}
            segment={editing === 'new' ? null : editing}
            onSave={async (s) => {
              await client.saveSegment(s);
              load();
              ToastQueue.positive(`Audience ${s.title} saved`, { timeout: 3000 });
            }}
          />
        )}
      </DialogContainer>
    </div>
  );
}
