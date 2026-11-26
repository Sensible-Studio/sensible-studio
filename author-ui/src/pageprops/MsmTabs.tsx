import { ActionButton, DialogContainer, ProgressCircle, Text, ToastQueue } from '@adobe/react-spectrum';
import Branch1 from '@spectrum-icons/workflow/Branch1';
import LinkOff from '@spectrum-icons/workflow/LinkOff';
import Pause from '@spectrum-icons/workflow/Pause';
import Play from '@spectrum-icons/workflow/Play';
import Refresh from '@spectrum-icons/workflow/Refresh';
import Sync from '@spectrum-icons/workflow/Sync';
import ViewTree from '@spectrum-icons/workflow/TreeExpandAll';
import { useEffect, useId, useState } from 'react';
import { describeResult, type MsmClient } from '../msm/MsmClient';
import { LiveCopyOverview } from '../msm/LiveCopyOverview';
import { RolloutDialog } from '../msm/RolloutDialog';
import type { MsmStatus, RolloutConfigInfo } from '../msm/types';
import { Section, usePendingChange } from './context';
import { Link } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';

/** "6 years ago", like AEM's relative dates. */
export function ago(iso?: string, now = Date.now()): string {
  if (!iso) return 'Never';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const s = Math.round((t - now) / 1000);
  const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  const steps: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 31536000],
    ['month', 2592000],
    ['day', 86400],
    ['hour', 3600],
    ['minute', 60],
  ];
  for (const [unit, size] of steps) {
    if (Math.abs(s) >= size) return rtf.format(Math.round(s / size), unit);
  }
  return rtf.format(s, 'second');
}

const same = (a: string[], b: string[]) => a.length === b.length && a.every((x, i) => x === b[i]);

