import { Button, ButtonGroup, Content, Divider, Heading, NumberField, ProgressCircle, Switch, ToastQueue } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { formatTime, type MaintenanceInfo, type WorkflowClient } from './api';
import { StudioDialog } from '../shell/StudioDialog';

/**
 * Purge settings of the workflow archive: after how many days completed and terminated workflows are removed (the
 * OSGi configuration of the engine's maintenance), and a purge on demand.
 */
export function PurgeDialog({ client, onClose, onPurged }: { client: WorkflowClient; onClose(): void; onPurged(removed: number): void }) {
  const [info, setInfo] = useState<MaintenanceInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [keep, setKeep] = useState(false);
  const [days, setDays] = useState(30);
  const [purgeDays, setPurgeDays] = useState(30);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    client.maintenance().then(
      (m) => {
        setInfo(m);
        setKeep(m.purgeDays === 0);
        setDays(m.purgeDays || 30);
        setPurgeDays(m.purgeDays || 30);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client]);

  const readOnly = !info?.admin;
  const valid = Number.isInteger(days) && days >= 1 && days <= 36500;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await client.setPurge(keep ? 0 : days);
      ToastQueue.positive(keep ? 'Ended flows are kept' : `Ended flows are removed after ${days} day${days === 1 ? '' : 's'}`, { timeout: 4000 });
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  const purge = async () => {
    setBusy(true);
    setError(null);
    try {
      const removed = await client.purgeNow(purgeDays);
      ToastQueue.positive(`${removed} flow${removed === 1 ? '' : 's'} removed`, { timeout: 4000 });
      onPurged(removed);
      setInfo(await client.maintenance());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  };

  return (
    <StudioDialog size="M">
      <Heading>Purge Settings</Heading>
      <Divider />
      <Content>
        {!info && !error ? (
          <ProgressCircle isIndeterminate aria-label="Loading" />
        ) : (
          info && (
            <div className="studio-wf__form">
              <dl className="studio-wf__facts">
                <dt>Ended flows</dt>
                <dd>{info.ended}</dd>
                {info.oldestEnded && (
                  <>
                    <dt>Oldest ended</dt>
                    <dd>{formatTime(info.oldestEnded)}</dd>
                  </>
                )}
                <dt>Last housekeeping</dt>
                <dd>
                  {info.lastRun ? formatTime(info.lastRun) : 'Not yet'}
                  {info.lastRemoved > 0 && <span className="studio-listview__muted"> · {info.lastRemoved} removed</span>}
                </dd>
              </dl>
              <h3 className="studio-wf__subhead">Automatic purge</h3>
              <Switch isSelected={keep} onChange={setKeep} isDisabled={readOnly}>
                Keep ended flows
              </Switch>
              {!keep && (
                <NumberField
                  label="Remove completed and terminated flows after (days)"
                  value={days}
                  onChange={setDays}
                  minValue={1}
                  maxValue={36500}
                  isDisabled={readOnly}
                  width="size-3600"
                />
              )}
              <p className="studio-wf__hint">
                Checked every minute. Running and suspended workflows are never removed; transient workflows are removed a minute after
                they end in any case.
              </p>
              <h3 className="studio-wf__subhead">Purge now</h3>
              <div className="studio-wf__purgenow">
                <NumberField
                  label="Flows that ended more than (days) ago"
                  value={purgeDays}
                  onChange={setPurgeDays}
                  minValue={1}
                  maxValue={36500}
                  isDisabled={readOnly}
                  width="size-3600"
                />
                <Button variant="negative" onPress={() => void purge()} isDisabled={readOnly || busy || !(purgeDays >= 1)}>
                  Purge Now
                </Button>
              </div>
              {readOnly && <p className="studio-wf__hint">Only administrators can change these settings.</p>}
            </div>
          )
        )}
        {error && (
          <p className="studio-wf__failure" role="alert">
            {error}
          </p>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={onClose} isDisabled={busy}>
          Close
        </Button>
        <Button variant="accent" onPress={() => void save()} isDisabled={readOnly || busy || !info || (!keep && !valid)}>
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