/** A drop-down to add a rollout configuration, the chosen ones as removable tags (AEM's multiple select). */
function ConfigSelect({
  id,
  configs,
  value,
  onChange,
  disabled,
  placeholder = 'Choose Sync rules',
}: {
  id: string;
  configs: RolloutConfigInfo[];
  value: string[];
  onChange(v: string[]): void;
  disabled?: boolean;
  placeholder?: string;
}) {
  const titleOf = (p: string) => configs.find((c) => c.path === p)?.title ?? p;
  return (
    <>
      <div className="studio-g-select">
        <select
          id={id}
          className="studio-g-input studio-g-select__control"
          value=""
          disabled={disabled}
          onChange={(e) => e.target.value && onChange([...value, e.target.value])}
        >
          <option value="">{placeholder}</option>
          {configs
            .filter((c) => !value.includes(c.path))
            .map((c) => (
              <option key={c.path} value={c.path}>
                {c.title}
              </option>
            ))}
        </select>
      </div>
      {value.length > 0 && (
        <ul className="studio-g-taglist">
          {value.map((p) => (
            <li key={p} className="studio-g-taglist__tag" title={p}>
              <span>{titleOf(p)}</span>
              {!disabled && (
                <button type="button" aria-label={`Remove ${titleOf(p)}`} onClick={() => onChange(value.filter((x) => x !== p))}>
                  ×
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function useConfigs(msm: MsmClient): RolloutConfigInfo[] {
  const [configs, setConfigs] = useState<RolloutConfigInfo[]>([]);
  useEffect(() => {
    msm.configs().then(setConfigs, () => setConfigs([]));
  }, [msm]);
  return configs;
}

/**
 * The Blueprint tab: the live copies of the page, Rollout and the Live Copy Overview, and the rollout configurations
 * the page gives its live copies (saved with the page properties).
 */
export function BlueprintTab({ msm, page, status, onChanged }: { msm: MsmClient; page: string; status: MsmStatus; onChanged(): void }) {
  const id = useId();
  const configs = useConfigs(msm);
  const stored = status.blueprintConfig?.rolloutConfigs ?? [];
  const [chosen, setChosen] = useState<string[]>(stored);
  const [rollout, setRollout] = useState(false);
  const [overview, setOverview] = useState(false);
  usePendingChange('blueprint', {
    isDirty: () => !same(chosen, stored),
    save: async () => {
      if (!same(chosen, stored)) await msm.blueprintConfig(page, chosen);
    },
  });
  const copies = status.liveCopies ?? [];
  return (
    <>
      <div className="studio-pp-actionbar">
        <ActionButton isQuiet onPress={() => setRollout(true)} isDisabled={copies.length === 0}>
          <Branch1 />
          <Text>Sync changes</Text>
        </ActionButton>
        <ActionButton isQuiet onPress={() => setOverview(true)}>
          <ViewTree />
          <Text>Linked copy Overview</Text>
        </ActionButton>
      </div>
      <div className="studio-pp-column">
        <Section title="Current Linked copies">
          {copies.length === 0 ? (
            <p className="studio-pp-muted">No linked copies.</p>
          ) : (
            <ul className="studio-pp-links">
              {copies.map((c) => (
                <li key={c.targetPath}>
                  <Link to={`/sites.html${c.targetPath}`}>{c.targetPath}</Link>
                </li>
              ))}
            </ul>
          )}
        </Section>
        <Section title="Sync rules">
          <div className="studio-g-field">
            <label className="studio-g-visually-hidden" htmlFor={id}>
              Sync changes configurations of the linked copies
            </label>
            <ConfigSelect id={id} configs={configs} value={chosen} onChange={setChosen} />
            <p className="studio-pp-hint">
              Used by the live copies of this page and the pages below it that name none of their own; without any, they use
              the standard sync rules.
            </p>
          </div>
        </Section>
      </div>
      <DialogContainer onDismiss={() => setRollout(false)}>
        {rollout && <RolloutDialog msm={msm} path={page} liveCopies={copies} onDone={onChanged} />}
      </DialogContainer>
      {overview && <LiveCopyOverview msm={msm} path={page} onClose={() => setOverview(false)} onChanged={onChanged} />}
    </>
  );
}

/**
 * The Live Copy tab: Synchronize, Reset, Suspend / Resume and Detach; the source and status of the page; its rollout
 * configurations (inherited from the parent or its own) and, on the live copy root, "Linked copy Inheritance" (child
 * pages too) and the excluded paths. The configuration is saved with the page properties.
 */
export function LiveCopyTab({ msm, page, status, onChanged }: { msm: MsmClient; page: string; status: MsmStatus; onChanged(): void }) {
  const ids = useId();
  const configs = useConfigs(msm);
  const rel = status.relationship!;
  const cfg = status.pageConfig;
  const root = !!cfg?.root;
  const [inherit, setInherit] = useState(cfg?.inheritRolloutConfigs ?? true);
  const [own, setOwn] = useState<string[]>(cfg && !cfg.inheritRolloutConfigs ? cfg.rolloutConfigs : (cfg?.effectiveRolloutConfigs ?? []));
  const [deep, setDeep] = useState(cfg?.deep ?? true);
  const [excluded, setExcluded] = useState((cfg?.excludedPaths ?? []).join('\n'));
  const [busy, setBusy] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<'reset' | 'detach' | null>(null);
  const [sourceModified, setSourceModified] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    let live = true;
    fetch(`${rel.sourcePath}/jcr:content.json`, { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : null))
      .then((c: Record<string, unknown> | null) => live && setSourceModified((c?.['cq:lastModified'] as string) ?? null))
      .catch(() => live && setSourceModified(null));
    return () => {
      live = false;
    };
  }, [rel.sourcePath]);

  const excludedList = excluded
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
  const dirty =
    !!cfg &&
    (inherit !== cfg.inheritRolloutConfigs ||
      (!inherit && !same(own, cfg.rolloutConfigs)) ||
      (root && deep !== cfg.deep) ||
      (root && !same(excludedList, cfg.excludedPaths)));
  usePendingChange('livecopy', {
    isDirty: () => dirty,
    save: async () => {
      if (!dirty) return;
      await msm.liveCopyConfig(page, {
        rolloutConfigs: inherit ? null : own,
        ...(root ? { deep, excludedPaths: excludedList } : {}),
      });
    },
  });

  const act = async (what: string, work: () => Promise<string>) => {
    setBusy(what);
    try {
      ToastQueue.positive(await work(), { timeout: 5000 });
      onChanged();
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  const suspended = rel.cancelled;
  const inSync =
    rel.status === 'synced' &&
    !!rel.lastRolledOut &&
    (sourceModified === null || (sourceModified !== undefined && new Date(sourceModified).getTime() <= new Date(rel.lastRolledOut).getTime()));

  return (
    <>
      <div className="studio-pp-actionbar">
        <ActionButton
          isQuiet
          isDisabled={!!busy || !rel.inherited || suspended || !rel.sourceExisting}
          onPress={() => void act('sync', async () => `Synchronized: ${describeResult(await msm.sync(page, { deep: false }))}`)}
        >
          <Sync />
          <Text>Synchronize</Text>
        </ActionButton>
        <ActionButton isQuiet isDisabled={!!busy || !rel.inherited || !rel.sourceExisting} onPress={() => setConfirm('reset')}>
          <Refresh />
          <Text>Reset</Text>
        </ActionButton>
        {suspended && !rel.cancelledByAncestor ? (
          <ActionButton
            isQuiet
            isDisabled={!!busy}
            onPress={() =>
              void act('resume', async () => {
                await msm.inheritance(page, 'resume');
                return 'Linked copy resumed and synchronized';
              })
            }
          >
            <Play />
            <Text>Resume</Text>
          </ActionButton>
        ) : (
          <ActionButton
            isQuiet
            isDisabled={!!busy || suspended || !rel.inherited}
            onPress={() =>
              void act('suspend', async () => {
                await msm.inheritance(page, 'suspend', { deep: false });
                return 'Linked copy suspended: syncs leave this page alone';
              })
            }
          >
            <Pause />
            <Text>Suspend</Text>
          </ActionButton>
        )}
        <ActionButton isQuiet isDisabled={!!busy} onPress={() => setConfirm('detach')}>
          <LinkOff />
          <Text>Detach</Text>
        </ActionButton>
        {busy && <ProgressCircle size="S" isIndeterminate aria-label="Working" />}
      </div>
      <div className="studio-pp-column">
        <Section title="Source">
          <Link className="studio-pp-link" to={`/sites.html${rel.sourcePath}`}>
            {rel.sourcePath}
          </Link>
          {!rel.inherited && <p className="studio-pp-muted">This page was created in the linked copy; syncs do not change it.</p>}
        </Section>
        <Section title="Status">
          <dl className="studio-pp-facts">
            <div>
              <dt>Suspended Linked copy</dt>
              <dd>{suspended ? 'Yes' : 'No'}</dd>
            </div>
            <div>
              <dt>In Sync with Source</dt>
              <dd>{sourceModified === undefined ? '…' : inSync ? 'Yes' : 'No'}</dd>
            </div>
            <div>
              <dt>Last Sync changes Date</dt>
              <dd title={rel.lastRolledOut ? new Date(rel.lastRolledOut).toLocaleString() : undefined}>{ago(rel.lastRolledOut)}</dd>
            </div>
            <div>
              <dt>Last Rolled Out by</dt>
              <dd>{rel.lastRolledOutBy === 'admin' ? 'Administrator' : (rel.lastRolledOutBy ?? '—')}</dd>
            </div>
          </dl>
        </Section>
        {cfg && (
          <Section title="Configuration">
            <div className="studio-g-checkrow">
              <label className="studio-g-checkbox" htmlFor={`${ids}-deep`}>
                <input
                  id={`${ids}-deep`}
                  type="checkbox"
                  className="studio-g-checkbox__input"
                  checked={deep}
                  disabled={!root}
                  onChange={(e) => setDeep(e.target.checked)}
                />
                <span className="studio-g-checkbox__box" aria-hidden="true" />
                <span className="studio-g-checkbox__text">Linked copy Inheritance</span>
              </label>
              <span className="studio-pp-hint studio-pp-hint--inline">
                {root ? 'Child pages are part of the linked copy too.' : `Set on the linked copy root, ${status.liveCopy?.path}.`}
              </span>
            </div>
            <div className="studio-g-checkrow">
              <label className="studio-g-checkbox" htmlFor={`${ids}-inherit`}>
                <input
                  id={`${ids}-inherit`}
                  type="checkbox"
                  className="studio-g-checkbox__input"
                  checked={inherit}
                  onChange={(e) => {
                    setInherit(e.target.checked);
                    if (!e.target.checked && own.length === 0) setOwn(cfg.effectiveRolloutConfigs);
                  }}
                />
                <span className="studio-g-checkbox__box" aria-hidden="true" />
                <span className="studio-g-checkbox__text">Inherit sync rules from the parent</span>
              </label>
            </div>
            <div className="studio-g-field">
              <label className="studio-g-visually-hidden" htmlFor={`${ids}-configs`}>
                Sync changes configurations
              </label>
              <ConfigSelect
                id={`${ids}-configs`}
                configs={configs}
                value={inherit ? cfg.effectiveRolloutConfigs : own}
                onChange={setOwn}
                disabled={inherit}
              />
            </div>
          </Section>
        )}
        {cfg && (
          <Section title="List of excluded paths">
            <div className="studio-g-field">
              <div className="studio-g-field__head">
                <label className="studio-g-field__label" htmlFor={`${ids}-excluded`}>
                  Excluded Paths
                </label>
              </div>
              <textarea
                id={`${ids}-excluded`}
                className="studio-g-input studio-g-textarea"
                rows={3}
                value={excluded}
                disabled={!root}
                placeholder={root ? 'One child path per line, e.g. magazine/archive' : ''}
                onChange={(e) => setExcluded(e.target.value)}
              />
              {!root && <p className="studio-pp-hint">Set on the linked copy root, {status.liveCopy?.path}.</p>}
            </div>
          </Section>
        )}
      </div>

      <DialogContainer onDismiss={() => setConfirm(null)}>
        {confirm === 'reset' && (
          <StudioConfirmDialog
            title="Reset linked copy page"
            variant="warning"
            primaryActionLabel="Reset"
            cancelLabel="Cancel"
            onPrimaryAction={() => void act('reset', async () => `Reset: ${describeResult(await msm.sync(page, { deep: false, reset: true }))}`)}
          >
            Every cancelled inheritance on this page is re-enabled and the page is synchronized with the source: the changes
            made in the linked copy to inherited content are lost.
          </StudioConfirmDialog>
        )}
        {confirm === 'detach' && (
          <StudioConfirmDialog
            title="Detach linked copy"
            variant="destructive"
            primaryActionLabel="Detach"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              void act('detach', async () => {
                await msm.inheritance(page, 'detach');
                return 'Detached: the page is no longer synchronized';
              })
            }
          >
            {root
              ? 'The linked copy is turned into normal pages: no sync will reach them any more. This cannot be undone.'
              : 'This page and the pages below it leave the linked copy for good: no sync will reach them any more.'}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </>
  );
}
